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

package koeln.becker.nomla.chat

import android.text.Spanned

/** The parsed body of one chat message. Parsed once per message and cached on the message. */
sealed class ChatContent {
    /** A message without images. */
    data class Text(val spanned: Spanned) : ChatContent()

    /**
     * A message containing an image. [source] is the raw `src` attribute of the first `<img>`;
     * [textBefore]/[textAfter] hold the text around it (null when empty). Further images are
     * replaced by a placeholder inside [textAfter].
     */
    data class Image(val source: String, val textBefore: Spanned?, val textAfter: Spanned?) : ChatContent()
}
