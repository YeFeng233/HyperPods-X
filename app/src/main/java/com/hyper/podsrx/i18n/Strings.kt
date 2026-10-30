package com.hyper.podsrx.i18n

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Injected into presentation logic so it stays independent of Android and the current locale. */
class Strings(private val resolve: (Int, Array<out Any?>) -> String) {
    operator fun invoke(@StringRes id: Int, vararg arguments: Any?): String = resolve(id, arguments)
}

fun Context.stringResolver(): Strings = Strings { id, arguments -> getString(id, *arguments) }

@Composable
internal fun rememberStrings(): Strings {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { context.stringResolver() }
}
