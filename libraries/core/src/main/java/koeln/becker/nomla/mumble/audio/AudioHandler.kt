/*
 * Copyright (C) 2014 Andrew Comminos
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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import com.google.protobuf.MessageLite
import koeln.becker.nomla.mumble.R
import koeln.becker.nomla.mumble.audio.capture.AndroidAudioRecordSource
import koeln.becker.nomla.mumble.audio.capture.AudioSourcePolicy
import koeln.becker.nomla.mumble.audio.capture.CapturePipeline
import koeln.becker.nomla.mumble.audio.capture.CapturePreprocessorFactory
import koeln.becker.nomla.mumble.audio.capture.CaptureRequest
import koeln.becker.nomla.mumble.audio.capture.CaptureState
import koeln.becker.nomla.mumble.audio.capture.PcmCaptureSourceFactory
import koeln.becker.nomla.mumble.audio.encoder.Encoder
import koeln.becker.nomla.mumble.audio.encoder.OpusEncoder
import koeln.becker.nomla.mumble.audio.inputmode.ActivityInputMode
import koeln.becker.nomla.mumble.audio.inputmode.ToggleInputMode
import koeln.becker.nomla.mumble.audio.routing.AudioDeviceCategory
import koeln.becker.nomla.mumble.diagnostics.CaptureTaps
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsHub
import koeln.becker.nomla.mumble.diagnostics.PipelineDescription
import koeln.becker.nomla.mumble.diagnostics.PlaybackTaps
import koeln.becker.nomla.mumble.exception.AudioException
import koeln.becker.nomla.mumble.exception.AudioInitializationException
import koeln.becker.nomla.mumble.exception.NativeAudioException
import koeln.becker.nomla.mumble.net.NomlaUdpMessageType
import koeln.becker.nomla.mumble.net.TcpMessageHandler
import koeln.becker.nomla.mumble.net.VoicePacket
import koeln.becker.nomla.mumble.net.VoicePacketHandler
import koeln.becker.nomla.mumble.protobuf.Mumble
import koeln.becker.nomla.mumble.util.NomlaLog
import koeln.becker.nomla.mumble.util.NomlaLogger

/**
 * Bridges the protocol's audio messages to the input and output threads. Audio playback and
 * recording are controlled exclusively by the protocol. [start] starts both threads, [shutdown]
 * cleans them up.
 *
 * @param params `self` carries the session id stamped on every voice packet, so a wrong one means
 *   sending as somebody else.
 * @param onCaptureState what the capture reports: [CaptureState.Silenced] and [CaptureState.Active]
 *   on a platform binder thread, [CaptureState.Error] on the capture thread.
 * @param preprocessorFactory builds the capture chain; tests replace it, null is the native one.
 * @param captureFactory opens the microphone; tests replace it. Last, so tests can pass a lambda.
 * @throws AudioInitializationException without the RECORD_AUDIO permission.
 */
@Suppress("LongParameterList") // The session's inputs and the two platform seams.
internal class AudioHandler(
    host: AudioHost,
    config: AudioConfig,
    private val params: AudioSessionParams,
    onCaptureState: (CaptureState) -> Unit = {},
    preprocessorFactory: CapturePreprocessorFactory? = null,
    captureFactory: PcmCaptureSourceFactory = AndroidAudioRecordSource.Factory(),
) : TcpMessageHandler, VoicePacketHandler {

    private val context: Context = host.context
    private val logger: NomlaLogger = host.logger
    private val encodeListener: AudioEncodeListener = host.encodeListener
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val input: AudioInput
    private val output: AudioOutput

    /** Capture chain in front of the encoder. Touched only by the capture thread, released in [shutdown]. */
    private val capturePipeline: CapturePipeline

    /** Captured frames to voice packets; the capture thread's listener. */
    private val transmitter: VoiceTransmitter

    private val session = params.self.session
    private val playbackStream: Int = config.playbackStream
    private var bitrate: Int = config.settings.bitrate
    private var framesPerPacket: Int = config.settings.framesPerPacket

    private var isInitialized = false

    /** The session's diagnostics, null when it has none. */
    private val diagnostics: DiagnosticsHub? = host.diagnostics

    /** This pipeline's taps; null without [diagnostics]. */
    private val taps: CaptureTaps?

    /** This pipeline's playback and receive taps; null without [diagnostics]. */
    private val playbackTaps: PlaybackTaps? = diagnostics?.let(::PlaybackTaps)

    /** This pipeline as the diagnostics record it, but for what [start] still decides. */
    private val description: PipelineDescription?

    init {
        val settings = config.settings
        val effects = settings.androidEffects
        val echo = config.echoCancellation
        val route = config.routedDeviceType?.let(AudioDeviceCategory::of)
        val voiceCallSource = settings.echoCancellationVoiceCallSource
        // AudioSourcePolicy resolves the source again inside PcmCaptureSource from the same inputs,
        // so both agree. In a session AudioRouter already holds MODE_IN_COMMUNICATION; this is a
        // second request for the same mode.
        if (AudioSourcePolicy.needsCommunicationMode(effects, echo)) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        val audioSource = AudioSourcePolicy.resolve(settings.audioSource, effects, echo, route, voiceCallSource)

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw AudioInitializationException("RECORD_AUDIO permission not granted")
        }
        val inputMode = params.inputMode
        taps = diagnostics?.let {
            CaptureTaps(it, pushToTalk = inputMode is ToggleInputMode, vad = (inputMode as? ActivityInputMode)?.detector)
        }
        val captureSource = captureFactory.open(
            CaptureRequest(
                audioSource, settings.inputSampleRate, effects, echo,
                route = route, echoCancellationVoiceCallSource = voiceCallSource,
            ),
        )
        // The listener is the transmitter below; AudioInput only calls it once recording starts.
        input = AudioInput(
            { frame, size -> transmitter.onAudioInputReceived(frame, size) },
            captureSource,
            taps?.let { t ->
                { state: CaptureState ->
                    t.onCaptureState(state)
                    onCaptureState(state)
                }
            } ?: onCaptureState,
            taps = taps,
        )
        // Both ends of the canceller come from one call: the chain goes to the capture thread, the
        // far-end tap to AudioOutput's playback thread, which must feed the reference for AEC to work.
        val wiring = CaptureWiring.wire(
            input.sampleRate, params.inputMode, settings.amplitudeBoost, settings.noiseSuppression, echo,
            settings.speexNoiseSuppressDb, settings.rnnoiseAttenuationLimitDb, logger,
            factory = preprocessorFactory, taps = taps,
        )
        capturePipeline = wiring.pipeline
        description = diagnostics?.let {
            PipelineDescription(captureSource.describe(), describeSettings(config), wiring.stagesPresent)
        }
        val halfDuplex = config.halfDuplex
        transmitter = VoiceTransmitter(capturePipeline, encodeListener, taps) { talking ->
            if (halfDuplex) {
                val direction = if (talking) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                audioManager.adjustStreamVolume(playbackStream, direction, 0)
            }
        }
        transmitter.targetId = params.targetId
        transmitter.udpProtocol = params.udpProtocol
        output = AudioOutput(host.outputListener, wiring.farEnd, taps = playbackTaps)
        // Last, so a constructor that throws never leaves the hub pointing at this canceller.
        taps?.attachApm(wiring.apm)
    }

    /**
     * Starts playback and recording, unless already running.
     * @throws AudioException if a thread is already recording or the encoder cannot be created.
     */
    @Synchronized
    fun start() {
        if (isInitialized) return
        try {
            startAll()
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Nobody shuts a pipeline down that failed to start: its taps go inert here, and the
            // hub forgets its canceller.
            taps?.detach()
            playbackTaps?.detach()
            throw e
        }
    }

    private fun startAll() {
        val self = params.self
        setMaxBandwidth(params.maxBandwidth)
        // After the bandwidth fit, so the bitrate and frames per packet are the ones sent with.
        description?.let { diagnostics?.onPipelineBuilt(it.withEncoder(bitrate, framesPerPacket)) }
        transmitter.setCodec(params.codec, ::createEncoder)
        transmitter.muteState = SelfMuteState(self.isMuted || self.isLocalMuted, self.isSelfMuted, self.isSuppressed)
        synchronized(input) {
            if (input.isRecording) throw AudioException("Attempted to start recording while recording!")
            input.startRecording()
        }
        output.startPlaying(playbackStream)
        isInitialized = true
    }

    /**
     * The encoder for [codec], or null (input disabled) for one it cannot encode.
     * @throws NativeAudioException if the encoder cannot be created.
     */
    private fun createEncoder(codec: NomlaUdpMessageType): Encoder? = when (codec) {
        // Resampling and preprocessing happen in capturePipeline, before the voice detector.
        NomlaUdpMessageType.UDPVoiceOpus ->
            OpusEncoder(SAMPLE_RATE, 1, FRAME_SIZE, framesPerPacket, bitrate, MAX_BUFFER_SIZE)
        else -> {
            NomlaLog.w(TAG, "Unsupported codec, input disabled.")
            null
        }
    }

    /**
     * The settings this pipeline is built from, as plain setting keys and values: never a server,
     * user or channel. The encoder's values follow in [withEncoder].
     */
    private fun describeSettings(config: AudioConfig): Map<String, String> {
        val settings = config.settings
        val mode = params.inputMode
        return linkedMapOf(
            "input_mode" to when (mode) {
                is ActivityInputMode -> "voice_activity"
                is ToggleInputMode -> "push_to_talk"
                else -> "continuous"
            },
            "input_sample_rate" to settings.inputSampleRate.toString(),
            "noise_suppression" to settings.noiseSuppression.preferenceValue,
            "rnnoise_attenuation_limit_db" to settings.rnnoiseAttenuationLimitDb.toString(),
            "speex_noise_suppress_db" to settings.speexNoiseSuppressDb.toString(),
            "echo_cancellation" to config.echoCancellation.preferenceValue,
            "vad" to ((mode as? ActivityInputMode)?.vadConfig?.toString() ?: "none"),
            "amplitude_boost" to settings.amplitudeBoost.toString(),
            "half_duplex" to config.halfDuplex.toString(),
            "android_noise_suppressor" to settings.androidEffects.noiseSuppressor.toString(),
            "android_automatic_gain_control" to settings.androidEffects.automaticGainControl.toString(),
            "echo_cancellation_voice_call_source" to settings.echoCancellationVoiceCallSource.toString(),
            "opus_fec" to "1",
            "opus_loss_percent" to OpusEncoder.EXPECTED_PACKET_LOSS_PERCENT.toString(),
            "opus_dtx" to "0",
        )
    }

    private fun PipelineDescription.withEncoder(bitrate: Int, framesPerPacket: Int) = copy(
        settings = settings + mapOf("bitrate" to bitrate.toString(), "frames_per_packet" to framesPerPacket.toString()),
    )

    /** Fits the bitrate and frames per packet to the server's maximum bandwidth in bps; -1 means no limit. */
    private fun setMaxBandwidth(maxBandwidth: Int) {
        if (maxBandwidth == -1) return
        val (newBitrate, newFramesPerPacket) = fitToBandwidth(bitrate, framesPerPacket, maxBandwidth)
        if (newBitrate != bitrate || newFramesPerPacket != framesPerPacket) {
            bitrate = newBitrate
            framesPerPacket = newFramesPerPacket
            val kbps = maxBandwidth / BPS_PER_KBPS
            logger.logInfo(
                context.getString(R.string.audio_max_bandwidth, kbps, kbps, newFramesPerPacket * MS_PER_FRAME),
            )
        }
    }

    val currentBandwidth: Int
        get() = audioBandwidth(bitrate, framesPerPacket)

    @Synchronized
    fun shutdown() {
        // First, so a capture thread that outlives the bounded join below writes nothing to the hub.
        taps?.detach()
        // Likewise for a playback thread that outlives its join, and packets still on their way.
        playbackTaps?.detach()
        synchronized(input) { input.shutdown() }
        // After the capture thread is joined, because every stage in it is single-threaded and
        // release() frees native state the loop reaches on every frame.
        capturePipeline.release()
        synchronized(output) { output.stopPlaying() }
        transmitter.releaseEncoder()
        isInitialized = false
        encodeListener.onTalkingStateChanged(false)
    }

    override fun onMessage(msg: MessageLite) {
        when (msg) {
            is Mumble.CodecVersion -> onCodecVersion(msg)
            is Mumble.ServerSync -> setMaxBandwidth(if (msg.hasMaxBandwidth()) msg.maxBandwidth else -1)
            // Stop audio input if the user is muted, and resume if the user has set talking enabled.
            // Before ServerSync there is nothing to update.
            is Mumble.UserState ->
                if (isInitialized && msg.hasSession() && msg.session == session) transmitter.muteState.update(msg)
        }
    }

    private fun onCodecVersion(msg: Mumble.CodecVersion) {
        if (!isInitialized) return // Only listen to change events in this handler.

        // Without Opus there is no codec to encode with; null turns input off.
        val newCodec = if (msg.opus) NomlaUdpMessageType.UDPVoiceOpus else null
        try {
            transmitter.setCodecIfChanged(newCodec, ::createEncoder)
        } catch (e: NativeAudioException) {
            NomlaLog.e(TAG, "Could not create the encoder", e)
        }
    }

    override fun onVoicePacket(packet: VoicePacket) {
        // On the protocol thread: the only one that assigns a talker's recording slot (P6).
        playbackTaps?.onPacketReceived(packet.session, packet.frameNumber, packet.opusLength, packet.isTerminator)
        synchronized(output) { output.queueVoiceData(packet) }
    }

    fun setVoiceTargetId(id: Byte) {
        transmitter.targetId = id
    }

    interface AudioEncodeListener {
        /** [data] is reused for the next packet: valid only until this call returns. */
        fun onAudioEncoded(data: ByteArray, length: Int)
        fun onTalkingStateChanged(talking: Boolean)
    }

    companion object {
        private val TAG = AudioHandler::class.java.name

        const val SAMPLE_RATE = 48_000
        const val FRAME_SIZE = SAMPLE_RATE / 100
        const val MAX_BUFFER_SIZE = 960

        /** The longest Opus packet, 120 ms, in frames. */
        const val MAX_PACKET_FRAMES = 12

        private const val BPS_PER_KBPS = 1000
        private const val MS_PER_FRAME = 10
    }
}

/**
 * The bitrate and frames per packet that fit [maxBandwidth] bps, as desktop Mumble's
 * `AudioInput::adjustBandwidth` picks them.
 */
@Suppress("MagicNumber") // Desktop Mumble's thresholds.
internal fun fitToBandwidth(bitrate: Int, framesPerPacket: Int, maxBandwidth: Int): Pair<Int, Int> {
    var newBitrate = bitrate
    var newFramesPerPacket = framesPerPacket
    if (audioBandwidth(newBitrate, newFramesPerPacket) > maxBandwidth) {
        newFramesPerPacket = when {
            newFramesPerPacket <= 4 && maxBandwidth <= 32_000 -> 4
            newFramesPerPacket == 1 && maxBandwidth <= 64_000 -> 2
            newFramesPerPacket == 2 && maxBandwidth <= 48_000 -> 4
            else -> newFramesPerPacket
        }
        while (audioBandwidth(newBitrate, newFramesPerPacket) > maxBandwidth &&
            newBitrate > 8_000
        ) {
            newBitrate -= 1_000
        }
    }
    return maxOf(8_000, newBitrate) to newFramesPerPacket
}

/** As desktop Mumble counts it: IP 20, UDP 8, crypt 4, header 1, sequence 2, TCP 12. */
private const val PACKET_OVERHEAD_BYTES = 20 + 8 + 4 + 1 + 2 + 12
private const val BITS_PER_BYTE = 8
private const val FRAMES_PER_SECOND = 100

/** Bandwidth in bps for audio with these parameters, including packet overhead. */
internal fun audioBandwidth(bitrate: Int, framesPerPacket: Int): Int {
    // The TCP overhead, the worst case, whichever transport carries the voice.
    val overheadBytes = PACKET_OVERHEAD_BYTES + framesPerPacket
    return overheadBytes * (BITS_PER_BYTE * FRAMES_PER_SECOND / framesPerPacket) + bitrate
}
