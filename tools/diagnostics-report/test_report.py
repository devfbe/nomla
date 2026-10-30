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
"""Tests for report.py on a synthetic diagnostics ZIP in the app's format (format_version 1).

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
FRAME_MS = 10
DROPOUT_START_S = 1.0
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


def tone(rate, n, hz=TONE_HZ, amplitude=AMPLITUDE):
    return array.array("h", (int(amplitude * math.sin(2 * math.pi * hz * i / rate)) for i in range(n)))


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


def build_zip(path):
    """Two seconds: transmitting throughout, a 100 ms digital-silence hole in 5_gate and 6_sent at
    1.00 s, a 1 kHz tone on 1_mic_raw at 44.1 kHz, and a 100 ms gap in the sent packets at 1.00 s."""
    n = RATE * DURATION_MS // 1000
    gate = tone(RATE, n)
    hole_start = int(DROPOUT_START_S * RATE)
    for i in range(hole_start, hole_start + RATE * DROPOUT_MS // 1000):
        gate[i] = 0
    tracks = {
        "1_mic_raw": (RAW_RATE, tone(RAW_RATE, RAW_RATE * DURATION_MS // 1000)),
        "4_denoised": (RATE, tone(RATE, n)),
        "5_gate": (RATE, gate),
        "6_sent": (RATE, array.array("h", gate)),
        "7_mix": (RATE, array.array("h", [0] * n)),
    }
    frames = []
    for i in range(DURATION_MS // FRAME_MS):
        level = -15.0
        frames.append(((i + 1) * FRAME_MS, {
            "send_allowed": 1, "transmit": 1, "muted": 0, "read_samples": 441, "expected_samples": 441,
            "read_ms": 10, "silenced": 0, "mic_raw_rms_dbfs": level, "denoised_rms_dbfs": level,
            "gated_rms_dbfs": level, "vad_open": 1,
        }))
    playback = [((i + 1) * FRAME_MS, {"played": 0, "mix_rms_dbfs": "-inf", "underruns": 0})
                for i in range(DURATION_MS // FRAME_MS)]
    packets = []
    frame_number = 0
    t = PACKET_MS
    while t < DURATION_MS:
        if not (DROPOUT_START_S * 1000 < t < DROPOUT_START_S * 1000 + DROPOUT_MS):
            packets.append((t, {"direction": 0, "speaker": 0, "frame_number": frame_number, "bytes": 60,
                                "terminator": 0}))
        frame_number += 2
        t += PACKET_MS
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
                    "rating": "bad" if tid == "5_gate" else None} for tid, (rate, _) in tracks.items()],
        "note": "cuts out mid-sentence",
    }
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("README.txt", "Nomla diagnostics recording\n")
        z.writestr("meta.json", json.dumps(meta, indent=2) + "\n")
        for tid, (rate, samples) in tracks.items():
            z.writestr(f"{tid}.wav", wav_bytes(rate, samples))
        z.writestr("frames.csv", csv_text(FRAME_COLUMNS, frames))
        z.writestr("playback.csv", csv_text(PLAYBACK_COLUMNS, playback))
        z.writestr("speakers.csv", csv_text(SPEAKER_COLUMNS, []))
        z.writestr("packets.csv", csv_text(PACKET_COLUMNS, packets))
        z.writestr("network.csv", csv_text(NETWORK_COLUMNS, [(100.0, {"using_udp": 1})]))
        z.writestr("events.csv", "t_ms,name,value\n0.0,talk_start,\n")


class ReportTest(unittest.TestCase):
    report_module = None

    @classmethod
    def setUpClass(cls):
        cls.report_module = load_report()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.zip_path = os.path.join(self.tmp.name, "nomla-diagnostics.zip")
        build_zip(self.zip_path)
        self.markdown = self.report_module.report(self.zip_path)

    def tearDown(self):
        self.tmp.cleanup()

    def test_report_runs_on_a_synthetic_zip(self):
        self.assertIn("## 5_gate", self.markdown)
        self.assertIn("cuts out mid-sentence", self.markdown)

    def test_finds_a_dropout(self):
        section = self.markdown.split("## Dropouts", 1)[1].split("\n## ", 1)[0]
        lines = [line for line in section.splitlines() if "5_gate" in line and "1.00 s" in line]
        self.assertTrue(lines, section)

    def test_band_peak_is_the_tone(self):
        section = self.markdown.split("## 1_mic_raw", 1)[1].split("\n## ", 1)[0]
        self.assertIn("Strongest band: 1000 Hz", section)

    def test_correlates_packet_gap_with_dropout(self):
        section = self.markdown.split("## Network and buffers", 1)[1].split("\n## ", 1)[0]
        lines = [line for line in section.splitlines() if "packet gap" in line]
        self.assertTrue(lines, section)
        self.assertIn("nearest dropout: 5_gate at 1.00 s", lines[0])

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
