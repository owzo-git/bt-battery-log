package com.owzo.btbatteryntfy.monitor

import android.bluetooth.BluetoothDevice

object BluetoothBatteryProbe {
    fun readBatteryLevel(device: BluetoothDevice): Int? {
        return try {
            val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
            val value = method.invoke(device) as? Int ?: return null
            value.takeIf { it in 0..100 }
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}

