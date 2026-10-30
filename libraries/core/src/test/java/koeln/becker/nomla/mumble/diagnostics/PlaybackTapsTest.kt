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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import koeln.becker.nomla.mumble.audio.AudioHandler
import koeln.becker.nomla.mumble.audio.AudioOutputSpeech
import koeln.becker.nomla.mumble.audio.FakeJitter
import koeln.becker.nomla.mumble.audio.capture.NanoClock
import koeln.becker.nomla.mumble.testutil.AllocationMeter
import koeln.becker.nomla.mumble.testutil.FakeOpusDecoder
import org.junit.Test

class PlaybackTapsTest {
    private var now = 1_000_000_000L
    private val hub = DiagnosticsHub(clock = NanoClock { now })

    /** A talker whose jitter buffer never delivers: concealment at 0.25 until the stream times out. */
    private fun speech(session: Int) =
        AudioOutputSpeech(session, FRAME, { _, _ -> }, FakeOpusDecoder(fill = 0.25f), FakeJitter())

    /** One mix as AudioOutput runs it: every talker decoded and tapped, then the mix. */
    private fun PlaybackTaps.mix(vararg speeches: AudioOutputSpeech) {
        for (speech in speeches) {
            speech.decode()
            onSpeaker(speech)
        }
        onMix(ShortArray(FRAME) { 100 }, FRAME, speeches.isNotEmpty(), 0, TRACK_FRAMES)
        now += DiagnosticsLimits.FRAME_NANOS
    }

    private fun TelemetryTable.column(name: String): List<Float> {
        val c = columns.indexOf(name)
        check(c >= 0) { "no column $name" }
        return List(rows) { values[it * columns.size + c] }
    }

    @Test
    fun `a fifth talker is counted, not recorded`() {
        hub.arm(5)
        val taps = PlaybackTaps(hub)
        val sessions = listOf(7, 9, 11, 13, 15)
        for (session in sessions) taps.onPacketReceived(session, 0L, 40, false)
        taps.onPacketReceived(15, 2L, 40, false)
        val speeches = sessions.map(::speech).toTypedArray()
        repeat(3) { taps.mix(*speeches) }
        val recording = hub.disarm()!!

        assertThat(recording.speakersWithoutTrack).isEqualTo(1)
        val tracks = recording.freezeSpeakers()
        assertThat(tracks.keys).containsExactly("speaker_1", "speaker_2", "speaker_3", "speaker_4").inOrder()
        for ((id, pcm) in tracks) {
            assertWithMessage("$id frames").that(pcm.frames).isEqualTo(3)
            assertWithMessage("$id has sound").that(pcm.samples.any { it.toInt() != 0 }).isTrue()
        }
        val speakers = recording.speakers.freeze(recording.t0Nanos)
        assertThat(speakers.rows).isEqualTo(3 * 4)
        assertThat(speakers.column("speaker").toSet()).containsExactly(1f, 2f, 3f, 4f)
    }

    @Test
    fun `a returning talker keeps their slot`() {
        hub.arm(5)
        val taps = PlaybackTaps(hub)
        taps.onPacketReceived(7, 0L, 40, false)
        taps.onPacketReceived(9, 0L, 40, false)
        taps.onPacketReceived(9, 2L, 40, true)
        taps.onPacketReceived(11, 0L, 40, false)
        taps.onPacketReceived(9, 50L, 40, false)
        val recording = hub.disarm()!!

        assertThat(recording.slotOf(9) + 1).isEqualTo(2)
        assertThat(recording.slotOf(11) + 1).isEqualTo(3)
    }

    @Test
    fun `a rebuild during a recording keeps the slots`() {
        hub.arm(5)
        val first = PlaybackTaps(hub)
        first.onPacketReceived(7, 0L, 40, false)
        first.onPacketReceived(9, 0L, 40, false)
        first.detach()

        val second = PlaybackTaps(hub)
        second.onPacketReceived(11, 0L, 40, false)
        second.onPacketReceived(9, 2L, 40, false)
        second.mix(speech(9))
        val recording = hub.disarm()!!

        assertThat(recording.slotOf(9) + 1).isEqualTo(2)
        assertThat(recording.slotOf(11) + 1).isEqualTo(3)
        assertThat(recording.freezeSpeakers().keys).containsExactly("speaker_2")
    }

    @Test
    fun `slots are assigned on first packet, not on playback`() {
        hub.arm(5)
        val taps = PlaybackTaps(hub)
        taps.mix(speech(7))
        val recording = hub.disarm()!!

        assertThat(recording.slotOf(7)).isEqualTo(-1)
        assertThat(recording.freezeSpeakers()).isEmpty()
        assertThat(recording.speakers.freeze(recording.t0Nanos).rows).isEqualTo(0)
        assertThat(recording.playback.freeze(recording.t0Nanos).rows).isEqualTo(1)
    }

    @Test
    fun `sent and received packets merge into one table`() {
        hub.arm(5)
        val capture = CaptureTaps(hub, pushToTalk = false, vad = null)
        val playback = PlaybackTaps(hub)
        capture.onOpusPacket(ByteArray(30), 0, 30, 0L, false)
        now += MS
        playback.onPacketReceived(7, 100L, 41, false)
        now += MS
        capture.onOpusPacket(ByteArray(31), 0, 31, 2L, true)
        now += MS
        playback.onPacketReceived(7, 102L, 42, true)
        val recording = hub.disarm()!!

        val packets = recording.freezePackets()
        assertThat(packets.name).isEqualTo("packets")
        assertThat(packets.rows).isEqualTo(4)
        assertThat(packets.timesMs.toList()).isInOrder()
        assertThat(packets.column("direction")).containsExactly(0f, 1f, 0f, 1f).inOrder()
        assertThat(packets.column("speaker")).containsExactly(0f, 1f, 0f, 1f).inOrder()
        assertThat(packets.column("frame_number")).containsExactly(0f, 100f, 2f, 102f).inOrder()
        assertThat(packets.column("bytes")).containsExactly(30f, 41f, 31f, 42f).inOrder()
        assertThat(packets.column("terminator")).containsExactly(0f, 0f, 1f, 1f).inOrder()
    }

    @Test
    fun `speaker slots hold no session id after freeze`() {
        hub.arm(5)
        val taps = PlaybackTaps(hub)
        val sessions = listOf(7, 9, 11, 13)
        for (session in sessions) taps.onPacketReceived(session, 0L, 40, false)
        val speeches = sessions.map(::speech).toTypedArray()
        repeat(2) { taps.mix(*speeches) }
        val recording = hub.disarm()!!

        assertThat(recording.freezeSpeakers().keys)
            .containsExactly("speaker_1", "speaker_2", "speaker_3", "speaker_4").inOrder()
        val speakerValues = recording.speakers.freeze(recording.t0Nanos).column("speaker") +
            recording.freezePackets().column("speaker")
        assertThat(speakerValues).isNotEmpty()
        for (session in sessions) assertThat(speakerValues).doesNotContain(session.toFloat())
        assertThat(speakerValues.toSet()).containsExactly(1f, 2f, 3f, 4f)
    }

    @Test
    fun `inert playback taps allocate nothing`() {
        AllocationMeter.checkInstrument(FRAME)
        val taps = PlaybackTaps(hub)
        val talker = speech(7).apply { decode() }
        val pcm = ShortArray(FRAME) { 100 }
        repeat(100) {
            taps.onPacketReceived(7, it.toLong(), 40, false)
            taps.onSpeaker(talker)
            taps.onMix(pcm, FRAME, true, 0, TRACK_FRAMES)
        }

        val before = AllocationMeter.allocatedBytes()
        repeat(1000) {
            taps.onPacketReceived(7, it.toLong(), 40, false)
            taps.onSpeaker(talker)
            taps.onMix(pcm, FRAME, true, 0, TRACK_FRAMES)
            taps.onOutputTimestamp(0L, 0L, 0L)
        }
        val delta = AllocationMeter.allocatedBytes() - before

        assertWithMessage("bytes allocated by 1000 inert mixes").that(delta).isLessThan(1024L)
        assertThat(hub.playback.mixMeter.rmsDbfs).isEqualTo(DiagnosticsLimits.SILENCE_DBFS)
        assertThat(hub.snapshot().playback).isNull()
    }

    @Test
    fun `the snapshot shows the mix and the talkers while metering`() {
        val taps = PlaybackTaps(hub)
        hub.meter().use {
            taps.onPacketReceived(7, 0L, 40, false)
            taps.mix(speech(7))
            taps.onOutputTimestamp(writtenFrames = 4800L, framePosition = 2400L, presentedNanos = now)
            val snapshot = hub.snapshot()

            val playback = snapshot.playback!!
            assertThat(playback.played).isTrue()
            assertThat(playback.mixRmsDbfs).isGreaterThan(DiagnosticsLimits.SILENCE_DBFS)
            assertThat(playback.trackBufferFrames).isEqualTo(TRACK_FRAMES)
            assertThat(playback.activeSpeakers).isEqualTo(1)
            assertThat(playback.outputLatencyMs!!).isWithin(0.01f).of(50f)
            val speaker = snapshot.speakers.single()
            assertThat(speaker.session).isEqualTo(7)
            assertThat(speaker.track).isNull()
            assertThat(speaker.concealedFrames).isEqualTo(1)
            assertThat(speaker.rmsDbfs).isGreaterThan(DiagnosticsLimits.SILENCE_DBFS)
        }
        assertThat(hub.isActive).isFalse()
    }

    private companion object {
        const val FRAME = AudioHandler.FRAME_SIZE
        const val TRACK_FRAMES = 1920
        const val MS = 1_000_000L
    }
}
