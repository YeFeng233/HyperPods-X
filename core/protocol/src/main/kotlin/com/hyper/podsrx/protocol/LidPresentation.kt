package com.hyper.podsrx.protocol

/** The presentation for one verified lid opening. */
enum class LidPresentation {
    SILENT,
    NOTIFICATION,
    COMPATIBILITY_CARD,
    NATIVE_DIALOG,
    NATIVE_ISLAND;

    companion object {
        @JvmStatic
        fun select(enabled: Boolean, interactive: Boolean, locked: Boolean, native: Boolean, desktop: Boolean) =
            when {
                !enabled -> SILENT
                !interactive || locked -> NOTIFICATION
                native && desktop -> NATIVE_DIALOG
                native -> NATIVE_ISLAND
                desktop -> COMPATIBILITY_CARD
                else -> SILENT
            }
    }
}
