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

import android.media.AudioManager
import android.media.AudioTrack
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import koeln.becker.nomla.mumble.testutil.LogRecorder
import koeln.becker.nomla.mumble.testutil.NoopOutputListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A track whose blocking write holds the playback thread until [open], or a pause if it may. */
@Suppress("ProtectedMemberInFinalClass") // Robolectric matches the framework's own signatures
@Implements(AudioTrack::class)
class BlockingAudioTrack : ShadowAudioTrack() {
    @Suppress("FunctionNaming", "UnusedParameter") // The framework's native method, name and parameters.
    @Implementation
    protected fun native_write_short(
        audioData: ShortArray,
        offsetInShorts: Int,
        sizeInShorts: Int,
        format: Int,
        isBlocking: Boolean,
    ): Int {
        writing.countDown()
        // Like the real write: an interrupt does not end it.
        while (true) {
            try {
                gate.await()
                break
            } catch (_: InterruptedException) {
                // keep blocking
            }
        }
        return sizeInShorts
    }

    @Suppress("FunctionNaming") // The framework's native method name.
    @Implementation
    protected fun native_pause() {
        if (pauseUnblocksWrite) gate.countDown()
    }

    companion object {
        @Volatile var gate = CountDownLatch(1)
        @Volatile var writing = CountDownLatch(1)
        @Volatile var pauseUnblocksWrite = true

        fun reset(pauseUnblocks: Boolean) {
            gate = CountDownLatch(1)
            writing = CountDownLatch(1)
            pauseUnblocksWrite = pauseUnblocks
        }

        fun open() = gate.countDown()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [BlockingAudioTrack::class])
class AudioOutputStopTest {
    @get:Rule
    val log = LogRecorder()

    private var playbackThread: Thread? = null

    @Before
    fun setUp() {
        ShadowAudioTrack.setMinBufferSize(MIN_BUFFER_BYTES)
    }

    @After
    fun tearDown() {
        BlockingAudioTrack.open()
        playbackThread?.join(TimeUnit.SECONDS.toMillis(5))
    }

    /** Starts [o] and waits until its playback thread blocks in the track's first write. */
    private fun blockedInWrite(o: AudioOutput): Thread {
        val thread = o.startPlaying(AudioManager.STREAM_MUSIC)!!
        playbackThread = thread
        assertWithMessage("the playback thread reached write")
            .that(BlockingAudioTrack.writing.await(5, TimeUnit.SECONDS)).isTrue()
        return thread
    }

    @Test
    fun `stopping ends a playback thread blocked in write`() {
        BlockingAudioTrack.reset(pauseUnblocks = true)
        val o = AudioOutput(NoopOutputListener, null)
        val thread = blockedInWrite(o)

        runBounded("stopPlaying") { o.stopPlaying() }

        thread.join(TimeUnit.SECONDS.toMillis(5))
        assertWithMessage("playback thread alive after stop").that(thread.isAlive).isFalse()
        assertThat(o.isPlaying).isFalse()
    }

    @Test
    fun `an interrupt of the stopping thread is kept`() {
        BlockingAudioTrack.reset(pauseUnblocks = false)
        val o = AudioOutput(NoopOutputListener, null)
        blockedInWrite(o)

        var interruptedAfter = false
        runBounded("interrupted stopPlaying") {
            Thread.currentThread().interrupt()
            o.stopPlaying()
            interruptedAfter = Thread.currentThread().isInterrupted
        }

        assertThat(interruptedAfter).isTrue()
        assertThat(o.isPlaying).isFalse()
    }

    @Test
    fun `a playback thread that does not end is given up after the join timeout`() {
        BlockingAudioTrack.reset(pauseUnblocks = false)
        val o = AudioOutput(NoopOutputListener, null, joinTimeoutMs = SHORT_JOIN_MS)
        val thread = blockedInWrite(o)
        log.clear()

        runBounded("stopPlaying with a stuck thread") { o.stopPlaying() }

        assertThat(o.isPlaying).isFalse()
        assertThat(o.playbackTrack()).isNull()
        assertThat(log.messages(AudioOutput::class.java.name).any { it.startsWith("playback thread still running") })
            .isTrue()
        assertWithMessage("a stuck thread blocks a new start").that(o.startPlaying(AudioManager.STREAM_MUSIC)).isNull()

        // Once its write returns, the thread ends by itself.
        BlockingAudioTrack.open()
        thread.join(TimeUnit.SECONDS.toMillis(5))
        assertThat(thread.isAlive).isFalse()
    }

    private fun runBounded(what: String, block: () -> Unit) {
        var failure: Throwable? = null
        val t = Thread {
            try {
                block()
            } catch (e: Throwable) {
                failure = e
            }
        }
        t.isDaemon = true
        t.start()
        t.join(TimeUnit.SECONDS.toMillis(5))
        assertWithMessage("$what did not return within 5 s").that(t.isAlive).isFalse()
        failure?.let { throw it }
    }

    private companion object {
        const val MIN_BUFFER_BYTES = 11520
        const val SHORT_JOIN_MS = 200L
    }
}
