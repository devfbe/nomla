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
import androidx.test.core.app.ApplicationProvider
import koeln.becker.nomla.app.AppContainer

/**
 * Puts fakes into this test's [AppContainer], the app's one seam for them. The application, and
 * with it the container, is new for every Robolectric test, so nothing needs to be put back.
 */
fun replaceParts(change: (AppContainer.Parts) -> AppContainer.Parts) =
    AppContainer.of(ApplicationProvider.getApplicationContext<Context>()).replaceParts(change)
