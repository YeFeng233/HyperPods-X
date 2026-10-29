/* SPDX-License-Identifier: GPL-3.0-or-later
 * HyperPods-X: case status advertisement, reverse engineered from HCI captures, 2026.
 */
package com.hyper.podsrx.protocol

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The 07 11 message keeps coming while the lid is closed, which is how Apple devices follow the
 * case charging. Its address is a rotating static random one that the IRK cannot resolve, so the
 * owner is recognised by the structure of the payload decrypted with the proximity key instead.
 */
object CaseStatusCodec {
    const val LENGTH = 0x11
    private val ZERO = intArrayOf(8, 10, 11, 15)

    /** [message] starts at the 07 11 header. Returns left, right and case readings; unknown ones have no level. */
    fun decode(message: ByteArray, encryptionKey: ByteArray?): List<Battery>? {
        if (message.size != LENGTH + 2 || message.u(0) != 7 || message.u(1) != LENGTH || encryptionKey?.size != 16) return null
        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"))
            cipher.doFinal(message, 3, 16)
        }.getOrNull() ?: return null
        // Another key turns these always-zero bytes into noise. A Pro 3 case also sent 04 in byte 9
        // and 01 in byte 14, on its cable or off it; what they mean is not known.
        if (ZERO.any { plain[it].toInt() != 0 }) return null
        // 0xff marks an earbud outside the case; 0x80 shows up while it is being taken out.
        return listOf(4 to 4, 2 to 5, 8 to 3).map { (component, index) ->
            val raw = plain.u(index)
            val level = (raw and 0x7f).takeIf { raw != 0xff && it in 1..100 }
            Battery(component, level, level != null && raw and 0x80 != 0, level != null)
        }
    }
}
