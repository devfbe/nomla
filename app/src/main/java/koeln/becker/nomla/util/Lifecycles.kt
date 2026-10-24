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

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs [block] in this owner's `lifecycleScope` each time the lifecycle reaches [state], and
 * cancels it each time it falls below; the returned job ends with the lifecycle.
 */
fun LifecycleOwner.repeatWhile(state: Lifecycle.State, block: suspend CoroutineScope.() -> Unit): Job =
    lifecycleScope.launch { repeatOnLifecycle(state, block) }

/** [repeatWhile] started: an activity's or a sheet's collection of what it shows. */
fun LifecycleOwner.whileStarted(block: suspend CoroutineScope.() -> Unit): Job =
    repeatWhile(Lifecycle.State.STARTED, block)

/** [whileStarted] on the view's lifecycle: collection of what the fragment's view shows. */
fun Fragment.whileViewStarted(block: suspend CoroutineScope.() -> Unit): Job =
    viewLifecycleOwner.whileStarted(block)
