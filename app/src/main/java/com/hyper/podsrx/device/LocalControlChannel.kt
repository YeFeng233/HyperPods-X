package com.hyper.podsrx.device

import android.bluetooth.BluetoothSocket
import com.hyper.podsrx.hook.ControlSocketWire

/** Retains the existing direct transport on platforms not using the host bridge. */
internal class LocalControlChannel(private val socket: BluetoothSocket): ControlSocketWire.Channel {
    override fun connect() = socket.connect()
    override fun isConnected(): Boolean = socket.isConnected
    override fun read(buffer: ByteArray): Int = socket.inputStream.read(buffer)
    override fun write(packet: ByteArray) {
        socket.outputStream.write(packet)
        socket.outputStream.flush()
    }
    override fun close() = socket.close()
}
