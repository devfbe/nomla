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

package koeln.becker.nomla.mumble.audio.capture

import koeln.becker.nomla.mumble.audio.native.RnnoiseApi
import koeln.becker.nomla.mumble.audio.native.RnnoiseNative
import koeln.becker.nomla.mumble.audio.native.SpeexPreprocessApi
import koeln.becker.nomla.mumble.audio.native.SpeexPreprocessNative
import koeln.becker.nomla.mumble.audio.native.WebRtcApmApi
import koeln.becker.nomla.mumble.audio.native.WebRtcApmNative
import koeln.becker.nomla.mumble.diagnostics.CaptureTaps
import koeln.becker.nomla.mumble.diagnostics.SendStage
import koeln.becker.nomla.mumble.diagnostics.TapStage

private const val WEBRTC_APM = "WebRTC APM"
private const val SPEEX = "Speex noise suppression"
private const val RNNOISE = "RNNoise noise suppression"

/**
 * One assembled capture chain: the stage the capture thread runs, and the far-end entry point the
 * playback thread feeds (only when the WebRTC canceller is in the chain). On a mode switch, publish
 * the whole chain before releasing the old one, so the reference never reaches the wrong canceller.
 *
 * @param farEndSink must be fed frames of exactly [farEndFrameSize] samples (use [FarEndFrameChunker]).
 * @param farEndFrameSize as reported by the APM, 0 without a sink.
 * @param rnnoise the RNNoise stage when it is in the chain, so its attenuation limit can be moved
 *   while the chain runs.
 * @param apm the WebRTC APM stage when it is in the chain.
 * @param denoiser the Speex or RNNoise stage when it is in the chain.
 */
internal class CaptureChain(
    val preprocessor: CapturePreprocessor,
    val farEndSink: FarEndSink?,
    val farEndFrameSize: Int = 0,
    val rnnoise: RnnoisePreprocessor? = null,
    val apm: WebRtcApmPreprocessor? = null,
    val denoiser: CapturePreprocessor? = null,
)

/**
 * Builds the capture chain: the WebRTC APM first when echo cancellation is WEBRTC, then Speex or
 * RNNoise. AGC2 stays in the canceller's APM, in front of RNNoise: behind it, AGC2 lifted what
 * RNNoise leaves of babble by about 25 dB and of fan noise by 15-18 dB
 * (`RnnoiseAttenuationLimitDeviceTest`).
 * With both the canceller and RNNoise, the canceller's far-end frames also drive RNNoise's
 * [DoubleTalkRelief]: its limit eases while the user talks over the far end.
 * The APIs are factories because touching the native objects loads the library; a missing `.so`
 * becomes a skipped stage and a [log] line.
 */
internal class CapturePreprocessorFactory(
    private val speexApi: () -> SpeexPreprocessApi = { SpeexPreprocessNative },
    private val rnnoiseApi: () -> RnnoiseApi = { RnnoiseNative },
    private val apmApi: () -> WebRtcApmApi = { WebRtcApmNative },
    /** Receives a message for each stage that could not be built, so the user can be told. */
    private val log: (String) -> Unit = {},
    /** Times the far end's activity; a replay faster than real time passes its own. */
    private val clock: NanoClock = NanoClock(System::nanoTime),
) {
    @Suppress("LongParameterList") // Both modes, both denoiser strengths and two optional seams, all defaulted.
    fun create(
        noise: NoiseSuppressionMode,
        echo: EchoCancellationMode,
        speexNoiseSuppressDb: Int = SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB,
        rnnoiseAttenuationLimitDb: Float = RnnoisePreprocessor.ATTENUATION_LIMIT_DB,
        /**
         * A stage that sees every frame after echo cancellation and before the denoiser, for the
         * self-test's reference gate. It must leave the frame alone and return null; the chain
         * releases it with the others.
         */
        beforeDenoiser: CapturePreprocessor? = null,
        /**
         * The diagnostics taps: a [TapStage] after the APM and one after the denoiser, each only
         * when that stage is built. Null (debug off) leaves the chain exactly as without them.
         */
        taps: CaptureTaps? = null,
    ): CaptureChain {
        val parts = ChainParts()
        val farEndActivity = farEndActivityFor(noise, echo)

        try {
            // AEC first: anything time-varying in front keeps it from converging.
            if (echo == EchoCancellationMode.WEBRTC) addApm(parts, farEndActivity, taps)
            beforeDenoiser?.let { parts.stages += it }
            when (noise) {
                NoiseSuppressionMode.NONE -> Unit
                NoiseSuppressionMode.SPEEX ->
                    tryStage(SPEEX) { SpeexPreprocessor(speexApi(), noiseSuppressDb = speexNoiseSuppressDb) }
                        ?.let(parts::addDenoiser)
                NoiseSuppressionMode.RNNOISE ->
                    // No canceller, no far-end frames: the relief would never engage.
                    tryStage(RNNOISE) {
                        rnnoiseStage(rnnoiseAttenuationLimitDb, farEndActivity.takeIf { parts.apm != null })
                    }?.let {
                        parts.addDenoiser(it)
                        parts.rnnoise = it
                    }
            }
            if (taps != null && parts.denoiser != null) parts.stages += TapStage(SendStage.DENOISED, taps)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Whatever ends the build, the native stages built so far are released before it propagates.
            for (stage in parts.stages) stage.release()
            throw e
        }
        return parts.toChain()
    }

    /** Adds the WebRTC canceller, followed by its tap when there are [taps], unless it cannot be built. */
    private fun addApm(parts: ChainParts, farEndActivity: FarEndActivity?, taps: CaptureTaps?) {
        val apm = tryStage(WEBRTC_APM) {
            WebRtcApmPreprocessor(apmApi(), WebRtcApmConfig.FOR_ECHO_CANCELLATION, farEndActivity = farEndActivity)
        } ?: return
        parts.stages += apm
        if (taps != null) parts.stages += TapStage(SendStage.APM, taps)
        parts.apm = apm
        parts.farEndFrameSize = apm.farEndFrameSize
    }

    /** Only a chain that denoises with RNNoise behind the canceller has a use for the far end's activity. */
    private fun farEndActivityFor(noise: NoiseSuppressionMode, echo: EchoCancellationMode): FarEndActivity? =
        if (echo == EchoCancellationMode.WEBRTC && noise == NoiseSuppressionMode.RNNOISE) {
            FarEndActivity(clock)
        } else {
            null
        }

    private fun rnnoiseStage(limitDb: Float, farEndActivity: FarEndActivity?) =
        RnnoisePreprocessor(rnnoiseApi(), limitDb, relief = farEndActivity?.let { DoubleTalkRelief(it, clock = clock) })

    /**
     * Builds one stage, or logs why there is none and returns null. A missing `.so` surfaces as a
     * [LinkageError]; [IllegalStateException] means the handle couldn't be allocated.
     */
    private fun <T : CapturePreprocessor> tryStage(name: String, build: () -> T): T? = try {
        build()
    } catch (e: LinkageError) {
        log("$name is unavailable, continuing without it: $e")
        null
    } catch (e: IllegalStateException) {
        log("$name is unavailable, continuing without it: ${e.message}")
        null
    }
}

/** A capture chain while [CapturePreprocessorFactory.create] builds it. */
private class ChainParts {
    val stages = mutableListOf<CapturePreprocessor>()

    /** The canceller; also the far-end sink, the stage itself, so both audio threads share its lock. */
    var apm: WebRtcApmPreprocessor? = null
    var farEndFrameSize = 0
    var rnnoise: RnnoisePreprocessor? = null
    var denoiser: CapturePreprocessor? = null

    fun addDenoiser(stage: CapturePreprocessor) {
        stages += stage
        denoiser = stage
    }

    fun toChain(): CaptureChain {
        val preprocessor: CapturePreprocessor = when (stages.size) {
            0 -> NoopPreprocessor
            1 -> stages[0]
            else -> ChainedPreprocessor(stages)
        }
        return CaptureChain(preprocessor, apm, farEndFrameSize, rnnoise, apm, denoiser)
    }
}
