package com.owzo.btbatteryntfy.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

data class NtfySendResult(
    val success: Boolean,
    val details: String,
)

object NtfyClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun resolveEndpoint(serverUrlOrEndpoint: String, topic: String): String? {
        val rawUrl = serverUrlOrEndpoint.trim()
        if (rawUrl.isBlank()) {
            return null
        }

        val baseUrl = rawUrl.toHttpUrlOrNull() ?: return null
        val cleanTopic = topic.trim().trim('/')
        if (cleanTopic.isBlank()) {
            return baseUrl.toString()
        }

        val builder = baseUrl.newBuilder()
        cleanTopic
            .split('/')
            .filter { it.isNotBlank() }
            .forEach(builder::addPathSegment)
        return builder.build().toString()
    }

    fun send(
        endpoint: String,
        authorizationHeader: String,
        title: String,
        message: String,
    ): NtfySendResult {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(message.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .header("Title", title)
            .header("Tags", "battery")

        val resolvedAuthorizationHeader = resolveAuthorizationHeader(authorizationHeader)
        if (resolvedAuthorizationHeader.isNotBlank()) {
            requestBuilder.header("Authorization", resolvedAuthorizationHeader)
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    NtfySendResult(
                        success = true,
                        details = "HTTP ${response.code}",
                    )
                } else {
                    NtfySendResult(
                        success = false,
                        details = "HTTP ${response.code} ${response.message}",
                    )
                }
            }
        } catch (error: Exception) {
            NtfySendResult(
                success = false,
                details = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    fun resolveAuthorizationHeader(rawAuthorizationInput: String): String {
        val trimmed = rawAuthorizationInput.trim()
        if (trimmed.isBlank()) {
            return ""
        }

        if (trimmed.any(Char::isWhitespace)) {
            return trimmed
        }

        if (trimmed.substringBefore(' ').lowercase(Locale.US) in KNOWN_AUTH_SCHEMES) {
            return trimmed
        }

        if (':' in trimmed) {
            val encoded = Base64.getEncoder().encodeToString(trimmed.toByteArray(Charsets.UTF_8))
            return "Basic $encoded"
        }

        return trimmed
    }

    private val KNOWN_AUTH_SCHEMES = setOf(
        "basic",
        "bearer",
        "token",
        "digest",
        "ntfy",
    )
}
