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

import android.media.AudioTrack
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import java.util.concurrent.CopyOnWriteArrayList

sealed interface TrackEvent
data class Write(val samples: Int, val silent: Boolean) : TrackEvent
data object Pause : TrackEvent
data object Flush : TrackEvent

/** The track calls of every [AudioTrack] in the test, in order; Robolectric drops `short` writes otherwise. */
val trackEvents = CopyOnWriteArrayList<TrackEvent>()

/** Records into [trackEvents] what the playback thread does to its track. */
@Suppress("ProtectedMemberInFinalClass") // Robolectric matches the framework's own signatures
@Implements(AudioTrack::class)
class RecordingAudioTrack : ShadowAudioTrack() {
    @Suppress("FunctionNaming", "UnusedParameter")
    @Implementation
    protected fun native_write_short(
        audioData: ShortArray,
        offsetInShorts: Int,
        sizeInShorts: Int,
        format: Int,
        isBlocking: Boolean,
    ): Int {
        val silent = (offsetInShorts until offsetInShorts + sizeInShorts).all { audioData[it] == 0.toShort() }
        trackEvents += Write(sizeInShorts, silent)
        return sizeInShorts
    }

    @Suppress("FunctionNaming")
    @Implementation
    protected fun native_pause() {
        trackEvents += Pause
    }

    @Implementation
    override fun flush() {
        trackEvents += Flush
        super.flush()
    }
}
