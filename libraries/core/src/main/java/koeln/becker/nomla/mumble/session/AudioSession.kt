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

package koeln.becker.nomla.mumble.session

import android.os.Handler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import koeln.becker.nomla.mumble.audio.AudioConfig
import koeln.becker.nomla.mumble.audio.AudioController
import koeln.becker.nomla.mumble.audio.AudioHandlerFactory
import koeln.becker.nomla.mumble.audio.AudioHost
import koeln.becker.nomla.mumble.audio.AudioSessionParams
import koeln.becker.nomla.mumble.audio.AudioSettings
import koeln.becker.nomla.mumble.audio.TransmitMode
import koeln.becker.nomla.mumble.audio.capture.EchoCancellationMode
import koeln.becker.nomla.mumble.audio.capture.InputMode
import koeln.becker.nomla.mumble.audio.capture.VoiceActivityDetector
import koeln.becker.nomla.mumble.audio.inputmode.ActivityInputMode
import koeln.becker.nomla.mumble.audio.inputmode.ContinuousInputMode
import koeln.becker.nomla.mumble.audio.inputmode.ToggleInputMode
import koeln.becker.nomla.mumble.audio.routing.AudioRouter
import koeln.becker.nomla.mumble.audio.routing.CommunicationDevice
import koeln.becker.nomla.mumble.audio.routing.CommunicationDevices
import koeln.becker.nomla.mumble.net.MessageHandlerRegistry

/**
 * The audio half of a session: the input modes, the router and the pipeline controller, and the
 * [AudioConfig] they resolve from [AudioSettings] and the current route. Main thread; the pipeline
 * itself is built and torn down on the controller's thread.
 *
 * @param onEvent a diagnostics event (name, value): `route_changed` with the routed device's type
 *   constant only, never its name (G2), and `settings_changed` with the keys of the settings that
 *   changed, never their values.
 */
@Suppress("LongParameterList") // The session's collaborators, the platform seams and its event sink.
internal class AudioSession(
    host: AudioHost,
    factory: AudioHandlerFactory,
    devices: CommunicationDevices,
    mainHandler: Handler,
    settings: AudioSettings,
    private val listener: Listener,
    private val onEvent: (String, String) -> Unit = { _, _ -> },
) {
    interface Listener {
        /** The platform refused a route the router asked for. */
        fun onRouteRefused()

        /** A pipeline could not start; [message] says why. */
        fun onAudioFailed(message: String)
    }

    var settings: AudioSettings = settings
        private set

    private val mutableRoute = MutableStateFlow<Int?>(null)

    /** The `AudioDeviceInfo` type voice is routed to; null while the platform decides. */
    val route: StateFlow<Int?> = mutableRoute.asStateFlow()

    private val mutableCaptureSilenced = MutableStateFlow(false)

    /** Whether the platform silences the running pipeline's microphone. */
    val captureSilenced: StateFlow<Boolean> = mutableCaptureSilenced.asStateFlow()

    val config: AudioConfig get() = settings.toAudioConfig(mutableRoute.value)

    private val toggleInputMode = ToggleInputMode()
    val activityInputMode = ActivityInputMode(VoiceActivityDetector(settings.vad))
    private val continuousInputMode = ContinuousInputMode()

    /** Held by identity: the audio thread and [isTalking] must see the same toggle object. */
    var inputMode: InputMode = inputModeFor(settings.transmitMode)
        private set

    /** Engaged only while a session is synchronized. */
    val router = AudioRouter(
        devices,
        object : AudioRouter.Listener {
            override fun onRouteChanged(type: Int?) = setRoute(type)

            override fun onRouteRefused() = listener.onRouteRefused()
        },
    ).apply {
        preferred = settings.preferredDevice
        bluetoothAutomatic = settings.bluetoothAutomatic
    }

    /** One controller and one thread for the life of this object; [release] quits it. */
    val controller = AudioController(host, factory, listener::onAudioFailed, mainHandler) {
        mutableCaptureSilenced.value = it
    }

    val isTalking: Boolean get() = toggleInputMode.isTalkingOn

    val isEchoCancellationEnabled: Boolean get() = config.echoCancellation != EchoCancellationMode.NONE

    /** The running pipeline's bandwidth in bps, or -1 while none runs. */
    val currentBandwidth: Int get() = controller.currentBandwidth

    val devices: List<CommunicationDevice> get() = router.availableDevices()

    val activeDevice: CommunicationDevice? get() = router.activeDevice()

    fun setTalking(talking: Boolean) = toggleInputMode.setTalkingOn(talking)

    /**
     * Applies [next] live: input mode, voice activity and routing at once, the pipeline through a
     * rebuild that [AudioController.reconfigure] skips when nothing it is built from changed.
     */
    fun apply(next: AudioSettings) {
        val previous = settings
        settings = next
        val changed = SETTING_KEYS.filter { (_, value) -> value(previous) != value(next) }
        if (changed.isNotEmpty()) onEvent(SETTINGS_CHANGED, changed.joinToString(",") { it.first })
        inputMode = inputModeFor(next.transmitMode)
        activityInputMode.setVadConfig(next.vad)
        val routing = next.preferredDevice to next.bluetoothAutomatic
        if (routing != previous.preferredDevice to previous.bluetoothAutomatic) {
            // A user's explicit choice is left standing; one the old preference seeded is not.
            router.preferred = next.preferredDevice
            router.bluetoothAutomatic = next.bluetoothAutomatic
            router.apply()
        }
        controller.reconfigure(config, inputMode)
    }

    /** The session synchronized: restore the route and build the pipeline for [params]. */
    fun start(params: AudioSessionParams, registry: MessageHandlerRegistry) {
        router.engage()
        controller.start(config, params.copy(inputMode = inputMode), registry)
    }

    /** Restores the route of a session that has no pipeline to build. */
    fun engageRoute() = router.engage()

    /**
     * A connection ended. Push-to-talk is cleared because the toggle outlives the connection, and an
     * automatic reconnect would otherwise transmit without a key press. The route is a connection
     * resource and the wish is not, so it is given back on every disconnect.
     */
    fun stop() {
        toggleInputMode.setTalkingOn(false)
        router.disengage()
        controller.shutdown()
    }

    /**
     * The session ended for good. The chooser's pick belongs to it, as a pick in the phone app
     * belongs to one call; a dropped connection keeps it, the end of the session does not.
     */
    fun endSession() = router.forgetChoice()

    fun setVoiceTargetId(id: Byte) = controller.setVoiceTargetId(id)

    /** Gives the route back and quits the controller thread, without waiting for the teardown. */
    fun release() {
        router.disengage()
        router.release()
        controller.quit()
    }

    /** A new route rebuilds the pipeline for its stream (and, for SCO, its sample rate). */
    private fun setRoute(type: Int?) {
        mutableRoute.value = type
        onEvent(ROUTE_CHANGED, type?.toString().orEmpty())
        controller.reconfigure(config, inputMode)
    }

    private fun inputModeFor(mode: TransmitMode): InputMode = when (mode) {
        TransmitMode.PUSH_TO_TALK -> toggleInputMode
        TransmitMode.CONTINUOUS -> continuousInputMode
        TransmitMode.VOICE_ACTIVITY -> activityInputMode
    }

    companion object {
        const val ROUTE_CHANGED = "route_changed"
        const val SETTINGS_CHANGED = "settings_changed"

        /** Every setting by the key a `settings_changed` event names it with. */
        private val SETTING_KEYS: List<Pair<String, (AudioSettings) -> Any?>> = listOf(
            "transmit_mode" to { it.transmitMode },
            "vad" to { it.vad },
            "half_duplex" to { it.halfDuplex },
            "echo_cancellation_overrides" to { it.echoCancellationOverrides },
            "preferred_device" to { it.preferredDevice },
            "bluetooth_automatic" to { it.bluetoothAutomatic },
            "audio_stream" to { it.pipeline.audioStream },
            "audio_source" to { it.pipeline.audioSource },
            "input_sample_rate" to { it.pipeline.inputSampleRate },
            "bitrate" to { it.pipeline.bitrate },
            "frames_per_packet" to { it.pipeline.framesPerPacket },
            "amplitude_boost" to { it.pipeline.amplitudeBoost },
            "noise_suppression" to { it.pipeline.noiseSuppression },
            "speex_noise_suppress_db" to { it.pipeline.speexNoiseSuppressDb },
            "rnnoise_attenuation_limit_db" to { it.pipeline.rnnoiseAttenuationLimitDb },
            "android_effects" to { it.pipeline.androidEffects },
            "echo_cancellation_voice_call_source" to { it.pipeline.echoCancellationVoiceCallSource },
        )
    }
}
