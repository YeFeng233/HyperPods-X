package com.hyper.podsrx.diagnostics

import com.hyper.podsrx.hook.LogRedactor
import java.io.File

/** App-private history; no device preferences, captures or keys are read. */
internal class RotatingLogStore(
    private val directory: File,
    private val maxFileBytes: Int = 256 * 1024,
    private val fileCount: Int = 4,
) {
    init {
        require(maxFileBytes > 1 && fileCount in 1..8)
    }

    @Synchronized
    fun append(record: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create log directory" }
        val text = LogRedactor.limitUtf8(LogRedactor.redact(record).trimEnd(), maxFileBytes - 1) + "\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (file(0).length() + bytes.size > maxFileBytes) {
            check(!file(fileCount - 1).exists() || file(fileCount - 1).delete()) { "Cannot expire log file" }
            for (index in fileCount - 2 downTo 0) {
                if (file(index).exists()) check(file(index).renameTo(file(index + 1))) { "Cannot rotate log file" }
            }
        }
        file(0).appendBytes(bytes)
    }

    @Synchronized
    fun snapshot(): String = (fileCount - 1 downTo 0).mapNotNull { index ->
        file(index).takeIf { it.isFile }?.inputStream()?.use { stream ->
            stream.readNBytes(maxFileBytes).toString(Charsets.UTF_8)
        }
    }.joinToString("")

    private fun file(index: Int) = File(directory, "events.$index.log")
}
