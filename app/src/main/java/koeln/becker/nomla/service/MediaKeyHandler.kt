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

package koeln.becker.nomla.service

import android.view.KeyEvent
import koeln.becker.nomla.mumble.audio.TransmitMode
import koeln.becker.nomla.MediaButtonAction
import koeln.becker.nomla.Settings

/** Maps headset / AVRCP media key events to push-to-talk or mute toggles. */
class MediaKeyHandler(
    private val settings: Settings,
    private val target: MediaKeyTarget,
) {
    /**
     * @return true if the event was consumed. The action fires on an uncanceled ACTION_DOWN with
     * repeatCount 0; every other event of a handled key is consumed without acting.
     *
     * Why DOWN, not UP: MediaSessionService swallows the real DOWN of HEADSETHOOK/PLAY_PAUSE and on
     * release dispatches a synthesized DOWN+UP pair, so a short press arrives as one DOWN. A long
     * press starts the voice assistant, after which only repeats and the final (uncanceled) UP reach
     * us; acting on the UP would open the microphone every time the assistant is summoned.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val handled = event.keyCode in HANDLED_KEYS && target.isConnected
        val action = if (handled) settings.mediaButtonAction else MediaButtonAction.NONE
        if (action == MediaButtonAction.NONE) return false
        if (isFreshPress(event)) perform(action)
        return true
    }

    /** An uncanceled ACTION_DOWN with repeatCount 0. */
    private fun isFreshPress(event: KeyEvent): Boolean =
        event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.flags and KeyEvent.FLAG_CANCELED) == 0

    private fun perform(action: MediaButtonAction) {
        when (action) {
            MediaButtonAction.AUTO ->
                if (target.transmitMode == TransmitMode.PUSH_TO_TALK) {
                    target.setTalking(!target.isTalking)
                } else {
                    target.toggleSelfMute()
                }
            MediaButtonAction.MUTE -> target.toggleSelfMute()
            MediaButtonAction.NONE -> Unit // onKeyEvent returns before
        }
    }

    companion object {
        /**
         * Each key toggles on its own, with no debounce: a time window cannot tell a duplicated
         * event (androidx/media#3083) from a deliberate double press, and swallowing the second
         * press of a real double press would leave the microphone open.
         */
        val HANDLED_KEYS: Set<Int> = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
