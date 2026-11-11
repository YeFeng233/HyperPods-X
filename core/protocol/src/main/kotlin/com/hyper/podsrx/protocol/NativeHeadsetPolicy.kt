package com.hyper.podsrx.protocol

/** Translation between the device center's ANC modes and AACP listening modes. */
object NativeHeadsetPolicy {
    @JvmStatic fun toAacpMode(nativeMode: Int): Int = when (nativeMode) {
        0 -> 2
        1 -> 3
        2 -> 1
        else -> 0
    }

    /** Adaptive has no native three-item equivalent. */
    @JvmStatic fun toNativeMode(aacpMode: Int): Int = when (aacpMode) {
        1 -> 2
        2 -> 0
        3 -> 1
        else -> -1
    }

    @JvmStatic fun guardNativeChannel(feature: String?, target: String?, bound: String?, appAlive: Boolean): Boolean =
        feature == "ConnectL2cap" && appAlive && !bound.isNullOrEmpty() && bound.equals(target, ignoreCase = true)

    /** Stable ten-slot OfflineFindProvider contract; unknown batteries use the ROM's dash marker. */
    @JvmStatic fun offlineState(
        leftWearing: Int, leftBattery: Int, rightWearing: Int, rightBattery: Int, caseBattery: Int,
        charging: Int, model: String, connected: Boolean,
    ): Array<String> = arrayOf(
        (leftWearing == 1).toString(), battery(leftBattery),
        (rightWearing == 1).toString(), battery(rightBattery), battery(caseBattery),
        (charging and 1 != 0).toString(), (charging and 2 != 0).toString(),
        (charging and 4 != 0).toString(), model, connected.toString(),
    )

    private fun battery(value: Int): String = if (value in 0..100) value.toString() else "-"
}
