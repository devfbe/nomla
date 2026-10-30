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
import koeln.becker.nomla.mumble.audio.VoiceTransmitter
import koeln.becker.nomla.mumble.audio.capture.CaptureFrame
import koeln.becker.nomla.mumble.audio.capture.CapturePipeline
import koeln.becker.nomla.mumble.audio.capture.CapturePreprocessorFactory
import koeln.becker.nomla.mumble.audio.capture.EchoCancellationMode
import koeln.becker.nomla.mumble.audio.capture.NanoClock
import koeln.becker.nomla.mumble.audio.capture.NoiseSuppressionMode
import koeln.becker.nomla.mumble.audio.capture.NoopPreprocessor
import koeln.becker.nomla.mumble.audio.capture.VadConfig
import koeln.becker.nomla.mumble.audio.capture.VoiceActivityDetector
import koeln.becker.nomla.mumble.audio.capture.WebRtcApmConfig
import koeln.becker.nomla.mumble.audio.capture.WebRtcApmPreprocessor
import koeln.becker.nomla.mumble.audio.capture.fakes.FakeSpeexPreprocessApi
import koeln.becker.nomla.mumble.audio.capture.fakes.FakeWebRtcApmApi
import koeln.becker.nomla.mumble.audio.encoder.Encoder
import koeln.becker.nomla.mumble.audio.inputmode.ToggleInputMode
import koeln.becker.nomla.mumble.diagnostics.export.TrackAssembler
import koeln.becker.nomla.mumble.net.NomlaUdpMessageType
import koeln.becker.nomla.mumble.net.PacketBuffer
import koeln.becker.nomla.mumble.testutil.AllocationMeter
import org.junit.Test

class CaptureTapsTest {
    private var now = 1_000_000_000L
    private val clock = NanoClock { now }
    private val hub = DiagnosticsHub(clock = clock)

    private fun frame(value: Int, transmit: Boolean = true) =
        CaptureFrame(ShortArray(FRAME) { value.toShort() }).apply {
            length = FRAME
            this.transmit = transmit
        }

    /** One capture frame through every tap, as AudioInput, CapturePipeline and the chain call them. */
    private fun CaptureTaps.feed(value: Int, muted: Boolean = false, transmit: Boolean = true) {
        val samples = ShortArray(FRAME) { value.toShort() }
        onMicRaw(samples, FRAME, FRAME, 48_000, READ_NANOS)
        for (stage in listOf(SendStage.RESAMPLED, SendStage.APM, SendStage.DENOISED)) onStage(stage, samples, FRAME)
        onStage(SendStage.GATED, if (transmit) samples else ShortArray(FRAME), FRAME)
        commit(frame(value, transmit), muted)
        now += DiagnosticsLimits.FRAME_NANOS
    }

    private fun TelemetryTable.column(name: String): List<Float> {
        val c = columns.indexOf(name)
        check(c >= 0) { "no column $name" }
        return List(rows) { values[it * columns.size + c] }
    }

    /** The first sample of every frame of [stage]'s ring. */
    private fun ArmedRecording.firstSamples(stage: SendStage): List<Int> {
        val frozen = ring(stage).freeze()
        var at = 0
        return List(frozen.frames) { i -> frozen.samples[at].toInt().also { at += frozen.lengths[i] } }
    }

    @Test
    fun `inert taps copy nothing`() {
        AllocationMeter.checkInstrument(FRAME)
        val taps = CaptureTaps(hub, pushToTalk = false, vad = null)
        val samples = ShortArray(FRAME) { 1234 }
        val captured = frame(1234)
        val packet = ByteArray(4)
        repeat(100) { taps.feed(1234) }

        val before = AllocationMeter.allocatedBytes()
        repeat(1000) {
            taps.onMicRaw(samples, FRAME, FRAME, 48_000, -1L)
            taps.onStage(SendStage.APM, samples, FRAME)
            taps.onStage(SendStage.GATED, samples, FRAME)
            taps.commit(captured, muted = false)
            taps.onOpusPacket(packet, 0, 4, 0L, false)
        }
        val delta = AllocationMeter.allocatedBytes() - before

        assertWithMessage("bytes allocated by 1000 inert frames").that(delta).isLessThan(1024L)
        for (stage in SendStage.entries) {
            assertWithMessage("$stage scratch").that(taps.scratchOf(stage).all { it.toInt() == 0 }).isTrue()
        }
        assertThat(hub.stageMeter(SendStage.MIC_RAW).rmsDbfs).isEqualTo(DiagnosticsLimits.SILENCE_DBFS)
    }

    /**
     * What is deliberately not sent never reaches a ZIP, not even as its level envelope: a frame
     * that may not be sent has silence on every send ring and NaN in every stage level column, every
     * detector column and `transmit`, while send_allowed, muted, the read figures, silenced and the
     * canceller's statistics stay. The live meters still measure it.
     */
    @Test
    fun `muted frames are silence on every send ring and have no levels on frames csv`() {
        val vad = VoiceActivityDetector(VadConfig.DEFAULT) { now }
        val taps = CaptureTaps(hub, pushToTalk = false, vad = vad)
        hub.arm(5)
        repeat(10) {
            vad.isVoice(ShortArray(FRAME) { 1000 }, FRAME, null)
            taps.feed(1000 + it, muted = true)
        }
        val liveWhileMuted = hub.stageMeter(SendStage.MIC_RAW).rmsDbfs
        repeat(10) {
            vad.isVoice(ShortArray(FRAME) { 2000 }, FRAME, null)
            taps.feed(2000 + it, muted = false)
        }
        val recording = hub.disarm()!!

        val pcmStages = listOf(
            SendStage.MIC_RAW, SendStage.RESAMPLED, SendStage.APM, SendStage.DENOISED, SendStage.GATED,
        )
        for (stage in pcmStages) {
            assertWithMessage("$stage").that(recording.firstSamples(stage))
                .isEqualTo(List(10) { 0 } + List(10) { 2000 + it })
        }
        val frames = recording.frames.freeze(recording.t0Nanos)
        assertThat(frames.column("send_allowed")).isEqualTo(List(10) { 0f } + List(10) { 1f })
        assertThat(frames.column("muted")).isEqualTo(List(10) { 1f } + List(10) { 0f })
        assertThat(frames.column("read_samples").toSet()).containsExactly(FRAME.toFloat())
        assertThat(frames.column("silenced").toSet()).containsExactly(0f)
        val hidden = frames.columns.filter { name ->
            name.endsWith("_rms_dbfs") || name.endsWith("_peak_dbfs") || name.endsWith("_clipped")
        } + listOf(
            "transmit", "vad_probability", "vad_level_dbfs", "vad_floor_dbfs", "vad_speech_dbfs",
            "vad_threshold_dbfs", "vad_open", "vad_holding", "vad_too_close",
        )
        assertThat(hidden).hasSize(SendStage.entries.size * PER_STAGE_COLUMNS + VOICE_HIDDEN_COLUMNS)
        val kept = listOf("send_allowed", "muted", "read_samples", "expected_samples", "read_ms", "silenced")
        assertThat(frames.columns.filter { it !in hidden && !it.startsWith("apm_") })
            .containsExactlyElementsIn(kept)
        for (name in hidden) {
            assertWithMessage("$name while muted").that(frames.column(name).take(10).all { it.isNaN() }).isTrue()
        }
        for (name in listOf("mic_raw_rms_dbfs", "sent_peak_dbfs", "vad_level_dbfs", "transmit", "vad_open")) {
            assertWithMessage("$name while sent").that(frames.column(name).drop(10).none { it.isNaN() }).isTrue()
        }
        for (name in kept) {
            assertWithMessage("$name while muted").that(frames.column(name).take(10).none { it.isNaN() }).isTrue()
        }
        assertWithMessage("the live meter measures muted frames")
            .that(liveWhileMuted).isGreaterThan(DiagnosticsLimits.SILENCE_DBFS)
    }

    @Test
    fun `push-to-talk without the key records silence, voice activity with a closed gate does not`() {
        val ptt = CaptureTaps(hub, pushToTalk = true, vad = null)
        hub.arm(5)
        repeat(3) { ptt.feed(500, transmit = false) }
        val pttRecording = hub.disarm()!!
        for (stage in listOf(SendStage.MIC_RAW, SendStage.APM, SendStage.DENOISED, SendStage.GATED)) {
            assertWithMessage("push-to-talk $stage").that(pttRecording.firstSamples(stage)).isEqualTo(listOf(0, 0, 0))
        }

        // The real pipeline decides the gate, so the GATED tap gets its silence from there.
        val vad = VoiceActivityDetector(VadConfig.DEFAULT) { now }
        val taps = CaptureTaps(hub, pushToTalk = false, vad = vad)
        val chain = CapturePreprocessorFactory(speexApi = { FakeSpeexPreprocessApi() })
            .create(NoiseSuppressionMode.SPEEX, EchoCancellationMode.NONE, taps = taps)
        val pipeline = CapturePipeline(null, chain.preprocessor, ToggleInputMode(), taps = taps)
        hub.arm(5)
        repeat(3) {
            val input = ShortArray(FRAME) { 500 }
            taps.onMicRaw(input, FRAME, FRAME, 48_000, READ_NANOS)
            taps.commit(pipeline.process(input, FRAME), muted = false)
            now += DiagnosticsLimits.FRAME_NANOS
        }
        val vadRecording = hub.disarm()!!

        assertThat(vadRecording.firstSamples(SendStage.MIC_RAW)).isEqualTo(listOf(500, 500, 500))
        assertThat(vadRecording.firstSamples(SendStage.DENOISED).all { it != 0 }).isTrue()
        assertThat(vadRecording.firstSamples(SendStage.GATED)).isEqualTo(listOf(0, 0, 0))
        assertThat(vadRecording.frames.freeze(vadRecording.t0Nanos).column("send_allowed"))
            .isEqualTo(listOf(1f, 1f, 1f))
    }

    /**
     * Two frames per packet, as the default Opus setup sends. The terminator after the push-to-talk
     * release carries the last held frame padded with silence; it left the device, so `6_sent` has
     * it although the release frame itself is not allowed to be sent.
     */
    @Test
    fun `every transmitted packet is recorded, including the terminator`() {
        val ptt = ToggleInputMode()
        val taps = CaptureTaps(hub, pushToTalk = true, vad = null)
        val sent = mutableListOf<ByteArray>()
        val listener = object : AudioHandler.AudioEncodeListener {
            override fun onAudioEncoded(data: ByteArray, length: Int) {
                sent += data.copyOf(length)
            }

            override fun onTalkingStateChanged(talking: Boolean) = Unit
        }
        val transmitter = VoiceTransmitter(CapturePipeline(null, NoopPreprocessor, ptt, taps = taps), listener, taps)
            .apply { setCodec(NomlaUdpMessageType.UDPVoiceOpus) { TwoFrameEncoder() } }
        fun send(first: Int) {
            transmitter.onAudioInputReceived(ShortArray(FRAME) { if (it == 0) first.toShort() else 0 }, FRAME)
            now += DiagnosticsLimits.FRAME_NANOS
        }
        hub.arm(5)

        send(0x0909)
        ptt.setTalkingOn(true)
        for (first in listOf(0x0101, 0x0202, 0x0303)) send(first)
        ptt.setTalkingOn(false)
        send(0x0404)
        val recording = hub.disarm()!!
        val packets = recording.sent.freeze()

        assertThat(sent).hasSize(2)
        assertThat(packets.count).isEqualTo(sent.size)
        assertThat(packets.frameNumbers.toList()).containsExactly(0L, 2L).inOrder()
        assertThat(packets.terminators.toList()).containsExactly(false, true).inOrder()
        for (i in 0 until packets.count) {
            val payload = packets.bytes.copyOfRange(packets.offsets[i], packets.offsets[i] + packets.lengths[i])
            assertWithMessage("packet $i is the payload that was sent")
                .that(sent[i].toList().takeLast(payload.size)).isEqualTo(payload.toList())
        }
        assertThat(packets.bytes.toList()).isEqualTo(listOf<Byte>(1, 1, 2, 2, 3, 3, 0, 0))
        assertThat(recording.frames.freeze(recording.t0Nanos).column("send_allowed"))
            .containsExactly(0f, 1f, 1f, 1f, 0f).inOrder()
    }

    @Test
    fun `detached taps write nothing and the hub forgets their canceller`() {
        val apm = WebRtcApmPreprocessor(FakeWebRtcApmApi(), WebRtcApmConfig.FOR_ECHO_CANCELLATION)
        val taps = CaptureTaps(hub, pushToTalk = false, vad = null)
        taps.attachApm(apm)
        hub.meter()
        hub.arm(5)
        taps.feed(1000)
        assertThat(apm.statisticsEveryFrames).isEqualTo(DiagnosticsLimits.STATS_EVERY_FRAMES)

        taps.detach()
        repeat(3) { taps.feed(2000) }
        taps.onOpusPacket(ByteArray(4), 0, 4, 0L, false)
        val recording = hub.disarm()!!

        assertThat(hub.apm).isNull()
        assertThat(taps.isActive).isFalse()
        assertThat(hub.isActive).isTrue()
        assertThat(recording.firstSamples(SendStage.MIC_RAW)).containsExactly(1000)
        assertThat(recording.frames.freeze(recording.t0Nanos).rows).isEqualTo(1)
        assertThat(recording.sent.freeze().count).isEqualTo(0)
        assertWithMessage("the released canceller stops polling").that(apm.statisticsEveryFrames).isEqualTo(0)
        apm.release()
    }

    @Test
    fun `detaching an old pipeline leaves the new pipeline's canceller`() {
        val old = WebRtcApmPreprocessor(FakeWebRtcApmApi(), WebRtcApmConfig.FOR_ECHO_CANCELLATION)
        val new = WebRtcApmPreprocessor(FakeWebRtcApmApi(), WebRtcApmConfig.FOR_ECHO_CANCELLATION)
        val oldTaps = CaptureTaps(hub, pushToTalk = false, vad = null).apply { attachApm(old) }
        CaptureTaps(hub, pushToTalk = false, vad = null).attachApm(new)

        oldTaps.detach()

        assertThat(hub.apm).isSameInstanceAs(new)
        old.release()
        new.release()
    }

    /** Two frames per packet; each frame is encoded as its first sample, two bytes. */
    private class TwoFrameEncoder : Encoder {
        private val samples = ShortArray(2)
        private var ready = false
        private var terminated = false
        override var bufferedFrames = 0
            private set
        override val isReady: Boolean get() = ready
        override val encodedLength: Int get() = if (ready) 4 else 0
        override val isTerminator: Boolean get() = terminated

        override fun encode(input: ShortArray, inputSize: Int): Int {
            terminated = false
            samples[bufferedFrames++] = input[0]
            ready = bufferedFrames == 2
            return encodedLength
        }

        override fun getEncodedData(packetBuffer: PacketBuffer) {
            for (sample in samples) {
                packetBuffer.append(sample.toLong() shr 8)
                packetBuffer.append(sample.toLong())
            }
            bufferedFrames = 0
            ready = false
            terminated = false
        }

        override fun terminate() {
            terminated = true
            if (bufferedFrames > 0 && !ready) {
                samples.fill(0, bufferedFrames)
                bufferedFrames = 2
                ready = true
            }
        }

        override fun close() = Unit
    }

    @Test
    fun `a rebuild at another rate during a recording splits the raw track`() {
        hub.onPipelineBuilt(description(48_000))
        hub.arm(5)
        val first = CaptureTaps(hub, pushToTalk = false, vad = null)
        repeat(5) {
            first.onMicRaw(ShortArray(480) { 100 }, 480, 480, 48_000, READ_NANOS)
            first.commit(frame(100), muted = false)
            now += DiagnosticsLimits.FRAME_NANOS
        }
        hub.onPipelineBuilt(description(16_000))
        val second = CaptureTaps(hub, pushToTalk = false, vad = null)
        repeat(5) {
            second.onMicRaw(ShortArray(160) { 200 }, 160, 160, 16_000, READ_NANOS)
            second.commit(frame(200), muted = false)
            now += DiagnosticsLimits.FRAME_NANOS
        }
        val recording = hub.disarm()!!

        val tracks = TrackAssembler.assemble(
            SendStage.MIC_RAW.trackId, recording.ring(SendStage.MIC_RAW).freeze(), recording.t0Nanos,
            recording.durationNanos, sendSide = true,
        )
        assertThat(tracks.map { it.id to it.sampleRate })
            .containsExactly("1_mic_raw" to 48_000, "1_mic_raw_2" to 16_000).inOrder()
        assertThat(tracks[0].samples[0].toInt()).isEqualTo(100)
        assertThat(tracks[1].samples[5 * 160].toInt()).isEqualTo(200)
        assertThat(recording.events.freeze(recording.t0Nanos).map { it.name }).contains("pipeline_rebuilt")
        assertThat(recording.pipelines.map { it.capture.sampleRate }).containsExactly(48_000, 16_000).inOrder()
    }

    @Test
    fun `apm statistics land in frames rows once per second`() {
        val api = FakeWebRtcApmApi().apply { statistics = floatArrayOf(12f, 20f, Float.NaN, Float.NaN, Float.NaN, 64f) }
        val apm = WebRtcApmPreprocessor(api, WebRtcApmConfig.FOR_ECHO_CANCELLATION)
        val taps = CaptureTaps(hub, pushToTalk = false, vad = null)
        taps.attachApm(apm)
        hub.arm(5)

        repeat(250) {
            val samples = ShortArray(FRAME) { 300 }
            apm.process(samples)
            taps.commit(frame(300), muted = false)
            now += DiagnosticsLimits.FRAME_NANOS
        }
        val frames = hub.disarm()!!.frames.freeze(0L)

        val erl = frames.column("apm_erl_db")
        assertThat(erl.count { !it.isNaN() }).isEqualTo(2)
        assertThat(erl.filter { !it.isNaN() }).containsExactly(12f, 12f)
        assertThat(frames.column("apm_erle_db").filter { !it.isNaN() }).containsExactly(20f, 20f)
        assertThat(frames.column("apm_delay_ms").filter { !it.isNaN() }).containsExactly(64f, 64f)
        apm.release()
    }

    @Test
    fun `apm polling stops when the hub goes inactive`() {
        val apm = WebRtcApmPreprocessor(FakeWebRtcApmApi(), WebRtcApmConfig.FOR_ECHO_CANCELLATION)
        val taps = CaptureTaps(hub, pushToTalk = false, vad = null)
        taps.attachApm(apm)

        val handle = hub.meter()
        taps.commit(frame(1), muted = false)
        assertThat(apm.statisticsEveryFrames).isEqualTo(DiagnosticsLimits.STATS_EVERY_FRAMES)

        handle.close()
        taps.commit(frame(1), muted = false)
        assertThat(apm.statisticsEveryFrames).isEqualTo(0)
        apm.release()
    }

    @Test
    fun `the hub reports its activity`() {
        assertThat(hub.active.value).isFalse()
        assertThat(hub.isActive).isFalse()

        val handle = hub.meter()
        assertThat(hub.active.value).isTrue()
        assertThat(hub.isActive).isTrue()

        handle.close()
        handle.close()
        assertThat(hub.active.value).isFalse()
        assertThat(hub.isActive).isFalse()

        hub.arm(5)
        assertThat(hub.active.value).isTrue()
        hub.disarm()
        assertThat(hub.active.value).isFalse()
    }

    @Test
    fun `the snapshot reports the stages of the pipeline and the gate`() {
        hub.onPipelineBuilt(description(48_000))
        val vad = VoiceActivityDetector(VadConfig.adaptive()) { now }
        val taps = CaptureTaps(hub, pushToTalk = false, vad = vad)
        hub.meter().use {
            taps.feed(1000)
            val snapshot = hub.snapshot()

            assertThat(snapshot.stages.map { it.stage }).isEqualTo(SendStage.entries)
            assertThat(snapshot.stages.filter { it.active }.map { it.stage })
                .containsExactly(SendStage.MIC_RAW, SendStage.GATED, SendStage.SENT).inOrder()
            assertThat(snapshot.stages.first().changeDb).isNull()
            assertThat(snapshot.stages.first().rmsDbfs).isGreaterThan(DiagnosticsLimits.SILENCE_DBFS)
            assertThat(snapshot.stages.single { it.stage == SendStage.GATED }.changeDb).isWithin(0.01f).of(0f)
            assertThat(snapshot.capture?.sampleRate).isEqualTo(48_000)
            assertThat(snapshot.gate).isNotNull()
            assertThat(snapshot.gateHistory).hasLength(DiagnosticsLimits.GATE_HISTORY_FRAMES)
            assertThat(snapshot.gateHistory.last()).isTrue()
            assertThat(snapshot.playback).isNull()
            assertThat(snapshot.speakers).isEmpty()
        }
    }

    private fun description(rate: Int) = PipelineDescription(
        CaptureDescription(rate),
        emptyMap(),
        setOf(SendStage.MIC_RAW, SendStage.GATED, SendStage.SENT),
    )

    private companion object {
        const val FRAME = 480
        const val READ_NANOS = 9_000_000L
        const val PER_STAGE_COLUMNS = 3

        /** `transmit` and the eight `vad_*` columns. */
        const val VOICE_HIDDEN_COLUMNS = 9
    }
}
