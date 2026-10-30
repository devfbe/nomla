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
package koeln.becker.nomla.session

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.audio.TransmitMode
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.FakeSession
import koeln.becker.nomla.Settings
import koeln.becker.nomla.testing.installSession

/** The talk keys act only in push-to-talk, only while connected, and hold or toggle as set. */
@RunWith(RobolectricTestRunner::class)
class PushToTalkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = FakeSession(SessionState.Connected)
    private var talking by session.audio::talkingNow
    private val keys = PushToTalk(context)

    init {
        installSession(session)
    }

    private fun pushToTalk(toggle: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Settings.INPUT_METHOD.key, TransmitMode.PUSH_TO_TALK.preferenceValue)
            .putBoolean(Settings.PTT_TOGGLE.key, toggle)
            .commit()
    }

    @Test
    fun holdToTalkTalksWhileTheKeyIsDown() {
        pushToTalk(toggle = false)

        keys.onKeyDown()
        assertThat(talking).isTrue()
        keys.onKeyUp()
        assertThat(talking).isFalse()
    }

    @Test
    fun toggleToTalkFlipsOnEveryRelease() {
        pushToTalk(toggle = true)

        keys.onKeyDown()
        assertThat(talking).isFalse()
        keys.onKeyUp()
        assertThat(talking).isTrue()
        keys.onKeyDown()
        assertThat(talking).isTrue()
        keys.onKeyUp()
        assertThat(talking).isFalse()
    }

    @Test
    fun theKeysDoNothingOutsidePushToTalk() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Settings.INPUT_METHOD.key, TransmitMode.VOICE_ACTIVITY.preferenceValue).commit()

        keys.onKeyDown()
        assertThat(talking).isFalse()
        talking = true
        keys.onKeyUp()
        assertThat(talking).isTrue()
    }

    /** Also while the connection is established but not synchronized yet. */
    @Test
    fun theKeysDoNothingWithoutASynchronizedSession() {
        pushToTalk(toggle = false)
        session.state.value = SessionState.Connecting

        keys.onKeyDown()
        assertThat(talking).isFalse()
        talking = true
        keys.onKeyUp()
        assertThat(talking).isTrue()
    }

    @Test
    fun toggleModeWithoutAConnectionDoesNotFlip() {
        pushToTalk(toggle = true)
        session.state.value = SessionState.Disconnected()

        keys.onKeyUp()

        assertThat(talking).isFalse()
    }

    @Test
    fun aHoldReleaseWhileNotTalkingStaysSilent() {
        pushToTalk(toggle = false)

        keys.onKeyUp()

        assertThat(talking).isFalse()
    }
}
