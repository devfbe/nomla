#!/usr/bin/env python3
# Copyright (C) 2026 The Nomla Authors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
"""Offline report for a Nomla diagnostics recording (the ZIP the app shares, format_version 1).

    python3 report.py nomla-diagnostics.zip [--out report.md]

Writes Markdown (to stdout unless --out is given) with
1. the recording: app, device, route, duration, whether the server showed the recording, the
   ratings and the note;
2. per track: RMS and peak level, clipped samples, and 1/3-octave band levels from 100 Hz to
   16 kHz (radix-2 FFT over 4096-sample Hann blocks);
3. dropouts: runs of at least 30 ms of digital silence on 5_gate / 6_sent while frames.csv says
   transmit=1 and send_allowed=1;
4. cut syllable onsets: transmit starts where 5_gate's first 30 ms are at least 10 dB below
   4_denoised;
5. echo and speech loss per stage: in far-end-only phases (mix playing, vad_open=0) each stage's
   level minus the mix level (echo leak); in near-end-only phases each stage's level and its change
   from the stage before;
6. network and buffers: packet gaps over 60 ms, speaker underruns, concealment bursts and transport
   changes, each with the nearest dropout within 200 ms.

Uses only the Python standard library. It reads the ZIP and nothing else: it opens no socket and
plays no audio.
"""

import argparse
import array
import csv
import io
import json
import math
import sys
import wave
import zipfile

FORMAT_VERSION = 1
FULL_SCALE = 32768.0
CLIP_HIGH = 32767
CLIP_LOW = -32768

FFT_SIZE = 4096
BAND_CENTERS_HZ = [100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500,
                   3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000]
THIRD_OCTAVE_HALF = 2 ** (1 / 6)

FRAME_MS = 10.0
DROPOUT_MIN_MS = 30.0
DROPOUT_TRACKS = ["5_gate", "6_sent"]
ONSET_WINDOW_MS = 30.0
ONSET_CUT_DB = 10.0
ONSET_TRACK = "5_gate"
ONSET_REFERENCE = "4_denoised"
PACKET_GAP_MS = 60.0
CORRELATION_MS = 200.0
CONCEALMENT_JOIN_MS = 30.0
CONTIGUOUS_MS = 1.0
MAX_LISTED = 50

# frames.csv column prefix of every send stage, with its track id, in signal order.
STAGES = [("mic_raw", "1_mic_raw"), ("resampled", "2_resampled"), ("apm", "3_apm"),
          ("denoised", "4_denoised"), ("gated", "5_gate"), ("sent", "6_sent")]

# AudioDeviceInfo type constants the route can hold.
DEVICE_TYPES = {
    1: "built-in earpiece", 2: "built-in speaker", 3: "wired headset", 4: "wired headphones",
    7: "Bluetooth SCO", 8: "Bluetooth A2DP", 11: "USB device", 12: "USB accessory", 15: "built-in microphone",
    22: "USB headset", 23: "hearing aid", 26: "BLE headset", 27: "BLE speaker", 30: "BLE broadcast",
}
AUDIO_MODES = {0: "normal", 1: "ringtone", 2: "in call", 3: "in communication", 4: "call screening"}


# ---------------------------------------------------------------- reading the ZIP

class Track:
    """One WAV track: mono 16-bit samples at their own rate."""

    def __init__(self, track_id, rate, samples):
        self.id = track_id
        self.rate = rate
        self.samples = samples

    def index(self, ms):
        return max(0, min(len(self.samples), int(round(ms * self.rate / 1000.0))))

    def ms(self, index):
        return index * 1000.0 / self.rate


def read_wav(data, track_id):
    with wave.open(io.BytesIO(data)) as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1:
            raise ValueError(f"{track_id}: expected mono 16-bit PCM")
        samples = array.array("h", w.readframes(w.getnframes()))
        if sys.byteorder != "little":
            samples.byteswap()
        return Track(track_id, w.getframerate(), samples)


def cell(text):
    """A CSV cell as a float; empty is None, `inf`/`-inf` are infinities."""
    if text is None or text == "":
        return None
    try:
        return float(text)
    except ValueError:
        return None


def read_table(z, name):
    """A telemetry CSV as a list of dicts of floats; missing file gives an empty list."""
    if name not in z.namelist():
        return []
    reader = csv.DictReader(io.StringIO(z.read(name).decode("utf-8")))
    return [{key: cell(value) for key, value in row.items()} for row in reader]


def read_events(z):
    if "events.csv" not in z.namelist():
        return []
    reader = csv.DictReader(io.StringIO(z.read("events.csv").decode("utf-8")))
    return [{"t_ms": cell(row["t_ms"]), "name": row["name"], "value": row.get("value") or ""} for row in reader]


class Recording:
    def __init__(self, path):
        with zipfile.ZipFile(path) as z:
            self.meta = json.loads(z.read("meta.json").decode("utf-8"))
            self.tracks = {}
            for entry in self.meta.get("tracks", []):
                name = entry.get("file") or f"{entry['id']}.wav"
                if name in z.namelist():
                    self.tracks[entry["id"]] = read_wav(z.read(name), entry["id"])
            self.frames = read_table(z, "frames.csv")
            self.playback = read_table(z, "playback.csv")
            self.speakers = read_table(z, "speakers.csv")
            self.packets = read_table(z, "packets.csv")
            self.network = read_table(z, "network.csv")
            self.events = read_events(z)


# ---------------------------------------------------------------- levels and spectra

def db(ratio):
    return 20 * math.log10(ratio) if ratio > 0 else -math.inf


def power_db(power):
    return 10 * math.log10(power) if power > 0 else -math.inf


def fmt_db(value):
    if value is None:
        return "–"
    if value == -math.inf:
        return "-inf"
    return f"{value:.1f}"


def fmt_s(ms):
    return f"{ms / 1000.0:.2f} s"


def rms_db(samples, start=0, end=None):
    end = len(samples) if end is None else end
    if end <= start:
        return -math.inf
    total = 0
    for i in range(start, end):
        total += samples[i] * samples[i]
    return db(math.sqrt(total / (end - start)) / FULL_SCALE)


def peak_db(samples):
    if not samples:
        return -math.inf
    return db(max(max(samples), -min(samples)) / FULL_SCALE)


def clipped(samples):
    return samples.count(CLIP_HIGH) + samples.count(CLIP_LOW)


class Fft:
    """Iterative radix-2 FFT of one fixed size, with its bit-reversal table and twiddles."""

    def __init__(self, size):
        bits = size.bit_length() - 1
        if 1 << bits != size:
            raise ValueError("size must be a power of two")
        self.size = size
        self.reverse = [int(format(i, f"0{bits}b")[::-1], 2) for i in range(size)]
        self.twiddles = [complex(math.cos(-2 * math.pi * k / size), math.sin(-2 * math.pi * k / size))
                         for k in range(size // 2)]

    def transform(self, values):
        n = self.size
        a = [complex(values[r]) for r in self.reverse]
        span = 2
        while span <= n:
            half = span // 2
            step = n // span
            factors = self.twiddles[::step][:half]
            for start in range(0, n, span):
                for k in range(half):
                    i = start + k
                    t = a[i + half] * factors[k]
                    a[i + half] = a[i] - t
                    a[i] = a[i] + t
            span *= 2
        return a


def band_edges(center):
    return center / THIRD_OCTAVE_HALF, center * THIRD_OCTAVE_HALF


def band_levels(samples, rate, fft=None):
    """Mean 1/3-octave band levels in dBFS, as {center Hz: level}, over the non-silent 4096-sample
    Hann blocks; bands above the Nyquist frequency are left out. None if no block has sound."""
    fft = fft or Fft(FFT_SIZE)
    n = fft.size
    window = [0.5 - 0.5 * math.cos(2 * math.pi * i / n) for i in range(n)]
    window_power = sum(w * w for w in window)
    resolution = rate / n
    nyquist = rate / 2
    bands = {}
    for center in BAND_CENTERS_HZ:
        low, high = band_edges(center)
        if high > nyquist:
            continue
        first = max(1, math.ceil(low / resolution))
        last = min(n // 2 - 1, math.ceil(high / resolution) - 1)
        if first <= last:
            bands[center] = (first, last)
    sums = {center: 0.0 for center in bands}
    blocks = 0
    for start in range(0, len(samples) - n + 1, n):
        block = samples[start:start + n]
        if not any(block):
            continue
        spectrum = fft.transform([block[i] / FULL_SCALE * window[i] for i in range(n)])
        power = [abs(x) ** 2 for x in spectrum[:n // 2]]
        for center, (first, last) in bands.items():
            sums[center] += 2 * sum(power[first:last + 1]) / (n * window_power)
        blocks += 1
    if blocks == 0:
        return None
    return {center: power_db(total / blocks) for center, total in sums.items()}


# ---------------------------------------------------------------- analyses

def active_spans(frames):
    """Merged (start, end) ms spans of frames with transmit=1 and send_allowed=1; a row's t_ms is
    its frame's end."""
    spans = []
    for row in frames:
        t = row.get("t_ms")
        if t is None or row.get("transmit") != 1 or row.get("send_allowed") != 1:
            continue
        start = t - FRAME_MS
        if spans and start <= spans[-1][1] + CONTIGUOUS_MS:
            spans[-1][1] = t
        else:
            spans.append([start, t])
    return spans


def zero_runs(track, min_ms):
    """(start, end) ms of every run of digital silence of at least min_ms."""
    samples = track.samples
    minimum = int(math.ceil(min_ms * track.rate / 1000.0))
    runs = []
    i = 0
    n = len(samples)
    while i < n:
        if samples[i] != 0:
            i += 1
            continue
        j = i
        while j < n and samples[j] == 0:
            j += 1
        if j - i >= minimum:
            runs.append((track.ms(i), track.ms(j)))
        i = j
    return runs


def dropouts(recording):
    """(track id, start ms, end ms) of every silence run of 30 ms or more inside an active span."""
    spans = active_spans(recording.frames)
    found = []
    for track_id in DROPOUT_TRACKS:
        track = recording.tracks.get(track_id)
        if track is None:
            continue
        for run_start, run_end in zero_runs(track, DROPOUT_MIN_MS):
            for span_start, span_end in spans:
                start, end = max(run_start, span_start), min(run_end, span_end)
                if end - start >= DROPOUT_MIN_MS:
                    found.append((track_id, start, end))
    found.sort(key=lambda d: (d[1], DROPOUT_TRACKS.index(d[0])))
    return found


def transmit_starts(frames):
    """Start ms of every frame where transmit goes from 0 to 1 while sending is allowed."""
    starts = []
    previous = None
    for row in frames:
        transmit = row.get("transmit")
        if transmit == 1 and previous == 0 and row.get("send_allowed") == 1 and row.get("t_ms") is not None:
            starts.append(row["t_ms"] - FRAME_MS)
        previous = transmit
    return starts


def cut_onsets(recording):
    """(start ms, gate dB, reference dB) of transmit starts whose gate is 10 dB or more too quiet."""
    gate = recording.tracks.get(ONSET_TRACK)
    reference = recording.tracks.get(ONSET_REFERENCE)
    if gate is None or reference is None:
        return None
    cuts = []
    for start in transmit_starts(recording.frames):
        end = start + ONSET_WINDOW_MS
        gate_db = rms_db(gate.samples, gate.index(start), gate.index(end))
        reference_db = rms_db(reference.samples, reference.index(start), reference.index(end))
        if reference_db > -math.inf and gate_db <= reference_db - ONSET_CUT_DB:
            cuts.append((start, gate_db, reference_db))
    return cuts


def power_mean_db(values):
    values = [v for v in values if v is not None]
    if not values:
        return None
    return power_db(sum(10 ** (v / 10) if v > -math.inf else 0.0 for v in values) / len(values))


def mix_at(playback_by_slot, t):
    """The playback row within one mix of t, preferring one that played."""
    slot = int(round(t / FRAME_MS))
    rows = [playback_by_slot.get(s) for s in (slot, slot - 1, slot + 1)]
    rows = [r for r in rows if r is not None]
    playing = [r for r in rows if r.get("played") == 1]
    return (playing or rows or [None])[0]


def stage_phases(recording):
    """Far-end-only frames (with their mix rows) and near-end-only frames."""
    by_slot = {}
    for row in recording.playback:
        if row.get("t_ms") is not None:
            by_slot[int(round(row["t_ms"] / FRAME_MS))] = row
    far, near = [], []
    for row in recording.frames:
        if row.get("t_ms") is None or row.get("send_allowed") != 1:
            continue
        mix = mix_at(by_slot, row["t_ms"])
        mix_on = mix is not None and mix.get("played") == 1
        if mix_on and row.get("vad_open") == 0:
            far.append((row, mix))
        elif not mix_on and row.get("vad_open") == 1:
            near.append(row)
    return far, near


# ---------------------------------------------------------------- network and buffers

def packet_gaps(packets):
    """(start ms, end ms, stream label) of gaps over 60 ms inside a transmission."""
    streams = {}
    for row in packets:
        if row.get("t_ms") is None:
            continue
        key = (row.get("direction"), row.get("speaker"))
        streams.setdefault(key, []).append(row)
    gaps = []
    for (direction, speaker), rows in streams.items():
        rows.sort(key=lambda r: r["t_ms"])
        label = stream_label(direction, speaker)
        for before, after in zip(rows, rows[1:]):
            if before.get("terminator") == 1:
                continue
            if after["t_ms"] - before["t_ms"] > PACKET_GAP_MS:
                gaps.append((before["t_ms"], after["t_ms"], label))
    return sorted(gaps)


def stream_label(direction, speaker):
    if direction == 0:
        return "sent"
    if speaker is None:
        return "received from a talker without a track"
    return f"received from speaker_{int(speaker)}"


def counter_increases(rows, column):
    """(t ms, increase) wherever a cumulative counter grows; a drop is a reset and counts from 0."""
    found = []
    previous = None
    for row in rows:
        value, t = row.get(column), row.get("t_ms")
        if value is None or t is None:
            continue
        if previous is not None:
            delta = value - previous if value >= previous else value
            if delta > 0:
                found.append((t, delta))
        previous = value
    return found


def concealment_bursts(speakers):
    """(start ms, end ms, speaker, frames) of concealment, joining increases under 30 ms apart."""
    by_speaker = {}
    for row in speakers:
        if row.get("speaker") is not None:
            by_speaker.setdefault(int(row["speaker"]), []).append(row)
    bursts = []
    for speaker, rows in sorted(by_speaker.items()):
        for t, frames in counter_increases(rows, "concealed_frames"):
            if bursts and bursts[-1][2] == speaker and t - bursts[-1][1] <= CONCEALMENT_JOIN_MS:
                start, _, _, total = bursts[-1]
                bursts[-1] = (start, t, speaker, total + frames)
            else:
                bursts.append((t, t, speaker, frames))
    return sorted(bursts)


def nearest_dropout(found, start, end):
    """Text naming the dropout nearest to [start, end] within 200 ms, or saying there is none."""
    best = None
    for track_id, d_start, d_end in found:
        distance = max(0.0, d_start - end, start - d_end)
        if distance <= CORRELATION_MS and (best is None or distance < best[0]):
            best = (distance, track_id, d_start)
    if best is None:
        return "no dropout within 200 ms"
    return f"nearest dropout: {best[1]} at {fmt_s(best[2])} ({best[0]:.0f} ms away)"


# ---------------------------------------------------------------- Markdown

def header_section(meta, out):
    app = meta.get("app", {})
    device = meta.get("device", {})
    route = meta.get("route", {})
    out.append("## Recording\n")
    if meta.get("format_version") != FORMAT_VERSION:
        out.append(f"**Warning:** format_version {meta.get('format_version')}; this tool reads "
                   f"version {FORMAT_VERSION}.\n")
    out.append(f"- App: Nomla {app.get('version_name')} (code {app.get('version_code')}, "
               f"{app.get('flavor')})")
    out.append(f"- Device: {device.get('manufacturer')} {device.get('model')}, Android "
               f"{device.get('android_release')} (SDK {device.get('sdk')})")
    out.append(f"- Route: {route_text(route)}")
    out.append(f"- Started: {meta.get('started_at')}, duration {fmt_s(meta.get('duration_ms') or 0)}")
    shown = meta.get("server_showed_recording")
    out.append(f"- Server showed the recording: {'yes' if shown else 'no'}")
    out.append(f"- Dropped frames: {meta.get('dropped_frames')}, talkers without a track: "
               f"{meta.get('speakers_without_track')}")
    ratings = [f"{t['id']}: {t['rating']}" for t in meta.get("tracks", []) if t.get("rating")]
    out.append(f"- Ratings: {', '.join(ratings) if ratings else 'none'}")
    note = (meta.get("note") or "").strip()
    out.append(f"- Note: {note if note else 'none'}")
    out.append("")


def route_text(route):
    device_type = route.get("device_type")
    if device_type is None:
        return "unknown"
    parts = [f"type {device_type} ({DEVICE_TYPES.get(device_type, 'other')})"]
    if route.get("product_name"):
        parts.append(str(route["product_name"]))
    if route.get("stream_volume") is not None:
        parts.append(f"volume {route['stream_volume']}/{route.get('stream_max_volume')}")
    mode = route.get("audio_mode")
    if mode is not None:
        parts.append(f"audio mode {mode} ({AUDIO_MODES.get(mode, 'other')})")
    return ", ".join(parts)


def track_section(track, entry, fft, out):
    out.append(f"## {track.id}\n")
    meaning = entry.get("meaning")
    if meaning:
        out.append(f"{meaning[0].upper()}{meaning[1:]}.\n")
    out.append(f"- {track.rate} Hz, {fmt_s(track.ms(len(track.samples)))}, {entry.get('side', '?')} side, "
               f"rating: {entry.get('rating') or 'none'}")
    out.append(f"- RMS {fmt_db(rms_db(track.samples))} dBFS, peak {fmt_db(peak_db(track.samples))} dBFS, "
               f"clipped samples: {clipped(track.samples)}")
    levels = band_levels(track.samples, track.rate, fft)
    if levels is None:
        out.append("- 1/3-octave bands: no block with sound\n")
        return
    strongest = max(levels, key=lambda c: levels[c])
    out.append(f"- Strongest band: {strongest} Hz ({fmt_db(levels[strongest])} dBFS)\n")
    out.append("| Hz | " + " | ".join(str(c) for c in levels) + " |")
    out.append("|---" * (len(levels) + 1) + "|")
    out.append("| dBFS | " + " | ".join(fmt_db(v) for v in levels.values()) + " |\n")


def dropout_section(found, out):
    out.append("## Dropouts\n")
    out.append(f"Runs of at least {DROPOUT_MIN_MS:.0f} ms of digital silence on "
               f"{' / '.join(DROPOUT_TRACKS)} while transmit=1 and send_allowed=1.\n")
    if not found:
        out.append("None found.\n")
        return
    for track_id, start, end in found[:MAX_LISTED]:
        out.append(f"- {track_id} at {fmt_s(start)}: {end - start:.0f} ms")
    more(len(found), out)
    out.append("")


def onset_section(cuts, out):
    out.append("## Cut syllable onsets\n")
    out.append(f"Transmit starts where {ONSET_TRACK} over the first {ONSET_WINDOW_MS:.0f} ms is at least "
               f"{ONSET_CUT_DB:.0f} dB below {ONSET_REFERENCE}.\n")
    if cuts is None:
        out.append(f"Not available: the recording has no {ONSET_TRACK} or {ONSET_REFERENCE} track.\n")
        return
    if not cuts:
        out.append("None found.\n")
        return
    for start, gate_db, reference_db in cuts[:MAX_LISTED]:
        out.append(f"- {fmt_s(start)}: {ONSET_TRACK} {fmt_db(gate_db)} dBFS, {ONSET_REFERENCE} "
                   f"{fmt_db(reference_db)} dBFS ({fmt_db(gate_db - reference_db)} dB)")
    more(len(cuts), out)
    out.append("")


def stage_section(recording, out):
    far, near = stage_phases(recording)
    out.append("## Echo and speech loss per stage\n")
    out.append(f"Far-end only (mix playing, vad_open=0): {len(far)} frames; echo leak is the stage level "
               f"minus the mix level. Near-end only (no mix, vad_open=1): {len(near)} frames.\n")
    mix_db = power_mean_db([mix.get("mix_rms_dbfs") for _, mix in far])
    out.append("| Stage | Echo leak (dB vs mix) | Near-end level (dBFS) | Change from previous stage (dB) |")
    out.append("|---|---|---|---|")
    previous = None
    for prefix, track_id in STAGES:
        column = f"{prefix}_rms_dbfs"
        stage_far = power_mean_db([row.get(column) for row, _ in far])
        leak = None
        if stage_far is not None and mix_db is not None and mix_db > -math.inf:
            leak = stage_far - mix_db
        level = power_mean_db([row.get(column) for row in near])
        change = None
        if level is not None and previous is not None and level > -math.inf and previous > -math.inf:
            change = level - previous
        if level is not None:
            previous = level
        out.append(f"| {track_id} | {fmt_db(leak)} | {fmt_db(level)} | {fmt_db(change)} |")
    out.append("")


def network_section(recording, found, out):
    out.append("## Network and buffers\n")
    out.append(f"Each item with the nearest dropout within {CORRELATION_MS:.0f} ms.\n")
    items = []
    for start, end, label in packet_gaps(recording.packets):
        items.append((start, f"packet gap {end - start:.0f} ms ({label})", start, end))
    for t, count in counter_increases(recording.playback, "underruns"):
        items.append((t, f"speaker underrun (+{count:.0f})", t, t))
    for start, end, speaker, frames in concealment_bursts(recording.speakers):
        items.append((start, f"concealment burst, speaker_{speaker}: {frames:.0f} frames", start, end))
    for event in recording.events:
        if event["name"] == "transport_changed" and event["t_ms"] is not None:
            items.append((event["t_ms"], f"transport changed to {event['value'] or '?'}",
                          event["t_ms"], event["t_ms"]))
    if not items:
        out.append("Nothing found.\n")
        return
    items.sort(key=lambda item: item[0])
    for t, text, start, end in items[:MAX_LISTED]:
        out.append(f"- {fmt_s(t)}: {text}; {nearest_dropout(found, start, end)}")
    more(len(items), out)
    out.append("")


def more(count, out):
    if count > MAX_LISTED:
        out.append(f"- … and {count - MAX_LISTED} more")


def report(path):
    """The Markdown report of the diagnostics ZIP at path."""
    recording = Recording(path)
    out = ["# Nomla diagnostics report\n"]
    header_section(recording.meta, out)
    fft = Fft(FFT_SIZE)
    for entry in recording.meta.get("tracks", []):
        track = recording.tracks.get(entry["id"])
        if track is not None:
            track_section(track, entry, fft, out)
    found = dropouts(recording)
    dropout_section(found, out)
    onset_section(cut_onsets(recording), out)
    stage_section(recording, out)
    network_section(recording, found, out)
    return "\n".join(out).rstrip("\n") + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description="Markdown report of a Nomla diagnostics recording.")
    parser.add_argument("zip", help="the diagnostics ZIP the app shared")
    parser.add_argument("--out", help="write the report here instead of to stdout")
    args = parser.parse_args(argv)
    text = report(args.zip)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
