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

package koeln.becker.nomla.service

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.chat.NoticeFormatter
import koeln.becker.nomla.chat.outgoingMessageHtml
import koeln.becker.nomla.mumble.Session
import koeln.becker.nomla.mumble.model.Message
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.session.serverName
import koeln.becker.nomla.util.HtmlUtils

/**
 * What a received chat message does outside the app: the chat notification with its inline reply,
 * and text-to-speech while the setting is on. Owned by [NomlaService] for its life; main thread.
 */
internal class ChatAlerts(
    private val context: Context,
    private val sessions: SessionManager,
    private val settings: Settings,
) {
    private val messageNotification = NomlaMessageNotification(context)
    private val notices by lazy { NoticeFormatter(context) }

    /** Non-null exactly while the setting is on ([applyTextToSpeechPreference] owns it). */
    var tts: TextToSpeech? = null

    private val ttsInitListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.ERROR) sessions.chat.warnOnce(context.getString(R.string.tts_failed))
    }

    fun onTextMessage(session: Session, message: Message) {
        val strippedMessage = HtmlUtils.toPlainText(message.message)
        val ttsMessage = if (settings.isShortTextToSpeechMessagesEnabled) {
            HtmlUtils.toPlainTextWithShortLinks(message.message) { host ->
                context.getString(R.string.chat_message_tts_short_link, host)
            }
        } else {
            strippedMessage
        }
        val sender = notices.senderName(message)
        val formattedTtsMessage = context.getString(R.string.notification_message, sender, ttsMessage)

        // tts is non-null exactly while the setting is on (the preference listener owns it).
        val tts = tts
        val deafened = session.model.value?.self?.isSelfDeafened
        if (tts != null && formattedTtsMessage.length <= TTS_THRESHOLD && deafened == false) {
            @Suppress("DEPRECATION")
            tts.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null)
        }

        // Every message notifies while enabled; there is no per-sender filter yet.
        if (settings.isChatNotifyEnabled) messageNotification.show(sender, strippedMessage, conversation(session))
    }

    /** Our name, the channel a reply goes to and the server, for the chat notification. */
    private fun conversation(session: Session): NomlaMessageNotification.Conversation {
        val model = session.model.value
        return NomlaMessageNotification.Conversation(
            self = model?.self?.name,
            channel = model?.selfChannel?.name,
            server = session.serverName,
        )
    }

    /**
     * Sends the chat notification's inline reply to our channel. The notification is re-posted in
     * any case, or it would keep waiting for the send; without a session it is removed instead.
     */
    fun onChatReply(intent: Intent) {
        val reply = NomlaMessageNotification.replyText(intent)?.trim()
        val session = sessions.connected
        val channel = session?.model?.value?.selfChannel
        if (session == null || channel == null) {
            messageNotification.dismiss()
            return
        }
        if (reply.isNullOrEmpty()) {
            messageNotification.refresh()
            return
        }
        val html = outgoingMessageHtml(reply, settings.isMarkdownEnabled)
        session.actions.sendChannelTextMessage(channel.id, html, false)
        messageNotification.showReply(reply, conversation(session))
    }

    /** Starts text-to-speech when the setting is on, shuts it down when it is off. */
    fun applyTextToSpeechPreference() {
        val current = tts
        if (current == null && settings.isTextToSpeechEnabled) {
            tts = TextToSpeech(context, ttsInitListener)
        } else if (current != null && !settings.isTextToSpeechEnabled) {
            current.shutdown()
            tts = null
        }
    }

    /** Removes the chat notification. */
    fun dismiss() = messageNotification.dismiss()

    /** The service ends: shuts text-to-speech down, then removes the chat notification. */
    fun release() {
        tts?.shutdown()
        messageNotification.dismiss()
    }

    companion object {
        const val TTS_THRESHOLD = 250 // Maximum number of characters to read
    }
}
