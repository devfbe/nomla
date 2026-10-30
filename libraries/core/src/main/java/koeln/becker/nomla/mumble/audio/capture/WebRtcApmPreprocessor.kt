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

import koeln.becker.nomla.mumble.audio.native.WebRtcApmApi

/**
 * How the APM is configured for one chain (the sample rate belongs to the stage).
 *
 * @param aec3 the echo canceller's configuration; null is webrtc's built-in default.
 */
internal data class WebRtcApmConfig(
    val echoCancellation: Boolean,
    val noiseSuppression: Boolean,
    val gainControl: Boolean,
    val highPass: Boolean = true,
    val aec3: Aec3Tuning? = null,
) {
    companion object {
        /** AEC3, AGC2 and high-pass on; APM noise suppression off so the user's setting stays authoritative. */
        val FOR_ECHO_CANCELLATION = WebRtcApmConfig(
            echoCancellation = true,
            noiseSuppression = false,
            gainControl = true,
        )
    }
}

/**
 * Maps the APM's output level in dBFS linearly onto a "voice probability" between [SILENCE_DBFS]
 * (0) and [FULL_DBFS] (1). A loudness threshold, not a speech model; AGC2 holds the non-speech floor
 * near -45 dBFS regardless of input level.
 */
internal object LevelToProbability {
    const val SILENCE_DBFS = -45f

    const val FULL_DBFS = -23.3f

    fun fromDbfs(dbfs: Float): Float =
        ((dbfs - SILENCE_DBFS) / (FULL_DBFS - SILENCE_DBFS)).coerceIn(0f, 1f)
}

/**
 * The WebRTC APM as a capture stage, with the far-end signal fed via [analyzeReverseStream] on the
 * playback thread ([SingleHandleStage]'s lock covers both threads and release). Per 10 ms tick the
 * far-end frame must go in before the near-end frame containing its echo, or cancellation silently
 * degrades. AEC3 estimates the stream delay itself.
 *
 * @param farEndActivity sees every far-end frame before the APM does (which may modify it).
 */
internal class WebRtcApmPreprocessor private constructor(
    private val api: WebRtcApmApi,
    handle: Long,
    sampleRate: Int,
    private val farEndActivity: FarEndActivity?,
) : SingleHandleStage(handle, "the webrtc audio processing module at $sampleRate Hz"),
    FarEndSink {

    constructor(
        api: WebRtcApmApi,
        config: WebRtcApmConfig,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        farEndActivity: FarEndActivity? = null,
    ) : this(
        api,
        api.create(
            sampleRate,
            config.echoCancellation,
            config.noiseSuppression,
            UNUSED_NOISE_SUPPRESSION_LEVEL,
            config.gainControl,
            config.highPass,
            config.aec3?.toArray(),
        ),
        sampleRate,
        farEndActivity,
    )

    /** Samples per far-end frame as reported by the APM; oversized frames lose their tail silently. */
    val farEndFrameSize: Int = api.frameSize(handle)

    /** Near-end frames the APM refused; distinguishes "refused" from "no opinion" (both yield null). */
    @Volatile
    var rejectedFrames: Int = 0
        private set

    /** Far-end frames the APM refused (too-short only); the only error signal of the reverse stream. */
    @Volatile
    var rejectedFarEndFrames: Int = 0
        private set

    /**
     * Poll the APM statistics every this many capture frames; 0 (the default) never polls. Set by
     * the diagnostics while they are active; read once per frame on the capture thread.
     */
    @Volatile
    var statisticsEveryFrames: Int = 0

    /** Capture frames since the last poll; capture thread only, under the stage lock. */
    private var framesSinceStatistics = 0

    /**
     * The last polled statistics, in [WebRtcApmApi.getStatistics] order. Written by the native call
     * on the capture thread and read by [copyStatistics] on another thread without a lock: a reader
     * may see a mix of two consecutive polls. That tearing is accepted; each value is a 1 s
     * aggregate that changes slowly, and the readers only display or log it.
     */
    private val statistics = FloatArray(WebRtcApmApi.STATISTICS_COUNT)

    /** Successful polls so far; publishes [statistics] (written after the array). */
    @Volatile
    private var statisticsPolls = 0

    override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
        val probability = if (api.processCapture(handle, frame) != 0) {
            rejectedFrames++
            null
        } else {
            LevelToProbability.fromDbfs(api.lastCaptureLevelDbfs(handle))
        }
        pollStatisticsIfDue(handle)
        return probability
    }

    /** One volatile read per frame while off; no allocation either way. */
    private fun pollStatisticsIfDue(handle: Long) {
        val every = statisticsEveryFrames
        if (every <= 0) {
            framesSinceStatistics = 0
            return
        }
        if (++framesSinceStatistics < every) return
        framesSinceStatistics = 0
        if (api.getStatistics(handle, statistics) == WebRtcApmApi.STATISTICS_COUNT) statisticsPolls++
    }

    /**
     * Copies the last polled statistics into the first [WebRtcApmApi.STATISTICS_COUNT] floats of
     * [into] (NaN where webrtc reports nothing). Any thread; may tear, see [statistics].
     *
     * @return false, leaving [into] untouched, if nothing has been polled yet.
     */
    fun copyStatistics(into: FloatArray): Boolean {
        require(into.size >= WebRtcApmApi.STATISTICS_COUNT) { "need ${WebRtcApmApi.STATISTICS_COUNT} floats" }
        if (statisticsPolls == 0) return false
        statistics.copyInto(into)
        return true
    }

    override fun onFarEndFrame(handle: Long, frame: ShortArray) {
        farEndActivity?.onFarEndFrame(frame)
        if (api.processRender(handle, frame) != 0) rejectedFarEndFrames++
    }

    override fun onReleaseHandle(handle: Long) = api.destroy(handle)

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48000

        /** No effect while the APM's own suppressor is off. */
        private const val UNUSED_NOISE_SUPPRESSION_LEVEL = 0
    }
}
