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

import koeln.becker.nomla.mumble.audio.capture.NanoClock
import koeln.becker.nomla.mumble.audio.capture.WebRtcApmPreprocessor
import koeln.becker.nomla.mumble.audio.native.WebRtcApmApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val FRAMES_PER_SECOND = 100

/** The rate of every capture stage behind the resampler. */
private const val CAPTURE_RATE = 48_000

/**
 * The diagnostics of one session, across its pipeline rebuilds: the live meters of every send
 * stage, the gate and echo state the taps publish, and the rings of a recording while one is
 * armed.
 *
 * It is **active** while at least one [meter] handle is open or a recording is armed. While it is
 * inactive every tap costs one volatile read ([isActive]) and does nothing else, and nothing polls
 * or ticks (P1, P9). [active] publishes the same state for those who start and stop work with it;
 * it changes only on the thread that opens or closes a handle, arms or disarms, never on an audio
 * thread.
 *
 * @param clock the time base of every frame, packet and event; `System.nanoTime` in production.
 * @param allocate builds a recording's rings; may throw [OutOfMemoryError]. A seam for that test.
 */
internal class DiagnosticsHub(
    private val clock: NanoClock = NanoClock(System::nanoTime),
    private val allocate: (seconds: Int, t0Nanos: Long) -> ArmedRecording = ::ArmedRecording,
) {
    private val lock = Any()

    /** Open [meter] handles; under [lock]. */
    private var meterHolders = 0

    @Volatile
    private var activeNow = false

    private val activeFlow = MutableStateFlow(false)

    /** Whether metering or a recording runs; see the class comment. */
    val active: StateFlow<Boolean> = activeFlow.asStateFlow()

    /** [active] as one volatile read, for the audio threads. */
    val isActive: Boolean get() = activeNow

    /** The running recording, null when none is armed. */
    @Volatile
    var armed: ArmedRecording? = null
        private set

    private val meters = Array(SendStage.entries.size) { LevelMeter() }

    /** What the capture taps publish for the live page, beyond the stage meters. */
    val live = LiveCapture()

    /** The canceller of the current pipeline, whose statistics the live page shows; set by the taps. */
    @Volatile
    var apm: WebRtcApmPreprocessor? = null

    @Volatile
    private var pipeline: PipelineDescription? = null

    /** The time on [clock], in nanoseconds. */
    fun now(): Long = clock.nanoTime()

    fun stageMeter(stage: SendStage): LevelMeter = meters[stage.ordinal]

    /** Keeps the hub active until the handle is closed; closing it again does nothing. */
    fun meter(): AutoCloseable {
        synchronized(lock) {
            meterHolders++
            updateActive()
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) {
                    meterHolders--
                    updateActive()
                }
            }
        }
    }

    /**
     * Allocates every ring for a recording of [seconds] and starts it; a running one is replaced.
     * The current pipeline is the recording's first.
     *
     * @throws OutOfMemoryError if the rings do not fit; nothing is armed then.
     */
    fun arm(seconds: Int) {
        val recording = allocate(seconds, clock.nanoTime())
        synchronized(lock) {
            pipeline?.let(recording::addPipeline)
            armed = recording
            updateActive()
        }
    }

    /** Stops the recording and hands it over, or null if none was armed. */
    fun disarm(): ArmedRecording? = synchronized(lock) {
        val recording = armed
        armed = null
        updateActive()
        recording
    }

    /** Logs an event in the running recording; nothing while none is armed. Never on an audio thread. */
    fun event(name: String, value: String = "") {
        armed?.events?.add(clock.nanoTime(), name, value)
    }

    /** A pipeline was built; during a recording it is added to it with a `pipeline_rebuilt` event. */
    fun onPipelineBuilt(description: PipelineDescription) {
        synchronized(lock) {
            pipeline = description
            armed?.let {
                it.addPipeline(description)
                it.events.add(clock.nanoTime(), PIPELINE_REBUILT, "")
            }
        }
    }

    /** A fresh reading of everything live; allocates. Any thread. */
    fun snapshot(): DiagnosticsSnapshot {
        val present = pipeline?.stagesPresent.orEmpty()
        var previousRms: Float? = null
        val stages = SendStage.entries.map { stage ->
            val meter = meters[stage.ordinal]
            val active = stage in present
            val rms = meter.rmsDbfs
            val change = if (active) previousRms?.let { rms - it } else null
            if (active) previousRms = rms
            StageReading(stage, active, rms, meter.peakDbfs, meter.peakHoldDbfs, meter.clippedSamples, change)
        }
        return DiagnosticsSnapshot(
            stages = stages,
            gateHistory = live.gateHistory(),
            gate = live.gate(),
            echo = echo(present),
            capture = pipeline?.capture,
        )
    }

    private fun echo(present: Set<SendStage>): EchoReading? {
        val canceller = apm ?: return null
        val statistics = FloatArray(WebRtcApmApi.STATISTICS_COUNT)
        if (!canceller.copyStatistics(statistics)) return null
        val input = if (SendStage.RESAMPLED in present) SendStage.RESAMPLED else SendStage.MIC_RAW
        val change = if (SendStage.APM in present) stageMeter(SendStage.APM).rmsDbfs - stageMeter(input).rmsDbfs else null
        return EchoReading(
            erlDb = statistics[ERL],
            erleDb = statistics[ERLE],
            divergentFraction = statistics[DIVERGENT],
            delayMedianMs = statistics[DELAY_MEDIAN],
            delayStdMs = statistics[DELAY_STD],
            delayMs = statistics[DELAY],
            levelChangeDb = change,
        )
    }

    private fun updateActive() {
        val now = meterHolders > 0 || armed != null
        activeNow = now
        activeFlow.value = now
    }

    companion object {
        const val PIPELINE_REBUILT = "pipeline_rebuilt"

        // The order of WebRtcApmApi.getStatistics.
        const val ERL = 0
        const val ERLE = 1
        const val DIVERGENT = 2
        const val DELAY_MEDIAN = 3
        const val DELAY_STD = 4
        const val DELAY = 5
    }
}

/**
 * The rings of one recording of [seconds], started at [t0Nanos]: one [PcmRing] per PCM send stage,
 * the sent Opus packets, the `frames` telemetry and the events. Each ring has a single writer: the
 * capture thread for the send stages, the packets and the frames; main and control threads for
 * the events. Allocated before arming, off the audio threads.
 */
internal class ArmedRecording(val seconds: Int, val t0Nanos: Long) {
    val durationNanos: Long = seconds * NANOS_PER_SECOND

    private val capacityFrames = seconds * FRAMES_PER_SECOND + DiagnosticsLimits.RING_MARGIN_FRAMES

    private val pcm: Array<PcmRing?> = Array(SendStage.entries.size) { i ->
        when (SendStage.entries[i]) {
            SendStage.SENT -> null
            SendStage.MIC_RAW ->
                PcmRing(capacityFrames, DiagnosticsLimits.MAX_RAW_RATE / FRAMES_PER_SECOND)
            else -> PcmRing(capacityFrames, CAPTURE_RATE / FRAMES_PER_SECOND)
        }
    }

    /** The sent Opus packets, for the `6_sent` track (B7) and `packets.csv` (P3). */
    val sent = PacketRing(capacityFrames, capacityFrames * DiagnosticsLimits.MAX_PACKET_BYTES_PER_FRAME)

    /** One row per capture frame (`frames.csv`, B10). */
    val frames = ColumnRing(FrameColumns.TABLE, FrameColumns.NAMES, capacityFrames)

    val events = EventLog()

    private val pipelineList = ArrayList<PipelineDescription>()

    /** Every pipeline the recording ran on, in order. */
    val pipelines: List<PipelineDescription> get() = synchronized(pipelineList) { pipelineList.toList() }

    fun addPipeline(description: PipelineDescription) {
        synchronized(pipelineList) { pipelineList += description }
    }

    /** The ring of a PCM send stage; [SendStage.SENT] has [sent] instead. */
    fun ring(stage: SendStage): PcmRing = requireNotNull(pcm[stage.ordinal]) { "$stage has no PCM ring" }
}

/**
 * What the capture thread publishes per frame for the live page, beyond the stage meters: the gate
 * and its history. Written by the capture thread only; read by any thread without a lock, so a
 * reader may see two frames mixed, which a live display can afford.
 */
internal class LiveCapture {
    /** Whether the current pipeline gates by voice activity; set when its taps are built. */
    @Volatile
    var vadPresent = false

    @Volatile var levelDbfs = Float.NaN

    @Volatile var floorDbfs = Float.NaN

    @Volatile var speechDbfs = Float.NaN

    @Volatile var thresholdDbfs = Float.NaN

    @Volatile var voice = false

    @Volatile var holding = false

    @Volatile var tooClose = false

    /** The chain's probability; NaN for none. */
    @Volatile var probability = Float.NaN

    private val history = BooleanArray(DiagnosticsLimits.GATE_HISTORY_FRAMES)

    /** Frames written into [history]; publishes them. */
    @Volatile
    private var historyFrames = 0L

    /** Capture thread only. */
    fun recordGate(open: Boolean) {
        val n = historyFrames
        history[(n % history.size).toInt()] = open
        historyFrames = n + 1
    }

    /** The gate of the last frames, oldest first. */
    fun gateHistory(): BooleanArray {
        val n = historyFrames
        val size = history.size
        return BooleanArray(size) { history[((n + it) % size).toInt()] }
    }

    fun gate(): GateReading? {
        if (!vadPresent) return null
        return GateReading(
            levelDbfs = levelDbfs,
            floorDbfs = floorDbfs.orNull(),
            speechDbfs = speechDbfs.orNull(),
            thresholdDbfs = thresholdDbfs.orNull(),
            voice = voice,
            holding = holding,
            tooClose = tooClose,
            probability = probability.orNull(),
        )
    }

    private fun Float.orNull(): Float? = takeUnless { it.isNaN() }
}

/** The columns of `frames.csv` (B10), one row per capture frame, and their indices. */
internal object FrameColumns {
    const val TABLE = "frames"

    const val SEND_ALLOWED = 0
    const val TRANSMIT = 1
    const val MUTED = 2
    const val READ_SAMPLES = 3
    const val EXPECTED_SAMPLES = 4
    const val READ_MS = 5
    const val SILENCED = 6

    /** The first stage column; each stage has [PER_STAGE] (RMS, peak, clipped samples in the frame). */
    const val STAGES = 7
    const val PER_STAGE = 3

    private val STAGE_NAMES = SendStage.entries.flatMap { stage ->
        val prefix = stage.name.lowercase()
        listOf("${prefix}_rms_dbfs", "${prefix}_peak_dbfs", "${prefix}_clipped")
    }

    val VAD: Int = STAGES + PER_STAGE * SendStage.entries.size

    // Offsets from VAD.
    const val VAD_PROBABILITY = 0
    const val VAD_LEVEL = 1
    const val VAD_FLOOR = 2
    const val VAD_SPEECH = 3
    const val VAD_THRESHOLD = 4
    const val VAD_OPEN = 5
    const val VAD_HOLDING = 6
    const val VAD_TOO_CLOSE = 7

    private val VAD_NAMES = listOf(
        "vad_probability", "vad_level_dbfs", "vad_floor_dbfs", "vad_speech_dbfs", "vad_threshold_dbfs",
        "vad_open", "vad_holding", "vad_too_close",
    )

    /** The first APM column, in [WebRtcApmApi.getStatistics] order. */
    val APM: Int = VAD + VAD_NAMES.size

    private val APM_NAMES = listOf(
        "apm_erl_db", "apm_erle_db", "apm_divergent_fraction", "apm_delay_median_ms", "apm_delay_std_ms",
        "apm_delay_ms",
    )

    val NAMES: List<String> = listOf(
        "send_allowed", "transmit", "muted", "read_samples", "expected_samples", "read_ms", "silenced",
    ) + STAGE_NAMES + VAD_NAMES + APM_NAMES
}
