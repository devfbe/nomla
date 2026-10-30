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

package koeln.becker.nomla.diagnostics

import android.content.Context
import android.media.AudioManager
import androidx.annotation.StringRes
import koeln.becker.nomla.R
import koeln.becker.nomla.audio.AudioDeviceLabels
import koeln.becker.nomla.mumble.audio.routing.CommunicationDevice
import koeln.becker.nomla.mumble.diagnostics.CaptureDescription
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsSnapshot
import koeln.becker.nomla.mumble.diagnostics.EchoReading
import koeln.becker.nomla.mumble.diagnostics.GateReading
import koeln.becker.nomla.mumble.diagnostics.NetworkReading
import koeln.becker.nomla.mumble.diagnostics.PlaybackReading
import koeln.becker.nomla.mumble.diagnostics.RouteInfo
import koeln.becker.nomla.mumble.diagnostics.SendStage
import koeln.becker.nomla.mumble.diagnostics.SpeakerReading
import koeln.becker.nomla.mumble.diagnostics.StageReading
import java.util.Locale
import kotlin.math.sqrt

/** One line of a block or of an expanded stage: a label and its value, [LiveFormat.NONE] where there is none. */
data class Metric(val label: String, val value: String)

/** A titled group of metrics below the stages: echo, receive, network or device. */
data class Block(@field:StringRes val title: Int, val metrics: List<Metric>)

/**
 * The Device block's inputs, read on the main thread with each reading: the [device] voice goes to
 * (null while no session routes) and the playback [route]. The Live page may name the device,
 * Bluetooth included, because nothing it shows leaves the phone (G2).
 */
data class DeviceState(val device: CommunicationDevice?, val route: RouteInfo)

/**
 * Numbers as the Live page shows them: [NONE] for every missing or non-finite value, never "NaN".
 * Units are the international ones and stay untranslated.
 */
object LiveFormat {
    /** What a value that is missing, NaN or infinite shows. */
    const val NONE = "–"

    const val DB = "dB"
    const val MS = "ms"
    const val HZ = "Hz"

    /** A yes/no state, as a symbol that needs no translation. */
    const val YES = "✓"
    const val NO = "✗"

    private const val PERCENT = 100f

    fun number(value: Float?, decimals: Int = 1): String =
        if (value == null || !value.isFinite()) NONE else String.format(Locale.getDefault(), "%.${decimals}f", value)

    /** With a sign, as a change: "+3.0". */
    fun signed(value: Float?): String =
        if (value == null || !value.isFinite()) NONE else String.format(Locale.getDefault(), "%+.1f", value)

    /** [text] followed by [unit], or [NONE] alone. */
    fun withUnit(text: String, unit: String): String = if (text == NONE) NONE else "$text $unit"

    /** A change in dB with its sign. */
    fun changeDb(value: Float?): String = withUnit(signed(value), DB)

    /** A ratio in dB, such as ERL. */
    fun db(value: Float?): String = withUnit(number(value), DB)

    /** Whole milliseconds. */
    fun ms(value: Float?): String = withUnit(number(value, 0), MS)

    /** A fraction from 0 to 1 as whole percent. */
    fun percent(fraction: Float?): String = withUnit(number(fraction?.times(PERCENT), 0), "%")

    /** A level and its held maximum: "-12.0 / -3.0 dBFS". */
    fun peak(peak: Float, hold: Float, unit: String): String {
        val now = number(peak)
        val max = number(hold)
        return if (now == NONE && max == NONE) NONE else "$now / $max $unit"
    }

    @StringRes
    fun stageName(stage: SendStage): Int = when (stage) {
        SendStage.MIC_RAW -> R.string.debug_stage_mic_raw
        SendStage.RESAMPLED -> R.string.debug_stage_resampled
        SendStage.APM -> R.string.debug_stage_apm
        SendStage.DENOISED -> R.string.debug_stage_denoised
        SendStage.GATED -> R.string.debug_stage_gated
        SendStage.SENT -> R.string.debug_stage_sent
    }

    /** The `AudioManager` mode by its constant's name, which is what the platform documents. */
    fun audioMode(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        else -> mode.toString()
    }
}

/**
 * The metrics of a reading as label/value lines, labelled with Task 7's `debug_metric_*` and
 * `debug_label_*` strings.
 */
class LiveMetrics(private val context: Context) {
    private val dbfsUnit = context.getString(R.string.debug_metric_dbfs)
    private val labels = HashMap<Int, String>()

    /** The string [id], resolved once: a reading asks for the same labels 15 times a second. */
    private fun label(@StringRes id: Int): String = labels.getOrPut(id) { context.getString(id) }

    private fun dbfs(value: Float?) = LiveFormat.withUnit(LiveFormat.number(value), dbfsUnit)

    /** Every figure of [reading], with what the [snapshot] knows about its stage beyond the levels. */
    fun stage(reading: StageReading, snapshot: DiagnosticsSnapshot): List<Metric> {
        val peak = LiveFormat.peak(reading.peakDbfs, reading.peakHoldDbfs, dbfsUnit)
        val levels = listOf(
            Metric(label(R.string.debug_metric_rms), dbfs(reading.rmsDbfs)),
            Metric(label(R.string.debug_metric_peak), peak),
            Metric(label(R.string.debug_label_clipped), reading.clippedSamples.toString()),
            Metric(label(R.string.debug_label_level_change), LiveFormat.changeDb(reading.changeDb)),
        )
        val probability =
            Metric(label(R.string.debug_label_probability), LiveFormat.percent(snapshot.gate?.probability))
        val extra = when (reading.stage) {
            SendStage.APM -> echo(snapshot.echo)
            SendStage.DENOISED -> listOf(probability)
            SendStage.GATED -> gate(snapshot.gate) + probability
            else -> emptyList()
        }
        return levels + extra
    }

    /** The blocks below the stages: Echo, Receive, Network and Device. */
    fun blocks(snapshot: DiagnosticsSnapshot, device: DeviceState?, nameOf: (Int) -> String?): List<Block> = listOf(
        Block(R.string.debug_block_echo, echo(snapshot.echo)),
        Block(R.string.debug_block_receive, receive(snapshot.playback, snapshot.speakers, nameOf)),
        Block(R.string.debug_block_network, network(snapshot.network)),
        Block(R.string.debug_block_device, device(device, snapshot.capture)),
    )

    private fun echo(echo: EchoReading?): List<Metric> {
        echo ?: return emptyList()
        val spread = "${LiveFormat.number(echo.delayMedianMs, 0)} ± ${LiveFormat.number(echo.delayStdMs, 0)}"
        return listOf(
            Metric(label(R.string.debug_metric_erl), LiveFormat.db(echo.erlDb)),
            Metric(label(R.string.debug_metric_erle), LiveFormat.db(echo.erleDb)),
            Metric(label(R.string.debug_label_delay), "${LiveFormat.ms(echo.delayMs)} ($spread ${LiveFormat.MS})"),
            Metric(label(R.string.debug_label_divergent), LiveFormat.percent(echo.divergentFraction)),
            Metric(label(R.string.debug_label_level_change), LiveFormat.changeDb(echo.levelChangeDb)),
        )
    }

    private fun gate(gate: GateReading?): List<Metric> {
        gate ?: return emptyList()
        val flag = { on: Boolean -> if (on) LiveFormat.YES else LiveFormat.NO }
        return listOf(
            Metric(label(R.string.debug_lane_gate), flag(gate.voice)),
            Metric(label(R.string.debug_label_noise_floor), dbfs(gate.floorDbfs)),
            Metric(label(R.string.debug_label_speech_level), dbfs(gate.speechDbfs)),
            Metric(label(R.string.debug_label_threshold), dbfs(gate.thresholdDbfs)),
            Metric(label(R.string.debug_label_hold), flag(gate.holding)),
            Metric(label(R.string.debug_label_too_close), flag(gate.tooClose)),
        )
    }

    private fun receive(playback: PlaybackReading?, speakers: List<SpeakerReading>, nameOf: (Int) -> String?) =
        playback?.let {
            listOf(
                Metric(label(R.string.debug_track_mix), dbfs(it.mixRmsDbfs)),
                Metric(
                    label(R.string.debug_metric_peak),
                    LiveFormat.peak(it.mixPeakDbfs, it.mixPeakHoldDbfs, dbfsUnit),
                ),
                Metric(label(R.string.debug_label_clipped), it.mixClippedSamples.toString()),
                Metric(label(R.string.debug_label_underruns), it.underruns.toString()),
                Metric(label(R.string.debug_label_buffer), it.trackBufferFrames.toString()),
                Metric(label(R.string.debug_label_latency), LiveFormat.ms(it.outputLatencyMs)),
            )
        }.orEmpty() + speakers.flatMap { speaker(it, nameOf) }

    /** A talker by name where the model knows it, else by session; live only, never exported. */
    private fun speaker(speaker: SpeakerReading, nameOf: (Int) -> String?): List<Metric> {
        val name = nameOf(speaker.session) ?: context.getString(R.string.debug_speaker, speaker.session)
        val sub = { id: Int -> "$INDENT${label(id)}" }
        return listOf(
            Metric(name, "${dbfs(speaker.rmsDbfs)} (${LiveFormat.number(speaker.peakDbfs)})"),
            Metric(sub(R.string.debug_label_jitter_buffer), speaker.availablePackets.toString()),
            Metric(sub(R.string.debug_label_lost), speaker.lostPackets.toString()),
            Metric(sub(R.string.debug_label_late), speaker.latePackets.toString()),
            Metric(sub(R.string.debug_label_concealed), speaker.concealedFrames.toString()),
            Metric(sub(R.string.debug_label_decode_errors), speaker.decodeErrors.toString()),
        )
    }

    private fun network(network: NetworkReading?): List<Metric> {
        network ?: return emptyList()
        val udp = label(R.string.debug_metric_udp)
        val tcp = label(R.string.debug_metric_tcp)
        val ping = { mean: Float, variance: Float ->
            "${LiveFormat.number(mean, 0)} ± ${LiveFormat.number(sqrt(variance), 0)} ${LiveFormat.MS}"
        }
        val pair = { here: Int, remote: Int -> "$here / $remote" }
        val bytes = { size: Int? -> size?.toString() ?: LiveFormat.NONE }
        return listOf(
            Metric(label(R.string.debug_label_transport), if (network.usingUdp) udp else tcp),
            Metric("${label(R.string.debug_label_ping)} $udp", ping(network.udpPingMs, network.udpPingVarMs)),
            Metric("${label(R.string.debug_label_ping)} $tcp", ping(network.tcpPingMs, network.tcpPingVarMs)),
            Metric(label(R.string.debug_label_lost), pair(network.lost, network.remoteLost)),
            Metric(label(R.string.debug_label_late), pair(network.late, network.remoteLate)),
            Metric(label(R.string.debug_label_resync), pair(network.resync, network.remoteResync)),
            Metric(label(R.string.debug_metric_so_sndbuf), bytes(network.sendBufferBytes)),
            Metric(label(R.string.debug_metric_so_rcvbuf), bytes(network.receiveBufferBytes)),
        ) + network.extra.map { (key, value) -> Metric(key, LiveFormat.number(value)) }
    }

    private fun device(state: DeviceState?, capture: CaptureDescription?): List<Metric> {
        val output = state?.device?.let { AudioDeviceLabels.label(context.resources, it) }
        val route = state?.route
        val volume = route?.let { "${it.streamVolume} / ${it.streamMaxVolume}" }
        val routeMetrics = listOf(
            Metric(label(R.string.debug_label_output), output.none()),
            Metric(label(R.string.debug_label_volume), volume.none()),
            Metric(label(R.string.debug_label_audio_mode), route?.let { LiveFormat.audioMode(it.audioMode) }.none()),
        )
        val captureMetrics = capture?.let {
            val effects = it.platformEffects.joinToString().ifEmpty { null }
            listOf(
                Metric(label(R.string.debug_label_sample_rate), "${it.sampleRate} ${LiveFormat.HZ}"),
                Metric(label(R.string.debug_label_source), it.audioSource?.toString().none()),
                Metric(label(R.string.debug_label_buffer), it.bufferFrames?.toString().none()),
                Metric(label(R.string.debug_label_effects), effects.none()),
            )
        }.orEmpty()
        return routeMetrics + captureMetrics
    }

    private companion object {
        const val INDENT = "  · "
    }
}

private fun String?.none(): String = this ?: LiveFormat.NONE
