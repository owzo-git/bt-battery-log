package com.owzo.btbatteryntfy.monitor.shokz

/** One component's last successful vendor reading. */
data class ShokzComponentReading(
    val percent: Int,
    val sampledAtMs: Long,
)

/**
 * Last known left/right/case values for one headset. Each part keeps its own timestamp, so a
 * partial read updates only what it returned and never erases a known value.
 */
data class ShokzComponents(
    val left: ShokzComponentReading? = null,
    val right: ShokzComponentReading? = null,
    val case: ShokzComponentReading? = null,
) {
    val isEmpty: Boolean
        get() = left == null && right == null && case == null

    /** Newest sample time of any part, or 0 when nothing is known. */
    val latestSampledAtMs: Long
        get() = listOfNotNull(left, right, case).maxOfOrNull { it.sampledAtMs } ?: 0L

    fun mergedWith(status: ShokzBatteryStatus): ShokzComponents {
        fun pick(current: ShokzComponentReading?, percent: Int?): ShokzComponentReading? =
            percent?.let { ShokzComponentReading(it, status.sampledAtMs) } ?: current

        return ShokzComponents(
            left = pick(left, status.left),
            right = pick(right, status.right),
            case = pick(case, status.case),
        )
    }

    /** Keeps only parts measured during the current connection; older parts become unknown. */
    fun sampledSince(sessionStartedAtMs: Long): ShokzComponents {
        fun keep(reading: ShokzComponentReading?) = reading?.takeIf { it.sampledAtMs >= sessionStartedAtMs }
        return ShokzComponents(keep(left), keep(right), keep(case))
    }
}

object ShokzComponentFormatter {
    const val UNKNOWN = "—"

    /** `L 80% · R 80% · Case 60%`, with `—` for unknown parts. */
    fun compact(components: ShokzComponents): String =
        "L ${percent(components.left)} · R ${percent(components.right)} · Case ${percent(components.case)}"

    /** `Left 80% · Right 80% · Case 60%`, with `—` for unknown parts. */
    fun labeled(components: ShokzComponents): String =
        "Left ${percent(components.left)} · Right ${percent(components.right)} · Case ${percent(components.case)}"

    private fun percent(reading: ShokzComponentReading?): String = reading?.let { "${it.percent}%" } ?: UNKNOWN
}

enum class ShokzReadTrigger {
    /** Headset (re)connected or monitoring just started while it was connected. */
    CONNECTED,

    /** Android reported a new single battery percent for the headset. */
    BATTERY_CHANGED,

    /** User pressed Refresh now or the debug read button. */
    MANUAL,
}

/** Per-connection bookkeeping used to keep vendor reads rare. */
data class ShokzSessionState(
    val sessionStartedAtMs: Long,
    val lastAttemptAtMs: Long,
    val lastSuccessAtMs: Long,
    val blockedInSession: Boolean,
)

/**
 * Decides when an automatic vendor read is allowed.
 *
 * Opening the vendor channel while the official Shokz app holds it fails and also kicks that app off
 * the headset, so automatic reads are rare: once per connection, then at most every
 * [REFRESH_INTERVAL_MS] when Android reports a battery change, and never again in a connection after
 * the channel was refused. Manual reads always go through.
 */
object ShokzReadPolicy {
    /** Several profile-connect events arrive per connection; one read covers them all. */
    const val CONNECT_RETRY_MS = 60_000L
    const val REFRESH_INTERVAL_MS = 30 * 60_000L

    /** Gives the headset time to finish setting up audio profiles before the vendor channel opens. */
    const val CONNECT_READ_DELAY_MS = 5_000L

    fun shouldRead(trigger: ShokzReadTrigger, state: ShokzSessionState, nowMs: Long): Boolean {
        val sinceAttemptMs = nowMs - state.lastAttemptAtMs
        return when (trigger) {
            ShokzReadTrigger.MANUAL -> true
            ShokzReadTrigger.CONNECTED ->
                !state.blockedInSession &&
                    state.lastSuccessAtMs < state.sessionStartedAtMs &&
                    sinceAttemptMs >= CONNECT_RETRY_MS
            ShokzReadTrigger.BATTERY_CHANGED ->
                !state.blockedInSession && sinceAttemptMs >= REFRESH_INTERVAL_MS
        }
    }

    /** A refused channel most likely means the official app is connected; stop until reconnect. */
    fun blocksSession(failure: ShokzReadFailure): Boolean = failure == ShokzReadFailure.CONNECT_FAILED
}
