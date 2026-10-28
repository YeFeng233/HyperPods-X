package com.hyper.podsrx.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostReloadPolicyTest {
    private val bluetooth = "com.xiaomi.bluetooth"
    private val milink = "com.milink.service"

    @Test fun appRestartAndFrameworkRebindDoNotResetAttemptBudget() {
        val initial = HostReloadPolicy("100:20", "", emptySet())
        assertTrue(initial.claim(bluetooth, "STALE", setOf(bluetooth)))
        assertFalse(initial.claim(bluetooth, "STALE", setOf(bluetooth)))
        val recreated = HostReloadPolicy("100:20", "100:20", initial.attemptedProcesses())
        assertFalse(recreated.claim(bluetooth, "STALE", setOf(bluetooth)))
    }

    @Test fun sameVersionReplacementGetsItsOwnAttemptBudget() {
        val previous = HostReloadPolicy.installation(100, 20)
        val updated = HostReloadPolicy.installation(200, 20)
        val policy = HostReloadPolicy(updated, previous, setOf(bluetooth))
        assertTrue(policy.claim(bluetooth, "STALE", setOf(bluetooth)))
    }

    @Test fun frameworkOwnedOrFailedReloadIsNeverResubmitted() {
        val policy = HostReloadPolicy("100:20", "", emptySet())
        for (state in listOf("UP_TO_DATE", "RELOADING", "FAILED", "UNKNOWN")) {
            assertFalse(policy.claim(bluetooth, state, setOf(bluetooth)))
        }
        assertTrue(policy.attemptedProcesses().isEmpty())
        assertTrue(policy.claim(bluetooth, "STALE", setOf(bluetooth)))
    }

    @Test fun scopeIsExactAndLimitedToOurThreeHosts() {
        val policy = HostReloadPolicy("100:20", "", emptySet())
        assertFalse(policy.claim(milink, "STALE", setOf(bluetooth)))
        assertFalse(policy.claim("com.android.bluetooth", "STALE", setOf("com.android.bluetooth")))
        assertFalse(policy.claim("$bluetooth.fake", "STALE", setOf(bluetooth)))
        assertTrue(policy.claim("$bluetooth:ui", "STALE", setOf(bluetooth)))
        assertTrue(policy.claim("$milink:core", "STALE", setOf(milink)))
    }

    @Test fun onlyTheWidgetProviderProcessOfTheSecurityCenterIsAHost() {
        val policy = HostReloadPolicy("100:20", "", emptySet())
        val center = "com.miui.securitycenter"
        assertTrue(policy.claim("$center.remote", "STALE", setOf(center)))
        assertFalse(policy.claim("$center.fake", "STALE", setOf(center)))
        assertFalse(policy.claim("$center.remote", "STALE", setOf(bluetooth)))
    }

    @Test fun bluetoothPrecedesDependentMilinkProcesses() {
        val processes = listOf("$milink:ui", "com.android.settings", bluetooth, "$milink:core", "$bluetooth:ui")
            .sortedWith(compareBy<String> { HostReloadPolicy.order(it) }.thenBy { it })
        assertEquals(listOf(bluetooth, "$bluetooth:ui", "$milink:core", "$milink:ui", "com.android.settings"), processes)
    }
}
