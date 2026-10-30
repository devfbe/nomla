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

import koeln.becker.nomla.mumble.audio.AudioOutputSpeech
import koeln.becker.nomla.mumble.audio.capture.NanoClock
import koeln.becker.nomla.mumble.audio.capture.WebRtcApmPreprocessor
import koeln.becker.nomla.mumble.audio.native.WebRtcApmApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val MS_PER_SECOND = 1_000L
private const val NANOS_PER_MS = 1_000_000.0
private const val FRAMES_PER_SECOND = 100

/** The rate of every capture stage behind the resampler, and of playback. */
private const val CAPTURE_RATE = 48_000

/** Samples of one 10 ms frame at [CAPTURE_RATE]. */
private const val FRAME_SAMPLES = CAPTURE_RATE / FRAMES_PER_SECOND

/** No talker in a slot. */
private const val NO_SESSION = -1

/** How long a live talker stays on the live page, and keeps their live slot, after their last frame. */
private const val LIVE_SPEAKER_IDLE_NANOS = NANOS_PER_SECOND

/** Talkers beyond the slots a recording remembers, so each is counted once. */
private const val UNTRACKED_CAPACITY = 64

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
 * The stage meters live here, across rebuilds, but each pipeline's taps reset them at their first
 * frame: a rebuild while metering starts the clip counts and peak holds afresh.
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

    /** What the playback taps publish for the live page: the mix and the talkers. */
    val playback = LivePlayback()

    /** The transport at the network sampler's last tick; null while it does not run. */
    @Volatile
    var network: NetworkReading? = null

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
            echo = apm?.let { echoReading(it, present, meters) },
            playback = playback.reading(),
            speakers = playback.speakers(clock.nanoTime(), armed),
            network = network,
            capture = pipeline?.capture,
        )
    }

    private fun updateActive() {
        val now = meterHolders > 0 || armed != null
        activeNow = now
        activeFlow.value = now
    }

    /**
     * Writes [isActive] again, unchanged: a volatile write that publishes whatever the calling
     * thread wrote before it to every audio thread's next [isActive] read (see `CaptureTaps.detach`).
     */
    fun republish() {
        synchronized(lock) { updateActive() }
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
 * The statistics of [canceller], or null while it has none, with the level change across it on
 * the stage [meters] when the APM stage is [present]. Allocates; off the audio threads.
 */
private fun echoReading(
    canceller: WebRtcApmPreprocessor,
    present: Set<SendStage>,
    meters: Array<LevelMeter>,
): EchoReading? {
    val statistics = FloatArray(WebRtcApmApi.STATISTICS_COUNT)
    if (!canceller.copyStatistics(statistics)) return null
    val input = if (SendStage.RESAMPLED in present) SendStage.RESAMPLED else SendStage.MIC_RAW
    val change = if (SendStage.APM in present) {
        meters[SendStage.APM.ordinal].rmsDbfs - meters[input.ordinal].rmsDbfs
    } else {
        null
    }
    return EchoReading(
        erlDb = statistics[DiagnosticsHub.ERL],
        erleDb = statistics[DiagnosticsHub.ERLE],
        divergentFraction = statistics[DiagnosticsHub.DIVERGENT],
        delayMedianMs = statistics[DiagnosticsHub.DELAY_MEDIAN],
        delayStdMs = statistics[DiagnosticsHub.DELAY_STD],
        delayMs = statistics[DiagnosticsHub.DELAY],
        levelChangeDb = change,
    )
}

/**
 * The rings of one recording of [seconds], started at [t0Nanos]: one [PcmRing] per PCM send stage,
 * the sent Opus packets, the `frames` telemetry, the playback mix ([MIX_TRACK_ID]) and one track
 * per talker slot with the `playback` and `speakers` telemetry, the received packets, the
 * `network` telemetry and the events. Each ring has a single writer: the capture thread for the
 * send stages, the sent packets and the frames; the playback thread for the mix, the talkers and
 * their telemetry; the protocol thread for the received packets and the talker slots (P3, P6);
 * the network sampler for `network`; main and control threads for the events. Allocated before
 * arming, off the audio threads.
 *
 * The talker slots live here, not in the taps, so a talker keeps their slot across a pipeline
 * rebuild (B4, P6): slots go to talkers in order of their first packet, and a talker beyond the
 * [DiagnosticsLimits.SPEAKER_SLOTS] is only counted in [speakersWithoutTrack].
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

    /** The mix as written to the track, at 48 kHz (`7_mix`, P10). */
    val mix = PcmRing(capacityFrames, FRAME_SAMPLES)

    private val speakerPcm = Array(DiagnosticsLimits.SPEAKER_SLOTS) { PcmRing(capacityFrames, FRAME_SAMPLES) }

    /** One row per mix (`playback.csv`). */
    val playback = ColumnRing(PlaybackColumns.TABLE, PlaybackColumns.NAMES, capacityFrames)

    /** One row per talker with a slot per mix (`speakers.csv`). */
    val speakers = ColumnRing(
        SpeakerColumns.TABLE, SpeakerColumns.NAMES, capacityFrames * DiagnosticsLimits.SPEAKER_SLOTS,
    )

    /** The received voice packets, one row each, in [PacketColumns]; merged with [sent] by [freezePackets]. */
    val received = ColumnRing(
        PacketColumns.RECEIVED, PacketColumns.NAMES, capacityFrames * DiagnosticsLimits.SPEAKER_SLOTS,
    )

    /** One row per network sample (`network.csv`). */
    val network = ColumnRing(
        NetworkColumns.TABLE,
        NetworkColumns.NAMES,
        (seconds * MS_PER_SECOND / DiagnosticsLimits.NETWORK_SAMPLE_MS).toInt() + DiagnosticsLimits.RING_MARGIN_FRAMES,
    )

    /** The session of each talker slot, [NO_SESSION] while free; written by the protocol thread only. */
    private val speakerSessions = AtomicIntegerArray(DiagnosticsLimits.SPEAKER_SLOTS).apply {
        for (i in 0 until length()) set(i, NO_SESSION)
    }

    /** Talkers without a slot, counted already; protocol thread only. */
    private val untracked = IntArray(UNTRACKED_CAPACITY)
    private var untrackedCount = 0

    /**
     * Talkers who sent while every slot was taken; each counted once (up to [UNTRACKED_CAPACITY]
     * of them).
     */
    @Volatile
    var speakersWithoutTrack: Int = 0
        private set

    val events = EventLog()

    private val pipelineList = ArrayList<PipelineDescription>()

    /** Every pipeline the recording ran on, in order. */
    val pipelines: List<PipelineDescription> get() = synchronized(pipelineList) { pipelineList.toList() }

    fun addPipeline(description: PipelineDescription) {
        synchronized(pipelineList) { pipelineList += description }
    }

    /** The ring of a PCM send stage; [SendStage.SENT] has [sent] instead. */
    fun ring(stage: SendStage): PcmRing = requireNotNull(pcm[stage.ordinal]) { "$stage has no PCM ring" }

    /** The track of talker [slot] (0-based). */
    fun speakerRing(slot: Int): PcmRing = speakerPcm[slot]

    /** The 0-based slot of [session], or -1 if it has none. Any thread. */
    fun slotOf(session: Int): Int {
        var slot = -1
        for (i in 0 until speakerSessions.length()) {
            val s = speakerSessions.get(i)
            if (s == session) slot = i
            // Slots fill in order: past a free one no talker follows.
            if (s == session || s == NO_SESSION) break
        }
        return slot
    }

    /**
     * The 0-based slot of [session], giving it the next free one on its first packet; -1 once all
     * are taken, and the talker is counted in [speakersWithoutTrack] once. Protocol thread only:
     * the slot table's single writer. Never allocates.
     */
    fun assignSlot(session: Int): Int {
        for (i in 0 until speakerSessions.length()) {
            val s = speakerSessions.get(i)
            if (s == NO_SESSION) speakerSessions.set(i, session)
            if (s == session || s == NO_SESSION) return i
        }
        countWithoutTrack(session)
        return -1
    }

    /** Counts [session] in [speakersWithoutTrack] unless it is already. Protocol thread only. */
    private fun countWithoutTrack(session: Int) {
        for (i in 0 until untrackedCount) if (untracked[i] == session) return
        if (untrackedCount < untracked.size) {
            untracked[untrackedCount++] = session
            speakersWithoutTrack = untrackedCount
        }
    }

    /**
     * The track of every slot a talker took and was heard in, by track id ([speakerTrackId]), in
     * slot order; a slot whose talker was never played (muted locally, say) has none. After disarm.
     */
    fun freezeSpeakers(): Map<String, FrozenPcm> {
        val tracks = LinkedHashMap<String, FrozenPcm>()
        for (i in 0 until speakerSessions.length()) {
            if (speakerSessions.get(i) == NO_SESSION) break
            val frozen = speakerPcm[i].freeze()
            if (frozen.frames > 0) tracks[speakerTrackId(i)] = frozen
        }
        return tracks
    }

    /**
     * `packets.csv`: the sent packets (direction 0, speaker 0) and the received ones (direction 1,
     * speaker 1 to 4, empty for a talker without a slot), merged by time (P3). After disarm.
     */
    fun freezePackets(): TelemetryTable {
        val out = sent.freeze()
        val inbound = received.freeze(t0Nanos)
        val width = PacketColumns.NAMES.size
        val rows = out.count + inbound.rows
        val times = DoubleArray(rows)
        val values = FloatArray(rows * width)
        var i = 0
        var j = 0
        for (k in 0 until rows) {
            val sentMs = if (i < out.count) (out.nanos[i] - t0Nanos) / NANOS_PER_MS else Double.POSITIVE_INFINITY
            val receivedMs = if (j < inbound.rows) inbound.timesMs[j] else Double.POSITIVE_INFINITY
            val at = k * width
            if (sentMs <= receivedMs) {
                times[k] = sentMs
                values[at + PacketColumns.DIRECTION] = PacketColumns.SENT_DIRECTION
                values[at + PacketColumns.SPEAKER] = PacketColumns.SELF
                values[at + PacketColumns.FRAME_NUMBER] = out.frameNumbers[i].toFloat()
                values[at + PacketColumns.BYTES] = out.lengths[i].toFloat()
                values[at + PacketColumns.TERMINATOR] = if (out.terminators[i]) 1f else 0f
                i++
            } else {
                times[k] = receivedMs
                inbound.values.copyInto(values, at, j * width, (j + 1) * width)
                j++
            }
        }
        return TelemetryTable(PacketColumns.TABLE, PacketColumns.NAMES, times, values, rows)
    }

    companion object {
        /** The playback mix's track (P10). */
        const val MIX_TRACK_ID = "7_mix"

        /** The track of talker [slot] (0-based): `speaker_1` to `speaker_4`, never a session or name. */
        fun speakerTrackId(slot: Int): String = "speaker_${slot + 1}"
    }
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

/**
 * What the playback thread publishes for the live page: the last mix and up to
 * [DiagnosticsLimits.SPEAKER_SLOTS] talkers heard within the last second. Written by the playback
 * thread only (its live talker table included); read by any thread without a lock, so a reader may
 * see two mixes mixed, which a live display can afford.
 */
internal class LivePlayback {
    val mixMeter = LevelMeter()

    /** Mixes since the last [reset]; publishes the mix fields. */
    @Volatile private var mixes = 0L
    @Volatile private var played = false
    @Volatile private var underruns = 0
    @Volatile private var trackFrames = 0
    @Volatile private var activeSpeakers = 0

    /** The last output latency estimate; NaN for none. */
    @Volatile var latencyMs = Float.NaN

    private val slots = DiagnosticsLimits.SPEAKER_SLOTS
    private val sessions = IntArray(slots) { NO_SESSION }
    private val lastNanos = LongArray(slots)
    private val meters = Array(slots) { LevelMeter() }
    private val available = IntArray(slots)
    private val concealed = IntArray(slots)
    private val errors = IntArray(slots)
    private val lost = IntArray(slots)
    private val late = IntArray(slots)

    /** Written after every change of the talker table; publishes it. */
    @Volatile private var updates = 0L

    /** Back to nothing heard; playback thread. */
    fun reset() {
        mixMeter.reset()
        sessions.fill(NO_SESSION)
        latencyMs = Float.NaN
        mixes = 0L
        updates++
    }

    /** One mix was written; playback thread. */
    fun onMix(played: Boolean, underruns: Int, trackFrames: Int, activeSpeakers: Int) {
        this.played = played
        this.underruns = underruns
        this.trackFrames = trackFrames
        this.activeSpeakers = activeSpeakers
        mixes++
    }

    /**
     * The live slot of [session], taking a free or idle one on its first frame; -1 while more
     * talkers than slots were heard within the last second. Playback thread.
     */
    fun slotFor(session: Int, nowNanos: Long): Int {
        var free = -1
        for (i in 0 until slots) {
            val s = sessions[i]
            if (s == session) return i
            if (free < 0 && (s == NO_SESSION || nowNanos - lastNanos[i] > LIVE_SPEAKER_IDLE_NANOS)) free = i
        }
        if (free >= 0) {
            sessions[free] = session
            meters[free].reset()
        }
        return free
    }

    fun speakerMeter(slot: Int): LevelMeter = meters[slot]

    /** [speech]'s counters after its frame in this mix, at [nowNanos]; playback thread. */
    fun update(slot: Int, nowNanos: Long, speech: AudioOutputSpeech) {
        lastNanos[slot] = nowNanos
        available[slot] = speech.lastAvailablePackets
        concealed[slot] = speech.concealedFrames
        errors[slot] = speech.decodeErrors
        lost[slot] = speech.lostPackets
        late[slot] = speech.latePackets
        updates++
    }

    /** The last mix, or null if none was written since the diagnostics became active. */
    fun reading(): PlaybackReading? {
        if (mixes == 0L) return null
        return PlaybackReading(
            played = played,
            mixRmsDbfs = mixMeter.rmsDbfs,
            mixPeakDbfs = mixMeter.peakDbfs,
            mixPeakHoldDbfs = mixMeter.peakHoldDbfs,
            mixClippedSamples = mixMeter.clippedSamples,
            underruns = underruns,
            trackBufferFrames = trackFrames,
            outputLatencyMs = latencyMs.takeUnless { it.isNaN() },
            activeSpeakers = activeSpeakers,
        )
    }

    /** The talkers heard within the last second before [nowNanos], with their [recording] track if any. */
    fun speakers(nowNanos: Long, recording: ArmedRecording?): List<SpeakerReading> {
        if (updates == 0L) return emptyList()
        val result = ArrayList<SpeakerReading>(slots)
        for (i in 0 until slots) {
            val session = sessions[i]
            if (session == NO_SESSION || nowNanos - lastNanos[i] > LIVE_SPEAKER_IDLE_NANOS) continue
            val track = recording?.slotOf(session)?.takeIf { it >= 0 }?.plus(1)
            result += SpeakerReading(
                session = session,
                track = track,
                availablePackets = available[i],
                concealedFrames = concealed[i],
                decodeErrors = errors[i],
                lostPackets = lost[i],
                latePackets = late[i],
                rmsDbfs = meters[i].rmsDbfs,
                peakDbfs = meters[i].peakDbfs,
            )
        }
        return result
    }
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

/** The columns of `playback.csv` (B10), one row per mix written to the track. */
internal object PlaybackColumns {
    const val TABLE = "playback"

    const val PLAYED = 0
    const val MIX_RMS = 1
    const val MIX_PEAK = 2
    const val MIX_CLIPPED = 3
    const val UNDERRUNS = 4
    const val TRACK_BUFFER_FRAMES = 5
    const val OUTPUT_LATENCY_MS = 6
    const val ACTIVE_SPEAKERS = 7

    val NAMES: List<String> = listOf(
        "played", "mix_rms_dbfs", "mix_peak_dbfs", "mix_clipped", "underruns", "track_buffer_frames",
        "output_latency_ms", "active_speakers",
    )
}

/** The columns of `speakers.csv` (B10), one row per talker with a slot per mix. */
internal object SpeakerColumns {
    const val TABLE = "speakers"

    /** The talker's slot, 1 to 4: `speaker_1` to `speaker_4`. */
    const val SPEAKER = 0
    const val AVAILABLE_PACKETS = 1
    const val CONCEALED_FRAMES = 2
    const val DECODE_ERRORS = 3
    const val LOST_PACKETS = 4
    const val LATE_PACKETS = 5
    const val RMS = 6
    const val PEAK = 7

    val NAMES: List<String> = listOf(
        "speaker", "available_packets", "concealed_frames", "decode_errors", "lost_packets", "late_packets",
        "rms_dbfs", "peak_dbfs",
    )
}

/** The columns of `packets.csv` (B10), one row per voice packet in either direction. */
internal object PacketColumns {
    const val TABLE = "packets"

    /** The ring of received packets, merged into [TABLE] at freeze. */
    const val RECEIVED = "packets_received"

    const val DIRECTION = 0
    const val SPEAKER = 1
    const val FRAME_NUMBER = 2
    const val BYTES = 3
    const val TERMINATOR = 4

    const val SENT_DIRECTION = 0f
    const val RECEIVED_DIRECTION = 1f

    /** The speaker of a sent packet: this device. */
    const val SELF = 0f

    val NAMES: List<String> = listOf("direction", "speaker", "frame_number", "bytes", "terminator")
}

/** The columns of `network.csv` (B10), one row per network sample. */
internal object NetworkColumns {
    const val TABLE = "network"

    val NAMES: List<String> = listOf(
        "using_udp", "tcp_ping_ms", "tcp_ping_var_ms", "udp_ping_ms", "udp_ping_var_ms", "good", "late", "lost",
        "resync", "remote_good", "remote_late", "remote_lost", "remote_resync", "so_sndbuf", "so_rcvbuf",
    )
}
