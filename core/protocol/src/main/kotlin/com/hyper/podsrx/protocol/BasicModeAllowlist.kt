package com.hyper.podsrx.protocol

/** Xiaomi's semicolon-delimited L2CAP interoperability property (91 ASCII bytes maximum). */
object BasicModeAllowlist {
    fun add(current: String, address: String): String {
        require(address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")))
        require(current.isEmpty() || current.endsWith(';'))
        if (current.split(';').any { it.equals(address, ignoreCase = true) }) return current
        val updated = current + address.lowercase(java.util.Locale.ROOT) + ";"
        check(updated.length <= 91) { "L2CAP basic-mode list is full" }
        return updated
    }
}
