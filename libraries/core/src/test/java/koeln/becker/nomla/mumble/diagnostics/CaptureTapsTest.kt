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
import koeln.becker.nomla.mumble.audio.capture.CaptureFrame
import koeln.becker.nomla.mumble.audio.capture.CapturePipeline
import koeln.becker.nomla.mumble.audio.capture.CapturePreprocessorFactory
import koeln.becker.nomla.mumble.audio.capture.EchoCancellationMode
import koeln.becker.nomla.mumble.audio.capture.NanoClock
import koeln.becker.nomla.mumble.audio.capture.NoiseSuppressionMode
import koeln.becker.nomla.mumble.audio.capture.VadConfig
import koeln.becker.nomla.mumble.audio.capture.VoiceActivityDetector
import koeln.becker.nomla.mumble.audio.capture.WebRtcApmConfig
import koeln.becker.nomla.mumble.audio.capture.WebRtcApmPreprocessor
import koeln.becker.nomla.mumble.audio.capture.fakes.FakeSpeexPreprocessApi
import koeln.becker.nomla.mumble.audio.capture.fakes.FakeWebRtcApmApi
import koeln.becker.nomla.mumble.audio.inputmode.ToggleInputMode
import koeln.becker.nomla.mumble.diagnostics.export.TrackAssembler
import koeln.becker.nomla.mumble.testutil.AllocationMeter
import org.junit.Test

class CaptureTapsTest {
    private var now = 1_000_000_000L
    private val clock = NanoClock { now }
    private val hub = DiagnosticsHub(clock = clock)

    private fun frame(value: Int, transmit: Boolean = true) = CaptureFrame(ShortArray(FRAME) { value.toShort() }).apply {
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

    @Test
    fun `muted frames are silence on every send ring but measured on frames csv`() {
        val taps = CaptureTaps(hub, pushToTalk = false, vad = null)
        hub.arm(5)
        repeat(10) { taps.feed(1000 + it, muted = true) }
        repeat(10) { taps.feed(2000 + it, muted = false) }
        val recording = hub.disarm()!!

        for (stage in listOf(SendStage.MIC_RAW, SendStage.RESAMPLED, SendStage.APM, SendStage.DENOISED, SendStage.GATED)) {
            assertWithMessage("$stage").that(recording.firstSamples(stage))
                .isEqualTo(List(10) { 0 } + List(10) { 2000 + it })
        }
        val frames = recording.frames.freeze(recording.t0Nanos)
        assertThat(frames.column("send_allowed")).isEqualTo(List(10) { 0f } + List(10) { 1f })
        assertThat(frames.column("muted")).isEqualTo(List(10) { 1f } + List(10) { 0f })
        assertWithMessage("muted frames are still measured")
            .that(frames.column("mic_raw_rms_dbfs").take(10).all { it > DiagnosticsLimits.SILENCE_DBFS }).isTrue()
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
        assertThat(vadRecording.frames.freeze(vadRecording.t0Nanos).column("send_allowed")).isEqualTo(listOf(1f, 1f, 1f))
    }

    @Test
    fun `opus packets are recorded only while sending is allowed`() {
        val taps = CaptureTaps(hub, pushToTalk = false, vad = null)
        val payload = byteArrayOf(9, 9, 1, 2, 3)
        hub.arm(5)

        taps.commit(frame(1), muted = true)
        taps.onOpusPacket(payload, 2, 3, 0L, false)
        taps.commit(frame(1), muted = false)
        taps.onOpusPacket(payload, 2, 3, 2L, true)
        val packets = hub.disarm()!!.sent.freeze()

        assertThat(packets.count).isEqualTo(1)
        assertThat(packets.frameNumbers.toList()).containsExactly(2L)
        assertThat(packets.terminators.toList()).containsExactly(true)
        assertThat(packets.bytes.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
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
    }
}
