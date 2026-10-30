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

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.mumble.audio.routing.CommunicationDevice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The route a recording keeps: Bluetooth by type only, everything else with its name (G2). */
@RunWith(RobolectricTestRunner::class)
class RouteInfoTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Test
    fun `a bluetooth device has no product name`() {
        val bluetooth = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        for (type in bluetooth) {
            val route = RouteInfo.current(context, CommunicationDevice(ID, type, NAME, "00:11:22:33:44:55"))

            assertThat(route.deviceType).isEqualTo(type)
            assertThat(route.productName).isNull()
            assertThat(route.toString()).doesNotContain(NAME)
            assertThat(route.toString()).doesNotContain("Felix")
        }
    }

    @Test
    fun `a wired, usb or built-in device keeps its product name`() {
        val named = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        )
        for (type in named) {
            val route = RouteInfo.current(context, CommunicationDevice(ID, type, "Pixel USB-C"))

            assertThat(route.deviceType).isEqualTo(type)
            assertThat(route.productName).isEqualTo("Pixel USB-C")
        }
        val unnamed = RouteInfo.current(context, CommunicationDevice(ID, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ""))
        assertThat(unnamed.productName).isNull()
    }

    @Test
    fun `the route reads the stream volume and the audio mode`() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, VOLUME, 0)

        val routed = RouteInfo.current(context, CommunicationDevice(ID, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, ""))
        val unrouted = RouteInfo.current(context, null)

        assertThat(routed.streamVolume).isEqualTo(VOLUME)
        assertThat(routed.streamMaxVolume).isEqualTo(audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL))
        assertThat(routed.audioMode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
        assertThat(unrouted.deviceType).isNull()
        assertThat(unrouted.productName).isNull()
        assertThat(unrouted.streamMaxVolume).isEqualTo(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    private companion object {
        const val ID = 3
        const val NAME = "Felix's AirPods"
        const val VOLUME = 4
    }
}
