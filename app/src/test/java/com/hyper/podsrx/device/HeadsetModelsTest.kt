package com.hyper.podsrx.device

import com.hyper.podsrx.hook.HeadsetModels
import org.junit.Assert.*
import org.junit.Test

class HeadsetModelsTest {
    @Test fun sessionModelNumbersSelectTheirProduct() {
        for (model in listOf("A3439", "A3440", "A3441")) assertSame(HeadsetModels.AIRPODS_5, HeadsetModels.of(model, -1))
        for (model in listOf("A3063", "A3064", "A3065")) assertSame(HeadsetModels.AIRPODS_PRO_3, HeadsetModels.of(model, -1))
    }

    @Test fun advertisedProductCoversALidOpenBeforeTheSession() {
        assertSame(HeadsetModels.AIRPODS_PRO_3, HeadsetModels.of("", 0x2720))
        assertSame(HeadsetModels.AIRPODS_PRO_3, HeadsetModels.of(null, 0x2720))
        // The model number reported by the session wins over a cached advertisement.
        assertSame(HeadsetModels.AIRPODS_5, HeadsetModels.of("A3441", 0x2720))
    }

    @Test fun unknownHeadsetsKeepTheOriginalArtworkWithoutNativeControls() {
        for ((model, product) in listOf("" to -1, "A3057" to -1, "Xiaomi" to 0x2014)) {
            val headset = HeadsetModels.of(model, product)
            assertSame(HeadsetModels.FALLBACK, headset)
            assertEquals("airpods5", headset.artwork)
            assertFalse(HeadsetModels.nativeControls(model))
        }
        assertTrue(HeadsetModels.nativeControls("A3065"))
        assertTrue(HeadsetModels.nativeControls("A3441"))
    }

    @Test fun pairAndCaseArtworkFollowTheProduct() {
        assertNotEquals(HeadsetModels.AIRPODS_5.pairArtwork, HeadsetModels.AIRPODS_PRO_3.pairArtwork)
        assertNotEquals(HeadsetModels.AIRPODS_5.caseArtwork, HeadsetModels.AIRPODS_PRO_3.caseArtwork)
        assertEquals(HeadsetModels.AIRPODS_5.pairArtwork, HeadsetModels.FALLBACK.pairArtwork)
    }
}
