package com.hyper.podsrx.protocol

/** AACP 1.2 subscription sequence used by the Android 16 HyperOS transport. */
object AacpInitialization {
    data class Step(val delayMs: Long, val packet: ByteArray)

    fun host(): List<Step> = listOf(
        Step(100, byteArrayOf(0, 0, 4, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        Step(100, Aacp.header + byteArrayOf(0x0f, 0, -1, -1, -2, -1)),
        Step(100, Aacp.notifications()),
        // Initialization subscribes/queries only; it must not change listening preferences (0x34).
        Step(200, Aacp.keyRequest(1)),
        Step(100, Aacp.keyRequest(4))
    )
}
