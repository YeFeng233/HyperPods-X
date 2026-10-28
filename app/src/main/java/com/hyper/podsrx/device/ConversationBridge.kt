package com.hyper.podsrx.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.hyper.podsrx.hook.ConversationWire
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Independent conversation leases; never starts or stops the spatial tracker. */
class ConversationBridge(context: Context) {
    companion object {
        private val sessions = AtomicLong(SystemClock.elapsedRealtimeNanos())
        private const val HOST = "com.xiaomi.bluetooth"
    }

    private class Session(
        val id: Long,
        val revision: Long,
        val address: String,
        val callback: (Bundle) -> Unit
    ) {
        val owner: IBinder = Binder()
        @Volatile var attempted = false
        var stopReply: Bundle? = null
        var heartbeat: Job? = null
    }

    private class Endpoint(val binder: IBinder, val death: IBinder.DeathRecipient)

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transactions = Mutex()
    private val lock = Any()
    private val operations = mutableSetOf<Job>()
    // START submission is uncertain until this exact lease receives a successful STOP.
    // Includes active and failed leases; clearing current must never lose cleanup ownership.
    private val unconfirmed = linkedMapOf<Long, Session>()
    private var endpoint: Endpoint? = null
    private var current: Session? = null
    // Retain the caller's session even when discovery failed before START was submitted.
    private var latestSession: Session? = null
    private var revision = 0L
    private var closed = false

    fun start(address: String, onResult: (Bundle) -> Unit) {
        synchronized(lock) {
            if (closed) {
                main.post { onResult(result(0, false, "对话桥接已关闭")) }
                return
            }
            val previous = current
            previous?.heartbeat?.cancel()
            val session = Session(sessions.incrementAndGet(), ++revision, address, onResult)
            current = session
            latestSession = session
            val pending = unconfirmed.values.toList()
            schedule {
                val stopped = stopAll(pending, session)
                if (!isCurrent(session)) return@schedule
                if (!stopped.getBoolean("stopConfirmed")) {
                    finish(session, Bundle(stopped).apply {
                        putString("status", "上一会话恢复待确认：${stopped.getString("status", "停止请求未确认")}")
                    })
                    return@schedule
                }
                val answer = request(session, ConversationWire.START)
                if (!isCurrent(session)) {
                    stopRemote(session)
                    return@schedule
                }
                if (!answer.getBoolean("ok") || !answer.getBoolean("active")) {
                    finish(session, answer)
                    return@schedule
                }
                publish(session.revision, session.callback, answer)
                heartbeat(session)
            }
        }
    }

    fun stop(onResult: ((Bundle) -> Unit)? = null) {
        synchronized(lock) {
            if (closed) {
                if (onResult != null) main.post { onResult(result(0, false, "对话桥接已关闭")) }
                return
            }
            val previous = current ?: latestSession
            current = null
            val stoppedRevision = ++revision
            previous?.heartbeat?.cancel()
            val pending = unconfirmed.values.toList()
            schedule {
                val answer = stopAll(pending, previous)
                if (onResult != null) publish(stoppedRevision, onResult, answer)
            }
        }
    }

    private fun heartbeat(session: Session) {
        synchronized(lock) {
            if (!isCurrent(session)) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                while (isCurrent(session)) {
                    delay(500)
                    val answer = request(session, ConversationWire.KEEPALIVE)
                    if (!isCurrent(session)) return@launch
                    if (!answer.getBoolean("ok") || !answer.getBoolean("active")) {
                        finish(session, answer)
                        return@launch
                    }
                    publish(session.revision, session.callback, answer)
                }
            }
            session.heartbeat = job
            job.start()
        }
    }

    private fun finish(session: Session, answer: Bundle) {
        synchronized(lock) {
            if (!isCurrent(session)) return
            current = null
            session.heartbeat?.cancel()
            val failure = Bundle(answer).apply {
                val pending = unconfirmed.isNotEmpty()
                putBoolean("ok", false)
                // Conservative activity while recovery is unknown, not a claim that
                // the host still has an active lease or has already restored audio.
                putBoolean("active", pending)
                putBoolean("activeKnown", !pending)
                putBoolean("cleanupPending", pending)
                putBoolean("stopConfirmed", !pending)
                putLong("session", session.id)
            }
            publish(session.revision, session.callback, failure)
            // Admit cleanup before releasing lock: close() must not race between
            // clearing current and registering the STOP operation.
            val pending = unconfirmed.values.toList()
            schedule { stopAll(pending, session) }
        }
    }

    private fun isCurrent(session: Session) = synchronized(lock) { !closed && current === session }

    /** Suppress callbacks queued before a newer start/stop, including a late START success. */
    private fun publish(expectedRevision: Long, callback: (Bundle) -> Unit, answer: Bundle) {
        val copy = Bundle(answer)
        main.post {
            if (synchronized(lock) { !closed && revision == expectedRevision }) callback(copy)
        }
    }

    // Called while holding lock so close() can join every operation already admitted.
    private fun schedule(block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        operations += job
        job.invokeOnCompletion { synchronized(lock) { operations -= job } }
        job.start()
    }

    private suspend fun stopRemote(session: Session): Bundle {
        if (!session.attempted) return nothingToStop(session.id)
        return request(session, ConversationWire.STOP)
    }

    private suspend fun stopAll(pending: List<Session>, requested: Session?): Bundle {
        // Public STOP responds for the session that the controller knows, even if
        // its START never reached Binder. Never reuse another session's cached reply.
        val responseId = requested?.id ?: sessions.incrementAndGet()
        var answer = synchronized(lock) { requested?.stopReply?.let { Bundle(it) } }
            ?: nothingToStop(responseId)
        val targets = pending.toMutableList()
        synchronized(lock) {
            if (requested != null && requested.attempted && requested.stopReply == null &&
                targets.none { it === requested }) targets += requested
        }
        var failed: Bundle? = null
        for (session in targets) {
            answer = stopRemote(session)
            if (!answer.getBoolean("stopConfirmed") && failed == null) failed = answer
        }
        return Bundle(failed ?: answer).apply {
            // Actual STOP packets above still use each lease's original owner/id.
            // This field correlates the aggregate result with the public stop request.
            putLong("session", responseId)
        }
    }

    private fun nothingToStop(id: Long) = result(id, true, "没有待清理的对话会话").apply {
        putBoolean("activeKnown", true)
        putBoolean("cleanupPending", false)
        putBoolean("stopConfirmed", true)
    }

    /** Only a real successful STOP reply clears an attempted lease. */
    private fun recordStop(session: Session, answer: Bundle): Bundle = synchronized(lock) {
        val confirmed = answer.getBoolean("ok") && !answer.getBoolean("active", true)
        val normalized = Bundle(answer).apply {
            putLong("session", session.id)
            putBoolean("ok", confirmed)
            putBoolean("active", !confirmed)
            putBoolean("activeKnown", confirmed)
            putBoolean("cleanupPending", !confirmed)
            putBoolean("stopConfirmed", confirmed)
        }
        if (confirmed) {
            session.stopReply = normalized
            if (unconfirmed[session.id] === session) unconfirmed.remove(session.id)
        }
        normalized
    }

    private suspend fun request(session: Session, code: Int): Bundle = transactions.withLock {
        if (code == ConversationWire.STOP) {
            synchronized(lock) { session.stopReply?.let { Bundle(it) } }?.let { return@withLock it }
        }
        if (code != ConversationWire.STOP && !isCurrent(session))
            return@withLock result(session.id, false, "对话会话已结束")
        var target: Endpoint? = null
        try {
            repeat(2) { attempt ->
                val connected = discover() ?: return@withLock failedRequest(session, code, "对话宿主未就绪，请检查小米蓝牙作用域")
                target = connected
                synchronized(lock) {
                    if (code != ConversationWire.STOP && !isCurrent(session))
                        return@withLock result(session.id, false, "对话会话已结束")
                    // Atomic with stop()/close(): they either capture this lease or prevent
                    // its START entirely. There is no unregistered in-flight START window.
                    if (code == ConversationWire.START) {
                        session.attempted = true
                        unconfirmed[session.id] = session
                    }
                }
                val answer = transact(connected.binder, session, code)
                if (answer.getBoolean("bridgeRetired")) {
                    discard(connected)
                    // The retired endpoint did not execute this request. Re-admit
                    // START under lock on the next attempt, in case stop() raced it.
                    if (attempt == 0) return@repeat
                }
                if (code == ConversationWire.START && !isCurrent(session)) {
                    // Keep the mutex until this late START is cleaned up. A newer START cannot
                    // overtake its STOP; the host also verifies the exact owner/session pair.
                    runCatching { stopOnEndpoint(session, connected) }
                    return@withLock result(session.id, false, "对话会话已结束")
                }
                if (code == ConversationWire.STOP) return@withLock recordStop(session, answer)
                // A rejected START can legitimately report session 0 or an older host
                // session. Preserve its diagnostic instead of masking it as a stale success.
                if (answer.getBoolean("ok") && answer.getBoolean("active") && answer.getLong("session", -1) != session.id)
                    return@withLock result(session.id, false, "对话宿主返回了过期会话")
                return@withLock Bundle(answer).apply {
                    if (answer.getBoolean("ok") && answer.getBoolean("active")) {
                        putBoolean("activeKnown", true)
                        putBoolean("cleanupPending", false)
                        putBoolean("stopConfirmed", false)
                    }
                }
            }
            error("对话桥接重连失败")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            target?.let { discard(it) }
            failedRequest(session, code, "对话桥接失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Already inside transactions: a late START still needs an acknowledged STOP. */
    private suspend fun stopOnEndpoint(session: Session, initial: Endpoint): Bundle {
        var target = initial
        repeat(2) { attempt ->
            val answer = try {
                transact(target.binder, session, ConversationWire.STOP)
            } catch (error: Exception) {
                discard(target)
                throw error
            }
            if (answer.getBoolean("bridgeRetired")) {
                discard(target)
                if (attempt == 0) {
                    target = discover() ?: return failedRequest(session, ConversationWire.STOP,
                        "更新后对话宿主尚未就绪")
                    return@repeat
                }
            }
            // Retirement itself never clears an unconfirmed lease.
            return recordStop(session, answer)
        }
        error("对话停止请求重连失败")
    }

    private fun failedRequest(session: Session, code: Int, status: String): Bundle {
        val answer = result(session.id, false, status)
        return if (code == ConversationWire.STOP) recordStop(session, answer) else answer
    }

    /** All synchronous Binder work runs on the independent IO scope. */
    private fun transact(binder: IBinder, session: Session, code: Int): Bundle {
        val input = Parcel.obtain()
        val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(ConversationWire.DESCRIPTOR)
            input.writeBundle(Bundle().apply {
                putString("address", session.address)
                putBinder("client", session.owner)
                putLong("session", session.id)
                putLong("captured", SystemClock.elapsedRealtime())
            })
            check(binder.transact(code, input, output, 0)) { "不支持的对话桥接版本" }
            output.readException()
            return output.readBundle(javaClass.classLoader) ?: error("对话桥接返回为空")
        } finally {
            input.recycle()
            output.recycle()
        }
    }

    private suspend fun discover(): Endpoint? {
        synchronized(lock) { endpoint?.takeIf { it.binder.isBinderAlive } }?.let { return it }
        val binder = withContext(Dispatchers.Main) {
            FrameworkBridge.publish()
            val reply = CompletableDeferred<IBinder?>()
            context.sendOrderedBroadcast(
                Intent(ConversationWire.ACTION).setPackage(HOST)
                    .putExtra("created", SystemClock.elapsedRealtime())
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        reply.complete(if (resultCode == 1) getResultExtras(false)?.getBinder("bridge") else null)
                    }
                }, main, 0, null, null
            )
            withTimeoutOrNull(1200) { reply.await() }
        } ?: return null
        val found = Endpoint(binder, IBinder.DeathRecipient { died(binder) })
        synchronized(lock) { endpoint = found }
        try {
            binder.linkToDeath(found.death, 0)
        } catch (error: Exception) {
            discard(found)
            throw error
        }
        return found
    }

    private fun discard(target: Endpoint) {
        synchronized(lock) { if (endpoint === target) endpoint = null }
        runCatching { target.binder.unlinkToDeath(target.death, 0) }
    }

    private fun died(binder: IBinder) {
        synchronized(lock) {
            if (endpoint?.binder !== binder) return
            endpoint = null
            val session = current ?: return
            finish(session, result(session.id, false, "小米蓝牙对话宿主已断开，媒体恢复待确认"))
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            ++revision
            val previous = current ?: latestSession
            current = null
            previous?.heartbeat?.cancel()
            val admitted = operations.toList()
            scope.launch {
                try {
                    // Do not cancel an in-flight START before its late response can be
                    // stopped. Owner death and the host lease remain independent guards.
                    admitted.forEach { it.join() }
                    previous?.heartbeat?.join()
                    val pending = synchronized(lock) { unconfirmed.values.toList() }
                    stopAll(pending, previous)
                } finally {
                    synchronized(lock) { endpoint }?.let { discard(it) }
                    scope.cancel()
                }
            }
        }
    }

    private fun result(session: Long, ok: Boolean, status: String) = Bundle().apply {
        putBoolean("ok", ok)
        putBoolean("active", false)
        putInt("players", 0)
        putString("status", status)
        putLong("session", session)
    }
}
