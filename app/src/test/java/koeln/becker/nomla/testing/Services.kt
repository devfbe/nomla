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

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.service.NomlaService
import koeln.becker.nomla.service.NotificationActionReceiver

/**
 * A created [NomlaService]. [beforeCreate] runs on the bare instance first, where the seams that
 * `onCreate` reads (connection factory, reconnect policy, communication devices) must be set.
 */
fun createNomlaService(beforeCreate: NomlaService.() -> Unit = {}): ServiceController<NomlaService> =
    Robolectric.buildService(NomlaService::class.java).also { it.get().beforeCreate() }.create()

/** From here on the platform refuses every foreground start, as with the screen off. */
fun Service.refuseForegroundStarts() {
    shadowOf(this).setThrowInStartForeground(
        ForegroundServiceStartNotAllowedException("startForeground() not allowed from the background"),
    )
}

/**
 * [intent] is a broadcast of [action] to [NotificationActionReceiver], named by class so no other
 * app's receiver can see it, and nobody can alter it.
 */
fun assertNotificationAction(intent: PendingIntent, action: String) {
    val pending = shadowOf(intent)
    assertThat(pending.isBroadcast).isTrue()
    assertThat(pending.isImmutable).isTrue()
    assertThat(pending.savedIntent.component?.className).isEqualTo(NotificationActionReceiver::class.java.name)
    assertThat(pending.savedIntent.action).isEqualTo(action)
}

/** Delivers [intent], a notification button's, to [NotificationActionReceiver] as the platform would. */
fun pressNotificationAction(intent: PendingIntent) {
    val sent = shadowOf(intent).savedIntent
    assertThat(sent.component?.className).isEqualTo(NotificationActionReceiver::class.java.name)
    NotificationActionReceiver().onReceive(ApplicationProvider.getApplicationContext(), sent)
    idleMainLooper()
}
