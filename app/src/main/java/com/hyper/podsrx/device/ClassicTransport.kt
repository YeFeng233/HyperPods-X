/* SPDX-License-Identifier: GPL-3.0-or-later
 * Socket constructor compatibility derived from LibrePods BluetoothConnectionManager.
 * Copyright (C) 2025 LibrePods contributors; adapted for HyperPods-X, 2026.
 */
package com.hyper.podsrx.device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import com.hyper.podsrx.protocol.Aacp
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException
import java.util.UUID

object ClassicTransport {
    @Suppress("MissingPermission")
    fun create(adapter: BluetoothAdapter, device: BluetoothDevice): BluetoothSocket {
        // The public LE-CoC API uses a different transport. AACP requires BR/EDR L2CAP.
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothSocket;")
        val uuid = ParcelUuid(UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"))
        val args: List<Array<out Any>> = listOf(
            arrayOf(adapter,device,3,true,true,Aacp.PSM,uuid),
            arrayOf(device,3,true,true,Aacp.PSM,uuid),
            arrayOf(device,3,1,true,true,Aacp.PSM,uuid),
            arrayOf(3,1,true,true,device,Aacp.PSM,uuid),
            arrayOf(3,true,true,device,Aacp.PSM,uuid)
        )
        var last: Throwable? = null
        for (values in args) {
            try {
                val types = values.map { when(it) { is Int -> Integer.TYPE; is Boolean -> java.lang.Boolean.TYPE; else -> it.javaClass } }.toTypedArray()
                val constructor = BluetoothSocket::class.java.getDeclaredConstructor(*types).apply { isAccessible=true }
                return constructor.newInstance(*values) as BluetoothSocket
            } catch (error: Throwable) { last=error }
        }
        throw IOException("当前系统的经典 L2CAP 接口不匹配",last)
    }
}
