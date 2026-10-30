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

package koeln.becker.nomla.mumble.diagnostics

import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.mumble.audio.PcmPlaybackSink
import koeln.becker.nomla.mumble.testutil.TestPlaybackSink
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A recording's track played on its own sink, at its own rate, on its own thread (B11), and a stop
 * that ends it and frees the sink exactly once. Fake sinks only: nothing is ever heard.
 */
@RunWith(RobolectricTestRunner::class)
class TrackPlayerTest {
    private val players = mutableListOf<TrackPlayer>()

    @After
    fun stopAll() {
        players.forEach { it.stop() }
    }

    private fun player(sink: PcmPlaybackSink): Pair<TrackPlayer, TestPlaybackSink.Factory> {
        val factory = TestPlaybackSink.Factory(sink)
        return TrackPlayer(factory, AudioManager.STREAM_VOICE_CALL).also { players += it } to factory
    }

    /** One second at [RATE]. */
    private fun track() =
        CapturedTrack("3_apm", RATE, ShortArray(RATE) { (it % SHORT_RANGE).toShort() }, sendSide = true)

    @Test
    fun `plays the whole track at its rate and reports the end`() {
        val sink = TestPlaybackSink()
        val (player, factory) = player(sink)
        val track = track()
        val positions = CopyOnWriteArrayList<Long>()
        val ended = CountDownLatch(1)

        player.play(track, onPosition = { positions += it }, onEnd = { ended.countDown() })

        assertThat(ended.await(TIMEOUT_S, TimeUnit.SECONDS)).isTrue()
        assertThat(factory.openedWith).isEqualTo(AudioManager.STREAM_VOICE_CALL to RATE)
        assertThat(sink.events.first()).isEqualTo("play")
        val played = sink.written.flatMap { it.toList() }
        assertThat(played.take(RATE)).containsExactlyElementsIn(track.samples.toList()).inOrder()
        assertThat(positions.last()).isEqualTo(MS_PER_SECOND)
        assertThat(positions).isInOrder()
    }

    @Test
    fun `a track played to its end is drained, not flushed`() {
        val sink = TestPlaybackSink()
        val (player, _) = player(sink)
        val ended = CountDownLatch(1)

        player.play(track(), onPosition = {}, onEnd = { ended.countDown() })

        assertThat(ended.await(TIMEOUT_S, TimeUnit.SECONDS)).isTrue()
        // A tail of silence follows the last sample, then a stop that plays out what the sink holds.
        val tail = sink.written.flatMap { it.toList() }.drop(RATE)
        assertThat(tail).hasSize(RATE / QUARTER)
        assertThat(tail.all { it == 0.toShort() }).isTrue()
        assertThat(sink.events).containsExactly("play", "stop").inOrder()

        player.stop()
        assertThat(sink.events.count { it == "release" }).isEqualTo(1)
    }

    @Test
    fun `a sink that throws ends the playback, not the app`() {
        val sink = object : PcmPlaybackSink by TestPlaybackSink() {
            override fun write(buffer: ShortArray, length: Int): Int = throw IllegalStateException("dead track")
        }
        val (player, _) = player(sink)
        val ended = CountDownLatch(1)

        player.play(track(), onPosition = {}, onEnd = { ended.countDown() })

        assertThat(ended.await(TIMEOUT_S, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `playing from a position skips what lies before it`() {
        val sink = TestPlaybackSink()
        val (player, _) = player(sink)
        val track = track()
        val ended = CountDownLatch(1)

        player.play(track, fromMs = HALF_SECOND_MS, onPosition = {}, onEnd = { ended.countDown() })

        assertThat(ended.await(TIMEOUT_S, TimeUnit.SECONDS)).isTrue()
        val played = sink.written.flatMap { it.toList() }
        assertThat(played.take(RATE / 2)).containsExactlyElementsIn(track.samples.drop(RATE / 2)).inOrder()
    }

    @Test
    fun `stop ends playback and releases the sink once`() {
        val sink = BlockingSink()
        val (player, _) = player(sink)
        val ends = AtomicInteger()

        player.play(track(), onPosition = {}, onEnd = { ends.incrementAndGet() })
        assertThat(sink.writing.await(TIMEOUT_S, TimeUnit.SECONDS)).isTrue()

        player.stop()
        player.stop()

        assertThat(sink.writerThread!!.isAlive).isFalse()
        assertThat(sink.events.count { it == "release" }).isEqualTo(1)
        // A stop is not the end of the track: the caller knows it stopped.
        assertThat(ends.get()).isEqualTo(0)
    }

    @Test
    fun `a new play stops the one before`() {
        val first = BlockingSink()
        val factorySinks = ArrayDeque<PcmPlaybackSink>(listOf(first, TestPlaybackSink()))
        val player = TrackPlayer({ _, _ -> factorySinks.removeFirst() }, AudioManager.STREAM_VOICE_CALL)
        players += player
        player.play(track(), onPosition = {}, onEnd = {})
        assertThat(first.writing.await(TIMEOUT_S, TimeUnit.SECONDS)).isTrue()

        val ended = CountDownLatch(1)
        player.play(track(), onPosition = {}, onEnd = { ended.countDown() })

        assertThat(first.events.count { it == "release" }).isEqualTo(1)
        assertThat(ended.await(TIMEOUT_S, TimeUnit.SECONDS)).isTrue()
    }

    /** A sink whose write blocks, as a full `AudioTrack` does, until it is paused. */
    private class BlockingSink : PcmPlaybackSink {
        val events: MutableList<String> = CopyOnWriteArrayList()
        val writing = CountDownLatch(1)
        private val paused = CountDownLatch(1)

        @Volatile
        var writerThread: Thread? = null

        override fun play() {
            events += "play"
        }

        override fun write(buffer: ShortArray, length: Int): Int {
            writerThread = Thread.currentThread()
            writing.countDown()
            paused.await(TIMEOUT_S, TimeUnit.SECONDS)
            return 0
        }

        override fun pause() {
            events += "pause"
            paused.countDown()
        }

        override fun flush() {
            events += "flush"
        }

        override fun stop() {
            events += "stop"
        }

        override fun release() {
            events += "release"
        }
    }

    private companion object {
        const val RATE = 16_000
        const val SHORT_RANGE = 1000
        const val MS_PER_SECOND = 1000L
        const val HALF_SECOND_MS = 500L
        const val QUARTER = 4
        const val TIMEOUT_S = 5L
    }
}
