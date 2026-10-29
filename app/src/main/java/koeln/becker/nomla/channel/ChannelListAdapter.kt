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

package koeln.becker.nomla.channel

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.asImage
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import coil3.util.CoilUtils
import koeln.becker.nomla.mumble.model.Bytes
import koeln.becker.nomla.mumble.model.TalkState
import koeln.becker.nomla.R
import koeln.becker.nomla.databinding.ChannelListenerRowBinding
import koeln.becker.nomla.databinding.ChannelRowBinding
import koeln.becker.nomla.databinding.ChannelUserRowBinding
import koeln.becker.nomla.util.dp
import koeln.becker.nomla.util.stateIconRes
import koeln.becker.nomla.util.talkStateDescription
import java.security.MessageDigest

/**
 * The channel list's rows, diffed by [DiffUtil] off the main thread; a user row whose only change
 * is its icon gets the icon repainted rather than the row rebound. Talk states come separately
 * through [setTalkStates], which repaints the icons of the users whose state changed in place: no
 * rebind, no layout pass. Avatars are decoded by [imageLoader], off the main thread. Main thread.
 */
@Suppress("TooManyFunctions") // RecyclerView.Adapter callbacks and the view holders' binding.
class ChannelListAdapter(
    private val context: Context,
    private val listener: Listener,
    private val imageLoader: ImageLoader,
    differConfig: AsyncDifferConfig<ChannelRow> = AsyncDifferConfig.Builder(DIFF).build(),
) : ListAdapter<ChannelRow, RecyclerView.ViewHolder>(differConfig) {

    /** What a tap on a row or one of its buttons asks for. */
    interface Listener {
        fun onChannelClick(row: ChannelRow.Channel)
        fun onUserClick(row: ChannelRow.User)
        fun onExpandClick(row: ChannelRow.Channel)
        fun onJoinClick(row: ChannelRow.Channel)
        fun onChannelMore(anchor: View, row: ChannelRow.Channel)
        fun onUserMore(anchor: View, row: ChannelRow.User)
        fun onStopListening(row: ChannelRow.Listener)
    }

    private var talkStates: Map<Int, TalkState> = emptyMap()

    private var list: RecyclerView? = null

    /** The avatars' memory-cache keys; a snapshot keeps the same [Bytes] until the picture changes. */
    private val avatarKeys = LruCache<Bytes, String>(AVATAR_CACHE_SIZE)

    private val avatarSizePx = context.resources.dp(AVATAR_SIZE_DP).toInt()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ChannelRow.Channel -> R.layout.channel_row
        is ChannelRow.User -> R.layout.channel_user_row
        is ChannelRow.Listener -> R.layout.channel_listener_row
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        list = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        list = null
    }

    /**
     * Repaints the icons of the users on screen whose talk state changed. A row off screen picks
     * the state up when it is bound, or when a cached row comes back ([onViewAttachedToWindow]).
     */
    fun setTalkStates(states: Map<Int, TalkState>) {
        val previous = talkStates
        talkStates = states
        val list = list ?: return
        for (session in previous.keys + states.keys) {
            if (previous[session] == states[session]) continue
            (list.findViewHolderForItemId(ChannelRow.USER_ID_MASK or session.toLong()) as? UserViewHolder)?.repaint()
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        if (holder is UserViewHolder) holder.repaint()
    }

    /** The position of the row with [id], or -1. */
    fun positionOf(id: Long): Int = currentList.indexOfFirst { it.id == id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(context)
        return when (viewType) {
            R.layout.channel_row -> ChannelViewHolder(ChannelRowBinding.inflate(inflater, parent, false))
            R.layout.channel_user_row -> UserViewHolder(ChannelUserRowBinding.inflate(inflater, parent, false))
            R.layout.channel_listener_row ->
                ListenerViewHolder(ChannelListenerRowBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("unknown view type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ChannelViewHolder -> holder.bind(getItem(position) as ChannelRow.Channel)
            is UserViewHolder -> holder.bind(getItem(position) as ChannelRow.User)
            is ListenerViewHolder -> holder.bind(getItem(position) as ChannelRow.Listener)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (holder is UserViewHolder && payloads.isNotEmpty() && payloads.all { it == ICON }) {
            holder.bindIcon(getItem(position) as ChannelRow.User)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    /** Pads [view] by [levels] of the tree's indentation. */
    private fun indent(view: View, levels: Int) {
        view.setPadding(
            context.resources.dp(INDENT_DP * levels).toInt(),
            view.paddingTop,
            view.paddingRight,
            view.paddingBottom,
        )
    }

    /** The row [holder] shows now, or null while it is on its way out. */
    private inline fun <reified R : ChannelRow> rowOf(holder: RecyclerView.ViewHolder): R? =
        holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { getItem(it) as? R }

    private inner class ChannelViewHolder(binding: ChannelRowBinding) : RecyclerView.ViewHolder(binding.root) {
        private val holder: LinearLayout = binding.channelRowTitle
        private val expandToggle: ImageView = binding.channelRowExpand
        private val name: TextView = binding.channelRowName
        private val userCount: TextView = binding.channelRowCount
        private val lock: ImageView = binding.channelRowLock
        private val more: ImageView = binding.channelRowMore

        init {
            itemView.setOnClickListener { row()?.let(listener::onChannelClick) }
            expandToggle.setOnClickListener { row()?.let(listener::onExpandClick) }
            binding.channelRowJoin.setOnClickListener { row()?.let(listener::onJoinClick) }
            more.setOnClickListener { view -> row()?.let { listener.onChannelMore(view, it) } }
            itemView.setOnLongClickListener {
                more.performClick()
                true
            }
        }

        private fun row(): ChannelRow.Channel? = rowOf(this)

        fun bind(row: ChannelRow.Channel) {
            bindExpandToggle(row)
            name.text = row.name
            // Separate constants rather than `or`: lint refuses combined Typeface @IntDef styles.
            name.setTypeface(
                null,
                when {
                    row.isOwn && row.isLinked -> Typeface.BOLD_ITALIC
                    row.isOwn -> Typeface.BOLD
                    row.isLinked -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            )
            userCount.visibility = if (row.userCount != null) View.VISIBLE else View.GONE
            row.userCount?.let { userCount.text = it.toString() }
            indent(holder, row.depth)
            bindLock(row.lock)
        }

        private fun bindExpandToggle(row: ChannelRow.Channel) {
            val icon = if (row.expanded) R.drawable.ic_action_expanded else R.drawable.ic_action_collapsed
            expandToggle.setImageResource(icon)
            expandToggle.contentDescription =
                context.getString(if (row.expanded) R.string.a11y_collapse else R.string.expand)
            expandToggle.isEnabled = row.expandable
            expandToggle.visibility = if (row.expandable) View.VISIBLE else View.INVISIBLE
        }

        private fun bindLock(state: ChannelRow.Lock) {
            lock.visibility = if (state == ChannelRow.Lock.NONE) View.GONE else View.VISIBLE
            if (state == ChannelRow.Lock.NONE) return
            val open = state == ChannelRow.Lock.OPEN
            lock.setImageResource(if (open) R.drawable.ic_lock_open else R.drawable.ic_lock)
            lock.contentDescription =
                context.getString(if (open) R.string.a11y_channel_restricted else R.string.a11y_channel_locked)
        }
    }

    private inner class UserViewHolder(binding: ChannelUserRowBinding) : RecyclerView.ViewHolder(binding.root) {
        private val holder: LinearLayout = binding.userRowTitle
        private val talkHighlight: ImageView = binding.userRowTalkHighlight
        private val name: TextView = binding.userRowName
        private val volume: TextView = binding.userRowVolume
        private val more: ImageView = binding.userRowMore

        init {
            itemView.setOnClickListener { rowOf<ChannelRow.User>(this)?.let(listener::onUserClick) }
            more.setOnClickListener { view -> rowOf<ChannelRow.User>(this)?.let { listener.onUserMore(view, it) } }
            itemView.setOnLongClickListener {
                more.performClick()
                true
            }
        }

        private var shownRow: ChannelRow.User? = null
        private var shownTalkState: TalkState? = null

        fun bind(row: ChannelRow.User) {
            name.text = row.name
            name.setTypeface(null, if (row.isSelf) Typeface.BOLD else Typeface.NORMAL)
            bindVolume(row.localVolumePercent)
            bindIcon(row)
            indent(holder, row.depth)
        }

        private fun bindVolume(percent: Int?) {
            volume.visibility = if (percent != null) View.VISIBLE else View.GONE
            percent ?: return
            volume.text = context.getString(R.string.local_volume_percent, percent)
            volume.contentDescription = context.getString(R.string.a11y_local_volume, percent)
        }

        /** The state icon, and for accessibility services the row's state in words. */
        fun bindIcon(row: ChannelRow.User) {
            val talkState = talkStates[row.session] ?: TalkState.PASSIVE
            shownRow = row
            shownTalkState = talkState
            paintIcon(row, talkState)
            ViewCompat.setStateDescription(itemView, talkStateDescription(context, row.status, talkState))
        }

        /**
         * The state icon, or for a silent user with an avatar the avatar. Either way the view's
         * previous request is replaced or cancelled, so a recycled row never shows a stale avatar.
         */
        private fun paintIcon(row: ChannelRow.User, talkState: TalkState) {
            val id = stateIconRes(row.status, talkState)
            val avatar = row.avatar
            if (id != R.drawable.outline_circle_talking_off || avatar == null) {
                CoilUtils.dispose(talkHighlight)
                talkHighlight.setImageDrawable(icon(id))
                return
            }
            // Until the avatar is decoded, and if it does not decode, the silent icon stays.
            val request = ImageRequest.Builder(context)
                .data(avatar.asReadOnlyBuffer())
                .memoryCacheKey(avatarKey(avatar))
                .size(avatarSizePx)
                .transformations(CircleCropTransformation())
                .placeholder { icon(id).asImage() }
                .error { icon(id).asImage() }
                .target(talkHighlight)
                .build()
            imageLoader.enqueue(request)
        }

        /** Repaints the icon if the talk state moved on since it was painted. */
        fun repaint() {
            val row = shownRow ?: return
            if (shownTalkState != (talkStates[row.session] ?: TalkState.PASSIVE)) bindIcon(row)
        }
    }

    private inner class ListenerViewHolder(binding: ChannelListenerRowBinding) : RecyclerView.ViewHolder(binding.root) {
        private val holder: LinearLayout = binding.listenerRowTitle
        private val name: TextView = binding.listenerRowName
        private val stop: ImageView = binding.listenerRowStop

        init {
            stop.setOnClickListener { rowOf<ChannelRow.Listener>(this)?.let(listener::onStopListening) }
        }

        fun bind(row: ChannelRow.Listener) {
            name.text = row.name
            itemView.contentDescription = context.getString(R.string.a11y_listener, row.name)
            stop.visibility = if (row.isOwn) View.VISIBLE else View.GONE
            indent(holder, row.depth)
        }
    }

    private fun icon(id: Int): Drawable = checkNotNull(ResourcesCompat.getDrawable(context.resources, id, null))

    /**
     * The SHA-1 of [avatar]: Coil cannot key raw bytes itself, and a content hash keeps one cached
     * bitmap per picture across rows and snapshots. Remembered per [Bytes], so it is hashed once.
     */
    private fun avatarKey(avatar: Bytes): String = avatarKeys.get(avatar)
        ?: MessageDigest.getInstance("SHA-1").run {
            update(avatar.asReadOnlyBuffer())
            AVATAR_KEY_PREFIX + digest().toHexString()
        }.also { avatarKeys.put(avatar, it) }

    companion object {
        private const val INDENT_DP = 25f
        private const val AVATAR_CACHE_SIZE = 64

        /** The user row's icon frame. */
        private const val AVATAR_SIZE_DP = 32f
        private const val AVATAR_KEY_PREFIX = "avatar:"

        /** The payload of a user row change that only its icon shows. */
        private val ICON = Any()

        /** Rows are the same row by id and unchanged when equal; a user's new state is an icon change. */
        val DIFF = object : DiffUtil.ItemCallback<ChannelRow>() {
            override fun areItemsTheSame(oldItem: ChannelRow, newItem: ChannelRow): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChannelRow, newItem: ChannelRow): Boolean = oldItem == newItem

            override fun getChangePayload(oldItem: ChannelRow, newItem: ChannelRow): Any? =
                ICON.takeIf {
                    oldItem is ChannelRow.User && newItem is ChannelRow.User &&
                        oldItem.copy(status = newItem.status, avatar = newItem.avatar) == newItem
                }
        }
    }
}
