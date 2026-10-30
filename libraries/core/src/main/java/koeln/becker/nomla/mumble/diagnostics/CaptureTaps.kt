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

import koeln.becker.nomla.mumble.audio.capture.CaptureFrame
import koeln.becker.nomla.mumble.audio.capture.CapturePreprocessor
import koeln.becker.nomla.mumble.audio.capture.CaptureState
import koeln.becker.nomla.mumble.audio.capture.VadMode
import koeln.becker.nomla.mumble.audio.capture.VoiceActivityDetector
import koeln.becker.nomla.mumble.audio.capture.WebRtcApmPreprocessor
import koeln.becker.nomla.mumble.audio.native.WebRtcApmApi

private const val CAPTURE_RATE = 48_000
private const val FRAMES_PER_SECOND = 100
private const val NANOS_PER_MS = 1_000_000f

/** The largest frame any tap copies: 10 ms at the highest raw rate, and 10 ms at 48 kHz. */
private const val SCRATCH_SAMPLES = DiagnosticsLimits.MAX_RAW_RATE / FRAMES_PER_SECOND

/** No frame time taken yet in this frame. */
private const val NO_TIME = Long.MIN_VALUE

/**
 * The send path's taps of one pipeline, on the capture thread. Per 10 ms frame `AudioInput` calls
 * [onMicRaw], `CapturePipeline` and the [TapStage]s call [onStage] for every stage the pipeline
 * has, and `VoiceTransmitter` calls [commit] once the frame is decided, then [onOpusPacket] for
 * each packet it sends.
 *
 * Taps only observe: they read the frame and never write to it. Every tap begins with one volatile
 * read of [DiagnosticsHub.isActive] and returns while the hub is inactive, with no copy, no
 * allocation and no lock. While it is active the taps measure every stage on the hub's meters, and
 * while a recording is armed they copy each stage into a preallocated scratch frame. [commit]
 * writes the scratch frames to the rings, or silence where sending is not allowed (muted, or
 * push-to-talk without the key), plus one `frames` row; in the row of a frame not to be sent every
 * level column (the stages' and the detector's levels and probability) is NaN, while the live
 * meters still measure it. Every Opus packet that left the device is recorded, the terminator after
 * a release or a mute included. Nothing here allocates per frame.
 *
 * Every frame of a recording is stamped with the time its microphone read returned: the **end** of
 * that 10 ms frame. All stages of a frame carry the same stamp.
 *
 * The meters belong to the hub and outlive a pipeline: the first tap after the hub becomes active
 * resets them, and so does the first tap of a rebuilt pipeline's taps while metering runs, so a
 * rebuild starts the clip count and the peak hold afresh.
 *
 * [detach] makes the taps inert for good when their pipeline shuts down, so a capture thread that
 * outlives its join cannot write into the hub.
 *
 * Built off the capture thread with its pipeline; every method but [onCaptureState] runs on the
 * capture thread only, and [detach] on the thread that shuts the pipeline down.
 *
 * @param pushToTalk whether sending needs the key, i.e. a frame the input mode refuses is not sent.
 * @param vad the detector of a voice-activity input mode, read for the gate's live state.
 */
@Suppress("TooManyFunctions") // A facade: one method per tap point, plus the per-frame helpers they share.
internal class CaptureTaps(
    private val hub: DiagnosticsHub,
    private val pushToTalk: Boolean,
    private val vad: VoiceActivityDetector?,
) {
    private val stages = SendStage.entries.toTypedArray()
    private val scratch = Array(stages.size) { ShortArray(SCRATCH_SAMPLES) }
    private val scratchLength = IntArray(stages.size)

    /** Stages measured in this frame. */
    private val touched = BooleanArray(stages.size)

    /** Stages copied into [scratch] in this frame. */
    private val filled = BooleanArray(stages.size)

    private val lastClipped = LongArray(stages.size)
    private val clippedInFrame = LongArray(stages.size)
    private val row = FloatArray(FrameColumns.NAMES.size)
    private val statistics = FloatArray(WebRtcApmApi.STATISTICS_COUNT)

    private var apm: WebRtcApmPreprocessor? = null

    /** Whether [apm] polls; doubles as the last activity [commit] saw. */
    private var apmPolling = false
    private var lastStatisticsPolls = 0
    private var newStatistics = false

    /** Whether the meters were reset since the hub last became active. */
    private var metersFresh = false

    /**
     * Set once by [detach]. A plain field read right after the hub's volatile [DiagnosticsHub.isActive]:
     * [detach] publishes it with a volatile write of that same flag ([DiagnosticsHub.republish]),
     * so the tap path keeps a single volatile read per frame.
     */
    private var detached = false

    private var frameNanos = NO_TIME
    private var rawRate = 0
    private var rawRead = -1
    private var rawExpected = -1
    private var readNanos = -1L

    /** Set by the platform's recording callback; read per frame. */
    @Volatile
    private var silenced = false

    init {
        hub.live.vadPresent = vad != null
    }

    /** Whether the taps do anything: the hub is active and the pipeline still runs. */
    val isActive: Boolean get() = hub.isActive && !detached

    /**
     * The pipeline's canceller, whose statistics the frames carry. It polls them every
     * [DiagnosticsLimits.STATS_EVERY_FRAMES] frames while the hub is active and not at all
     * otherwise; [commit] switches that. Call before capture starts.
     */
    fun attachApm(apm: WebRtcApmPreprocessor?) {
        this.apm = apm
        hub.apm = apm
        apmPolling = false
        apm?.statisticsEveryFrames = 0
        lastStatisticsPolls = apm?.statisticsPolls ?: 0
    }

    /**
     * The pipeline shuts down: every tap is inert from now on, also on a capture thread whose join
     * timed out, and the hub forgets this pipeline's canceller, which is about to be released.
     */
    fun detach() {
        detached = true
        if (hub.apm === apm) hub.apm = null
        hub.republish()
    }

    /** The platform silenced the microphone or let it be heard again; on a binder thread. */
    fun onCaptureState(state: CaptureState) {
        val now = when (state) {
            CaptureState.Silenced -> true
            CaptureState.Active -> false
            is CaptureState.Error -> return
        }
        if (silenced == now) return
        silenced = now
        hub.event(CAPTURE_SILENCED, if (now) "1" else "0")
    }

    /**
     * The microphone's frame: [read] samples came from the platform, [expected] is the padded frame
     * at [sampleRate]. [readNanos] is how long the read blocked, negative when it was not timed.
     * The frame is stamped now, when its read has returned: the end of the frame.
     */
    fun onMicRaw(frame: ShortArray, read: Int, expected: Int, sampleRate: Int, readNanos: Long) {
        if (!isActive) return
        frameNanos = hub.now()
        rawRate = sampleRate
        rawRead = read
        rawExpected = expected
        this.readNanos = readNanos
        tap(SendStage.MIC_RAW.ordinal, frame, minOf(maxOf(read, 0), frame.size), minOf(expected, frame.size))
    }

    /** The first [length] samples of [frame] behind [stage]; read only. */
    fun onStage(stage: SendStage, frame: ShortArray, length: Int) {
        if (!isActive) return
        tap(stage.ordinal, frame, length, length)
    }

    private fun tap(index: Int, frame: ShortArray, measured: Int, copied: Int) {
        if (!metersFresh) resetMeters()
        hub.stageMeter(stages[index]).measure(frame, measured)
        touched[index] = true
        if (hub.armed == null) return
        val length = minOf(copied, SCRATCH_SAMPLES)
        frame.copyInto(scratch[index], 0, 0, length)
        scratchLength[index] = length
        filled[index] = true
    }

    /**
     * The frame is decided: [frame] is what goes to the encoder (boosted), [muted] the user's mute
     * state. Measures the sent level, publishes the gate, and while recording writes every stage
     * of this frame and its `frames` row.
     */
    fun commit(frame: CaptureFrame, muted: Boolean) {
        val active = isActive
        if (active != apmPolling) onActivityChanged(active)
        if (!active) return
        val sendAllowed = !muted && (!pushToTalk || frame.transmit)
        tap(SendStage.SENT.ordinal, frame.samples, frame.length, 0)
        filled[SendStage.SENT.ordinal] = false
        countClipped()
        pollStatistics()
        publishGate(frame)
        val recording = hub.armed
        if (recording != null) record(recording, frame, muted, sendAllowed)
        endFrame()
    }

    /**
     * An Opus packet left the device; kept while recording. Every packet sent is kept, the
     * terminator after a push-to-talk release or a mute included, so `6_sent` is what the peer
     * hears.
     */
    @Suppress("LongParameterList") // One packet's fields; a holder object would allocate per packet.
    fun onOpusPacket(data: ByteArray, offset: Int, length: Int, frameNumber: Long, terminator: Boolean) {
        if (!isActive) return
        val recording = hub.armed ?: return
        recording.sent.write(data, offset, length, frameNumber, terminator, hub.now())
    }

    /** The scratch frame of [stage]; for tests. */
    internal fun scratchOf(stage: SendStage): ShortArray = scratch[stage.ordinal]

    /**
     * The canceller polls only while the hub is active (P1), and the meters start afresh the next
     * time it becomes active.
     */
    private fun onActivityChanged(active: Boolean) {
        apm?.statisticsEveryFrames = if (active) DiagnosticsLimits.STATS_EVERY_FRAMES else 0
        apmPolling = active
        if (!active) {
            metersFresh = false
            // Taps that ran before the hub went inactive belong to no frame now.
            endFrame()
        }
    }

    /** On the capture thread, the meters' only writer, so no measurement races the reset. */
    private fun resetMeters() {
        for (stage in stages) hub.stageMeter(stage).reset()
        lastClipped.fill(0L)
        metersFresh = true
    }

    private fun countClipped() {
        for (i in stages.indices) {
            if (!touched[i]) continue
            val clipped = hub.stageMeter(stages[i]).clippedSamples
            clippedInFrame[i] = clipped - lastClipped[i]
            lastClipped[i] = clipped
        }
    }

    private fun pollStatistics() {
        val canceller = apm
        newStatistics = false
        if (canceller == null) return
        val polls = canceller.statisticsPolls
        if (polls == lastStatisticsPolls) return
        lastStatisticsPolls = polls
        newStatistics = canceller.copyStatistics(statistics)
    }

    /** The gate as `meterReading` shows it, without allocating; also written into [row]. */
    private fun publishGate(frame: CaptureFrame) {
        val live = hub.live
        live.recordGate(frame.transmit)
        val probability = frame.probability ?: Float.NaN
        live.probability = probability
        val v = vad
        val base = FrameColumns.VAD
        row[base + FrameColumns.VAD_PROBABILITY] = probability
        if (v == null) {
            for (i in FrameColumns.VAD_LEVEL..FrameColumns.VAD_TOO_CLOSE) row[base + i] = Float.NaN
            return
        }
        val level = v.lastLevelDbfs
        val voice = v.isTalking
        var floor = Float.NaN
        var speech = Float.NaN
        var threshold = Float.NaN
        var tooClose = false
        val config = v.config
        when (config.mode) {
            VadMode.ADAPTIVE -> {
                floor = v.floorDbfs
                speech = v.speechDbfs
                threshold = v.thresholdDbfs
                tooClose = v.tooClose
            }
            VadMode.AMPLITUDE -> threshold = VoiceActivityDetector.scoreToDbfs(config.startThreshold)
            VadMode.PROBABILITY -> Unit
        }
        val holding = voice && !threshold.isNaN() && level < threshold
        live.levelDbfs = level
        live.floorDbfs = floor
        live.speechDbfs = speech
        live.thresholdDbfs = threshold
        live.voice = voice
        live.holding = holding
        live.tooClose = tooClose
        row[base + FrameColumns.VAD_LEVEL] = level
        row[base + FrameColumns.VAD_FLOOR] = floor
        row[base + FrameColumns.VAD_SPEECH] = speech
        row[base + FrameColumns.VAD_THRESHOLD] = threshold
        row[base + FrameColumns.VAD_OPEN] = flag(voice)
        row[base + FrameColumns.VAD_HOLDING] = flag(holding)
        row[base + FrameColumns.VAD_TOO_CLOSE] = flag(tooClose)
    }

    private fun record(recording: ArmedRecording, frame: CaptureFrame, muted: Boolean, sendAllowed: Boolean) {
        val nanos = if (frameNanos != NO_TIME) frameNanos else hub.now()
        for (i in stages.indices) {
            if (!filled[i]) continue
            val rate = if (i == SendStage.MIC_RAW.ordinal) rawRate else CAPTURE_RATE
            val ring = recording.ring(stages[i])
            if (sendAllowed) {
                ring.write(scratch[i], scratchLength[i], rate, nanos)
            } else {
                ring.writeSilence(scratchLength[i], rate, nanos)
            }
        }
        row[FrameColumns.SEND_ALLOWED] = flag(sendAllowed)
        row[FrameColumns.TRANSMIT] = flag(frame.transmit)
        row[FrameColumns.MUTED] = flag(muted)
        row[FrameColumns.READ_SAMPLES] = if (rawRead >= 0) rawRead.toFloat() else Float.NaN
        row[FrameColumns.EXPECTED_SAMPLES] = if (rawExpected >= 0) rawExpected.toFloat() else Float.NaN
        row[FrameColumns.READ_MS] = if (readNanos >= 0) readNanos / NANOS_PER_MS else Float.NaN
        row[FrameColumns.SILENCED] = flag(silenced)
        for (i in stages.indices) writeStageLevels(i, sendAllowed)
        // What is deliberately not sent never reaches a ZIP, not even as its level envelope.
        if (!sendAllowed) hideVoiceLevels()
        for (i in 0 until WebRtcApmApi.STATISTICS_COUNT) {
            row[FrameColumns.APM + i] = if (newStatistics) statistics[i] else Float.NaN
        }
        recording.frames.write(nanos, row)
    }

    /** RMS, peak and clipped samples of stage [i] in [row]; NaN when not measured or not to be sent. */
    private fun writeStageLevels(i: Int, sendAllowed: Boolean) {
        val at = FrameColumns.STAGES + i * FrameColumns.PER_STAGE
        if (sendAllowed && touched[i]) {
            val meter = hub.stageMeter(stages[i])
            row[at] = meter.rmsDbfs
            row[at + 1] = meter.peakDbfs
            row[at + 2] = clippedInFrame[i].toFloat()
        } else {
            row[at] = Float.NaN
            row[at + 1] = Float.NaN
            row[at + 2] = Float.NaN
        }
    }

    /** The detector's levels and probability of a frame not to be sent; its gate flags stay. */
    private fun hideVoiceLevels() {
        val base = FrameColumns.VAD
        row[base + FrameColumns.VAD_PROBABILITY] = Float.NaN
        row[base + FrameColumns.VAD_LEVEL] = Float.NaN
        row[base + FrameColumns.VAD_FLOOR] = Float.NaN
        row[base + FrameColumns.VAD_SPEECH] = Float.NaN
    }

    private fun endFrame() {
        touched.fill(false)
        filled.fill(false)
        frameNanos = NO_TIME
        rawRead = -1
        rawExpected = -1
        readNanos = -1L
    }

    private fun flag(value: Boolean): Float = if (value) 1f else 0f

    companion object {
        const val CAPTURE_SILENCED = "capture_silenced"
    }
}

/**
 * A chain stage that shows the frame behind the stage before it to [taps] as [stage]. It never
 * writes the frame and has no opinion (null), so the chain's output and probability stay those
 * without it.
 */
internal class TapStage(val stage: SendStage, private val taps: CaptureTaps) : CapturePreprocessor {
    override fun process(frame: ShortArray): Float? {
        taps.onStage(stage, frame, frame.size)
        return null
    }

    override fun release() = Unit
}
