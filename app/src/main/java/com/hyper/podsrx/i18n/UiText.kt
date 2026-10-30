package com.hyper.podsrx.i18n

import androidx.annotation.StringRes

/** Keep persistent status text unresolved so changing language also updates existing messages. */
data class UiText(
    @param:StringRes val resource: Int,
    val arguments: List<Any?> = emptyList(),
    private val literal: String = "",
) {
    fun resolve(strings: Strings): String = if (resource == 0) literal else strings(resource,
        *arguments.map { if (it is UiText) it.resolve(strings) else it }.toTypedArray())

    fun isNotBlank(): Boolean = resource != 0 || literal.isNotBlank()
}

fun text(@StringRes resource: Int, vararg arguments: Any?): UiText = UiText(resource, arguments.toList())
fun text(literal: String): UiText = UiText(0, literal = literal)
