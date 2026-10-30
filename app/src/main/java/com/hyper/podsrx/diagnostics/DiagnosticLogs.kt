package com.hyper.podsrx.diagnostics

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.widget.Toast
import com.hyper.podsrx.BuildConfig
import com.hyper.podsrx.R
import com.hyper.podsrx.device.DeviceRepository
import com.hyper.podsrx.device.HostReloadCoordinator
import com.hyper.podsrx.device.HostReloadPolicy
import com.hyper.podsrx.hook.DiagnosticLog
import com.hyper.podsrx.hook.LogRedactor
import com.hyper.podsrx.i18n.UiText
import com.hyper.podsrx.i18n.stringResolver
import com.hyper.podsrx.i18n.text
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class LogExportState(
    val exporting: Boolean = false,
    val status: UiText = text(R.string.log_export_description),
)

object DiagnosticLogs {
    private val strings get() = DeviceRepository.app.stringResolver()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val records = Channel<String>(256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private lateinit var store: RotatingLogStore
    @Volatile private var storageError: String? = null
    private val hosts = listOf("com.android.settings", "com.xiaomi.bluetooth", "com.milink.service", "com.miui.securitycenter")
    private val exportRunning = AtomicBoolean(false)
    private val mutableExportState = MutableStateFlow(LogExportState())
    val exportState = mutableExportState.asStateFlow()

    fun initialize(app: Application) {
        store = RotatingLogStore(File(app.filesDir, "diagnostic-logs"))
        scope.launch {
            for (record in records) {
                runCatching { store.append(record) }
                    .onFailure { storageError = it.javaClass.simpleName }
            }
        }
        DiagnosticLog.setSink { records.trySend(it) }
        DiagnosticLog.i("HyperPods-X", "App started: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                DiagnosticLog.e("HyperPods-X", "Uncaught exception on ${thread.name}", error)
                // The normal async writers may not run before Android terminates the process.
                runCatching { store.append(DiagnosticLog.snapshot()) }
                runCatching { DeviceRepository.flushDiagnostics() }
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** An activity recreation must not cancel a save that the user already confirmed. */
    fun requestExport(context: Context, destination: Uri) {
        if (!exportRunning.compareAndSet(false, true)) return
        val app = context.applicationContext
        mutableExportState.value = LogExportState(true, text(R.string.collecting_logs))
        scope.launch {
            var result = text(R.string.log_export_cancelled)
            try {
                export(app, destination)
                result = text(R.string.log_exported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog.w("HyperPods-X", "Diagnostic export failed", error)
                result = text(R.string.log_export_failed)
            } finally {
                exportRunning.set(false)
                mutableExportState.value = LogExportState(false, result)
            }
            withContext(Dispatchers.Main) { Toast.makeText(app, result.resolve(strings), Toast.LENGTH_LONG).show() }
        }
    }

    private suspend fun export(context: Context, destination: Uri) = withContext(Dispatchers.IO) {
        DiagnosticLog.i("HyperPods-X", "Diagnostic export requested")
        DeviceRepository.flushDiagnostics()
        val hostLogs = collectHosts(context)
        val snapshot = DeviceRepository.diagnosticSnapshot().apply {
            remove("name")
            remove("log")
        }
        val environment = JSONObject()
            .put("exported_at", Instant.now().toString())
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("manufacturer", Build.MANUFACTURER)
            .put("phone_model", Build.MODEL)
            .put("android_version", Build.VERSION.RELEASE)
            .put("android_api", Build.VERSION.SDK_INT)
            .put("history_write_error", storageError ?: JSONObject.NULL)
            .put("host_reload", HostReloadCoordinator.status.resolve(strings))
            .put("host_restart", com.hyper.podsrx.device.HostRestartCoordinator.state.value.message.resolve(strings))
            .put("device", snapshot)
        val history = runCatching { store.snapshot() }
            .getOrElse { "History unavailable: ${it.javaClass.simpleName}\n" }
        val output = context.contentResolver.openOutputStream(destination, "w")
            ?: throw IOException("Cannot open export destination")
        ZipOutputStream(output.buffered()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("diagnostics.json", redactJson(environment).toString(2))
            entry("app-history.log", LogRedactor.redact(history))
            entry("app-session.log", DiagnosticLog.snapshot())
            for ((process, log) in hostLogs.toSortedMap()) {
                entry("hosts/${process.replace(':', '-')}.log", log)
            }
            entry("collection-status.txt", hosts.joinToString("\n") { host ->
                val processes = hostLogs.keys.filter { it == host || it.startsWith("${host}:") }.sorted()
                "${host}: ${if (processes.isEmpty()) strings(R.string.host_logs_missing) else processes.joinToString(", ")}"
            } + "\n")
            entry("README.txt", "HyperPods-X diagnostic logs\n\n"
                + "app-history.log: recent app logs, retained across restarts (up to 1 MiB).\n"
                + "app-session.log: current app session; overlaps recent history and includes queued records.\n"
                + "hosts/: this module's in-memory logs from currently running scoped host processes.\n"
                + "A missing host does not prevent export; see collection-status.txt.\n"
                + "Timestamps use UTC. Device addresses and key values are redacted before collection.\n"
                + "No preferences, identity keys, Bluetooth captures or other apps' logcat are included.\n")
        }
        DiagnosticLog.i("HyperPods-X", "Diagnostic export completed")
    }

    private suspend fun collectHosts(context: Context): Map<String, String> {
        val results = ConcurrentHashMap<String, String>()
        val accepting = AtomicBoolean(true)
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (!accepting.get() || resultCode != 1 || resultData == null) return
                val process = resultData.getString("process") ?: return
                if (process.length > 100 || !process.matches(Regex("[a-zA-Z0-9_.:-]+"))) return
                if (HostReloadPolicy.hostFor(process) == null || results.size >= 8) return
                val log = resultData.getString("log") ?: return
                results[process] = LogRedactor.limitUtf8(LogRedactor.redact(log), DiagnosticLog.MAX_BYTES)
            }
        }
        try {
            hosts.forEach { host ->
                runCatching {
                    context.sendBroadcast(Intent(DiagnosticLog.ACTION).setPackage(host)
                        .putExtra("created", SystemClock.elapsedRealtime()).putExtra("result", receiver))
                }.onFailure { DiagnosticLog.w("HyperPods-X", "Host log request unavailable: ${host}", it) }
            }
            delay(1500)
        } finally {
            accepting.set(false)
        }
        return results.toMap()
    }

    private fun redactJson(json: JSONObject): JSONObject {
        for (key in json.keys()) {
            when (val value = json.get(key)) {
                is String -> json.put(key, LogRedactor.redact(value))
                is JSONObject -> redactJson(value)
                is JSONArray -> for (index in 0 until value.length()) {
                    when (val item = value.get(index)) {
                        is String -> value.put(index, LogRedactor.redact(item))
                        is JSONObject -> redactJson(item)
                    }
                }
            }
        }
        return json
    }
}
