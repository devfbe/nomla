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

import koeln.becker.nomla.mumble.audio.AudioHandler
import koeln.becker.nomla.mumble.audio.AudioOutputSpeech

private const val FRAME = AudioHandler.FRAME_SIZE
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MS = 1_000_000f

/**
 * The receive side's taps of one pipeline. On the playback thread `PlaybackMix` calls [onSpeaker]
 * for every live talker's decoded frame, and `AudioOutput` calls [onMix] once the mix is written to
 * the track and [onOutputTimestamp] for its latency estimate. On the protocol thread
 * `AudioHandler` calls [onPacketReceived] for every voice packet before it is queued.
 *
 * Taps only observe: they read the frames and never write to them. Every tap begins with one
 * volatile read of [DiagnosticsHub.isActive] and returns while the hub is inactive, with no copy,
 * no allocation and no lock. While it is active the taps measure the mix and the talkers for the
 * live page ([DiagnosticsHub.playback]); while a recording is armed they also write the mix
 * (`7_mix`), each talker with a slot, one `playback` row per mix, one `speakers` row per talker per
 * mix and one `packets` row per received packet. Nothing here allocates per frame or packet.
 *
 * A talker's frame is held until its mix is written, so the mix and its talkers carry the same
 * stamp: the time the track's write returned, the **end** of that 10 ms frame.
 *
 * Talker slots are the recording's ([ArmedRecording.assignSlot]): only [onPacketReceived] gives
 * one, on a talker's first packet, so they survive a pipeline rebuild (P6); [onSpeaker] skips a
 * talker without one.
 *
 * [detach] makes the taps inert for good when their pipeline shuts down, so a playback thread that
 * outlives its join, or a packet still on its way, cannot write into the hub.
 */
internal class PlaybackTaps(private val hub: DiagnosticsHub) {
    private val live = hub.playback
    private val slots = DiagnosticsLimits.SPEAKER_SLOTS

    /**
     * Set once by [detach]. A plain field read right after the hub's volatile [DiagnosticsHub.isActive]:
     * [detach] publishes it with a volatile write of that same flag ([DiagnosticsHub.republish]).
     */
    private var detached = false

    // Playback thread only.
    private var wasActive = false
    private val levelScratch = LevelMeter()
    private val speakerFrames = Array(slots) { FloatArray(FRAME) }
    private val speakerLengths = IntArray(slots)
    private val speakerRows = Array(slots) { FloatArray(SpeakerColumns.NAMES.size) }
    private val pending = BooleanArray(slots)

    /** The recording the [pending] talker frames belong to. */
    private var pendingRecording: ArmedRecording? = null
    private var speakersInMix = 0
    private var lastClipped = 0L
    private val playbackRow = FloatArray(PlaybackColumns.NAMES.size)

    // Protocol thread only.
    private val receivedRow = FloatArray(PacketColumns.NAMES.size)

    /** Whether the taps do anything: the hub is active and the pipeline still runs. */
    val isActive: Boolean get() = hub.isActive && !detached

    /** The pipeline shuts down: every tap is inert from now on, also on a thread whose join timed out. */
    fun detach() {
        detached = true
        hub.republish()
    }

    /** [speech] decoded its frame of this mix ([AudioOutputSpeech.samples]); read only. Playback thread. */
    fun onSpeaker(speech: AudioOutputSpeech) {
        if (!begin()) return
        val length = minOf(speech.numSamples, FRAME)
        val samples = speech.samples
        speakersInMix++
        val now = hub.now()
        val liveSlot = live.slotFor(speech.session, now)
        val meter = if (liveSlot >= 0) live.speakerMeter(liveSlot) else levelScratch
        meter.measure(samples, length)
        if (liveSlot >= 0) live.update(liveSlot, now, speech)

        val recording = hub.armed ?: return
        hold(recording, speech, meter, length)
    }

    /**
     * Holds [speech]'s frame and its `speakers` row until its mix is written, if the talker has a
     * slot in [recording]; [meter] measured the frame's first [length] samples. Playback thread.
     */
    private fun hold(recording: ArmedRecording, speech: AudioOutputSpeech, meter: LevelMeter, length: Int) {
        val slot = recording.slotOf(speech.session)
        if (slot < 0) return
        if (pendingRecording !== recording) {
            pending.fill(false)
            pendingRecording = recording
        }
        speech.samples.copyInto(speakerFrames[slot], 0, 0, length)
        speakerLengths[slot] = length
        pending[slot] = true
        val row = speakerRows[slot]
        row[SpeakerColumns.SPEAKER] = (slot + 1).toFloat()
        row[SpeakerColumns.AVAILABLE_PACKETS] = speech.lastAvailablePackets.toFloat()
        row[SpeakerColumns.CONCEALED_FRAMES] = speech.concealedFrames.toFloat()
        row[SpeakerColumns.DECODE_ERRORS] = speech.decodeErrors.toFloat()
        row[SpeakerColumns.LOST_PACKETS] = speech.lostPackets.toFloat()
        row[SpeakerColumns.LATE_PACKETS] = speech.latePackets.toFloat()
        row[SpeakerColumns.RMS] = meter.rmsDbfs
        row[SpeakerColumns.PEAK] = meter.peakDbfs
    }

    /**
     * The first [length] samples of [pcm] were written to the track; [played] is false for the
     * silence that drains it. [underruns] and [trackFrames] are the track's. Read only. Playback
     * thread.
     */
    fun onMix(pcm: ShortArray, length: Int, played: Boolean, underruns: Int, trackFrames: Int) {
        if (!begin()) return
        val n = minOf(length, FRAME)
        val meter = live.mixMeter
        meter.measure(pcm, n)
        val clipped = meter.clippedSamples
        val clippedInMix = clipped - lastClipped
        lastClipped = clipped
        live.onMix(played, underruns, trackFrames, speakersInMix)

        val recording = hub.armed
        if (recording != null) {
            val row = playbackRow
            row[PlaybackColumns.PLAYED] = if (played) 1f else 0f
            row[PlaybackColumns.MIX_RMS] = meter.rmsDbfs
            row[PlaybackColumns.MIX_PEAK] = meter.peakDbfs
            row[PlaybackColumns.MIX_CLIPPED] = clippedInMix.toFloat()
            row[PlaybackColumns.UNDERRUNS] = underruns.toFloat()
            row[PlaybackColumns.TRACK_BUFFER_FRAMES] = trackFrames.toFloat()
            row[PlaybackColumns.OUTPUT_LATENCY_MS] = live.latencyMs
            row[PlaybackColumns.ACTIVE_SPEAKERS] = speakersInMix.toFloat()
            record(recording, pcm, n)
        }
        endMix()
    }

    /**
     * The track presented frame [framePosition] at [presentedNanos] (`AudioTrack.getTimestamp`),
     * with [writtenFrames] written so far: the newest frame plays that much later. Playback thread.
     */
    fun onOutputTimestamp(writtenFrames: Long, framePosition: Long, presentedNanos: Long) {
        if (!isActive) return
        val queuedNanos = (writtenFrames - framePosition) * NANOS_PER_SECOND / AudioHandler.SAMPLE_RATE
        live.latencyMs = (presentedNanos + queuedNanos - hub.now()) / NANOS_PER_MS
    }

    /**
     * A voice packet of [session] arrived: [frameNumber] its first frame, [bytes] its Opus payload.
     * Gives the talker a recording slot on their first packet. Protocol thread.
     */
    fun onPacketReceived(session: Int, frameNumber: Long, bytes: Int, terminator: Boolean) {
        if (!isActive) return
        val recording = hub.armed ?: return
        val slot = recording.assignSlot(session)
        val row = receivedRow
        row[PacketColumns.DIRECTION] = PacketColumns.RECEIVED_DIRECTION
        row[PacketColumns.SPEAKER] = if (slot >= 0) (slot + 1).toFloat() else Float.NaN
        row[PacketColumns.FRAME_NUMBER] = frameNumber.toFloat()
        row[PacketColumns.BYTES] = bytes.toFloat()
        row[PacketColumns.TERMINATOR] = if (terminator) 1f else 0f
        recording.received.write(hub.now(), row)
    }

    /** Writes the mix and the held talker frames with one stamp: now, as the track's write returned. */
    private fun record(recording: ArmedRecording, pcm: ShortArray, length: Int) {
        val now = hub.now()
        recording.mix.write(pcm, length, AudioHandler.SAMPLE_RATE, now)
        if (pendingRecording === recording) {
            for (slot in 0 until slots) {
                if (!pending[slot]) continue
                recording.speakerRing(slot).writeFloat(speakerFrames[slot], speakerLengths[slot], now)
                recording.speakers.write(now, speakerRows[slot])
            }
        }
        recording.playback.write(now, playbackRow)
    }

    /**
     * One volatile read; on a change of activity the live state starts afresh (active) or the
     * frames held for a mix are dropped (inactive). Playback thread.
     */
    private fun begin(): Boolean {
        val active = isActive
        if (active != wasActive) {
            wasActive = active
            if (active) {
                live.reset()
                lastClipped = 0L
            }
            endMix()
        }
        return active
    }

    private fun endMix() {
        pending.fill(false)
        speakersInMix = 0
    }
}
