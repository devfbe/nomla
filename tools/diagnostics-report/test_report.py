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
"""Tests for report.py on synthetic diagnostics ZIPs in the app's format (format_version 1).

The fixture follows the exporter's timing: every track lies on the 10 ms grid from the recording
start, and every telemetry row is stamped at the start of its grid slot with a few ms of jitter,
as TrackAssembler and the telemetry rings see it.

Run: python3 -I tools/diagnostics-report/test_report.py
"""

import array
import ast
import importlib.util
import io
import json
import math
import os
import sys
import tempfile
import unittest
import wave
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPORT_PATH = os.path.join(HERE, "report.py")

ALLOWED_MODULES = {"zipfile", "json", "csv", "wave", "array", "math", "argparse", "sys", "io"}

RATE = 48000
RAW_RATE = 44100
DURATION_MS = 2000
SLOT_MS = 10
SLOTS = DURATION_MS // SLOT_MS
JITTER_MS = 3
DROPOUT_START_MS = 1000
DROPOUT_MS = 100
TONE_HZ = 1000
AMPLITUDE = 8000
PACKET_MS = 20

STAGES = ["mic_raw", "resampled", "apm", "denoised", "gated", "sent"]
FRAME_COLUMNS = (
    ["send_allowed", "transmit", "muted", "read_samples", "expected_samples", "read_ms", "silenced"]
    + [f"{s}_{m}" for s in STAGES for m in ("rms_dbfs", "peak_dbfs", "clipped")]
    + ["vad_probability", "vad_level_dbfs", "vad_floor_dbfs", "vad_speech_dbfs", "vad_threshold_dbfs",
       "vad_open", "vad_holding", "vad_too_close", "apm_erl_db", "apm_erle_db", "apm_divergent_fraction",
       "apm_delay_median_ms", "apm_delay_std_ms", "apm_delay_ms"]
)
PLAYBACK_COLUMNS = ["played", "mix_rms_dbfs", "mix_peak_dbfs", "mix_clipped", "underruns",
                    "track_buffer_frames", "output_latency_ms", "active_speakers"]
SPEAKER_COLUMNS = ["speaker", "available_packets", "concealed_frames", "decode_errors", "lost_packets",
                   "late_packets", "rms_dbfs", "peak_dbfs"]
PACKET_COLUMNS = ["direction", "speaker", "frame_number", "bytes", "terminator"]
NETWORK_COLUMNS = ["using_udp", "tcp_ping_ms", "tcp_ping_var_ms", "udp_ping_ms", "udp_ping_var_ms", "good",
                   "late", "lost", "resync", "remote_good", "remote_late", "remote_lost", "remote_resync",
                   "so_sndbuf", "so_rcvbuf"]


def load_report():
    sys.dont_write_bytecode = True  # keep __pycache__ out of the checkout
    spec = importlib.util.spec_from_file_location("report", REPORT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def stamp(slot):
    """A row's t_ms: the start of its grid slot with deterministic jitter in [-3, +3] ms."""
    return max(0.0, slot * SLOT_MS + (slot * 37) % (2 * JITTER_MS + 1) - JITTER_MS)


def segment(rate, start_ms=0, end_ms=DURATION_MS):
    """A whole-recording track with a 1 kHz tone from start_ms to end_ms and silence elsewhere."""
    n = rate * DURATION_MS // 1000
    first, last = rate * start_ms // 1000, rate * end_ms // 1000
    return array.array("h", (int(AMPLITUDE * math.sin(2 * math.pi * TONE_HZ * i / rate)) if first <= i < last
                             else 0 for i in range(n)))


def wav_bytes(rate, samples):
    if sys.byteorder != "little":
        samples = array.array("h", samples)
        samples.byteswap()
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(samples.tobytes())
    return out.getvalue()


def csv_text(columns, rows):
    lines = [",".join(["t_ms"] + columns)]
    for t, values in rows:
        cells = [f"{t:.1f}"] + ["" if values.get(c) is None else str(values[c]) for c in columns]
        lines.append(",".join(cells))
    return "\n".join(lines) + "\n"


def packets_every(interval_ms, step, skip=lambda t: False, direction=0, speaker=0):
    rows = []
    frame_number = 0
    for slot in range(interval_ms // SLOT_MS, SLOTS, interval_ms // SLOT_MS):
        if not skip(slot * SLOT_MS):
            rows.append((stamp(slot), {"direction": direction, "speaker": speaker, "frame_number": frame_number,
                                       "bytes": 60, "terminator": 0}))
        frame_number += step
    return rows


class Fixture:
    """Two seconds of transmitting by default: a 100 ms digital-silence hole in 5_gate and 6_sent at
    1.00 s, a 1 kHz tone on 1_mic_raw at 44.1 kHz, and a 100 ms gap in the 20 ms sent packets at 1.00 s."""

    def __init__(self):
        gate = segment(RATE)
        hole = RATE * DROPOUT_START_MS // 1000
        for i in range(hole, hole + RATE * DROPOUT_MS // 1000):
            gate[i] = 0
        self.tracks = {
            "1_mic_raw": (RAW_RATE, segment(RAW_RATE)),
            "4_denoised": (RATE, segment(RATE)),
            "5_gate": (RATE, gate),
            "6_sent": (RATE, array.array("h", gate)),
            "7_mix": (RATE, array.array("h", [0] * (RATE * DURATION_MS // 1000))),
        }
        self.frame = lambda slot: {}
        self.mix = lambda slot: {}
        self.speakers = []
        self.packets = packets_every(
            PACKET_MS, 2, skip=lambda t: DROPOUT_START_MS < t < DROPOUT_START_MS + DROPOUT_MS)

    def frames_rows(self):
        rows = []
        for slot in range(SLOTS):
            values = {"send_allowed": 1, "transmit": 1, "muted": 0, "read_samples": 441,
                      "expected_samples": 441, "read_ms": 10, "silenced": 0, "mic_raw_rms_dbfs": -15,
                      "denoised_rms_dbfs": -15, "gated_rms_dbfs": -15, "vad_open": 1}
            values.update(self.frame(slot))
            rows.append((stamp(slot), values))
        return rows

    def playback_rows(self):
        rows = []
        for slot in range(SLOTS):
            values = {"played": 0, "mix_rms_dbfs": "-inf", "underruns": 0}
            values.update(self.mix(slot))
            rows.append((stamp(slot), values))
        return rows

    def write(self, path):
        meta = {
            "format_version": 1,
            "app": {"version_name": "0.1.0-134", "version_code": 134, "flavor": "beta"},
            "device": {"manufacturer": "Acme", "model": "Phone 1", "android_release": "15", "sdk": 35},
            "route": {"device_type": 2, "product_name": "Built-in speaker", "stream_volume": 7,
                      "stream_max_volume": 15, "audio_mode": 3},
            "audio": {"pipelines": []},
            "started_at": "2026-10-30T12:00:00.000Z",
            "duration_ms": DURATION_MS,
            "server_showed_recording": True,
            "speakers_without_track": 0,
            "dropped_frames": 0,
            "tracks": [{"id": tid, "file": f"{tid}.wav", "sample_rate": rate,
                        "side": "receive" if tid == "7_mix" else "send", "meaning": "",
                        "rating": "bad" if tid == "5_gate" else None} for tid, (rate, _) in self.tracks.items()],
            "note": "cuts out mid-sentence",
        }
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("README.txt", "Nomla diagnostics recording\n")
            z.writestr("meta.json", json.dumps(meta, indent=2) + "\n")
            for tid, (rate, samples) in self.tracks.items():
                z.writestr(f"{tid}.wav", wav_bytes(rate, samples))
            z.writestr("frames.csv", csv_text(FRAME_COLUMNS, self.frames_rows()))
            z.writestr("playback.csv", csv_text(PLAYBACK_COLUMNS, self.playback_rows()))
            z.writestr("speakers.csv", csv_text(SPEAKER_COLUMNS, self.speakers))
            z.writestr("packets.csv", csv_text(PACKET_COLUMNS, self.packets))
            z.writestr("network.csv", csv_text(NETWORK_COLUMNS, [(100.0, {"using_udp": 1})]))
            z.writestr("events.csv", "t_ms,name,value\n0.0,talk_start,\n")


def section(markdown, heading):
    return markdown.split(f"## {heading}\n", 1)[1].split("\n## ", 1)[0]


def items(text, word):
    """The list items of a section that contain word."""
    return [line for line in text.splitlines() if line.startswith("- ") and word in line]


class ReportTest(unittest.TestCase):
    report_module = None

    @classmethod
    def setUpClass(cls):
        cls.report_module = load_report()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.zip_path = os.path.join(self.tmp.name, "nomla-diagnostics.zip")
        Fixture().write(self.zip_path)
        self.markdown = self.report_module.report(self.zip_path)

    def tearDown(self):
        self.tmp.cleanup()

    def report_of(self, fixture):
        path = os.path.join(self.tmp.name, "variant.zip")
        fixture.write(path)
        return self.report_module.report(path)

    def test_report_runs_on_a_synthetic_zip(self):
        self.assertIn("## 5_gate", self.markdown)
        self.assertIn("cuts out mid-sentence", self.markdown)

    def test_finds_a_dropout(self):
        dropouts = section(self.markdown, "Dropouts")
        lines = items(dropouts, "5_gate at 1.00 s")
        self.assertEqual(1, len(lines), dropouts)
        self.assertIn("100 ms", lines[0])

    def test_band_peak_is_the_tone(self):
        self.assertIn("Strongest band: 1000 Hz", section(self.markdown, "1_mic_raw"))

    def test_correlates_packet_gap_with_dropout(self):
        network = section(self.markdown, "Network and buffers")
        lines = items(network, "packet gap")
        self.assertEqual(1, len(lines), network)
        self.assertIn("nearest dropout: 5_gate at 1.00 s", lines[0])

    def test_flags_an_onset_the_gate_opened_late(self):
        fixture = Fixture()
        fixture.frame = lambda slot: {"transmit": int(slot >= 100)}
        fixture.tracks["4_denoised"] = (RATE, segment(RATE, start_ms=970))
        fixture.tracks["5_gate"] = (RATE, segment(RATE, start_ms=1000))
        onsets = section(self.report_of(fixture), "Cut syllable onsets")
        self.assertIn("- 1.00 s:", onsets)

    def test_does_not_flag_an_onset_the_gate_caught(self):
        fixture = Fixture()
        fixture.frame = lambda slot: {"transmit": int(slot >= 100)}
        fixture.tracks["4_denoised"] = (RATE, segment(RATE, start_ms=1000))
        fixture.tracks["5_gate"] = (RATE, segment(RATE, start_ms=1000))
        self.assertIn("None found.", section(self.report_of(fixture), "Cut syllable onsets"))

    def test_measures_echo_leak_in_a_far_end_phase(self):
        fixture = Fixture()
        far = range(50, 150)
        fixture.frame = lambda slot: ({"vad_open": 0, "mic_raw_rms_dbfs": -20, "apm_rms_dbfs": -40}
                                      if slot in far else {})
        fixture.mix = lambda slot: {"played": 1, "mix_rms_dbfs": -10} if slot in far else {}
        fixture.speakers = [(stamp(slot), {"speaker": 1, "concealed_frames": 0, "rms_dbfs": -10}) for slot in far]
        stages = section(self.report_of(fixture), "Echo and speech loss per stage")
        self.assertIn("Far-end only (mix playing, vad_open=0): 100 frames", stages)
        self.assertIn("| 1_mic_raw | -10.0 |", stages)
        self.assertIn("| 3_apm | -30.0 |", stages)
        self.assertIn("rough approximation", stages)

    def test_lists_a_concealment_burst(self):
        fixture = Fixture()
        concealed = lambda slot: 0 if slot < 120 else 2 * (min(slot, 122) - 119)
        fixture.speakers = [(stamp(slot), {"speaker": 1, "concealed_frames": concealed(slot)})
                            for slot in range(SLOTS)]
        network = section(self.report_of(fixture), "Network and buffers")
        lines = items(network, "concealment burst")
        self.assertEqual(1, len(lines), network)
        self.assertIn("1.20 s: concealment burst, speaker_1: 6 frames", lines[0])

    def test_60_ms_packets_give_no_false_gaps(self):
        fixture = Fixture()
        fixture.packets = (packets_every(60, 6)
                           + packets_every(200, 2, direction=1, speaker=None))
        network = section(self.report_of(fixture), "Network and buffers")
        self.assertEqual([], items(network, "packet gap"))

    def test_reports_a_split_raw_track(self):
        fixture = Fixture()
        fixture.tracks["1_mic_raw"] = (RAW_RATE, segment(RAW_RATE, end_ms=1000))
        fixture.tracks["1_mic_raw_2"] = (RATE, segment(RATE, start_ms=1000))
        markdown = self.report_of(fixture)
        self.assertIn("Strongest band: 1000 Hz", section(markdown, "1_mic_raw"))
        split = section(markdown, "1_mic_raw_2")
        self.assertIn("48000 Hz, 2.00 s", split)
        self.assertIn("Strongest band: 1000 Hz", split)

    def test_writes_the_report_to_a_file(self):
        out = os.path.join(self.tmp.name, "report.md")
        self.report_module.main([self.zip_path, "--out", out])
        with open(out, encoding="utf-8") as f:
            self.assertEqual(self.markdown, f.read())

    def test_uses_only_the_standard_library(self):
        with open(REPORT_PATH, encoding="utf-8") as f:
            tree = ast.parse(f.read())
        imported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.add((node.module or "").split(".")[0])
        self.assertTrue(imported <= ALLOWED_MODULES, imported - ALLOWED_MODULES)


if __name__ == "__main__":
    unittest.main()
