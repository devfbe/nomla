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

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import koeln.becker.nomla.mumble.audio.routing.CommunicationDevice
import koeln.becker.nomla.mumble.diagnostics.export.TelemetryCsv
import koeln.becker.nomla.mumble.diagnostics.export.WavWriter
import java.io.OutputStream

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

/** Where a recording stands. */
public sealed interface RecordingState {
    /** No recording; [aborted] says why the last one was dropped, null if none was. */
    public data class Idle(val aborted: AbortReason? = null) : RecordingState

    /** A recording of [seconds] runs since [startedElapsedMs] (`SystemClock.elapsedRealtime`). */
    public data class Recording(val startedElapsedMs: Long, val seconds: Int) : RecordingState

    /** The last recording, kept until it is discarded or a new one starts. */
    public class Finished(public val capture: DiagnosticCapture) : RecordingState
}

/** Why a recording was dropped before its capture was kept. */
public enum class AbortReason {
    CANCELLED,
    DISCONNECTED,

    /** Its capture could not be built (too large, or an export error, which is logged). */
    FAILED,
}

/** What [SessionDiagnostics.startRecording] answers. */
public sealed interface RecordingStart {
    public data object Started : RecordingStart

    public data class Refused(val reason: RecordingRefusal) : RecordingStart
}

/** Why a recording did not start. */
public enum class RecordingRefusal {
    /** No synchronized connection. */
    NOT_CONNECTED,

    /**
     * Connected, but the server has not sent its `ServerConfig` yet, so whether it allows
     * recording is unknown.
     */
    NOT_READY,

    /** The server does not allow clients to record. */
    SERVER_FORBIDS,
    ALREADY_RECORDING,

    /** Not one of the offered lengths. */
    INVALID_DURATION,

    /** The recording's buffers do not fit into memory. */
    OUT_OF_MEMORY,
}

/**
 * A finished recording: every track on one time grid, the telemetry tables, the events and the
 * pipelines it ran on. Talkers appear as `speaker_1..4` only, never by session or name.
 *
 * @param serverShowedRecording whether the server showed this user as recording at the end.
 * @param speakersWithoutTrack talkers heard after all speaker tracks were taken.
 * @param droppedFrames frames the fullest PCM track lost for lack of room.
 * @param route the audio route, captured once at the start (P15); Bluetooth without a name (G2).
 */
@Suppress("LongParameterList") // The parts of one recording, as the export writes them.
public class DiagnosticCapture(
    public val startedAtEpochMs: Long,
    public val durationMs: Long,
    public val tracks: List<CapturedTrack>,
    public val tables: List<TelemetryTable>,
    public val events: List<DiagnosticEvent>,
    public val pipelines: List<PipelineDescription>,
    public val serverShowedRecording: Boolean,
    public val speakersWithoutTrack: Int,
    public val droppedFrames: Int,
    public val route: RouteInfo?,
) {
    /** Writes [track] as a mono 16-bit WAV at its own rate to [out], which stays open. */
    public fun writeTrack(track: CapturedTrack, out: OutputStream) {
        WavWriter.write(out, track.samples, track.sampleRate)
    }

    /**
     * Writes [table] as CSV to [out]: a header `t_ms,<columns>`, then one line per row; times in ms
     * to 1 decimal, values to at most 2, an empty cell for no value, `.` as the decimal point.
     */
    public fun writeTable(table: TelemetryTable, out: Appendable) {
        TelemetryCsv.write(table, out)
    }

    /** Writes [events] as `events.csv` to [out]: `t_ms,name,value`, times as in [writeTable]. */
    public fun writeEvents(out: Appendable) {
        TelemetryCsv.writeEvents(events, out)
    }
}

/**
 * The audio route at one moment: the `AudioDeviceInfo` [deviceType] voice is routed to (null while
 * the platform decides), its [productName] (null for every Bluetooth device, G2, and when unknown),
 * the playback stream's volume and maximum, and the `AudioManager` mode.
 */
public data class RouteInfo(
    val deviceType: Int?,
    val productName: String?,
    val streamVolume: Int,
    val streamMaxVolume: Int,
    val audioMode: Int,
) {
    public companion object {
        /** Bluetooth devices, which are recorded by type only. */
        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        /**
         * The route to [device] now, with the volume of [stream]: the voice-call stream while a
         * device is routed, music otherwise, as the playback track picks it.
         * productName is null for every Bluetooth type (SCO, BLE headset/speaker, A2DP, hearing aid).
         */
        public fun current(
            context: Context,
            device: CommunicationDevice?,
            stream: Int = if (device != null) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC,
        ): RouteInfo {
            val audioManager = context.getSystemService(AudioManager::class.java)
            return RouteInfo(
                deviceType = device?.type,
                productName = device?.let(::productNameOf),
                streamVolume = audioManager.getStreamVolume(stream),
                streamMaxVolume = audioManager.getStreamMaxVolume(stream),
                audioMode = audioManager.mode,
            )
        }

        /** The name of a wired, USB or built-in [device]; null for Bluetooth and for none. */
        internal fun productNameOf(device: CommunicationDevice): String? =
            device.name.takeUnless { device.type in BLUETOOTH_TYPES || it.isEmpty() }
    }
}
