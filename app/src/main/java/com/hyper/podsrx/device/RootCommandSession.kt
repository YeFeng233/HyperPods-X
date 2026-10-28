package com.hyper.podsrx.device

import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/** Interactive stdin stays empty of restart commands until root authorization succeeds. */
internal class RootCommandSession : Closeable {
    private val process = ProcessBuilder("su").redirectErrorStream(true).start()
    private val writer = process.outputStream.bufferedWriter()
    private val lines = LinkedBlockingQueue<String>(2048)
    private var sequence = 0
    private val reader = thread(name = "scope-root-output", isDaemon = true) {
        try {
            process.inputStream.bufferedReader().use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.length > 8192 || !lines.offer(line)) break
                }
            }
        } catch (_: java.io.IOException) {
            // Closing the shell interrupts its output reader; this is not an app crash.
        } finally { lines.offer(EOF) }
    }

    fun authorize() {
        val reply = execute("id -u", 45)
        check(reply.code == 0 && reply.output.any { it.trim() == "0" }) { "Root permission denied" }
    }

    fun execute(command: String, timeoutSeconds: Long = 10): Reply {
        val marker = "HYPERPODS_COMMAND_${++sequence}:"
        writer.write(command)
        writer.write("\nprintf '\\n$marker%s\\n' \"\$?\"\n")
        writer.flush()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val output = mutableListOf<String>()
        var bytes = 0
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("Root command timed out")
            val line = lines.poll(remaining, TimeUnit.NANOSECONDS) ?: throw TimeoutException("Root command timed out")
            check(line != EOF) { "Root shell closed or permission denied" }
            if (line.startsWith(marker)) return Reply(line.removePrefix(marker).toInt(), output)
            bytes += line.length
            check(bytes <= 128 * 1024) { "Root output limit exceeded" }
            output += line
        }
    }

    override fun close() {
        // Closing stdin first also cancels commands when a late authorization opens the shell.
        runCatching { writer.close() }
        process.destroy()
        if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        runCatching { process.inputStream.close() }
        reader.join(300)
    }

    data class Reply(val code: Int, val output: List<String>)
    private companion object { const val EOF = "\u0000" }
}
