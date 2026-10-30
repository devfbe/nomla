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

import koeln.becker.nomla.mumble.audio.AudioWorker
import koeln.becker.nomla.mumble.audio.PcmPlaybackSink
import koeln.becker.nomla.mumble.audio.PcmPlaybackSinkFactory
import koeln.becker.nomla.mumble.audio.releaseAfter
import koeln.becker.nomla.mumble.exception.AudioInitializationException
import koeln.becker.nomla.mumble.util.NomlaLog

private const val TAG = "TrackPlayer"
private const val THREAD_NAME = "nomla-diagnostics-playback"

/** The join on the calling (main) thread, as `CapturePreview`'s; the pause has unblocked the write. */
private const val JOIN_TIMEOUT_MS = 500L

/** One write is 10 ms of the track. */
private const val CHUNKS_PER_SECOND = 100
private const val MS_PER_SECOND = 1000L

/**
 * Silence written after a track's last sample, more than any sink buffers: once it is written, the
 * track itself has been heard to its end, and the release that follows cuts only silence.
 */
private const val TAIL_MS = 250

/**
 * Plays one track of a recording on its own sink from [sinkFactory], on [stream], at the track's
 * own rate, on its own thread (B11). The session is not touched: this is a separate playback.
 *
 * [play] and [stop] are called from one thread (the main thread); the callbacks run on the playback
 * thread. One track plays at a time: [play] stops the one before.
 *
 * A track played to its end is drained, not dropped: a tail of silence follows it, then the sink is
 * stopped without a flush, so its last buffer is heard. A sink that throws ends the playback.
 */
public class TrackPlayer(private val sinkFactory: PcmPlaybackSinkFactory, private val stream: Int) {
    private var worker: AudioWorker? = null
    private var sink: PcmPlaybackSink? = null

    /**
     * Plays [track] from [fromMs]. [onPosition] gets the position in ms after every written chunk;
     * [onEnd] runs once when the track has been written to its end, or when the sink fails (also when
     * it cannot be opened, then at once on the caller). A [stop] ends the thread without [onEnd],
     * unless the track was just ending anyway, so a caller drops callbacks of a playback it stopped.
     */
    public fun play(track: CapturedTrack, fromMs: Long = 0, onPosition: (Long) -> Unit, onEnd: () -> Unit) {
        stop()
        val opened = try {
            sinkFactory.open(stream, track.sampleRate)
        } catch (e: AudioInitializationException) {
            NomlaLog.w(TAG, "no sink at ${track.sampleRate} Hz: ${e.message}")
            null
        } catch (e: UnsupportedOperationException) {
            NomlaLog.w(TAG, "no sink at ${track.sampleRate} Hz: ${e.message}")
            null
        }
        if (opened == null) {
            onEnd()
            return
        }
        val start = (fromMs * track.sampleRate / MS_PER_SECOND).coerceIn(0, track.samples.size.toLong()).toInt()
        val playing = AudioWorker(THREAD_NAME)
        worker = playing
        sink = opened
        playing.start { isRunning ->
            run(opened, track, start, isRunning, onPosition)
            if (isRunning()) onEnd()
        }
    }

    /** Plays [track] from sample [start] and drains it at its end; a failing sink just ends it. */
    private fun run(
        sink: PcmPlaybackSink,
        track: CapturedTrack,
        start: Int,
        isRunning: () -> Boolean,
        onPosition: (Long) -> Unit,
    ) {
        try {
            val finished = write(sink, track, start, isRunning, onPosition)
            if (finished && isRunning()) drain(sink, track.sampleRate, isRunning)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // An AudioTrack throws IllegalStateException and others from play, write and stop; any of
            // them ends this playback, never the app.
            NomlaLog.w(TAG, "playback ended by the sink: $e")
        }
    }

    /** Writes the track; true when all of it was written, false when stopped or the sink failed. */
    private fun write(
        sink: PcmPlaybackSink,
        track: CapturedTrack,
        start: Int,
        isRunning: () -> Boolean,
        onPosition: (Long) -> Unit,
    ): Boolean {
        val rate = track.sampleRate
        val samples = track.samples
        val chunk = ShortArray((rate / CHUNKS_PER_SECOND).coerceAtLeast(1))
        sink.play()
        var position = start
        while (isRunning() && position < samples.size) {
            val length = minOf(chunk.size, samples.size - position)
            System.arraycopy(samples, position, chunk, 0, length)
            val written = sink.write(chunk, length)
            if (written <= 0) return false
            position += written
            onPosition(position * MS_PER_SECOND / rate)
        }
        return position >= samples.size
    }

    /** Writes [TAIL_MS] of silence, then stops [sink] without a flush, so what it holds plays out. */
    private fun drain(sink: PcmPlaybackSink, rate: Int, isRunning: () -> Boolean) {
        val silence = ShortArray((rate / CHUNKS_PER_SECOND).coerceAtLeast(1))
        var left = rate * TAIL_MS / MS_PER_SECOND.toInt()
        while (isRunning() && left > 0) {
            val written = sink.write(silence, minOf(silence.size, left))
            if (written <= 0) break
            left -= written
        }
        sink.stop()
    }

    /**
     * Ends the playback, if one runs, and frees its sink exactly once: here when the thread has
     * ended, or, after a timed-out join, on the thread as it ends (see `releaseAfter`).
     */
    public fun stop() {
        val playing = worker ?: return
        val playingSink = sink
        worker = null
        sink = null
        playing.stop(JOIN_TIMEOUT_MS) { playingSink?.pause() }
        releaseAfter(listOf(playing)) { playingSink?.shutdown() }
    }
}
