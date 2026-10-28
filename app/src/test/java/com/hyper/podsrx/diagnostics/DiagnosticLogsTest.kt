package com.hyper.podsrx.diagnostics

import com.hyper.podsrx.hook.DiagnosticLogBuffer
import com.hyper.podsrx.hook.LogRedactor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun addressesAreRedactedInErrorsAndJson() {
        val clean = LogRedactor.redact("connect AA:BB:CC:DD:EE:FF failed; {\"address\":\"aa-bb-cc-dd-ee-ff\"}")
        assertFalse(clean.contains("AA:BB"))
        assertFalse(clean.contains("aa-bb"))
        assertTrue(clean.contains("connect [DEVICE_ADDRESS] failed"))
    }

    @Test fun secretValuesAreRemovedInDifferentFormats() {
        val cases = listOf(
            "IRK=00112233445566778899aabbccddeeff",
            "enc_key: 00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff",
            "{\"serialNumber\":\"private-serial\"}",
            "link_key=[1, 2, 3, 4]",
            "password='private-password'",
        )
        for (input in cases) {
            val clean = LogRedactor.redact(input)
            assertTrue(clean, clean.contains("[REDACTED]"))
            assertFalse(clean, clean.contains("private-"))
            assertFalse(clean, clean.contains("00 11"))
            assertEquals(clean, LogRedactor.redact(clean))
        }
    }

    @Test fun unlabelledIdentityKeysAreRemoved() {
        assertEquals("key=[REDACTED_HEX]", LogRedactor.redact("key=00112233445566778899aabbccddeeff"))
    }

    @Test fun usefulConnectionAndTimingDataIsRetained() {
        val original = "A2DP/HFP reconnect: API=102 elapsedMs=317 model=A3441 stage=ack"
        assertEquals(original, LogRedactor.redact(original))
    }

    @Test fun utf8LimitNeverSplitsChineseOrEmoji() {
        val text = "日志🙂done"
        for (bytes in 0..text.toByteArray().size) {
            val limited = LogRedactor.limitUtf8(text, bytes)
            assertTrue(limited.toByteArray().size <= bytes)
            assertFalse(limited.contains('\uFFFD'))
            assertTrue(text.startsWith(limited))
        }
    }

    @Test fun byteLimitEvictsOldestWholeRecords() {
        val buffer = DiagnosticLogBuffer(16, 10)
        buffer.add("oldest")
        buffer.add("middle")
        buffer.add("latest")
        assertEquals("middle\nlatest\n", buffer.snapshot())
    }

    @Test fun recordCountIsBoundedEvenForShortLines() {
        val buffer = DiagnosticLogBuffer(1024, 2)
        repeat(10) { buffer.add("event-$it") }
        assertEquals("event-8\nevent-9\n", buffer.snapshot())
    }

    @Test fun oversizedRecordsCannotExceedMemoryLimitOrLeakKeys() {
        val buffer = DiagnosticLogBuffer(40, 10)
        buffer.add("IRK=00112233445566778899aabbccddeeff " + "日志".repeat(1000))
        val snapshot = buffer.snapshot()
        assertTrue(snapshot.toByteArray().size <= 40)
        assertFalse(snapshot.contains("00112233"))
        assertFalse(snapshot.contains('\uFFFD'))
    }

    @Test fun concurrentCallbacksKeepBoundedCompleteRecords() {
        val buffer = DiagnosticLogBuffer(1024, 20)
        val workers = Executors.newFixedThreadPool(4)
        repeat(4) { worker -> workers.submit { repeat(100) { buffer.add("worker-$worker-event-$it") } } }
        workers.shutdown()
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        val snapshot = buffer.snapshot()
        assertTrue(snapshot.toByteArray().size <= 1024)
        val lines = snapshot.lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(20, lines.size)
        assertTrue(lines.all { it.matches(Regex("worker-\\d-event-\\d+")) })
    }

    @Test fun historySurvivesReopeningAndRemainsInChronologicalOrder() {
        val directory = temporary.newFolder()
        val store = RotatingLogStore(directory, maxFileBytes = 12, fileCount = 3)
        store.append("event-1")
        store.append("event-2")
        store.append("event-3")
        assertEquals("event-1\nevent-2\nevent-3\n", RotatingLogStore(directory, 12, 3).snapshot())
    }

    @Test fun expiredHistoryIsDiscardedAndDiskUseIsBounded() {
        val directory = temporary.newFolder()
        val store = RotatingLogStore(directory, maxFileBytes = 12, fileCount = 3)
        repeat(10) { store.append("event-$it") }
        assertEquals("event-7\nevent-8\nevent-9\n", store.snapshot())
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 36)
    }

    @Test fun longUnicodeRecordsAreRedactedAndBoundedOnDisk() {
        val directory = temporary.newFolder()
        val store = RotatingLogStore(directory, maxFileBytes = 32, fileCount = 1)
        store.append("AA:BB:CC:DD:EE:FF " + "日志🙂".repeat(100))
        val text = store.snapshot()
        assertTrue(directory.listFiles()!!.single().length() <= 32)
        assertFalse(text.contains("AA:BB"))
        assertFalse(text.contains('\uFFFD'))
    }
}
