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

/**
 * One telemetry table of a finished recording, such as `frames.csv`: [rows] rows of
 * [columns].size values each, row-major in [values]. [timesMs] is each row's time since the
 * recording start. `Float.NaN` means "no value" and is written as an empty CSV cell.
 */
public class TelemetryTable(
    public val name: String,
    public val columns: List<String>,
    public val timesMs: DoubleArray,
    public val values: FloatArray,
    public val rows: Int,
)

/** Something that happened during a recording, [timeMs] after its start (`events.csv`). */
public data class DiagnosticEvent(val timeMs: Double, val name: String, val value: String)

/**
 * One mono 16-bit track of a finished recording. Every track spans the whole recording at its own
 * [sampleRate], so tracks align without an offset. [sendSide] tracks are silent while sending is
 * not allowed (muted, push-to-talk released).
 */
public class CapturedTrack(
    public val id: String,
    public val sampleRate: Int,
    public val samples: ShortArray,
    public val sendSide: Boolean,
)

/** The taps of the send path, in signal order, each with the id of its track. */
public enum class SendStage(public val trackId: String) {
    /** The microphone as the platform delivers it, at its own rate. */
    MIC_RAW("1_mic_raw"),

    /** Resampled to 48 kHz; only when the microphone runs at another rate. */
    RESAMPLED("2_resampled"),

    /** Behind the WebRTC APM (echo cancellation, gain control, high-pass). */
    APM("3_apm"),

    /** Behind the denoiser (Speex or RNNoise). */
    DENOISED("4_denoised"),

    /** Behind the input mode's gate: the frame while it is open, silence while it is closed. */
    GATED("5_gate"),

    /** What leaves the device: the boosted frame live, the decoded Opus packets in a recording. */
    SENT("6_sent"),
}

/**
 * How the microphone was opened: [sampleRate] in Hz, the `MediaRecorder.AudioSource` constant,
 * the channel count, the recorder's buffer in frames and the class names of the platform effects
 * (`NoiseSuppressor`, `AutomaticGainControl`) that are enabled on it.
 */
public data class CaptureDescription(
    val sampleRate: Int,
    val audioSource: Int? = null,
    val channels: Int = 1,
    val bufferFrames: Int? = null,
    val platformEffects: List<String> = emptyList(),
)

/**
 * One built send pipeline: the microphone, the audio settings it was built from (setting keys and
 * plain values, never a server, user or channel), and the send stages it contains.
 */
public data class PipelineDescription(
    val capture: CaptureDescription,
    val settings: Map<String, String>,
    val stagesPresent: Set<SendStage>,
)

/**
 * The live level of one send stage. [active] is false for a stage the current pipeline does not
 * have; its levels are then stale. [changeDb] is this stage's RMS minus the previous active
 * stage's, null for the first active stage and for inactive ones. Levels in dBFS.
 */
public data class StageReading(
    val stage: SendStage,
    val active: Boolean,
    val rmsDbfs: Float,
    val peakDbfs: Float,
    val peakHoldDbfs: Float,
    val clippedSamples: Long,
    val changeDb: Float?,
)

/**
 * The voice-activity gate as `MeterReading` shows it, plus the chain's voice [probability] (null
 * when no stage gives one). Levels in dBFS; null where the detector's mode has no such level.
 */
public data class GateReading(
    val levelDbfs: Float,
    val floorDbfs: Float?,
    val speechDbfs: Float?,
    val thresholdDbfs: Float?,
    val voice: Boolean,
    val holding: Boolean,
    val tooClose: Boolean,
    val probability: Float?,
)

/**
 * The WebRTC echo canceller's statistics of its last poll (NaN where webrtc reports nothing), and
 * [levelChangeDb], the APM's output RMS minus its input RMS (null while unknown).
 */
public data class EchoReading(
    val erlDb: Float,
    val erleDb: Float,
    val divergentFraction: Float,
    val delayMedianMs: Float,
    val delayStdMs: Float,
    val delayMs: Float,
    val levelChangeDb: Float?,
)

/**
 * The playback side of a snapshot, as of the last mix written to the track: whether anybody was
 * [played] in it, its levels in dBFS and its clipped samples since metering started, the track's
 * underruns since it was built and its buffer in frames, the estimated [outputLatencyMs] from a
 * write to the speaker (null until the first estimate), and how many talkers were mixed.
 */
public data class PlaybackReading(
    val played: Boolean,
    val mixRmsDbfs: Float,
    val mixPeakDbfs: Float,
    val mixPeakHoldDbfs: Float,
    val mixClippedSamples: Long,
    val underruns: Int,
    val trackBufferFrames: Int,
    val outputLatencyMs: Float?,
    val activeSpeakers: Int,
)

/**
 * One talker heard within the last second. [session] is the server's session id, for the live page
 * to show a name; it never leaves the device (a recording names talkers `speaker_1..4` only).
 * [track] is the talker's recording track (1 to 4) while a recording runs and they have one, else
 * null. The counters are those of the talker's current stream; levels of their last frame in dBFS.
 */
public data class SpeakerReading(
    val session: Int,
    val track: Int?,
    val availablePackets: Int,
    val concealedFrames: Int,
    val decodeErrors: Int,
    val lostPackets: Int,
    val latePackets: Int,
    val rmsDbfs: Float,
    val peakDbfs: Float,
)

/**
 * The transport at the network sampler's last tick: where voice goes, the ping round trips (mean
 * in ms and variance in ms², NaN before the first ping), the crypt counters of this side and the
 * server's, the UDP socket's buffers in bytes (null without a socket), and [extra] values the
 * session adds.
 */
public data class NetworkReading(
    val usingUdp: Boolean,
    val tcpPingMs: Float,
    val tcpPingVarMs: Float,
    val udpPingMs: Float,
    val udpPingVarMs: Float,
    val good: Int,
    val late: Int,
    val lost: Int,
    val resync: Int,
    val remoteGood: Int,
    val remoteLate: Int,
    val remoteLost: Int,
    val remoteResync: Int,
    val sendBufferBytes: Int?,
    val receiveBufferBytes: Int?,
    val extra: Map<String, Float> = emptyMap(),
)

/**
 * Everything the live page shows, read at one moment. [gateHistory] holds whether the gate was
 * open for each of the last [DiagnosticsLimits.GATE_HISTORY_FRAMES] capture frames, oldest first.
 */
public data class DiagnosticsSnapshot(
    val stages: List<StageReading>,
    val gateHistory: BooleanArray,
    val gate: GateReading?,
    val echo: EchoReading?,
    val playback: PlaybackReading? = null,
    val speakers: List<SpeakerReading> = emptyList(),
    val network: NetworkReading? = null,
    val capture: CaptureDescription? = null,
) {
    // By content: a data class compares arrays by identity.
    override fun equals(other: Any?): Boolean =
        this === other || other is DiagnosticsSnapshot &&
            stages == other.stages && gateHistory.contentEquals(other.gateHistory) && gate == other.gate &&
            echo == other.echo && playback == other.playback && speakers == other.speakers &&
            network == other.network && capture == other.capture

    override fun hashCode(): Int {
        var result = stages.hashCode()
        for (part in listOf(gateHistory.contentHashCode(), gate, echo, playback, speakers, network, capture)) {
            result = HASH_MULTIPLIER * result + part.hashCode()
        }
        return result
    }

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}
