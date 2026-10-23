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

package koeln.becker.nomla.mumble.audio

import koeln.becker.nomla.mumble.audio.capture.CapturePipeline
import koeln.becker.nomla.mumble.audio.capture.CapturePreprocessorFactory
import koeln.becker.nomla.mumble.audio.capture.EchoCancellationMode
import koeln.becker.nomla.mumble.audio.capture.FarEndFrameChunker
import koeln.becker.nomla.mumble.audio.capture.InputMode
import koeln.becker.nomla.mumble.audio.capture.NoiseSuppressionMode
import koeln.becker.nomla.mumble.audio.capture.NoopPreprocessor
import koeln.becker.nomla.mumble.audio.capture.Resampler
import koeln.becker.nomla.mumble.audio.capture.RnnoisePreprocessor
import koeln.becker.nomla.mumble.audio.capture.SpeexPreprocessor
import koeln.becker.nomla.mumble.audio.capture.SpeexResampler
import koeln.becker.nomla.mumble.util.NomlaLog
import koeln.becker.nomla.mumble.util.NomlaLogger

/**
 * Assembles the capture chain for `AudioHandler`. Echo cancellation has two ends, the near-end stage
 * and [Wiring.farEnd] fed by `AudioOutput` on the playback thread: both or neither.
 */
internal object CaptureWiring {
    private const val TAG = "CaptureWiring"

    /** @param farEnd null whenever the WebRTC canceller is not in the chain. */
    class Wiring(val pipeline: CapturePipeline, val farEnd: FarEndFrameChunker?)

    /**
     * @param inputSampleRate a resampler is inserted when it differs from [AudioHandler.SAMPLE_RATE].
     * @param logger receives a user-visible line for each stage that could not be built.
     */
    @Suppress("LongParameterList") // Every setting the chain is built from, and the test seams.
    fun wire(
        inputSampleRate: Int,
        inputMode: InputMode,
        amplitudeBoost: Float,
        noise: NoiseSuppressionMode,
        echo: EchoCancellationMode,
        speexNoiseSuppressDb: Int = SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB,
        rnnoiseAttenuationLimitDb: Float = RnnoisePreprocessor.ATTENUATION_LIMIT_DB,
        logger: NomlaLogger? = null,
        factory: CapturePreprocessorFactory? = null,
        newResampler: (Int, Int) -> Resampler = { from, to -> SpeexResampler(from, to) },
    ): Wiring {
        val log: (String) -> Unit = { message ->
            NomlaLog.w(TAG, message)
            logger?.logWarning(message)
        }
        val chain = (factory ?: CapturePreprocessorFactory(log = log))
            .create(noise, echo, speexNoiseSuppressDb, rnnoiseAttenuationLimitDb)
        if (noise != NoiseSuppressionMode.NONE && chain.preprocessor === NoopPreprocessor) {
            log("noise suppression (${noise.preferenceValue}) is not running on this device")
        }
        if (echo == EchoCancellationMode.WEBRTC && chain.farEndSink == null) {
            log("echo cancellation (${echo.preferenceValue}) is not running on this device")
        }
        val resampler =
            if (inputSampleRate == AudioHandler.SAMPLE_RATE) null
            else newResampler(inputSampleRate, AudioHandler.SAMPLE_RATE)
        val pipeline = CapturePipeline(
            resampler,
            chain.preprocessor,
            inputMode,
            amplitudeBoost,
            AudioHandler.FRAME_SIZE,
            log,
        )
        val farEnd = chain.farEndSink?.let { FarEndFrameChunker(chain.farEndFrameSize, it) }
        return Wiring(pipeline, farEnd)
    }
}
