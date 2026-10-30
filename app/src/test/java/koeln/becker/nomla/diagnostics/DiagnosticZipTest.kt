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
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import koeln.becker.nomla.mumble.diagnostics.CaptureDescription
import koeln.becker.nomla.mumble.diagnostics.CapturedTrack
import koeln.becker.nomla.mumble.diagnostics.DiagnosticCapture
import koeln.becker.nomla.mumble.diagnostics.DiagnosticEvent
import koeln.becker.nomla.mumble.diagnostics.PipelineDescription
import koeln.becker.nomla.mumble.diagnostics.RouteInfo
import koeln.becker.nomla.mumble.diagnostics.SendStage
import koeln.becker.nomla.mumble.diagnostics.TelemetryTable
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class DiagnosticZipTest {
    private val info = AppInfo(
        versionName = "1.2.3-beta",
        versionCode = VERSION_CODE,
        flavor = "beta",
        manufacturer = "Fairphone",
        model = "FP5",
        release = "15",
        sdk = SDK,
    )

    private val review = CaptureReview(
        ratings = mapOf("1_mic_raw" to Rating.GOOD, "7_mix" to Rating.BAD, "speaker_2" to Rating.MEDIUM),
        note = "echo after 3 s",
    )

    // --- the entries -----------------------------------------------------------------------------

    @Test
    fun `the zip holds exactly the documented entries`() {
        val entries = zip(capture())

        assertThat(entries.keys).containsExactly(
            "README.txt", "meta.json",
            "1_mic_raw.wav", "1_mic_raw_2.wav", "2_resampled.wav", "3_apm.wav", "4_denoised.wav", "5_gate.wav",
            "6_sent.wav", "7_mix.wav", "speaker_1.wav", "speaker_2.wav",
            "frames.csv", "playback.csv", "speakers.csv", "packets.csv", "network.csv", "events.csv",
        ).inOrder()
    }

    @Test
    fun `the mix is exported as 7_mix`() {
        val wav = zip(capture()).getValue("7_mix.wav")

        assertThat(rateOf(wav)).isEqualTo(CAPTURE_RATE)
    }

    @Test
    fun `speakers are numbered, not named`() {
        val entries = zip(capture())

        assertThat(entries.keys).containsAtLeast("speaker_1.wav", "speaker_2.wav")
        val tracks = meta(entries).getJSONArray("tracks").objects()
        val speakers = tracks.filter { it.getString("id").startsWith("speaker_") }
        assertThat(speakers.map { it.getString("file") }).containsExactly("speaker_1.wav", "speaker_2.wav")
        assertThat(speakers.map { it.getString("side") }).containsExactly("receive", "receive")
    }

    @Test
    fun `wav entries carry their real rate`() {
        val entries = zip(capture())

        assertThat(rateOf(entries.getValue("1_mic_raw.wav"))).isEqualTo(RAW_RATE)
        assertThat(rateOf(entries.getValue("1_mic_raw_2.wav"))).isEqualTo(CAPTURE_RATE)
        val raw = meta(entries).getJSONArray("tracks").objects().first { it.getString("id") == "1_mic_raw" }
        assertThat(raw.getInt("sample_rate")).isEqualTo(RAW_RATE)
        assertThat(raw.getString("side")).isEqualTo("send")
    }

    // --- meta.json -------------------------------------------------------------------------------

    @Test
    fun `meta json has exactly the allowed keys`() {
        val meta = meta(zip(capture()))

        assertThat(meta.keyList()).containsExactly(
            "format_version", "app", "device", "route", "audio", "started_at", "duration_ms",
            "server_showed_recording", "speakers_without_track", "dropped_frames", "tracks", "note",
        ).inOrder()
        assertThat(meta.getInt("format_version")).isEqualTo(1)
        assertThat(meta.getJSONObject("app").keyList()).containsExactly("version_name", "version_code", "flavor")
        assertThat(meta.getJSONObject("device").keyList())
            .containsExactly("manufacturer", "model", "android_release", "sdk")
        assertThat(meta.getJSONObject("route").keyList())
            .containsExactly("device_type", "product_name", "stream_volume", "stream_max_volume", "audio_mode")
        assertThat(meta.getString("started_at")).isEqualTo("2026-10-30T12:34:56.789Z")
        assertThat(meta.getLong("duration_ms")).isEqualTo(DURATION_MS)
        assertThat(meta.getBoolean("server_showed_recording")).isTrue()
        assertThat(meta.getInt("speakers_without_track")).isEqualTo(1)
        assertThat(meta.getInt("dropped_frames")).isEqualTo(2)
        val pipeline = meta.getJSONObject("audio").getJSONArray("pipelines").getJSONObject(0)
        assertThat(pipeline.keyList()).containsExactly("capture", "settings", "stages").inOrder()
        assertThat(pipeline.getJSONObject("settings").getString("input_mode")).isEqualTo("voice_activity")
        for (track in meta.getJSONArray("tracks").objects()) {
            assertThat(track.keyList()).containsExactly("id", "file", "sample_rate", "side", "meaning", "rating")
                .inOrder()
        }
    }

    @Test
    fun `ratings and note are carried`() {
        val meta = meta(zip(capture()))

        val ratings = meta.getJSONArray("tracks").objects().associate { it.getString("id") to it.get("rating") }
        assertThat(ratings["1_mic_raw"]).isEqualTo("good")
        assertThat(ratings["7_mix"]).isEqualTo("bad")
        assertThat(ratings["speaker_2"]).isEqualTo("medium")
        assertThat(ratings["3_apm"]).isEqualTo(JSONObject.NULL)
        assertThat(meta.getString("note")).isEqualTo("echo after 3 s")
    }

    /**
     * T6: only the capture, the review and the app info reach the zip. Every key of meta.json is on
     * the allow-list (the pipeline settings are the capture's own keys), the note is the review's
     * only free text, and every CSV header is the documented one.
     */
    @Test
    fun `only capture, review and app info reach the zip`() {
        val capture = capture()
        val entries = zip(capture)
        val meta = meta(entries)

        val keys = mutableSetOf<String>()
        collectKeys(meta, keys, skip = "settings")
        assertThat(ALLOWED_KEYS).containsAtLeastElementsIn(keys)
        val settings = meta.getJSONObject("audio").getJSONArray("pipelines").getJSONObject(0)
            .getJSONObject("settings")
        assertThat(settings.keyList()).containsExactlyElementsIn(capture.pipelines.single().settings.keys)

        val strings = mutableListOf<String>()
        collectStrings(meta, strings, skip = "meaning")
        val fromCapture = capture.pipelines.flatMap { it.settings.values + it.capture.platformEffects } +
            capture.tracks.map { it.id }
        val fixed = setOf("send", "receive", "good", "medium", "bad", info.versionName, info.flavor,
            info.manufacturer, info.model, info.release, "2026-10-30T12:34:56.789Z", WIRED_NAME)
        val free = strings.filterNot { it in fromCapture || it in fixed || it.endsWith(".wav") }
        assertThat(free).containsExactly(review.note)
        for (track in meta.getJSONArray("tracks").objects()) {
            assertThat(track.getString("meaning")).isEqualTo(DiagnosticZip.meaningOf(track.getString("id")))
        }

        for ((table, columns) in DiagnosticZip.CSV_COLUMNS) {
            val header = entries.getValue("$table.csv").toString(Charsets.UTF_8).lineSequence().first()
            assertWithMessage(table).that(header.split(',')).containsExactlyElementsIn(columns).inOrder()
        }
    }

    @Test
    fun `a bluetooth headset is exported without its name`() {
        val name = "Felix's AirPods"
        for (type in listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID,
        )) {
            val entries = zip(capture(route = route(type, name)))

            val route = meta(entries).getJSONObject("route")
            assertWithMessage("type $type").that(route.isNull("product_name")).isTrue()
            assertThat(route.getInt("device_type")).isEqualTo(type)
            for ((entry, bytes) in entries) {
                assertWithMessage("$entry of type $type")
                    .that(bytes.toString(Charsets.ISO_8859_1)).doesNotContain("Felix")
            }
        }

        val wired = meta(zip(capture(route = route(AudioDeviceInfo.TYPE_WIRED_HEADSET, WIRED_NAME))))
        assertThat(wired.getJSONObject("route").getString("product_name")).isEqualTo(WIRED_NAME)
    }

    // --- README.txt ------------------------------------------------------------------------------

    /**
     * A ZIP entry's DOS time is local wall-clock time; the device's time zone must not show in it.
     * The same capture gives the same bytes in any zone, and every entry reads as the start in UTC
     * (to the DOS format's 2 s).
     */
    @Test
    fun `entry times do not reveal the time zone`() {
        val capture = capture()
        val original = TimeZone.getDefault()
        try {
            val bytes = listOf("UTC", "Asia/Kolkata", "America/Los_Angeles").map { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                ByteArrayOutputStream().also { DiagnosticZip.write(capture, review, info, it) }.toByteArray()
            }
            assertThat(bytes[1]).isEqualTo(bytes[0])
            assertThat(bytes[2]).isEqualTo(bytes[0])

            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            ZipInputStream(ByteArrayInputStream(bytes[0])).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    assertWithMessage(entry.name).that(entry.time).isEqualTo(STARTED_AT_DOS_MS)
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `README names every event`() {
        val capture = capture()
        val readme = zip(capture).getValue("README.txt").toString(Charsets.UTF_8)

        for (event in capture.events) assertThat(readme).contains(event.name)
        for (event in DiagnosticZip.EVENT_NAMES) assertThat(readme).contains(event)
    }

    @Test
    fun `README names every csv column`() {
        val entries = zip(capture())
        val readme = entries.getValue("README.txt").toString(Charsets.UTF_8)

        for (table in DiagnosticZip.CSV_COLUMNS.keys) {
            val header = entries.getValue("$table.csv").toString(Charsets.UTF_8).lineSequence().first()
            assertThat(readme).contains("$table.csv")
            for (column in header.split(',')) {
                assertWithMessage("$table.csv: $column").that(readme).contains(column)
            }
        }
        assertThat(readme).contains("ms²")
        assertThat(readme).contains("running mean")
    }

    /** The frames of what was not sent carry no levels; README says so where send_allowed is explained. */
    @Test
    fun `README says frames that were not sent have no levels`() {
        val readme = zip(capture()).getValue("README.txt").toString(Charsets.UTF_8)
        val sendAllowed = readme.lineSequence().single { it.startsWith("send_allowed: ") }

        assertThat(sendAllowed).contains("at 0 every level column of the row")
        for (column in listOf("vad_probability", "vad_level_dbfs", "vad_floor_dbfs", "vad_speech_dbfs")) {
            assertThat(sendAllowed).contains(column)
        }
        assertThat(readme).contains("not even as a\nlevel curve")
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun zip(capture: DiagnosticCapture): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        DiagnosticZip.write(capture, review, info, out)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun meta(entries: Map<String, ByteArray>) =
        JSONObject(entries.getValue("meta.json").toString(Charsets.UTF_8))

    private fun rateOf(wav: ByteArray): Int =
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(WAV_RATE_OFFSET)

    private fun JSONObject.keyList(): List<String> = keys().asSequence().toList()

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private fun collectKeys(value: Any?, into: MutableSet<String>, skip: String) {
        when (value) {
            is JSONObject -> for (key in value.keyList()) {
                into += key
                if (key != skip) collectKeys(value.get(key), into, skip)
            }
            is JSONArray -> for (i in 0 until value.length()) collectKeys(value.get(i), into, skip)
        }
    }

    private fun collectStrings(value: Any?, into: MutableList<String>, skip: String) {
        when (value) {
            is JSONObject -> for (key in value.keyList()) {
                if (key != skip) collectStrings(value.get(key), into, skip)
            }
            is JSONArray -> for (i in 0 until value.length()) collectStrings(value.get(i), into, skip)
            is String -> into += value
        }
    }

    private fun route(type: Int, name: String?) = RouteInfo(
        deviceType = type,
        productName = name,
        streamVolume = VOLUME,
        streamMaxVolume = MAX_VOLUME,
        audioMode = AudioManager.MODE_IN_COMMUNICATION,
    )

    private fun track(id: String, rate: Int, sendSide: Boolean) =
        CapturedTrack(id, rate, ShortArray(rate * DURATION_MS.toInt() / MS_PER_SECOND), sendSide)

    private fun table(name: String, columns: List<String>) = TelemetryTable(
        name = name,
        columns = columns,
        timesMs = doubleArrayOf(0.0, ROW_MS),
        values = FloatArray(columns.size * 2) { it.toFloat() },
        rows = 2,
    )

    /** A capture with every kind of track, table and event, as the controller builds them. */
    private fun capture(route: RouteInfo? = route(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, null)) = DiagnosticCapture(
        startedAtEpochMs = STARTED_AT_MS,
        durationMs = DURATION_MS,
        tracks = listOf(
            track("1_mic_raw", RAW_RATE, true),
            track("1_mic_raw_2", CAPTURE_RATE, true),
            track("2_resampled", CAPTURE_RATE, true),
            track("3_apm", CAPTURE_RATE, true),
            track("4_denoised", CAPTURE_RATE, true),
            track("5_gate", CAPTURE_RATE, true),
            track("6_sent", CAPTURE_RATE, true),
            track("7_mix", CAPTURE_RATE, false),
            track("speaker_1", CAPTURE_RATE, false),
            track("speaker_2", CAPTURE_RATE, false),
        ),
        tables = DiagnosticZip.CSV_COLUMNS.filterKeys { it != "events" }.map { (name, columns) ->
            table(name, columns.drop(1))
        },
        events = DiagnosticZip.EVENT_NAMES.mapIndexed { i, name -> DiagnosticEvent(i * ROW_MS, name, "$i") },
        pipelines = listOf(
            PipelineDescription(
                capture = CaptureDescription(
                    sampleRate = RAW_RATE,
                    audioSource = AUDIO_SOURCE,
                    bufferFrames = BUFFER_FRAMES,
                    platformEffects = listOf("NoiseSuppressor"),
                ),
                settings = linkedMapOf("input_mode" to "voice_activity", "bitrate" to "40000"),
                stagesPresent = SendStage.entries.toSet(),
            ),
        ),
        serverShowedRecording = true,
        speakersWithoutTrack = 1,
        droppedFrames = 2,
        route = route,
    )

    private companion object {
        const val VERSION_CODE = 123
        const val SDK = 35
        const val RAW_RATE = 44_100
        const val CAPTURE_RATE = 48_000
        const val DURATION_MS = 50L
        const val MS_PER_SECOND = 1_000
        const val ROW_MS = 10.0
        const val VOLUME = 5
        const val MAX_VOLUME = 15
        const val AUDIO_SOURCE = 7
        const val BUFFER_FRAMES = 960
        const val WAV_RATE_OFFSET = 24
        const val WIRED_NAME = "USB-C Headset"

        // 2026-10-30T12:34:56.789Z
        const val STARTED_AT_MS = 1_793_363_696_789L

        // The start as a DOS time, which keeps even seconds: 2026-10-30T12:34:56Z.
        const val STARTED_AT_DOS_MS = 1_793_363_696_000L

        val ALLOWED_KEYS = setOf(
            "format_version", "app", "version_name", "version_code", "flavor",
            "device", "manufacturer", "model", "android_release", "sdk",
            "route", "device_type", "product_name", "stream_volume", "stream_max_volume", "audio_mode",
            "audio", "pipelines", "capture", "sample_rate", "audio_source", "channels", "buffer_frames",
            "platform_effects", "settings", "stages",
            "started_at", "duration_ms", "server_showed_recording", "speakers_without_track", "dropped_frames",
            "tracks", "id", "file", "side", "meaning", "rating", "note",
        )
    }
}
