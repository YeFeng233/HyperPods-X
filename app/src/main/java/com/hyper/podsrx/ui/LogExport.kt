package com.hyper.podsrx.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hyper.podsrx.R
import com.hyper.podsrx.diagnostics.DiagnosticLogs
import com.hyper.podsrx.hook.DiagnosticLog
import com.hyper.podsrx.i18n.rememberStrings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class LogExportAction(val busy: Boolean, val status: String, val export: () -> Unit)

internal val LocalLogExport = staticCompositionLocalOf<LogExportAction> {
    error("Log export host is required")
}

/** Keeps the export active while navigating between pages. */
@Composable
internal fun rememberLogExport(): LogExportAction {
    val strings = rememberStrings()
    val context = LocalContext.current
    var choosingFile by rememberSaveable { mutableStateOf(false) }
    val operation by DiagnosticLogs.exportState.collectAsStateWithLifecycle()
    var pickerError by rememberSaveable { mutableStateOf<Int?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        choosingFile = false
        if (uri != null) DiagnosticLogs.requestExport(context.applicationContext, uri)
    }
    val busy = choosingFile || operation.exporting
    val status = operation.status.takeUnless { it.resource == R.string.log_export_description }?.resolve(strings).orEmpty()
    return LogExportAction(busy, pickerError?.let { strings(it) } ?: status) {
        if (!choosingFile && !DiagnosticLogs.exportState.value.exporting) {
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            try {
                choosingFile = true
                pickerError = null
                launcher.launch("HyperPods-X-logs-${time}.zip")
            } catch (error: Exception) {
                choosingFile = false
                pickerError = R.string.file_picker_failed
                DiagnosticLog.w("HyperPods-X", "Document picker unavailable", error)
                Toast.makeText(context, strings(R.string.file_picker_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
}
