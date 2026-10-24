/*
 * Copyright (C) 2026 The Nomla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package koeln.becker.nomla.mumble.audio

import android.os.Process
import koeln.becker.nomla.mumble.util.NomlaLog
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "AudioWorker"

/**
 * One bare audio thread: a running flag, the thread, and a stop that unblocks it and joins with a
 * bound. Single use: [start] once, [stop] once (a second [stop] does not wait again).
 *
 * A join can time out while the thread is still inside native code (a wedged recorder), so whoever
 * owns the native state the thread uses frees it through [releaseAfter], never right after [stop]:
 * that frees it at once if the thread has ended, and otherwise hands it to the thread, which frees
 * it as it ends.
 *
 * @param priority a `Process.THREAD_PRIORITY_*` the thread sets first, or null to keep the default.
 */
internal class AudioWorker(private val name: String, private val priority: Int? = null) {
    @Volatile
    private var running = false

    private val lock = Any()

    // Guarded by lock.
    private var thread: Thread? = null
    private var ended = false
    private var onEnd: (() -> Unit)? = null

    /** Only the stopping thread reads and writes this. */
    private var stopped = false

    /** Whether [start] has been called; a worker is not reusable. */
    val isStarted: Boolean get() = synchronized(lock) { thread != null }

    /**
     * Starts the thread, which runs [body] until it returns. [body] loops while its `isRunning`
     * answers true.
     *
     * @throws IllegalStateException when started before.
     */
    fun start(body: (isRunning: () -> Boolean) -> Unit) {
        val t = synchronized(lock) {
            check(thread == null) { "$name already started" }
            running = true
            Thread({ run(body) }, name).also { thread = it }
        }
        var started = false
        try {
            t.start()
            started = true
        } finally {
            // A thread that never ran has ended as far as any release is concerned.
            if (!started) {
                running = false
                synchronized(lock) { ended = true }
            }
        }
    }

    private fun run(body: (isRunning: () -> Boolean) -> Unit) {
        try {
            priority?.let(Process::setThreadPriority)
            body { running }
        } finally {
            // From here on the thread touches nothing of its owner's: hand over to the release.
            val action = synchronized(lock) {
                ended = true
                onEnd.also { onEnd = null }
            }
            action?.invoke()
        }
    }

    /** Tells the thread to end without waiting for it, so several workers can be told at once. */
    fun requestStop() {
        running = false
    }

    /**
     * Tells the thread to end, calls [unblock] (which must make a blocking read or write return),
     * and joins for up to [timeoutMs]. An interrupt of the calling thread during the join ends the
     * wait early and is restored on it. Called on the worker's own thread, it does not wait.
     *
     * @return whether the thread really ended. `false` means it may still use native state, so the
     *   caller must not free it; [releaseAfter] does that safely either way.
     */
    fun stop(timeoutMs: Long, unblock: () -> Unit = {}): Boolean {
        running = false
        val t = synchronized(lock) { thread }
        if (t != null && !stopped) {
            stopped = true
            unblock()
            if (t !== Thread.currentThread()) join(t, timeoutMs)
            if (t.isAlive) NomlaLog.w(TAG, "$name still running after $timeoutMs ms; it frees its state as it ends")
        }
        return t?.isAlive != true
    }

    private fun join(t: Thread, timeoutMs: Long) {
        try {
            t.join(timeoutMs)
        } catch (_: InterruptedException) {
            // Restore the flag so callers further up see the cancellation.
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Makes [action] run on this thread as it ends.
     *
     * @return false, without keeping [action], when no thread runs: never started, or already ended.
     */
    fun runOnEnd(action: () -> Unit): Boolean = synchronized(lock) {
        if (thread == null || ended) return false
        check(onEnd == null) { "$name already has an end action" }
        onEnd = action
        true
    }
}

/**
 * Runs [release] exactly once, as soon as the caller and every thread of [workers] that still
 * runs have let go: now, on the caller's thread, when all of them have ended, or otherwise on the
 * last of them to end. Call it after stopping [workers].
 *
 * @return whether [release] ran now.
 */
internal fun releaseAfter(workers: List<AudioWorker>, release: () -> Unit): Boolean {
    // The caller's own hold keeps the count above zero until every worker has been asked.
    val holders = AtomicInteger(1)
    val drop = { if (holders.decrementAndGet() == 0) release() }
    for (worker in workers) {
        holders.incrementAndGet()
        if (!worker.runOnEnd(drop)) holders.decrementAndGet()
    }
    val now = holders.decrementAndGet() == 0
    if (now) release()
    return now
}
