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

package koeln.becker.nomla.mumble.testutil

import koeln.becker.nomla.mumble.audio.PcmPlaybackSink
import koeln.becker.nomla.mumble.audio.PcmPlaybackSinkFactory
import koeln.becker.nomla.mumble.audio.capture.AndroidAudioRecordSource
import koeln.becker.nomla.mumble.audio.capture.CaptureRequest
import koeln.becker.nomla.mumble.audio.capture.PcmCaptureSource
import koeln.becker.nomla.mumble.audio.capture.PcmCaptureSourceFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [PcmCaptureSource] that behaves like a blocking `AudioRecord`: it hands out [frames], or a
 * [script] read by read (full, short and zero reads, negative codes), and then blocks, as the real
 * one does between frames, until [stop].
 *
 * Copied from the real thing on purpose:
 * - Reads ignore thread interrupts; only `stop()` or an error ends a native read.
 * - What is still scripted at [stop] is still read; then a read answers [codeAfterStop], which a
 *   real one may make negative when the recorder is taken away underneath it, and [hangAfterStop]
 *   can hold that answer back like a hung native read.
 * - After [release], reads answer `ERROR_RELEASED` and [sampleRate] throws, as `AudioRecord`'s
 *   accessors do. [Factory] hands the same instance out again, so opening it [reopen]s it.
 */
@Suppress("LongParameterList") // One per behaviour of the real recorder a test may need.
public class TestCaptureSource(
    frames: List<ShortArray> = emptyList(),
    sampleRate: Int = 48000,
    /** Repeats the last frame until [stop] instead of blocking, for tests that need a steady stream. */
    private val loopLastFrame: Boolean = false,
    script: List<Read> = frames.map { Read.Frame(it) },
    /** Held shut, a read in flight at [stop] does not return until it opens: a hung native read. */
    private val hangAfterStop: CountDownLatch? = null,
    private val failStart: Boolean = false,
    private val codeAfterStop: Int = 0,
) : PcmCaptureSource {
    /** One scripted answer from [read]. */
    public sealed interface Read {
        /** Copied into the caller's buffer; the count returned is how many samples fit. */
        public class Frame(public val samples: ShortArray) : Read

        /** Returned verbatim: `0` for "nothing was read", negative for an error. */
        public class Code(public val value: Int) : Read
    }

    private val rate = sampleRate

    override val sampleRate: Int
        get() = if (released) error("sampleRate read after release") else rate

    override val audioSessionId: Int = 1

    /** `start`, `stop` and `release` in the order they happened. */
    public val events: MutableList<String> = CopyOnWriteArrayList()

    /** By identity, not name: coroutines rename the threads they run on. */
    public val readingThreads: MutableSet<Thread> = CopyOnWriteArraySet()

    public val reads: AtomicInteger = AtomicInteger()

    public var silenceListener: ((Boolean) -> Unit)? = null
        private set

    private val queue = LinkedBlockingQueue<Read>().apply { addAll(script) }
    private var last: Read? = null

    @Volatile
    public var stopped: Boolean = false
        private set

    @Volatile
    private var released = false

    override fun start() {
        events += "start"
        stopped = false
        check(!failStart) { "startRecording failed" }
    }

    override fun read(buffer: ShortArray, length: Int): Int {
        readingThreads += Thread.currentThread()
        reads.incrementAndGet()
        var answer: Int? = null
        while (answer == null) answer = if (released) AndroidAudioRecordSource.ERROR_RELEASED else next(buffer, length)
        return answer
    }

    /** The answer of the next scripted read, or null while there is none yet. */
    private fun next(buffer: ShortArray, length: Int): Int? {
        val next = try {
            queue.poll(2, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            null // Swallowed, as a blocking native read does.
        } ?: last.takeIf { loopLastFrame && !stopped }
        if (next != null) last = next
        return when (next) {
            is Read.Frame -> minOf(next.samples.size, length).also { System.arraycopy(next.samples, 0, buffer, 0, it) }
            is Read.Code -> next.value
            null -> if (stopped) {
                hangIgnoringInterrupts()
                codeAfterStop
            } else {
                null
            }
        }
    }

    private fun hangIgnoringInterrupts() {
        val gate = hangAfterStop ?: return
        while (true) {
            try {
                gate.await()
                return
            } catch (e: InterruptedException) {
                // A native read does not return on interrupt either.
            }
        }
    }

    override fun stop() {
        events += "stop"
        stopped = true
    }

    override fun release() {
        events += "release"
        released = true
    }

    /** Undoes [release] and [stop], for a test that opens the same instance again. */
    public fun reopen() {
        released = false
        stopped = false
    }

    override fun setSilenceListener(listener: ((Boolean) -> Unit)?) {
        silenceListener = listener
    }

    public class Factory(private val source: PcmCaptureSource) : PcmCaptureSourceFactory {
        public var request: CaptureRequest? = null
        override fun open(request: CaptureRequest): PcmCaptureSource {
            this.request = request
            (source as? TestCaptureSource)?.reopen()
            return source
        }
    }
}

public class TestPlaybackSink : PcmPlaybackSink {
    public val events: MutableList<String> = CopyOnWriteArrayList()
    public val written: MutableList<ShortArray> = CopyOnWriteArrayList()

    override fun play() {
        events += "play"
    }

    override fun write(buffer: ShortArray, length: Int): Int {
        written += buffer.copyOf(length)
        return length
    }

    override fun pause() {
        events += "pause"
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

    public class Factory(private val sink: PcmPlaybackSink) : PcmPlaybackSinkFactory {
        public var openedWith: Pair<Int, Int>? = null
        override fun open(audioStream: Int, sampleRate: Int): PcmPlaybackSink {
            openedWith = audioStream to sampleRate
            return sink
        }
    }
}
