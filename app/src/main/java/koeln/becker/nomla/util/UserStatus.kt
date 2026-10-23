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

package koeln.becker.nomla.util

import android.content.Context
import androidx.annotation.DrawableRes
import koeln.becker.nomla.mumble.model.TalkState
import koeln.becker.nomla.mumble.model.UserState
import koeln.becker.nomla.R

/** The state a user's icon shows ahead of talking, by priority; [NONE] leaves it to the talk state. */
enum class UserStatus {
    SELF_DEAFENED, DEAFENED, SELF_MUTED, MUTED, SUPPRESSED, NONE;

    companion object {
        fun of(user: UserState): UserStatus = when {
            user.isSelfDeafened -> SELF_DEAFENED
            user.isDeafened -> DEAFENED
            user.isSelfMuted -> SELF_MUTED
            user.isMuted -> MUTED
            user.isSuppressed -> SUPPRESSED
            else -> NONE
        }
    }
}

/** Whether [talkState] is any kind of talking (normal, shout or whisper); null is silent. */
private fun isTalking(talkState: TalkState?): Boolean = talkState != null && talkState != TalkState.PASSIVE

/**
 * The drawable of a user's state icon: [status] by its priority, then whether the user talks. The
 * channel list shows a silent user's avatar instead of [R.drawable.outline_circle_talking_off] when it has one.
 */
@DrawableRes
fun stateIconRes(status: UserStatus, talkState: TalkState?): Int = when (status) {
    UserStatus.SELF_DEAFENED -> R.drawable.outline_circle_deafened
    UserStatus.DEAFENED -> R.drawable.outline_circle_server_deafened
    UserStatus.SELF_MUTED -> R.drawable.outline_circle_muted
    UserStatus.MUTED -> R.drawable.outline_circle_server_muted
    UserStatus.SUPPRESSED -> R.drawable.outline_circle_suppressed
    UserStatus.NONE ->
        if (isTalking(talkState)) R.drawable.outline_circle_talking_on else R.drawable.outline_circle_talking_off
}

/**
 * What a user's state icon shows, for accessibility services, by the same priority as the channel
 * list's icon; null for a user who is just silent.
 */
fun talkStateDescription(context: Context, status: UserStatus, talkState: TalkState?): String? {
    val id = when (status) {
        UserStatus.SELF_DEAFENED -> R.string.a11y_state_deafened
        UserStatus.DEAFENED -> R.string.a11y_state_server_deafened
        UserStatus.SELF_MUTED -> R.string.a11y_state_muted
        UserStatus.MUTED -> R.string.a11y_state_server_muted
        UserStatus.SUPPRESSED -> R.string.a11y_state_suppressed
        UserStatus.NONE -> if (isTalking(talkState)) {
            R.string.a11y_state_talking
        } else {
            return null
        }
    }
    return context.getString(id)
}
