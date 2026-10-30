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

package koeln.becker.nomla.mumble.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import koeln.becker.nomla.mumble.audio.native.SpeexJitterNative
import koeln.becker.nomla.mumble.model.TalkState
import koeln.becker.nomla.mumble.net.VoicePacket
import koeln.becker.nomla.mumble.testutil.FakeOpusDecoder
import java.nio.ByteBuffer

class AudioOutputSpeechTest {
    /** A packet as the jitter buffer holds it: opus frame, volume adjustment bits, terminator flag. */
    private fun jitterPacket(payload: ByteArray, volume: Float = 1f, terminator: Boolean = false): ByteArray =
        payload + ByteBuffer.allocate(4).putFloat(volume).array() + byteArrayOf(if (terminator) 1 else 0)

    private fun voicePacket(payload: ByteArray, frameNumber: Long, context: Int = 0, volume: Float = 1f) =
        VoicePacket().apply {
            data = byteArrayOf(0x7F) + payload
            opusOffset = 1
            opusLength = payload.size
            this.frameNumber = frameNumber
            this.context = context
            volumeAdjustment = volume
            isTerminator = true
        }

    @Test
    fun `an opus frame reaches the jitter buffer with its sample count as span and its trailer`() {
        val jitter = FakeJitter()
        val speech = AudioOutputSpeech(
            42,
            AudioHandler.FRAME_SIZE,
            { _, _ -> },
            FakeOpusDecoder(framesPerPacket = 2, samplesPerFrame = 480),
            jitter,
        )

        speech.addFrameToBuffer(voicePacket(byteArrayOf(0x41, 0x42, 0x43), frameNumber = 7, context = 2, volume = 0.5f))

        // [length, timestamp, span, sequence, userData]: the frame and trailer, FRAME_SIZE * frame
        // number, frames * samplesPerFrame, a sequence of 0 and the context as user data.
        assertThat(jitter.puts).containsExactly(listOf(3 + 5, AudioHandler.FRAME_SIZE * 7, 2 * 480, 0, 2))
        assertThat(jitter.lastPutData!!.copyOf(8))
            .isEqualTo(jitterPacket(byteArrayOf(0x41, 0x42, 0x43), volume = 0.5f, terminator = true))
    }

    @Test
    fun `a packet the opus parser refuses never reaches the jitter buffer`() {
        val jitter = FakeJitter()
        val refusing = FakeOpusDecoder(framesPerPacket = -4)
        val speech = AudioOutputSpeech(42, AudioHandler.FRAME_SIZE, { _, _ -> }, refusing, jitter)

        speech.addFrameToBuffer(voicePacket(byteArrayOf(0x41), frameNumber = 1))

        assertThat(jitter.puts).isEmpty()
    }

    private fun talkStateFor(context: Int): TalkState {
        val packet = jitterPacket(byteArrayOf(0x41, 0x42, 0x43))
        val jitter = FakeJitter().apply {
            nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
            nextPacket = packet
            nextMeta = intArrayOf(packet.size, 0, 480, 0, context)
            ctlResult = 3
        }
        val states = mutableListOf<TalkState>()
        val listener = AudioOutputSpeech.Listener { _, state -> states += state }
        AudioOutputSpeech(42, AudioHandler.FRAME_SIZE, listener, FakeOpusDecoder(), jitter).decode()
        return states.single()
    }

    @Test
    fun `the context maps to the talk state like on desktop Mumble`() {
        assertThat(talkStateFor(VoicePacket.CONTEXT_NORMAL)).isEqualTo(TalkState.TALKING)
        assertThat(talkStateFor(VoicePacket.CONTEXT_SHOUT)).isEqualTo(TalkState.SHOUTING)
        assertThat(talkStateFor(VoicePacket.CONTEXT_WHISPER)).isEqualTo(TalkState.WHISPERING)
        assertThat(talkStateFor(VoicePacket.CONTEXT_LISTEN)).isEqualTo(TalkState.TALKING)
        assertThat(talkStateFor(17)).isEqualTo(TalkState.TALKING)
    }

    @Test
    fun `a decoded packet reports the talk state carried in its user data`() {
        val packet = jitterPacket(byteArrayOf(0x41, 0x42, 0x43))
        val jitter = FakeJitter().apply {
            nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
            nextPacket = packet
            nextMeta = intArrayOf(packet.size, 0, 480, 0, 1) // userData 1 = shouting
            ctlResult = 3                                    // three packets available
        }
        val states = mutableListOf<Pair<Int, TalkState>>()
        val speech = AudioOutputSpeech(
            42,
            AudioHandler.FRAME_SIZE,
            { session, state -> states += session to state },
            FakeOpusDecoder(),
            jitter,
        )

        val alive = speech.decode()

        assertThat(states).containsExactly(42 to TalkState.SHOUTING)
        assertThat(alive).isTrue()
        assertThat(speech.numSamples).isEqualTo(AudioHandler.FRAME_SIZE)
        assertThat(jitter.ticks).isEqualTo(1)
    }

    private fun decodedSamples(localVolume: Float, volumeAdjustment: Float = 1f): FloatArray {
        val packet = jitterPacket(byteArrayOf(0x41, 0x42, 0x43), volumeAdjustment)
        val jitter = FakeJitter().apply {
            nextStatus = SpeexJitterNative.JITTER_BUFFER_OK
            nextPacket = packet
            nextMeta = intArrayOf(packet.size, 0, 480, 0, 0)
            ctlResult = 3
        }
        val listener = object : AudioOutputSpeech.Listener {
            override fun onTalkStateUpdated(session: Int, state: TalkState) = Unit
            override fun gainOf(session: Int): Float = localVolume
        }
        val speech = AudioOutputSpeech(42, AudioHandler.FRAME_SIZE, listener, FakeOpusDecoder(fill = 0.5f), jitter)
        speech.decode()
        return speech.samples.copyOf(speech.numSamples)
    }

    @Test
    fun `the user's local volume scales the decoded samples`() {
        val unity = decodedSamples(1f)
        val half = decodedSamples(0.5f)
        val double = decodedSamples(2f)

        assertThat(unity.any { it != 0f }).isTrue()
        for (i in unity.indices) {
            assertThat(half[i]).isWithin(1e-6f).of(unity[i] * 0.5f)
            assertThat(double[i]).isWithin(1e-6f).of(unity[i] * 2f)
        }
    }

    @Test
    fun `the server's volume adjustment scales the samples on top of the local volume`() {
        val unity = decodedSamples(1f)
        val adjusted = decodedSamples(2f, volumeAdjustment = 0.25f)

        assertThat(unity.any { it != 0f }).isTrue()
        for (i in unity.indices) assertThat(adjusted[i]).isWithin(1e-6f).of(unity[i] * 0.5f)
    }

    @Test
    fun `destroy releases the decoder and the jitter buffer only once`() {
        val jitter = FakeJitter()
        val opus = FakeOpusDecoder()
        val speech = AudioOutputSpeech(
            42,
            AudioHandler.FRAME_SIZE,
            { _, _ -> },
            opus,
            jitter,
        )

        speech.close()
        speech.close()

        // AudioOutputSpeech has no guard of its own: it relies on the ones in OpusDecoder and
        // SpeexJitterBuffer, so it is the pair that has to stay idempotent.
        assertThat(opus.destroys).isEqualTo(1)
        assertThat(jitter.destroys).isEqualTo(1)
    }

    /** A talker of [framesPerPacket] 10 ms frames per packet whose packets arrive with [frameNumbers]. */
    private fun arrivals(framesPerPacket: Int, vararg frameNumbers: Long): AudioOutputSpeech {
        val speech = AudioOutputSpeech(
            42, AudioHandler.FRAME_SIZE, { _, _ -> }, FakeOpusDecoder(framesPerPacket = framesPerPacket), FakeJitter(),
        )
        for (frameNumber in frameNumbers) {
            speech.addFrameToBuffer(voicePacket(byteArrayOf(0x41), frameNumber).apply { isTerminator = false })
        }
        return speech
    }

    @Test
    fun `a gap in frame numbers counts lost packets, an older frame counts late`() {
        val speech = arrivals(2, 0L, 4L, 6L, 2L)

        assertThat(speech.lostPackets).isEqualTo(1)
        assertThat(speech.latePackets).isEqualTo(1)
    }

    @Test
    fun `the same with 10 ms packets`() {
        val speech = arrivals(1, 0L, 2L, 3L, 1L)

        assertThat(speech.lostPackets).isEqualTo(1)
        assertThat(speech.latePackets).isEqualTo(1)
    }

    @Test
    fun `consecutive 60 ms packets count nothing`() {
        val speech = arrivals(6, 0L, 6L, 12L, 18L)

        assertThat(speech.lostPackets).isEqualTo(0)
        assertThat(speech.latePackets).isEqualTo(0)
    }

    @Test
    fun `a talk spurt after a terminator counts no gap`() {
        val speech = AudioOutputSpeech(42, AudioHandler.FRAME_SIZE, { _, _ -> }, FakeOpusDecoder(), FakeJitter())
        speech.addFrameToBuffer(voicePacket(byteArrayOf(0x41), 0L).apply { isTerminator = false })
        speech.addFrameToBuffer(voicePacket(byteArrayOf(0x41), 1L))
        speech.addFrameToBuffer(voicePacket(byteArrayOf(0x41), 500L).apply { isTerminator = false })

        assertThat(speech.lostPackets).isEqualTo(0)
        assertThat(speech.latePackets).isEqualTo(0)
    }

    @Test
    fun `concealed frames are counted`() {
        // The jitter buffer never delivers: every frame until the stream times out is concealed.
        val speech = AudioOutputSpeech(42, AudioHandler.FRAME_SIZE, { _, _ -> }, FakeOpusDecoder(), FakeJitter())

        repeat(5) { speech.decode() }

        assertThat(speech.concealedFrames).isEqualTo(5)
        assertThat(speech.decodeErrors).isEqualTo(0)
        assertThat(speech.lastAvailablePackets).isEqualTo(0)
    }
}
