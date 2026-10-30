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

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import koeln.becker.nomla.mumble.Session

/** A resumed [ServiceHostActivity] with [session] as the current session. */
fun hostWith(session: Session): ActivityController<ServiceHostActivity> =
    Robolectric.buildActivity(ServiceHostActivity::class.java).setup().also { it.get().bind(session) }

/**
 * Takes the activity down to destroyed from whatever state a test left it in; nothing if it is
 * destroyed already. Call it after every test that set one up: Robolectric never destroys a test's
 * activities, and a RecyclerView still in a window stays registered in RecyclerView's per-thread
 * `GapWorker`, which keeps the activity, its application, resources and native themes reachable
 * for the rest of the test run (several MB per test, until the suite's heap runs out).
 */
fun ActivityController<out FragmentActivity>.close() {
    val state = get().lifecycle.currentState
    if (state == Lifecycle.State.DESTROYED) return
    if (state.isAtLeast(Lifecycle.State.RESUMED)) pause()
    if (state.isAtLeast(Lifecycle.State.STARTED)) stop()
    destroy()
}

/** Adds [fragment] under [tag], into the content view if [inContent], else without a view slot. */
fun <F : Fragment> FragmentActivity.addNow(fragment: F, tag: String, inContent: Boolean = false): F {
    supportFragmentManager.beginTransaction().apply {
        if (inContent) add(android.R.id.content, fragment, tag) else add(fragment, tag)
    }.commitNow()
    return fragment
}

/**
 * Adds [child] under a new [ChatTargetParentFragment], which holds the chat target as
 * `ChannelFragment` does; into the content view if [inContent].
 */
fun FragmentActivity.addUnderChatParent(
    child: Fragment,
    childTag: String,
    parentTag: String = "parent",
    inContent: Boolean = false,
): ChatTargetParentFragment {
    val parent = addNow(ChatTargetParentFragment(), parentTag, inContent)
    parent.childFragmentManager.beginTransaction().apply {
        if (inContent) add(ChatTargetParentFragment.CONTAINER_ID, child, childTag) else add(child, childTag)
    }.commitNow()
    return parent
}
