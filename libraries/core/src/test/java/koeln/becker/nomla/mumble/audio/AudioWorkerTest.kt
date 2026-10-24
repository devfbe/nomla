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

import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.mumble.testutil.awaitUntil
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The stop contract the audio threads rely on: a bounded join that says whether the thread really
 * ended, and a release that never runs while a thread may still be inside what it frees.
 */
@RunWith(RobolectricTestRunner::class)
class AudioWorkerTest {
    /** Opened at the end of each test, so a body that ignores the stop never outlives it. */
    private val gate = CountDownLatch(1)

    @After
    fun openGate() {
        gate.countDown()
    }

    /** Ignores the running flag, as a read wedged in native code does, until [gate] opens. */
    private fun AudioWorker.startWedged() {
        val entered = CountDownLatch(1)
        start {
            entered.countDown()
            awaitIgnoringInterrupts(gate)
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `a body that follows the flag ends, and the release runs at once on the caller`() {
        val worker = AudioWorker("test-worker")
        val loops = AtomicInteger()
        worker.start { isRunning -> while (isRunning()) loops.incrementAndGet() }
        awaitUntil { loops.get() > 0 }

        assertThat(worker.stop(TIMEOUT_MS)).isTrue()

        val releasedOn = CopyOnWriteArrayList<Thread>()
        assertThat(releaseAfter(listOf(worker)) { releasedOn += Thread.currentThread() }).isTrue()
        assertThat(releasedOn).containsExactly(Thread.currentThread())
    }

    @Test
    fun `unblock is what lets a blocking body return`() {
        val worker = AudioWorker("test-worker")
        val read = CountDownLatch(1)
        worker.start { awaitIgnoringInterrupts(read) }

        assertThat(worker.stop(TIMEOUT_MS) { read.countDown() }).isTrue()
    }

    @Test
    fun `a body that ignores the stop times out, and nothing is released under it`() {
        val worker = AudioWorker("test-worker")
        worker.startWedged()

        assertThat(worker.stop(SHORT_TIMEOUT_MS)).isFalse()

        val releasedOn = CopyOnWriteArrayList<Thread>()
        assertThat(releaseAfter(listOf(worker)) { releasedOn += Thread.currentThread() }).isFalse()
        assertThat(releasedOn).isEmpty()

        // The thread frees what it used as it ends, and only then.
        gate.countDown()
        awaitUntil { releasedOn.isNotEmpty() }
        assertThat(releasedOn).hasSize(1)
        assertThat(releasedOn.single().name).isEqualTo("test-worker")
    }

    @Test
    fun `with two threads the release waits for the last of them`() {
        val quick = AudioWorker("quick")
        val wedged = AudioWorker("wedged")
        quick.start { isRunning -> while (isRunning()) Thread.yield() }
        wedged.startWedged()

        assertThat(quick.stop(TIMEOUT_MS)).isTrue()
        assertThat(wedged.stop(SHORT_TIMEOUT_MS)).isFalse()
        val released = AtomicInteger()
        assertThat(releaseAfter(listOf(quick, wedged)) { released.incrementAndGet() }).isFalse()
        assertThat(released.get()).isEqualTo(0)

        gate.countDown()
        awaitUntil { released.get() == 1 }
        Thread.sleep(SHORT_TIMEOUT_MS)
        assertThat(released.get()).isEqualTo(1)
    }

    @Test
    fun `an interrupted caller stops waiting and keeps its interrupt`() {
        val worker = AudioWorker("test-worker")
        worker.startWedged()

        Thread.currentThread().interrupt()
        val ended = worker.stop(TIMEOUT_MS)

        assertThat(Thread.interrupted()).isTrue()
        assertThat(ended).isFalse()
    }

    @Test
    fun `a second stop does not wait again`() {
        val worker = AudioWorker("test-worker")
        worker.startWedged()
        assertThat(worker.stop(SHORT_TIMEOUT_MS)).isFalse()

        val before = System.nanoTime()
        assertThat(worker.stop(TIMEOUT_MS)).isFalse()
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)).isLessThan(TIMEOUT_MS)
    }

    @Test
    fun `a stop on the worker's own thread does not wait for itself`() {
        val worker = AudioWorker("test-worker")
        val answer = CopyOnWriteArrayList<Boolean>()
        val releasedInBody = CopyOnWriteArrayList<Int>()
        val released = AtomicInteger()
        worker.start {
            answer += worker.stop(TIMEOUT_MS)
            // As a callback on the thread stopping its owner would: the release waits for the end.
            releaseAfter(listOf(worker)) { released.incrementAndGet() }
            releasedInBody += released.get()
        }
        awaitUntil { released.get() == 1 }
        assertThat(answer).containsExactly(false)
        assertThat(releasedInBody).containsExactly(0)
    }

    @Test
    fun `a worker that never started has nothing to wait for`() {
        val worker = AudioWorker("test-worker")
        assertThat(worker.stop(TIMEOUT_MS)).isTrue()
        val released = AtomicInteger()
        assertThat(releaseAfter(listOf(worker)) { released.incrementAndGet() }).isTrue()
        assertThat(released.get()).isEqualTo(1)
    }

    @Test
    fun `a worker is started once`() {
        val worker = AudioWorker("test-worker")
        worker.start { }
        assertThat(worker.isStarted).isTrue()
        assertThrows(IllegalStateException::class.java) { worker.start { } }
        assertThat(worker.stop(TIMEOUT_MS)).isTrue()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SHORT_TIMEOUT_MS = 100L

        fun awaitIgnoringInterrupts(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    return
                } catch (_: InterruptedException) {
                    // A native read does not return on interrupt either.
                }
            }
        }
    }
}
