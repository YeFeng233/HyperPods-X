/* SPDX-License-Identifier: GPL-3.0-or-later
 * Protocol definitions derived from LibrePods, Copyright (C) 2025 LibrePods contributors.
 * HyperPods-X: rewritten bounded parsers and command framing, 2026.
 * See THIRD_PARTY.md and LICENSE for attribution and license terms.
 */
package com.hyper.podsrx.protocol

data class Battery(val component: Int, val level: Int?, val charging: Boolean, val connected: Boolean)
sealed interface Event {
    data class Batteries(val values: List<Battery>) : Event
    data class Control(val id: Int, val value: List<Int>) : Event
    // The wire order is primary/secondary, not fixed left/right.
    data class Wearing(val primary: WearState, val secondary: WearState) : Event {
        val inEarCount: Int get() = listOf(primary, secondary).count { it == WearState.IN_EAR }
    }
    data class Information(
        val name: String, val model: String, val firmware: String,
        val internalFirmware: String = "", val firmwareIdentifier: String = "",
    ) : Event
    data class Equalizer(val state: Int, val low: Int, val mid: Int, val high: Int) : Event
    data class CardStatus(val flags: List<Int>) : Event
    // Preserve firmware values. The controller decides which subtype/status is supported.
    data class Conversation(val subtype: Int, val status: Int) : Event
    // Diagnostic source state only; the peer address stays out of events and logs.
    data class AudioSource(val state: Int) : Event
    data class Motion(val orientation:Quaternion,val service:Int):Event
    data class MotionAcknowledgement(val service:Int,val error:Long=0):Event
    class Keys(val values: Map<Int, ByteArray>) : Event { override fun toString() = "Keys(redacted)" }
    data class Unknown(val opcode: Int, val length: Int) : Event
}

object Aacp {
    const val PSM = 0x1001
    val header get() = byteArrayOf(4, 0, 4, 0)
    // LibrePods 2.0.0-alpha.3 negotiates AACP 1.3 with the AirPods 5 family.
    val handshake get() = byteArrayOf(0, 0, 4, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    fun notifications() = header + byteArrayOf(0x0f, 0, -1, -1, -1, -1)
    fun features() = header + byteArrayOf(0x4d, 0, 0xd7.toByte(), 0, 0, 0, 0, 0, 0, 0)
    fun keyRequest(type: Int): ByteArray { require(type == 1 || type == 4); return header + byteArrayOf(0x30, 0, type.toByte(), 0) }
    fun control(id: Int, values: List<Int>): ByteArray {
        require(id in 0..255 && values.size in 1..4 && values.all { it in 0..255 })
        return header + byteArrayOf(9, 0, id.toByte()) + ByteArray(4) { values.getOrElse(it) { 0 }.toByte() }
    }
    fun rename(name: String): ByteArray {
        val data = name.trim().toByteArray(Charsets.UTF_8)
        require(data.size in 1..32 && 0.toByte() !in data)
        return header + byteArrayOf(0x1a, 0, 1, data.size.toByte(), 0) + data
    }
    fun equalizer(values: List<Int>): ByteArray {
        require(values.size == 4 && values[0] in 1..2 && values.drop(1).all { it in 0..100 })
        return header + byteArrayOf(0x63, 0, 5, 0, 1) + values.map { it.toByte() }.toByteArray()
    }
    fun decode(packet: ByteArray): Event? {
        if (packet.size < 6 || !packet.copyOfRange(0, 4).contentEquals(header)) return null
        val opcode = packet.u(4)
        return when (opcode) {
            4 -> {
                if (packet.size < 7) return null
                val count = packet.u(6)
                if (count !in 1..8 || packet.size < 7 + count * 5) return null
                val values = (0 until count).mapNotNull { i ->
                    val pos = 7 + i * 5
                    val component = packet.u(pos)
                    if (component !in setOf(2, 4, 8)) return@mapNotNull null
                    val status = packet.u(pos + 3)
                    Battery(component, packet.u(pos + 2).takeIf { it <= 100 && status != 4 }, status == 1, status != 4)
                }
                Event.Batteries(values)
            }
            9 -> if (packet.size >= 8) Event.Control(packet.u(6), packet.drop(7).map { it.toInt() and 255 }) else null
            6 -> {
                if (packet.size != 8) return null
                val primary = WearState.fromWire(packet.u(6)) ?: return null
                val secondary = WearState.fromWire(packet.u(7)) ?: return null
                Event.Wearing(primary, secondary)
            }
            0x1d -> AacpInformation.decode(packet)
            0x31 -> {
                if (packet.size < 7 || packet.u(6) > 8) return null
                val keys = mutableMapOf<Int, ByteArray>()
                var pos = 7
                repeat(packet.u(6)) {
                    if (pos + 4 > packet.size) return null
                    val type = packet.u(pos) or (packet.u(pos+1) shl 8)
                    val length = packet.u(pos + 2) or (packet.u(pos + 3) shl 8)
                    pos += 4
                    if (length > 256 || pos + length > packet.size) return null
                    if (type in setOf(1, 4) && length == 16) keys[type] = packet.copyOfRange(pos, pos + length)
                    pos += length
                }
                Event.Keys(keys)
            }
            0x63 -> if (packet.size >= 13 && packet.u(6) == 5 && packet.u(7) == 0
                && packet.u(9) in 1..2 && (10..12).all { packet.u(it) <= 100 })
                Event.Equalizer(packet.u(9),packet.u(10),packet.u(11),packet.u(12)) else null
            0x4e -> if(packet.size == 14) Event.CardStatus(packet.drop(6).map{it.toInt() and 255}) else null
            0x4b -> {
                val length = conversationFrameLength(packet) ?: return null
                if (length < 0 || packet.size != length) return null
                if (length == 10) Event.Conversation(packet.u(8), packet.u(9))
                else Event.Unknown(opcode, packet.size)
            }
            0x0e -> if (packet.u(5) == 0 && packet.size == 13 && packet.u(12) in 0..2)
                Event.AudioSource(packet.u(12)) else null
            0x17 -> MotionCodec.decode(packet)?:MotionCodec.acknowledgement(packet)?:Event.Unknown(opcode,packet.size)
            else -> Event.Unknown(opcode, packet.size)
        }
    }
    /** Includes the eight-byte header; unknown small payloads remain opaque. */
    internal fun conversationFrameLength(packet: ByteArray): Int? {
        if (packet.size < 8) return null
        if (packet.u(5) != 0) return -1
        val payloadLength = packet.u(6) or (packet.u(7) shl 8)
        // The known report has two payload bytes. Bound future/unknown variants
        // so a corrupt length cannot hold the shared control/motion stream open.
        return if (payloadLength in 1..64) 8 + payloadLength else -1
    }
}
internal fun ByteArray.u(index: Int) = this[index].toInt() and 255

/** Frame known fixed/length-bearing messages; unknown messages retain their L2CAP SDU boundary. */
class AacpFramer {
    private var pending = byteArrayOf()
    fun feed(data: ByteArray): List<ByteArray> {
        if (pending.size + data.size > 65536) { pending = byteArrayOf(); return emptyList() }
        pending += data
        val result = mutableListOf<ByteArray>()
        while (pending.isNotEmpty()) {
            if (pending.size < 4) break
            if (pending.take(4) == listOf<Byte>(0, 0, 4, 0)) {
                if (pending.size < 16) break
                pending = pending.drop(16).toByteArray(); continue
            }
            if (!pending.copyOfRange(0, 4).contentEquals(Aacp.header)) {
                pending = pending.drop(1).toByteArray(); continue
            }
            if (pending.size < 7) break
            val size = when (pending.u(4)) {
                4 -> 7 + pending.u(6) * 5
                9 -> 11
                6 -> 8
                0x31 -> keyLength(pending) ?: break
                0x63 -> 13
                0x4e -> 14
                0x4b -> Aacp.conversationFrameLength(pending) ?: break
                0x0e -> 13
                0x17 -> MotionCodec.frameLength(pending)?:break
                else -> (4..pending.size - 4).firstOrNull { pending.copyOfRange(it, it + 4).contentEquals(Aacp.header) } ?: pending.size
            }
            if (size !in 6..4096) {
                if (pending.u(4) == 0x4b) {
                    // Resynchronize on an actual AACP header. Byte-at-a-time
                    // skipping could mistake corrupt length bytes followed by
                    // the next frame for a 16-byte handshake and swallow it.
                    val next = (4..pending.size - 4).firstOrNull {
                        pending.copyOfRange(it, it + 4).contentEquals(Aacp.header)
                    }
                    if (next != null) { pending = pending.drop(next).toByteArray(); continue }
                    val prefix = (3 downTo 1).firstOrNull {
                        pending.takeLast(it).toByteArray().contentEquals(Aacp.header.copyOf(it))
                    } ?: 0
                    pending = pending.takeLast(prefix).toByteArray(); break
                }
                pending = byteArrayOf(); break
            }
            if (pending.size < size) break
            result += pending.copyOfRange(0, size)
            pending = pending.copyOfRange(size, pending.size)
        }
        return result
    }
    private fun keyLength(p: ByteArray): Int? {
        if (p.u(6) > 8) return 65537
        var pos = 7
        repeat(p.u(6)) {
            if (pos + 4 > p.size) return null
            val length = p.u(pos + 2) or (p.u(pos + 3) shl 8)
            if (length > 256) return 65537
            pos += 4 + length
        }
        return pos
    }
}
