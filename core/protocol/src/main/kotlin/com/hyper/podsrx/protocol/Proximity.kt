/* SPDX-License-Identifier: GPL-3.0-or-later
 * Based on BLEManager/BluetoothCryptography in LibrePods (C) 2025 LibrePods contributors.
 * HyperPods-X: strict identity validation, null handling, and lid-session state, 2026.
 */
package com.hyper.podsrx.protocol

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class Proximity(val modelId: Int, val lidOpen: Boolean, val batteries: List<Battery>, val precise: Boolean)
/** Apple manufacturer data may contain several length-prefixed Continuity messages. */
object AppleAdvertisement {
    fun proximityMessages(data: ByteArray): List<ByteArray> {
        val messages=mutableListOf<ByteArray>()
        var offset=0
        while(offset+2<=data.size) {
            val length=data.u(offset+1)
            val end=offset+2+length
            if(length==0 || end>data.size)break
            if(data.u(offset)==7)messages+=data.copyOfRange(offset,end)
            offset=end
        }
        return messages
    }
}
object ProximityCodec {
    fun matchesIdentity(address: String, irk: ByteArray): Boolean = runCatching {
        if (irk.size != 16 || !address.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) return false
        val rpa = address.split(':').map { it.toInt(16).toByte() }.reversed().toByteArray()
        if ((rpa.u(5) and 0xc0) != 0x40) return false
        val block = ByteArray(16).also { rpa.copyInto(it, 0, 3, 6) }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(irk.reversedArray(), "AES"))
        val hash = cipher.doFinal(block.reversedArray()).reversedArray().copyOf(3)
        hash.contentEquals(rpa.copyOf(3))
    }.getOrDefault(false)

    fun decode(data: ByteArray, encryptionKey: ByteArray? = null): Proximity? {
        if (data.size < 11 || data.u(0) != 7 || data.u(1) + 2 != data.size) return null
        val flags = data.u(5)
        val swapped = (flags and 0x20) == 0
        val decrypted = if (data.size >= 27 && encryptionKey?.size == 16) runCatching {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"))
            cipher.doFinal(data.takeLast(16).toByteArray())
        }.getOrNull() else null
        val batteries = if (decrypted != null) {
            listOf(4 to (if (swapped) 2 else 1), 2 to (if (swapped) 1 else 2), 8 to 3).map { (component, i) ->
                val raw = decrypted.u(i)
                Battery(component, (raw and 0x7f).takeIf { it <= 100 && raw != 255 }, raw and 128 != 0 && raw != 255, raw != 255)
            }
        } else {
            val rawLeft = if (swapped) data.u(6) shr 4 else data.u(6) and 15
            val rawRight = if (swapped) data.u(6) and 15 else data.u(6) shr 4
            val charge = data.u(7) shr 4
            listOf(Triple(4, rawLeft, if (swapped) 2 else 1), Triple(2, rawRight, if (swapped) 1 else 2), Triple(8, data.u(7) and 15, 4)).map { (component, n, mask) ->
                Battery(component, when (n) { in 0..9 -> n * 10; in 10..14 -> 100; else -> null }, charge and mask != 0, n != 15)
            }
        }
        return Proximity((data.u(3) shl 8) or data.u(4), data.u(8) and 8 == 0, batteries, decrypted != null)
    }
}

/** Scan batches can contain old or out-of-order lid states. Use capture time, then settle. */
class LidSession(
    private val cooldownMs: Long = 3000,
    private val settleMs: Long = 900,
    private val maxAgeMs: Long = 15000
) {
    var open: Boolean? = null
        private set
    private var lastObserved = Long.MIN_VALUE / 2
    private var candidateSince: Long? = null
    private var notified = false
    private var lastPopup = Long.MIN_VALUE / 2
    /** Returns whether this sample is newer and fresh enough to update device state. */
    fun observe(value: Boolean, capturedAt: Long, now: Long): Boolean {
        if (capturedAt <= lastObserved || now - capturedAt !in 0..maxAgeMs) return false
        lastObserved = capturedAt
        if (open != value) {
            open = value
            notified = value && now - lastPopup < cooldownMs
            candidateSince = if (value && !notified) now else null
        }
        return true
    }
    fun confirmationDelay(now: Long): Long? = candidateSince?.let { (it + settleMs - now).coerceAtLeast(0) }
    /** Called after the entire batch has been processed, on the same serial event loop. */
    fun confirm(now: Long): Boolean {
        val since = candidateSince ?: return false
        if (open != true || notified || now - since < settleMs || now - lastObserved !in 0..maxAgeMs) return false
        candidateSince = null
        notified = true
        lastPopup = now
        return true
    }
    fun expire() { /* silence never proves the physical lid was closed */ }
    fun reset() { open = null; lastObserved = Long.MIN_VALUE / 2; candidateSince = null; notified = false }
}
