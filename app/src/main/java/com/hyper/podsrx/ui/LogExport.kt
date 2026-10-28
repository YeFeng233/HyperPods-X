package com.hyper.podsrx.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.hyper.podsrx.diagnostics.DiagnosticLogs
import com.hyper.podsrx.hook.DiagnosticLog
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class LogExportAction(val busy: Boolean, val status: String, val export: () -> Unit)

internal val LocalLogExport = staticCompositionLocalOf<LogExportAction> {
    error("Log export host is required")
}

/** Kept above the appearance switch so either settings style uses the same export. */
@Composable
internal fun rememberLogExport(): LogExportAction {
    val context = LocalContext.current
    var choosingFile by rememberSaveable { mutableStateOf(false) }
    val operation by DiagnosticLogs.exportState.collectAsState()
    var pickerError by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        choosingFile = false
        if (uri != null) DiagnosticLogs.requestExport(context.applicationContext, uri)
    }
    val busy = choosingFile || operation.exporting
    return LogExportAction(busy, pickerError ?: operation.status) {
        if (!choosingFile && !DiagnosticLogs.exportState.value.exporting) {
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            try {
                choosingFile = true
                pickerError = null
                launcher.launch("HyperPods-X-logs-$time.zip")
            } catch (error: Exception) {
                choosingFile = false
                pickerError = "无法打开文件保存界面，请稍后重试"
                DiagnosticLog.w("HyperPods-X", "Document picker unavailable", error)
                Toast.makeText(context, pickerError, Toast.LENGTH_LONG).show()
            }
        }
    }
}
