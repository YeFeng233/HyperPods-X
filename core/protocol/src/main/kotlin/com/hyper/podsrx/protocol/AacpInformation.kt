package com.hyper.podsrx.protocol

/** Metadata strings are positional, including empty values and private identity fields. */
internal object AacpInformation {
    private val modelNumber = Regex("A[0-9]{4}")
    private val publicBuild = Regex("[0-9]{1,3}[A-Z][0-9]{1,6}[a-z]?")
    private val internalVersion = Regex("[0-9]+(?:\\.[0-9]+){1,4}")
    private val numericIdentifier = Regex("[0-9]{1,20}")

    fun decode(packet: ByteArray): Event.Information? {
        if (packet.size !in 7..4096) return null
        // Drop only the unterminated tail, never empty slots between fields.
        val fields = packet.copyOfRange(6, packet.size).toString(Charsets.UTF_8).split('\u0000').dropLast(1)
        val modelIndex = (1 until minOf(fields.size, 6)).firstOrNull {
            modelNumber.matches(fields[it]) && fields.getOrNull(it + 1) == "Apple Inc."
        } ?: return null
        fun version(offset: Int) = fields.getOrNull(modelIndex + offset).orEmpty().takeIf { it.length <= 64 }.orEmpty()
        val version1 = version(3)
        val version2 = version(4)
        val version3 = version(9)
        // Serial numbers, hardware revisions and updater IDs are NOT firmware candidates.
        val firmware = listOf(version3, version1, version2).firstOrNull(publicBuild::matches).orEmpty()
        val internal = listOf(version1, version2).firstOrNull(internalVersion::matches).orEmpty()
        val identifier = version3.takeIf(numericIdentifier::matches).orEmpty()
        return Event.Information(fields[modelIndex - 1].ifBlank { "AirPods" }, fields[modelIndex], firmware, internal, identifier)
    }
}
