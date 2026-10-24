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
package koeln.becker.nomla.ui

import com.google.android.material.slider.Slider

/** Whether a finger is dragging the thumb of the slider [onValueChosen] was set up on. */
class SliderDrag internal constructor() {
    var isDragging: Boolean = false
        internal set
}

/**
 * Tells the user's moves of this slider apart: while a finger drags the thumb each value is only
 * [preview]ed, and the value it is let go at is [commit]ted once. Keys and accessibility actions
 * move it without a touch to end, so each such move is committed right away. Values set in code
 * reach neither.
 */
fun Slider.onValueChosen(preview: (Float) -> Unit = {}, commit: (Float) -> Unit): SliderDrag {
    val drag = SliderDrag()
    addOnChangeListener { _, value, fromUser ->
        if (!fromUser) return@addOnChangeListener
        if (drag.isDragging) preview(value) else commit(value)
    }
    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) {
            drag.isDragging = true
        }

        override fun onStopTrackingTouch(slider: Slider) {
            drag.isDragging = false
            commit(slider.value)
        }
    })
    return drag
}
