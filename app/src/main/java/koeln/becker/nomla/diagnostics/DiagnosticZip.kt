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

package koeln.becker.nomla.diagnostics

import android.media.AudioDeviceInfo
import koeln.becker.nomla.mumble.diagnostics.CapturedTrack
import koeln.becker.nomla.mumble.diagnostics.DiagnosticCapture
import koeln.becker.nomla.mumble.diagnostics.PipelineDescription
import koeln.becker.nomla.mumble.diagnostics.RouteInfo
import koeln.becker.nomla.mumble.diagnostics.SendStage
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a finished recording as the ZIP the user shares: `README.txt`, `meta.json`, one WAV per
 * track and the CSV tables. Only the capture, the user's review and the [AppInfo] go in, and of
 * the review only the ratings and the note.
 *
 * `meta.json` is built key by key from an allow-list, never by serializing an object, so nothing
 * the capture does not name explicitly can leave the device. A Bluetooth route is written by its
 * type only, without a product name, whatever the capture holds (G2).
 */
object DiagnosticZip {
    const val FORMAT_VERSION = 1

    private const val README = "README.txt"
    private const val META = "meta.json"
    private const val EVENTS = "events"
    private const val JSON_INDENT = 2
    private const val SEND = "send"
    private const val RECEIVE = "receive"
    private const val SPEAKER_PREFIX = "speaker_"
    private const val MIX_TRACK_ID = "7_mix"

    /** Bluetooth devices, which leave the device by type only (G2). */
    private val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    private val STARTED_AT_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** A split track: its base id and its part, as `1_mic_raw_2`. */
    private val SPLIT_TRACK = Regex("(.+)_(\\d+)")

    private val TRACK_MEANINGS: Map<String, String> = mapOf(
        SendStage.MIC_RAW.trackId to "microphone as the platform delivers it, at its own rate",
        SendStage.RESAMPLED.trackId to "microphone resampled to 48 kHz",
        SendStage.APM.trackId to "behind the WebRTC audio processing (echo cancellation, gain control, high-pass)",
        SendStage.DENOISED.trackId to "behind the denoiser (Speex or RNNoise)",
        SendStage.GATED.trackId to "behind the input mode's gate: the frame while open, silence while closed",
        SendStage.SENT.trackId to "the Opus packets actually sent, decoded as the other side hears them",
        MIX_TRACK_ID to "the playback mix as written to the speaker",
    )

    /** Every CSV file (without `.csv`) with each column and what it holds, in file order. */
    private val COLUMN_DOCS: Map<String, List<Pair<String, String>>> = DiagnosticReadme.COLUMNS

    /** Every CSV file (without `.csv`) with its header columns, `t_ms` first. */
    val CSV_COLUMNS: Map<String, List<String>> = COLUMN_DOCS.mapValues { (_, columns) -> columns.map { it.first } }

    /** Every event name `events.csv` can hold. */
    val EVENT_NAMES: List<String> = DiagnosticReadme.EVENTS.map { it.first }

    /** What the track [id] holds, in English; empty for an id this version does not know. */
    fun meaningOf(id: String): String {
        val split = SPLIT_TRACK.matchEntire(id)?.groupValues
        val base = split?.get(1)
        val part = split?.get(2)
        return when {
            id in TRACK_MEANINGS -> TRACK_MEANINGS.getValue(id)
            base == null -> ""
            "${base}_" == SPEAKER_PREFIX -> "talker $part, numbered in order of their first packet"
            base in TRACK_MEANINGS -> "${TRACK_MEANINGS.getValue(base)}; part $part, after a rate change"
            else -> ""
        }
    }

    /** Writes the ZIP of [capture] with [review] and [info] to [out], which stays open. Blocking. */
    fun write(capture: DiagnosticCapture, review: CaptureReview, info: AppInfo, out: OutputStream) {
        val zip = ZipOutputStream(out).apply { setMethod(ZipOutputStream.DEFLATED) }
        textEntry(zip, README) { it.append(DiagnosticReadme.text()) }
        textEntry(zip, META) { it.append(meta(capture, review, info).toString(JSON_INDENT)).append('\n') }
        for (track in capture.tracks) {
            zip.putNextEntry(ZipEntry("${track.id}.wav"))
            capture.writeTrack(track, zip)
            zip.closeEntry()
        }
        for (table in capture.tables) textEntry(zip, "${table.name}.csv") { capture.writeTable(table, it) }
        textEntry(zip, "$EVENTS.csv") { capture.writeEvents(it) }
        zip.finish()
    }

    /** One UTF-8 text entry; the writer is flushed, never closed, so [zip] stays open. */
    private fun textEntry(zip: ZipOutputStream, name: String, write: (Appendable) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
        write(writer)
        writer.flush()
        zip.closeEntry()
    }

    private fun meta(capture: DiagnosticCapture, review: CaptureReview, info: AppInfo): JSONObject = JSONObject()
        .put("format_version", FORMAT_VERSION)
        .put(
            "app",
            JSONObject().put("version_name", info.versionName).put("version_code", info.versionCode)
                .put("flavor", info.flavor),
        )
        .put(
            "device",
            JSONObject().put("manufacturer", info.manufacturer).put("model", info.model)
                .put("android_release", info.release).put("sdk", info.sdk),
        )
        .put("route", route(capture.route))
        .put("audio", JSONObject().put("pipelines", JSONArray(capture.pipelines.map(::pipeline))))
        .put("started_at", STARTED_AT_FORMAT.format(Instant.ofEpochMilli(capture.startedAtEpochMs)))
        .put("duration_ms", capture.durationMs)
        .put("server_showed_recording", capture.serverShowedRecording)
        .put("speakers_without_track", capture.speakersWithoutTrack)
        .put("dropped_frames", capture.droppedFrames)
        .put("tracks", JSONArray(capture.tracks.map { track(it, review.ratings[it.id]) }))
        .put("note", review.note)

    /** The route by its fields; a Bluetooth device never with its name (G2), an unknown route all null. */
    private fun route(route: RouteInfo?): JSONObject {
        val name = route?.productName?.takeUnless { route.deviceType in BLUETOOTH_TYPES }
        return JSONObject()
            .put("device_type", route?.deviceType.orNull())
            .put("product_name", name.orNull())
            .put("stream_volume", route?.streamVolume.orNull())
            .put("stream_max_volume", route?.streamMaxVolume.orNull())
            .put("audio_mode", route?.audioMode.orNull())
    }

    private fun pipeline(description: PipelineDescription): JSONObject {
        val capture = description.capture
        val settings = JSONObject()
        for ((key, value) in description.settings) settings.put(key, value)
        return JSONObject()
            .put(
                "capture",
                JSONObject()
                    .put("sample_rate", capture.sampleRate)
                    .put("audio_source", capture.audioSource.orNull())
                    .put("channels", capture.channels)
                    .put("buffer_frames", capture.bufferFrames.orNull())
                    .put("platform_effects", JSONArray(capture.platformEffects)),
            )
            .put("settings", settings)
            .put(
                "stages",
                JSONArray(SendStage.entries.filter { it in description.stagesPresent }.map { it.trackId }),
            )
    }

    private fun track(track: CapturedTrack, rating: Rating?): JSONObject = JSONObject()
        .put("id", track.id)
        .put("file", "${track.id}.wav")
        .put("sample_rate", track.sampleRate)
        .put("side", if (track.sendSide) SEND else RECEIVE)
        .put("meaning", meaningOf(track.id))
        .put("rating", rating?.name?.lowercase(Locale.ROOT).orNull())

    /** `JSONObject.put` drops a key whose value is null; JSON null keeps it. */
    private fun Any?.orNull(): Any = this ?: JSONObject.NULL
}

/**
 * The fixed English `README.txt` of a diagnostics ZIP: every file, every CSV column (one line
 * each) and every event, from the tables below, which [DiagnosticZip] also checks headers against.
 */
internal object DiagnosticReadme {
    private val STAGE_COLUMNS: List<Pair<String, String>> = SendStage.entries.flatMap { stage ->
        val prefix = stage.name.lowercase(Locale.ROOT)
        val track = stage.trackId
        listOf(
            "${prefix}_rms_dbfs" to "RMS level of the $track tap in this frame, dBFS",
            "${prefix}_peak_dbfs" to "peak level of the $track tap in this frame, dBFS",
            "${prefix}_clipped" to "samples of the $track tap at full scale in this frame",
        )
    }

    private const val T_MS = "t_ms"

    val COLUMNS: Map<String, List<Pair<String, String>>> = linkedMapOf(
        "frames" to listOf(
            T_MS to "time of the capture frame (its end), ms since the recording start",
            "send_allowed" to "1 if sending was allowed (not muted, push-to-talk held or not in use), else 0",
            "transmit" to "1 if the input mode's gate was open for this frame",
            "muted" to "1 if you were muted",
            "read_samples" to "samples the microphone read returned",
            "expected_samples" to "samples a full 10 ms frame has at the microphone's rate",
            "read_ms" to "how long the microphone read blocked, ms",
            "silenced" to "1 while the platform silenced the microphone (another app, a call)",
        ) + STAGE_COLUMNS + listOf(
            "vad_probability" to "voice probability of the capture chain, 0 to 1: RNNoise's when RNNoise runs, " +
                "otherwise the APM level map",
            "vad_level_dbfs" to "level the voice activity detector measured, dBFS",
            "vad_floor_dbfs" to "adaptive detector: estimated noise floor, dBFS",
            "vad_speech_dbfs" to "adaptive detector: estimated speech level, dBFS",
            "vad_threshold_dbfs" to "detector threshold, dBFS",
            "vad_open" to "1 if the detector heard voice",
            "vad_holding" to "1 if the gate was held open below the threshold (hangover)",
            "vad_too_close" to "adaptive detector: 1 if speech and noise floor were too close to tell apart",
            "apm_erl_db" to "echo canceller: echo return loss, dB (only in frames with a new statistics poll)",
            "apm_erle_db" to "echo canceller: echo return loss enhancement, dB",
            "apm_divergent_fraction" to "echo canceller: fraction of divergent filter blocks",
            "apm_delay_median_ms" to "echo canceller: median estimated echo delay, ms",
            "apm_delay_std_ms" to "echo canceller: standard deviation of the echo delay, ms",
            "apm_delay_ms" to "echo canceller: current estimated echo delay, ms",
        ),
        "playback" to listOf(
            T_MS to "time the mix was written to the speaker track, ms since the recording start",
            "played" to "1 if anybody was heard in this mix",
            "mix_rms_dbfs" to "RMS level of the mix, dBFS",
            "mix_peak_dbfs" to "peak level of the mix, dBFS",
            "mix_clipped" to "samples of the mix at full scale since metering started",
            "underruns" to "underruns of the speaker track since it was built",
            "track_buffer_frames" to "the speaker track's buffer, frames",
            "output_latency_ms" to "estimated latency from a write to the speaker, ms",
            "active_speakers" to "talkers mixed into this mix",
        ),
        "speakers" to listOf(
            T_MS to "time of the mix, ms since the recording start",
            "speaker" to "the talker's number: 1 to 4 for speaker_1 to speaker_4",
            "available_packets" to "packets waiting in the talker's jitter buffer",
            "concealed_frames" to "frames concealed (packet loss concealment) in the talker's stream",
            "decode_errors" to "Opus decode errors in the talker's stream",
            "lost_packets" to "packets lost, from frame-number gaps on arrival",
            "late_packets" to "packets that arrived too late, from frame numbers on arrival",
            "rms_dbfs" to "RMS level of the talker's last frame, dBFS",
            "peak_dbfs" to "peak level of the talker's last frame, dBFS",
        ),
        "packets" to listOf(
            T_MS to "time the packet was sent or received, ms since the recording start",
            "direction" to "0 sent by this device, 1 received",
            "speaker" to "0 for this device, 1 to 4 for speaker_1 to speaker_4, empty for a talker without a track",
            "frame_number" to "the packet's Mumble frame (sequence) number",
            "bytes" to "Opus payload size, bytes",
            "terminator" to "1 if the packet ends a transmission",
        ),
        "network" to listOf(
            T_MS to "time of the sample (every 100 ms), ms since the recording start",
            "using_udp" to "1 if voice went over UDP, 0 if tunnelled through TCP",
            "tcp_ping_ms" to "TCP ping round trip: running mean since connect, ms (not the current value)",
            "tcp_ping_var_ms" to "TCP ping round trip: variance since connect, in ms² despite the name " +
                "(running value since connect, not the current one)",
            "udp_ping_ms" to "UDP ping round trip: running mean since connect, ms (not the current value)",
            "udp_ping_var_ms" to "UDP ping round trip: variance since connect, in ms² despite the name " +
                "(running value since connect, not the current one)",
            "good" to "voice packets this side decrypted fine, since connect",
            "late" to "voice packets this side received late, since connect",
            "lost" to "voice packets this side lost, since connect",
            "resync" to "crypt resyncs this side requested, since connect",
            "remote_good" to "the server's count of good packets from this device",
            "remote_late" to "the server's count of late packets from this device",
            "remote_lost" to "the server's count of lost packets from this device",
            "remote_resync" to "the server's count of crypt resyncs",
            "so_sndbuf" to "UDP socket send buffer (SO_SNDBUF), bytes; empty without a socket",
            "so_rcvbuf" to "UDP socket receive buffer (SO_RCVBUF), bytes; empty without a socket",
        ),
        "events" to listOf(
            T_MS to "time of the event, ms since the recording start",
            "name" to "the event, one of the names listed under EVENTS",
            "value" to "the event's value, see EVENTS (quoted if it holds a comma)",
        ),
    )

    val EVENTS: List<Pair<String, String>> = listOf(
        "route_changed" to "voice was routed to another device; value: its AudioDeviceInfo type constant " +
            "(empty while the platform decides). Never a device name.",
        "connection_warning" to "the connection reported a problem; value: its kind",
        "settings_changed" to "audio settings changed; value: their keys, comma-separated",
        "capture_silenced" to "the platform silenced the microphone (1) or let it be heard again (0)",
        "audio_mode" to "the AudioManager mode changed; value: the new mode constant",
        "pipeline_rebuilt" to "the send pipeline was rebuilt; meta.json audio.pipelines lists each in order",
        "transport_changed" to "voice switched transport; value: udp or tcp",
        "talk_start" to "you started transmitting",
        "talk_end" to "you stopped transmitting",
        "connected" to "the connection to the server was established",
        "reconnecting" to "the connection dropped and is being re-established",
        "disconnected" to "the connection ended",
    )

    private val INTRO = """
        Nomla diagnostics recording
        ===========================

        A short recording of Nomla's audio path, made to find audio problems. It holds no server,
        user or channel names, no chat and no credentials. Talkers appear as speaker_1 to
        speaker_4 only, numbered in the order they were first heard.

        All times (t_ms) are milliseconds since the recording start, to 0.1 ms. Levels are dBFS
        (0 is full scale). Flags are 1 or 0. An empty CSV cell means "no value".

        FILES

        README.txt: this file.
        meta.json: app version, device, audio route, the send pipelines and their settings, start
          time (UTC), length, the tracks with your ratings, and your note. A Bluetooth device
          appears by type only, without its name.
        <track>.wav: one mono 16-bit PCM WAV per track, at the track's own sample rate (meta.json
          lists each track's rate, side and meaning).
        frames.csv: one row per 10 ms capture frame.
        playback.csv: one row per 10 ms playback mix.
        speakers.csv: one row per talker with a track per mix.
        packets.csv: one row per voice packet, sent and received.
        network.csv: one row per network sample, every 100 ms.
        events.csv: one row per event (see EVENTS).

        TRACKS

        Every track spans the whole recording on a common 10 ms grid, with silence where the tap
        gave nothing, so the tracks line up without an offset.
        1_mic_raw: the microphone as the platform delivers it, at its own rate. When the rate
          changes mid-recording a new track starts (1_mic_raw_2, 1_mic_raw_3, ...); every split
          raw track still spans the whole recording and is silent outside its own segment.
        2_resampled: the microphone resampled to 48 kHz (only when the microphone runs at another
          rate).
        3_apm: behind the WebRTC audio processing (echo cancellation, gain control, high-pass).
        4_denoised: behind the denoiser (Speex or RNNoise).
        5_gate: behind the input mode's gate.
        6_sent: the Opus packets actually sent, decoded. Each packet is placed at its first
          captured frame, not at its send time, so it lines up with 5_gate.
        7_mix: the playback mix as written to the speaker, 48 kHz.
        speaker_1 .. speaker_4: each talker's decoded voice, 48 kHz. Talkers beyond four get no
          track and are counted in meta.json speakers_without_track.
        Send-side tracks are silent while you were muted or push-to-talk was released
        (send_allowed = 0 in frames.csv).
    """.trimIndent()

    /** The whole README, ending in a line break. */
    fun text(): String = buildString {
        append(INTRO).append("\n\nCOLUMNS\n")
        for ((table, columns) in COLUMNS) {
            append('\n').append(table).append(".csv\n")
            for ((column, meaning) in columns) append(column).append(": ").append(meaning).append('\n')
        }
        append("\nEVENTS\n\n")
        for ((name, meaning) in EVENTS) append(name).append(": ").append(meaning).append('\n')
    }
}
