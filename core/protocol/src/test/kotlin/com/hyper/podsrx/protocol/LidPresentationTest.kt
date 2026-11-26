package com.hyper.podsrx.protocol

import com.hyper.podsrx.protocol.LidPresentation.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LidPresentationTest {
    @Test fun unlockedOpenUsesTheForegroundAndNativeSetting() {
        assertEquals(NATIVE_DIALOG, LidPresentation.select(true, true, false, true, true))
        assertEquals(NATIVE_ISLAND, LidPresentation.select(true, true, false, true, false))
        assertEquals(COMPATIBILITY_CARD, LidPresentation.select(true, true, false, false, true))
        assertEquals(SILENT, LidPresentation.select(true, true, false, false, false))
    }

    @Test fun unavailableScreenUsesNotificationsOnlyWhenTheLidPopupIsEnabled() {
        assertEquals(NOTIFICATION, LidPresentation.select(true, true, true, true, true))
        assertEquals(NOTIFICATION, LidPresentation.select(true, false, false, false, false))
        assertEquals(SILENT, LidPresentation.select(false, true, true, true, true))
        assertEquals(SILENT, LidPresentation.select(false, true, false, true, true))
    }
}
