/* SPDX-License-Identifier: GPL-3.0-or-later
 * Status mappings from LibrePods 2.0.0-alpha.3, Copyright (C) 2025 LibrePods contributors.
 * HyperPods-X: bounded conversation ownership policy, 2026.
 * See THIRD_PARTY.md for attribution and license terms.
 */
package com.hyper.podsrx.protocol

enum class ConversationAction { NONE, START, RELEASE }

/**
 * Owns one bounded conversation operation. The caller serializes access, validates
 * the packet subtype/session, and cancels ownership if its media operation fails.
 * Checks never start an operation; duplicate active reports never extend its lease.
 */
class ConversationPolicy(private val maxHoldMs: Long = 60_000) {
    init { require(maxHoldMs > 0) }

    private var startedAt: Long? = null
    private var lastObservedAt: Long? = null

    val active: Boolean get() = startedAt != null

    fun status(status: Int, eligible: Boolean, now: Long): ConversationAction {
        val expiration = check(eligible, now)
        // An event that discovers an expired lease cannot immediately acquire another.
        if (expiration == ConversationAction.RELEASE || !eligible) return expiration
        return when (status) {
            // The installed alpha.3 service (yw1.java) also treats 3 as active and 7 as ended.
            1, 2, 3 -> if (active) ConversationAction.NONE else {
                startedAt = now
                lastObservedAt = now
                ConversationAction.START
            }
            6, 7, 8, 9 -> cancel()
            else -> ConversationAction.NONE
        }
    }

    fun check(eligible: Boolean, now: Long): ConversationAction {
        if (!eligible) return cancel()
        val start = startedAt ?: return ConversationAction.NONE
        val age = now - start
        if (now < (lastObservedAt ?: start) || age < 0 || age >= maxHoldMs) return cancel()
        lastObservedAt = now
        return ConversationAction.NONE
    }

    fun cancel(): ConversationAction {
        val action = if (active) ConversationAction.RELEASE else ConversationAction.NONE
        startedAt = null
        lastObservedAt = null
        return action
    }
}
