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

package koeln.becker.nomla.testing

import android.content.Context
import androidx.recyclerview.widget.AsyncDifferConfig
import coil3.ImageLoader
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import koeln.becker.nomla.mumble.model.Bytes
import koeln.becker.nomla.channel.ChannelListAdapter
import koeln.becker.nomla.channel.ChannelRow
import koeln.becker.nomla.util.UserStatus

/**
 * An adapter that diffs and decodes avatars on the calling thread, so a submit is done, avatars
 * included, once the main looper is idle.
 */
fun channelListAdapter(
    context: Context,
    listener: ChannelListAdapter.Listener = mockk(relaxed = true),
): ChannelListAdapter = ChannelListAdapter(
    context,
    listener,
    ImageLoader.Builder(context).coroutineContext(Dispatchers.Unconfined).build(),
    AsyncDifferConfig.Builder(ChannelListAdapter.DIFF).setBackgroundThreadExecutor { it.run() }.build(),
)

@Suppress("LongParameterList") // One per field, all defaulted.
fun channelRow(
    id: Int,
    name: String? = "channel-$id",
    depth: Int = 0,
    userCount: Int? = 0,
    expanded: Boolean = true,
    expandable: Boolean = true,
    isOwn: Boolean = false,
    isLinked: Boolean = false,
    lock: ChannelRow.Lock = ChannelRow.Lock.NONE,
) = ChannelRow.Channel(id, name, depth, userCount, expanded, expandable, isOwn, isLinked, lock)

@Suppress("LongParameterList") // One per field, all defaulted.
fun userRow(
    session: Int,
    depth: Int = 1,
    isSelf: Boolean = false,
    status: UserStatus = UserStatus.NONE,
    avatar: Bytes? = null,
    localVolumePercent: Int? = null,
) = ChannelRow.User(session, "user-$session", depth, isSelf, status, avatar, localVolumePercent)
