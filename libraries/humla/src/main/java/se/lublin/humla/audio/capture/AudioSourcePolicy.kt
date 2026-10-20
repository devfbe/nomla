/*
 * Copyright (C) 2026 The Mumla Authors
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

package se.lublin.humla.audio.capture

import android.media.MediaRecorder
import se.lublin.humla.audio.routing.AudioDeviceCategory

/**
 * Picks the audio mode and the recording source. `MODE_IN_COMMUNICATION` whenever any Android audio
 * effect or echo canceller is active ([needsCommunicationMode]); in a session `AudioRouter` holds
 * that mode anyway, so the mode never decides between the paths below, only the source does.
 *
 * An `android.media.audiofx` effect keeps the `VOICE_COMMUNICATION` source it has always had.
 * WebRTC AEC3 alone takes the requested source (normally `MIC`): `VOICE_COMMUNICATION`
 * switches on the device's own AEC/NS/AGC in front of ours, and two cascaded echo suppressors chop
 * the user's speech on some phones (reported on a Motorola G56). AEC3 wants the raw microphone.
 *
 * Two exceptions keep `VOICE_COMMUNICATION` with AEC3: a Bluetooth route, because a `MIC` capture
 * does not follow the communication device to the SCO headset's microphone; and the user's
 * `voiceCallSource` setting, which brings the old behaviour back for comparison.
 */
internal object AudioSourcePolicy {
    fun needsCommunicationMode(effects: AndroidAudioEffects, echo: EchoCancellationMode): Boolean =
        effects.any || echo != EchoCancellationMode.NONE

    /**
     * @param route the kind of device capture is routed to, null while the route is the platform's.
     * @param voiceCallSource the user asks AEC3 to record on `VOICE_COMMUNICATION` too.
     * @return `VOICE_COMMUNICATION` for an effect, or for AEC3 on Bluetooth or with
     *   [voiceCallSource]; [requested] untouched otherwise.
     */
    fun resolve(
        requested: Int,
        effects: AndroidAudioEffects,
        echo: EchoCancellationMode,
        route: AudioDeviceCategory?,
        voiceCallSource: Boolean,
    ): Int {
        val voiceCall = effects.any ||
            (echo != EchoCancellationMode.NONE && (voiceCallSource || route == AudioDeviceCategory.BLUETOOTH))
        return if (voiceCall) MediaRecorder.AudioSource.VOICE_COMMUNICATION else requested
    }
}
