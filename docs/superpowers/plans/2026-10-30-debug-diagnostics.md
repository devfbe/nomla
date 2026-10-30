# Debug Diagnostics (Live Dashboard and Diagnostic Recording) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "Debug-Funktionen aktivieren" switch adds two pages behind the chat in the channel view: a live view of every send stage and all telemetry, and a recording of every stage (send and receive) plus telemetry that the user rates and shares as a ZIP.

**Architecture:** One `DiagnosticsHub` per `NomlaSession` outlives pipeline rebuilds. Inert taps sit at every stage of the capture and playback threads. With nobody metering and no recording armed, each tap is one volatile read and copies nothing. Metering updates preallocated level meters. A recording arms preallocated rings that the audio threads write without locks or allocation. A main-thread `RecordingController` owns the Mumble recording flag and every path that ends a recording. When a recording finishes, the rings are frozen into a `DiagnosticCapture`. The app shows it, plays tracks back, and writes the ZIP (WAV + CSV + `meta.json` + `README.txt`) off the main thread.

**Tech Stack:** Kotlin (core, protocol, app), C/C++ (nomla_apm wrapper + JNI), CMake/ctest host tests (plain + ASan/UBSan), JUnit/Robolectric/Truth, Android Views/Material, Python 3 standard library (offline report tool).

**Spec:** `docs/superpowers/specs/2026-10-30-debug-diagnostics-design.md` (German; read it fully before any task).

## Global Constraints

- Project rules are in `CLAUDE.md`. Find code with `jetbrains-index`, read it with Read, and edit only through studio `apply_patch` / `create_new_file`. After each batch of edits, run `ide_diagnostics` with `files:` set to every changed file; it must show no errors. Do not run Gradle from the shell. Do not use shell text tools on sources. Work on `main` without worktrees. Make small conventional commits (`feat(diagnostics): …`, `test(…)`, `docs(website): …`). End each commit with the attribution lines from the session's system reminder.
- **Running a unit test** (written RUN-TEST below): `get_run_configurations(filePath)`, then `execute_run_configuration(filePath, line=<class line>, waitForExit=false)`. Then read the result XML: `app/build/test-results/testBetaDebugUnitTest/TEST-<fqcn>.xml`, `libraries/core/build/test-results/testDebugUnitTest/TEST-<fqcn>.xml` or `libraries/protocol/build/test-results/test/TEST-<fqcn>.xml`.
- **Running the native host tests** (written RUN-NATIVE below): `execute_run_configuration(configurationName="nativeHostTests", waitForExit=false)` (the configuration is added in Task 1). Wait for `BUILD SUCCESSFUL|BUILD FAILED` in the returned `fullOutputPath`, then read `libraries/core/build/test-results/nativeHostTests/ctest-junit.xml`.
- The orchestrator runs one `verify` per wave. A task does not run `verify`.
- **Debug off means zero behaviour change.** No signal is modified, ever. Taps only observe: they copy from the frame and never write to it. With metering and recording both off, a tap costs at most one volatile read per frame, with no copy, no allocation, no lock and no I/O.
- **Debug off means zero cost, too (P1, P9).** Anything a tap or the session switches on while the hub is active (APM statistics polling, the 100 ms network sampler, the `AudioManager` mode listener) is switched off again when it goes inactive. Nothing ticks, polls or listens while the hub is inactive. The hub publishes its activity as `active: StateFlow<Boolean>` for this.
- **One producer per ring (P3, P6).** Every ring and every slot table has exactly one writing thread. Data that two threads produce goes into two rings, which are merged at freeze.
- **Real-time:** on `nomla-capture`, the playback thread and the protocol thread's voice path there is no allocation, no lock (beyond those already present), no I/O and no logging per frame or per packet. Rings, meters and scratch frames are allocated before arming, off the audio threads.
- **Native:** vendored WebRTC (`cpp/third_party/webrtc-audio-processing`) is not patched. Only the project's wrapper (`cpp/webrtc_apm/nomla_apm.{h,cpp}`) and `cpp/jni_webrtc_apm.cpp` change.
- **Recording flag:** if `ServerSettings.recordingAllowed` is false, no recording starts and `recording = true` is never sent. A missing field counts as allowed, as today (`ServerSettings.from`). Every end of a recording (finish, cancel, debug off) sends `recording = false` exactly once while the connection is synchronized. On a disconnect nothing is sent.
- **Mute/PTT:** while the user is muted (`SelfMuteState.isMuted`), or in push-to-talk without the key held, every send-side track records silence and `frames.csv` marks the frame `send_allowed=0`. Receive tracks and telemetry keep running.
- **ZIP privacy:** never contains server name/address, user names, channel names, passwords, certificates, tokens or chat text. Speakers are `speaker_1..speaker_4` in order of first appearance.
- **ZIP privacy, Bluetooth (G2, orchestrator ruling overriding the spec's "Name und Typ" for Bluetooth):** Bluetooth devices (SCO, BLE headset/speaker, A2DP, hearing aid) appear in `meta.json` **and in every event** (`route_changed` and any other) with their type only, never their product name. Wired, USB and built-in devices keep their product name. The `route_changed` event value is the device type constant only. The local Live page may show the name, because it never leaves the device.
- **Strings:** EN in `values/`, DE in `values-de/`, and an AI translation in all 29 other app locales (`values-ar, ca, cs, da, el, es, fi, fr, hu, in, it, ja, kab, lv, nb-rNO, nl, pl, pt, pt-rBR, ro, ru, sv, ta, th, tr, uk, vi, zh-rCN, zh-rTW`). The non-translatable acronyms are exactly `RMS, ERL, ERLE, dBFS, UDP, TCP, SO_SNDBUF, SO_RCVBUF`, as `translatable="false"` in `values/` only (G4). "Peak" is a normal translated string. The consent dialog's German text is fixed verbatim (Task 7).
- **Intents:** the ZIP is shared with `ACTION_SEND` and a read grant on exactly one `FileProvider` URI. Nothing in the feature starts the app's own components through an implicit intent.
- **No sound:** no task plays test audio, on the PC or on a phone. Acoustic or device tests of this feature happen only after the owner has been told and has said OK, against a local murmur. They are not part of any task.
- **Non-goals (do not build):** the emulator/Piper test environment (a separate, later feature), automatic upload, a report server, storing recordings across processes, and separate AEC/AGC taps.

### Builder choices (decided here; the orchestrator may overrule)

| # | Choice |
|---|---|
| B1 | The switch is a new `PreferenceCategory` "Diagnose" at the bottom of `settings_audio.xml`, outside the collapsed "Advanced" group. |
| B2 | Wide screens (`layout-sw600dp`): the right pane gets its own `TabLayout` (`R.id.side_tabs`) with Chat / Live / Aufnahme, shown only with debug on. It swaps the fragment in `R.id.chat_fragment`. |
| B3 | Durations: 5, 10, 20, 30 s. Default 10 s, maximum 30 s. At most 11 PCM tracks × 96 kB/s × 30 s ≈ 32 MB. |
| B4 | Four speaker slots, assigned in order of first packet. A fifth talker gets no track but is counted (`speakers_without_track` in `meta.json`). A talker who returns keeps their slot, also across a pipeline rebuild: the slot table lives in `ArmedRecording`, and only the protocol thread assigns slots (P6). |
| B5 | Taps are always wired into a session's pipeline and are inert until metering or a recording is armed. There is no rebuild on toggle. The "zero behaviour change" property is pinned by a test (Task 3). |
| B6 | Tracks are aligned by time: every ring frame carries `System.nanoTime()`, and export places frames on a 10 ms grid from the recording start, with silence in the gaps. The raw microphone track keeps its real rate; a rate change mid-recording starts a new raw WAV (`1_mic_raw.wav`, `1_mic_raw_2.wav`, …). Every split track spans the whole recording, with silence outside its own segment, so alignment needs no offset (P5). |
| B7 | The "sent" track decodes the **actually sent** Opus packets (payload, frame number, send time) with `OpusDecoder` at export. That is exactly the encoder's bitrate, frames per packet and FEC, as the peer receives them. Each packet is placed at its first captured frame, `sendNanos − packetFrames × FRAME_NANOS`, not at its send time (G6). |
| B8 | APM statistics: while the hub is active (metering or armed), `WebRtcApmPreprocessor` calls `getStatistics` on the capture thread inside its existing stage lock every 100 frames (1 s, webrtc's own aggregation window) (G3). |
| B9 | Live refresh is 15 Hz, polled by the UI while the Live page is started. Metering is reference-counted (`meter(): AutoCloseable`) and also on while a recording runs. Live meters work while muted, because nothing leaves the device. |
| B10 | CSV files: `frames.csv` (per 10 ms capture frame), `playback.csv` (per 10 ms mix), `speakers.csv` (per speaker per mix), `packets.csv` (per voice packet, both directions), `network.csv` (every 100 ms), `events.csv`. All are explained in `README.txt`. |
| B11 | Track playback uses a separate `PcmPlaybackSink` from `AppContainer.parts.micPlayback` on `Settings.PLAYBACK_STREAM`, on an `AudioWorker` thread, at the track's own rate. The session is not touched. A hint warns that the loudspeaker can carry the playback into the microphone. |
| B12 | A cancelled or aborted recording (cancel, disconnect, debug off) is discarded entirely. A finished capture stays in the session's hub until it is discarded, a new recording starts, or the session/process ends. The Recording page shows it even while disconnected; only "Aufnehmen" needs a connection. |
| B13 | Shared ZIPs live in `cacheDir/shared_diagnostics/`. The directory is cleared before every export, on discard, and once at process start (`AppContainer` init, on `parts.ioDispatcher`). |
| B14 | Spec items that cannot be measured are substituted. The app requests no audio focus, so `events.csv` records `AudioManager` mode changes (`addOnModeChangedListener`, API 31) instead. Output latency is estimated from `AudioTrack.getTimestamp`. The Bluetooth codec is not readable without `BLUETOOTH_PRIVILEGED`, so the route records the device type (SCO / BLE headset / A2DP); a product name only for wired, USB and built-in devices, never for Bluetooth (G2). Late/lost per speaker come from frame-number gaps on arrival, measured in packets (T4). |
| B15 | The report tool `tools/diagnostics-report/report.py` uses the Python 3 standard library only, like `tools/room-test/room_test.py`. It writes a Markdown report and never opens a socket or plays audio. |
| B16 | A shared run configuration `.run/nativeHostTests.run.xml` (Gradle task `:libraries:core:nativeHostTests`) lets agents run native tests through Studio. |

### Preflight rulings (binding; each is written into the owning task below)

- **P1** `CaptureTaps.commit` syncs APM polling before its inactive early return, so polling stops when the hub goes inactive (Task 3).
- **P3** Sent packets go to the capture thread's `SENT` `PacketRing`, received ones to a protocol-thread `received` `ColumnRing`; `packets.csv` is merged at freeze (Tasks 3, 4).
- **P4** `FrozenPackets` is specified in Task 2.
- **P5** Every split raw track spans the whole recording (B6, Task 2).
- **P6** The speaker slot table and `speakersWithoutTrack` live in `ArmedRecording`; only `onPacketReceived` (protocol thread) assigns slots (Task 4).
- **P7** Task 3 creates the full `DiagnosticsSnapshot`; `DiagnosticsHub` gets an `allocate` seam for the OOM test (Tasks 3, 5).
- **P9** The hub exposes `active: StateFlow<Boolean>`; the sampler and the mode listener run only while it is true (Tasks 3, 4, 5).
- **P10** The playback-mix track id is `7_mix` (Tasks 4, 6).
- **P11** Task 6 modifies core to add public `DiagnosticCapture.writeTrack`, `writeTable` and `writeEvents`.
- **P15** `RouteInfo` and `RouteInfo.current(context, device)` live in core (Task 5, because `DiagnosticCapture` is a core type); Task 5 snapshots the route into `DiagnosticCapture.route` at recording start; Task 6 exports `capture.route`; Task 9 passes no route.
- **P18** Task 7 creates `DiagnosticsLiveFragment.kt` and `DiagnosticsRecordFragment.kt` as separate placeholder files.
- **P19** Task 7 adds every string Tasks 8–9 need; its empty-state test covers only the Live page; Task 9 tests that a finished capture survives a disconnect (B12).
- **P20** `DiagnosticsViewModel` has no `recording` property (Task 8).
- **T1** The fallback of `test_statistics_values` still asserts `isfinite(s.delay_ms)`.
- **T3** The output-identity tests build their chains through `CapturePreprocessorFactory`, so `TapStage`s are inside the pinned path.
- **T4** Lost/late counting steps by the packet's frame count, not by 1.
- **T5** `RecordingController` takes `scope` and `exportDispatcher`.
- **T6** Task 6 tests the Bluetooth rule; the "real code path" privacy test moves to core (`NomlaSessionDiagnosticsTest`, Task 5).
- **T9** `TrackPlayer` lives in core as a public class (it needs the internal `AudioWorker`/`releaseAfter`).
- **G1** `events.csv` gets `talk_start`/`talk_end` and `connected`/`reconnecting`/`disconnected` (Task 5).
- **G2** Bluetooth devices are exported by type only, in `meta.json` and in every event; wired/USB/built-in keep their product name (orchestrator override of the spec) (Global Constraints, Tasks 5, 6).
- **G3** B8 polls while the hub is active, not only while armed.
- **G4** The non-translatable list is `RMS, ERL, ERLE, dBFS, UDP, TCP, SO_SNDBUF, SO_RCVBUF`; "Peak" is translated (Global Constraints, Task 7).
- **G5** README documents that `vad_probability` is the capture chain's probability (RNNoise's when RNNoise runs, else the APM level map) (Task 6).
- **G6** `6_sent` packets are placed at their first captured frame (B7, Task 5).

## Review Focus

1. **Route switch during a recording** (e.g. Bluetooth SCO reopens capture at 16 kHz and rebuilds the pipeline). Expected: the recording continues across the rebuild, the raw track splits by rate, nothing overflows or crashes, and `events.csv` shows `pipeline_rebuilt`. The test is in Task 3 (`a rebuild at another rate during a recording splits the raw track`).
2. **More talkers than slots, or a talker who leaves and returns.** Expected: the first four get `speaker_1..4` and keep them, the rest are counted, and no session id appears. The test is in Task 4 (`a fifth talker is counted, not recorded`, `a returning talker keeps their slot`).
3. **The Recording page goes away during a recording** (rotation, back, app in background). Expected: the recording keeps running in the session and the page re-attaches to its progress or result. The test is in Task 9 (`recreating the activity during a recording shows its progress`).
4. **Abort paths that race:** debug switched off while the consent dialog is open, disconnect during a recording, cancel pressed twice. Expected: no start after the dialog is confirmed with debug off, and `recording=false` sent exactly once (never on disconnect). Tests are in Task 5 (`cancel twice sends false once`, `disconnect sends nothing and discards`) and Task 7 (`confirming the dialog after debug was switched off starts nothing`).
5. **Not enough memory for the rings** (a 30 s recording on a low-RAM phone). Expected: `RecordingStart.Refused(OUT_OF_MEMORY)`, no flag sent, the session unaffected. The test is in Task 5 (`an allocation failure refuses the recording without sending the flag`).

Decided by the orchestrator (G2, overrides the spec's "Name und Typ des Headsets" for Bluetooth): Bluetooth devices (SCO, BLE headset/speaker, A2DP, hearing aid) go into `meta.json` and into every event (`route_changed` carries the type only) with their type only, never their product name, because users rename them ("Felix's AirPods"). Wired, USB and built-in devices keep their product name (a model name). Tests: Task 6 `a bluetooth headset is exported without its name`, Task 5 `route events carry the type only`.

---

## File Structure

**Core, new package `koeln.becker.nomla.mumble.diagnostics`** (`libraries/core/src/main/java/koeln/becker/nomla/mumble/diagnostics/`):

| File | Responsibility |
|---|---|
| `DiagnosticsTypes.kt` | Public API types: `SendStage`, `DiagnosticsSnapshot` and its readings, `RecordingState`, `RecordingStart`, `RecordingRefusal`, `DiagnosticCapture`, `CapturedTrack`, `TelemetryTable`, `DiagnosticEvent`, `PipelineDescription` |
| `SessionDiagnostics.kt` | Public interface the app uses |
| `DiagnosticsLimits.kt` | Internal constants (B3, B4, rates, intervals) |
| `LevelMeter.kt` | RMS/peak/clip/peak-hold of one tap |
| `PcmRing.kt` | Preallocated time-stamped PCM ring + `FrozenPcm` |
| `TelemetryRing.kt` | Preallocated float-column ring, `PacketRing` (Opus payloads), `EventLog` |
| `DiagnosticsHub.kt` | Per-session state: meters, armed rings, snapshot, events, freeze |
| `CaptureTaps.kt` | Capture-thread facade (raw mic, stages, commit, sent packets), `TapStage` |
| `PlaybackTaps.kt` | Playback/protocol-thread facade (speakers, mix, received packets) |
| `RecordingController.kt` | Main-thread recording lifecycle and the Mumble flag |
| `DiagnosticsSampler.kt` | 10 Hz main-thread sampler of network/transport stats while active |
| `export/WavWriter.kt`, `export/TelemetryCsv.kt`, `export/TrackAssembler.kt`, `export/SentTrackDecoder.kt` | Pure export helpers (time grid, WAV, CSV, Opus decode) |

**App, new package `koeln.becker.nomla.diagnostics`** (`app/src/main/java/koeln/becker/nomla/diagnostics/`): `DiagnosticZip.kt`, `DiagnosticShare.kt`, `AppInfo.kt`, `DiagnosticsViewModel.kt`, `DiagnosticsLiveFragment.kt`, `StageStripsView.kt`, `GateHistoryView.kt`, `StageListAdapter.kt`, `DiagnosticsRecordFragment.kt`, `RecordingConsentDialog.kt`, `TrackListAdapter.kt`, `TrackPlayer.kt`, `DebugPages.kt`. Layouts: `fragment_diagnostics_live.xml`, `fragment_diagnostics_record.xml`, `item_diagnostics_stage.xml`, `item_diagnostics_track.xml`, `dialog_recording_consent.xml`.

**Tools:** `tools/diagnostics-report/report.py`, `tools/diagnostics-report/test_report.py`.

---

## Wave 1: Measurement in core (inert; nothing visible)

### Task 1: APM statistics through the JNI bridge

**Files:**
- Modify: `libraries/core/src/main/cpp/webrtc_apm/nomla_apm.h` (after `nomla_apm_last_capture_level_dbfs`), `libraries/core/src/main/cpp/webrtc_apm/nomla_apm.cpp` (after line 297)
- Modify: `libraries/core/src/main/cpp/jni_webrtc_apm.cpp` (new `getStatistics`; method array 6 → 7)
- Modify: `libraries/core/src/main/cpp/jni_registrations.txt` (add a line)
- Modify: `libraries/core/src/main/java/koeln/becker/nomla/mumble/audio/native/WebRtcApmNative.kt` (interface + object)
- Modify: `libraries/core/src/testFixtures/java/koeln/becker/nomla/mumble/audio/capture/fakes/FakeWebRtcApmApi.kt`
- Modify: `libraries/core/src/main/java/koeln/becker/nomla/mumble/audio/capture/WebRtcApmPreprocessor.kt`
- Test: `libraries/core/src/main/cpp/tests/test_apm.c`, `libraries/core/src/main/cpp/tests/test_jni_bridges.cpp`, `libraries/core/src/test/java/koeln/becker/nomla/mumble/audio/native/JniRegistrationsTest.kt` (it reads the txt; no edit is expected), `libraries/core/src/test/java/koeln/becker/nomla/mumble/audio/capture/WebRtcApmPreprocessorTest.kt`
- Create: `.run/nativeHostTests.run.xml` (copy of `.run/verify.run.xml` with name `nativeHostTests` and task `:libraries:core:nativeHostTests`)

**Interfaces:**
- Produces (C): `typedef struct { float erl_db, erle_db, divergent_filter_fraction, delay_median_ms, delay_std_ms, delay_ms; } nomla_apm_stats;` and `int nomla_apm_get_statistics(nomla_apm *h, nomla_apm_stats *out) NOMLA_APM_NOEXCEPT;`. It returns 0, or `kNullPointerError` (-5) for a NULL handle or out. A field webrtc's `AudioProcessingStats` leaves empty is `NAN`. The function catches `(...)` and returns -1 (`kUnspecifiedError`).
- Produces (JNI): `WebRtcApmNative.getStatistics(handle: Long, out: FloatArray): Int`, signature `(J[F)I`. It writes `WebRtcApmApi.STATISTICS_COUNT = 6` floats in the struct's order and returns 6. It returns -8 for `out.size < 6` (array untouched) and -5 for a released handle.
- Produces (Kotlin): in `WebRtcApmPreprocessor`, `@Volatile var statisticsEveryFrames: Int = 0` (0 = off) and `fun copyStatistics(into: FloatArray): Boolean` (false before the first poll). Internally there is a preallocated `FloatArray(6)` and a `@Volatile` sequence counter (benign tearing is accepted and documented). It is polled inside `onCaptureFrame` when `statisticsEveryFrames > 0` and the frame counter hits it.

- [ ] **Step 1: Add `.run/nativeHostTests.run.xml`** with `create_new_file` (B16). Run it once to confirm it is green before any change: RUN-NATIVE → all tests pass.
- [ ] **Step 2: Write the failing native tests.** In `test_apm.c`:
  - `test_statistics_null`: `nomla_apm_get_statistics(NULL, &s) == -5` and `nomla_apm_get_statistics(h, NULL) == -5`.
  - `test_statistics_values`: run the file's existing echo scenario (far end before near end, ≥ 3 s at 48 kHz). Then `CHECK(nomla_apm_get_statistics(h, &s) == 0, …)`, every field `isnan(x) || isfinite(x)`, and `isfinite(s.erle_db) || isfinite(s.erl_db)`, with the message "AEC3 reports ERL or ERLE after 3 s of echo". If this webrtc version reports neither, replace that check with `CHECK(isfinite(s.delay_ms), "AEC3 reports a delay estimate after 3 s of echo")` and say so in the report. If that fails too, stop and report; do not weaken the test further (T1).

  In `test_jni_bridges.cpp`, `test_apm_statistics_bridge`:
  - a 5-element array returns -8 and stays unchanged (pre-filled with 7.0f);
  - a released handle returns -5;
  - a live handle returns 6.

  Register both in each file's `main`.
- [ ] **Step 3: RUN-NATIVE.** It is expected to fail to compile (`nomla_apm_get_statistics` undeclared).
- [ ] **Step 4: Implement** the C function (`h->apm->GetStatistics()`, map each `std::optional` to the float or `NAN`), the JNI `getStatistics` (fixed-size local `float[6]` + `SetFloatArrayRegion`, no allocation), the registration line `koeln/becker/nomla/mumble/audio/native/WebRtcApmNative getStatistics (J[F)I` in sorted position, the Kotlin `external` declaration, `FakeWebRtcApmApi.getStatistics` (answers a settable `statistics: FloatArray?`, -5 when null) and the preprocessor polling.
- [ ] **Step 5: Write the Kotlin tests** in `WebRtcApmPreprocessorTest`:
  - `statistics are not polled while off`: with `statisticsEveryFrames = 0`, 300 frames give 0 `getStatistics` calls on the fake.
  - `statistics are polled every N frames and copied`: with `statisticsEveryFrames = 100`, 250 frames give 2 calls, and `copyStatistics(out)` returns true with `out` equal to the fake's values.
  - `polling leaves the frame and probability unchanged`: frames and returned probabilities are identical with polling at 1 and at 0.
- [ ] **Step 6: RUN-NATIVE and RUN-TEST** (`WebRtcApmPreprocessorTest`, `JniRegistrationsTest`). All pass, including the `jni_registration` ctest and the sanitized variants.
- [ ] **Step 7: `ide_diagnostics`** on the changed Kotlin files; no errors.
- [ ] **Step 8: Commit** `feat(native): expose WebRTC APM statistics through JNI`.

### Task 2: Meters, rings and export primitives

**Files:**
- Create: `diagnostics/DiagnosticsLimits.kt`, `diagnostics/LevelMeter.kt`, `diagnostics/PcmRing.kt`, `diagnostics/TelemetryRing.kt`, `diagnostics/export/WavWriter.kt`, `diagnostics/export/TelemetryCsv.kt`, `diagnostics/export/TrackAssembler.kt` (all under `libraries/core/src/main/java/koeln/becker/nomla/mumble/`)
- Create: `diagnostics/DiagnosticsTypes.kt` (only `TelemetryTable`, `DiagnosticEvent`, `CapturedTrack` in this task)
- Test: `libraries/core/src/test/java/koeln/becker/nomla/mumble/diagnostics/LevelMeterTest.kt`, `PcmRingTest.kt`, `TelemetryRingTest.kt`, `export/WavWriterTest.kt`, `export/TelemetryCsvTest.kt`, `export/TrackAssemblerTest.kt`

**Interfaces (produces):**
- `internal object DiagnosticsLimits { val DURATIONS_SECONDS = listOf(5, 10, 20, 30); const val DEFAULT_SECONDS = 10; const val SPEAKER_SLOTS = 4; const val FRAME_NANOS = 10_000_000L; const val RING_MARGIN_FRAMES = 50; const val MAX_RAW_RATE = 48_000; const val NETWORK_SAMPLE_MS = 100L; const val STATS_EVERY_FRAMES = 100; const val PEAK_HOLD_FRAMES = 150; const val SILENCE_DBFS = -100f }`
- `internal class LevelMeter { fun measure(samples: ShortArray, length: Int); fun measure(samples: FloatArray, length: Int) /* full scale 1.0 */; val rmsDbfs: Float; val peakDbfs: Float; val peakHoldDbfs: Float; val clippedSamples: Long; fun reset() }`. The fields are `@Volatile`, one writer thread. A sample counts as clipped at `|s| >= 32767` (short) or `|s| >= 1f` (float).
- `internal class PcmRing(capacityFrames: Int, maxFrameSamples: Int) { fun write(samples: ShortArray, length: Int, sampleRate: Int, nanos: Long): Boolean; fun writeSilence(length: Int, sampleRate: Int, nanos: Long): Boolean; fun writeFloat(samples: FloatArray, length: Int, nanos: Long): Boolean /* clamps ×32767, rate 48000 */; val overflowFrames: Int; fun freeze(): FrozenPcm }`. Single producer. Frames past capacity are dropped and counted, never blocking. `freeze` is only called after disarm.
- `internal class FrozenPcm(val nanos: LongArray, val rates: IntArray, val lengths: IntArray, val samples: ShortArray, val frames: Int)`
- `internal class ColumnRing(val name: String, val columns: List<String>, capacityRows: Int) { fun write(nanos: Long, row: FloatArray): Boolean; fun freeze(t0Nanos: Long): TelemetryTable }`
- `internal class PacketRing(capacityPackets: Int, capacityBytes: Int) { fun write(data: ByteArray, offset: Int, length: Int, frameNumber: Long, terminator: Boolean, nanos: Long): Boolean; fun freeze(): FrozenPackets }`
- `internal class FrozenPackets(val nanos: LongArray, val frameNumbers: LongArray, val terminators: BooleanArray, val offsets: IntArray, val lengths: IntArray, val bytes: ByteArray, val count: Int, val dropped: Int)` (P4). Packet `i`'s payload is `bytes[offsets[i] until offsets[i] + lengths[i]]`; `dropped` counts writes refused for lack of packets or bytes.
- `internal class EventLog(capacity: Int = 10_000) { @Synchronized fun add(nanos: Long, name: String, value: String); fun freeze(t0Nanos: Long): List<DiagnosticEvent> }` (main/control threads only)
- `public class TelemetryTable(public val name: String, public val columns: List<String>, public val timesMs: DoubleArray, public val values: FloatArray, public val rows: Int)` (row-major; `Float.NaN` = no value)
- `public data class DiagnosticEvent(val timeMs: Double, val name: String, val value: String)`
- `public class CapturedTrack(public val id: String, public val sampleRate: Int, public val samples: ShortArray, public val sendSide: Boolean)`
- `internal object WavWriter { fun write(out: OutputStream, samples: ShortArray, sampleRate: Int) }` (RIFF/WAVE, PCM 16-bit mono LE, 44-byte header)
- `internal object TelemetryCsv { fun write(table: TelemetryTable, out: Appendable) }`: header `t_ms,<columns>`, `t_ms` with 1 decimal, values with up to 2 decimals, NaN written as empty, `\n` line ends, `Locale.ROOT`.
- `internal object TrackAssembler { fun assemble(id: String, frozen: FrozenPcm, t0Nanos: Long, durationNanos: Long, sendSide: Boolean): List<CapturedTrack> }`. It places each frame at `round((nanos - t0)/FRAME_NANOS) * rate/100` and fills gaps with silence (B6). It returns one track per contiguous rate, with ids `id`, `id_2`, … Every track spans the whole recording: `durationNanos × its own rate` samples, silent outside its own segment, so tracks align without an offset (P5).

- [ ] **Step 1: Write the failing tests:**
  - `LevelMeterTest`:
    - `full-scale square reads 0 dBFS and counts clipped samples`: 480 × 32767 gives rms ≈ 0f (±0.01), and `clippedSamples == 480`.
    - `silence reads -100`.
    - `-20 dBFS sine reads -23 dB rms and -20 dB peak` (±0.1).
    - `peak hold keeps the peak for 150 frames then follows`.
    - `float input at 0.1 reads -20 dBFS`.
  - `PcmRingTest`:
    - `write past capacity drops and counts, never throws`: capacity 3, 5 writes give 2 false and `overflowFrames == 2`.
    - `freeze returns frames in order with their rates and times`.
    - `writeFloat clamps and scales`: 1.5f becomes 32767 and -0.5f becomes -16384 (±1).
    - `writes allocate nothing`: wrap 1000 writes in `ThreadMXBean.getThreadAllocatedBytes` (`com.sun.management.ThreadMXBean`) and assert the delta is < 1024.
  - `TelemetryRingTest`: `ColumnRing` keeps columns and NaN; `PacketRing` keeps payload bytes and drops when bytes run out; `EventLog` caps at capacity.
  - `WavWriterTest`: header fields (`"RIFF"`, size = 36 + 2n, `"fmt "` 16, format 1, channels 1, the given rate, byte rate = 2·rate, block align 2, bits 16, `"data"` 2n), and samples little-endian.
  - `TelemetryCsvTest`: `header and NaN as empty`, with expected text `"t_ms,a,b\n0.0,1.5,\n10.0,,2\n"`.
  - `TrackAssemblerTest`:
    - `frames land on the 10 ms grid with silence in gaps`: frames at 0, 10 and 40 ms give 50 ms of track with zeros in 20–40 ms.
    - `a rate change splits the track`: 48000 then 16000 gives ids `1_mic_raw` and `1_mic_raw_2` with those rates.
    - `split tracks span the whole recording`: with a 100 ms duration, 48000 for the first 50 ms then 16000, `1_mic_raw` has 4800 samples (zeros after 50 ms) and `1_mic_raw_2` has 1600 samples (zeros before 50 ms) (P5).
    - `frames before t0 or after the duration are dropped`.
- [ ] **Step 2: RUN-TEST each class.** They are expected to fail (unresolved references).
- [ ] **Step 3: Implement** the signatures above.
- [ ] **Step 4: RUN-TEST each class.** All pass.
- [ ] **Step 5: `ide_diagnostics`** on all new files.
- [ ] **Step 6: Commit** `feat(diagnostics): add level meters, lock-free rings and WAV/CSV export`.

### Task 3: Hub and capture taps

**Files:**
- Create: `diagnostics/DiagnosticsHub.kt`, `diagnostics/CaptureTaps.kt`
- Extend: `diagnostics/DiagnosticsTypes.kt` (`SendStage`, `StageReading`, `GateReading`, `EchoReading`, `PipelineDescription`, and the complete `DiagnosticsSnapshot` with every field of Task 5's definition, P7. `PlaybackReading`, `SpeakerReading` and `NetworkReading` are declared here as empty placeholders that Task 4 fills in; until then the snapshot carries `null`/`emptyList()` for them. Task 5 does not redefine the snapshot.)
- Modify: `audio/AudioInput.kt` (constructor `taps: CaptureTaps? = null`; time the read only when `taps?.isActive == true`)
- Modify: `audio/capture/CapturePipeline.kt` (constructor `taps: CaptureTaps? = null`; `onStage(RESAMPLED)` after resampling when a resampler is set; `onStage(GATED, …)` after the input mode decides and before the boost, with the frame on an open gate and silence on a closed one. There is no separate post-boost PCM tap: the `6_sent` track comes from the decoded packets (B7), and the live "Gesendet" meter measures the boosted frame in `commit`.)
- Modify: `audio/capture/CapturePreprocessorFactory.kt` (`create(…, taps: CaptureTaps? = null)` inserts `TapStage(APM)` after the APM and `TapStage(DENOISED)` after the denoiser, only when `taps != null`). `CaptureChain` gets `val apm: WebRtcApmPreprocessor? = null, val denoiser: CapturePreprocessor? = null`.
- Modify: `audio/CaptureWiring.kt` (`wire(…, taps: CaptureTaps? = null)`; the "not running" checks become `chain.denoiser == null` and `chain.farEndSink == null`, so tap stages cannot hide a missing denoiser)
- Modify: `audio/VoiceTransmitter.kt` (constructor `taps: CaptureTaps? = null`; `taps?.commit(processed, muted = muteState.isMuted)` after the talking decision; `taps?.onOpusPacket(packetBytes, opusOffset, encoder.encodedLength, frameNumber, terminator)` in `send`, where `opusOffset` is `packet.size()` read before `getEncodedData`)
- Modify: `audio/inputmode/ActivityInputMode.kt` (`internal val detector`)
- Modify: `audio/ManagedAudio.kt` (`AudioHost` gets `val diagnostics: DiagnosticsHub? = null`)
- Modify: `audio/AudioHandler.kt` (build `CaptureTaps(hub, pushToTalk = params.inputMode is ToggleInputMode, vad = (params.inputMode as? ActivityInputMode)?.detector)` when `host.diagnostics != null`, pass it to `AudioInput`, `CaptureWiring.wire` and `VoiceTransmitter`, then `taps.attachApm(wiring.apm)` and `hub.onPipelineBuilt(PipelineDescription(...))`. `CaptureWiring.Wiring` gets `val apm: WebRtcApmPreprocessor?`.)
- Modify: `audio/capture/PcmCaptureSource.kt` (`fun describe(): CaptureDescription = CaptureDescription(sampleRate)` default; `AndroidAudioRecordSource` overrides it with `record.audioSource`, `record.channelCount`, `record.bufferSizeInFrames` and the class names of `effects` that are enabled)
- Test: `diagnostics/CaptureTapsTest.kt`, `audio/capture/CapturePipelineTest.kt` (add), `audio/CaptureWiringTest.kt` (add), `audio/VoiceTransmitterTest.kt` (add), `audio/capture/CapturePreprocessorFactoryTest.kt` (add)

**Interfaces:**
- Consumes: Task 1 `WebRtcApmPreprocessor.statisticsEveryFrames` and `copyStatistics`; Task 2 rings and meters.
- Produces:
  - `public enum class SendStage(public val trackId: String) { MIC_RAW("1_mic_raw"), RESAMPLED("2_resampled"), APM("3_apm"), DENOISED("4_denoised"), GATED("5_gate"), SENT("6_sent") }`
  - `public data class CaptureDescription(val sampleRate: Int, val audioSource: Int? = null, val channels: Int = 1, val bufferFrames: Int? = null, val platformEffects: List<String> = emptyList())`
  - `public data class PipelineDescription(val capture: CaptureDescription, val settings: Map<String, String>, val stagesPresent: Set<SendStage>)`. `settings` comes from `AudioConfig`: sample rate, noise suppression, RNNoise limit, Speex dB, echo, VAD config, boost, bitrate, frames per packet, half duplex, Android effects, voice-call source, `opus_fec=1`, `opus_loss_percent=10`, `opus_dtx=0` (from `OpusEncoder`).
  - `internal class DiagnosticsHub(clock: () -> Long = System::nanoTime, allocate: (seconds: Int, t0Nanos: Long) -> ArmedRecording = ::ArmedRecording)`. `allocate` is the seam that Task 5's out-of-memory test overrides (P7).
    - `val isActive: Boolean` (one volatile read)
    - `val active: StateFlow<Boolean>` (P9). It changes on the first `meter()`/`arm` and after the last close/disarm. It is set from the thread that opens or closes, never from an audio thread.
    - `fun meter(): AutoCloseable` (reference count)
    - `fun arm(seconds: Int)` (allocates every ring; may throw `OutOfMemoryError`)
    - `fun disarm(): ArmedRecording?`
    - `fun snapshot(): DiagnosticsSnapshot` (the type is complete in this task; this task fills `stages`, `gateHistory`, `gate`, `echo` and `capture`; Task 4 fills `playback`, `speakers` and `network`)
    - `fun event(name: String, value: String = "")`
    - `fun onPipelineBuilt(description: PipelineDescription)` (also adds `pipeline_rebuilt` while armed)
    - `internal fun stageMeter(stage: SendStage): LevelMeter`
    - `internal val armed: ArmedRecording?` (`@Volatile`)
  - `internal class ArmedRecording(…)`: the rings for every `SendStage` (the `SENT` stage as a `PacketRing`), the `frames` `ColumnRing` with the columns below, `t0Nanos`, `durationNanos`.
  - `internal class CaptureTaps(hub: DiagnosticsHub, pushToTalk: Boolean, vad: VoiceActivityDetector?)`:
    - `val isActive: Boolean`
    - `fun attachApm(apm: WebRtcApmPreprocessor?)` (sets `statisticsEveryFrames = DiagnosticsLimits.STATS_EVERY_FRAMES` while active, 0 otherwise, checked at commit). `commit` syncs this **before** its inactive early return (P1): `val active = hub.isActive; if (active != apmPolling) { apm?.statisticsEveryFrames = if (active) STATS_EVERY_FRAMES else 0; apmPolling = active }; if (!active) return`. This is one volatile read plus one plain field read per frame.
    - `fun onMicRaw(frame: ShortArray, read: Int, expected: Int, sampleRate: Int, readNanos: Long)`
    - `fun onStage(stage: SendStage, frame: ShortArray, length: Int)` (meter when metering; copy into a preallocated per-stage scratch when armed)
    - `fun commit(frame: CaptureFrame, muted: Boolean)`. It computes `sendAllowed = !muted && (!pushToTalk || frame.transmit)`, writes every stage's scratch or silence to its ring, measures the boosted frame on the `SENT` meter, and writes one `frames` row.
    - `fun onOpusPacket(data: ByteArray, offset: Int, length: Int, frameNumber: Long, terminator: Boolean)` (only when armed and `sendAllowed` was true for the last commit)
    - `internal class TapStage(val stage: SendStage, private val taps: CaptureTaps) : CapturePreprocessor`, whose `process` returns null and never writes the frame.
- `frames` columns (B10): `send_allowed, transmit, muted, read_samples, expected_samples, read_ms, silenced, <stage>_rms_dbfs, <stage>_peak_dbfs, <stage>_clipped` for each `SendStage`, `vad_probability, vad_level_dbfs, vad_floor_dbfs, vad_speech_dbfs, vad_threshold_dbfs, vad_open, vad_holding, vad_too_close, apm_erl_db, apm_erle_db, apm_divergent_fraction, apm_delay_median_ms, apm_delay_std_ms, apm_delay_ms`. Silenced comes from `CaptureState.Silenced` via `hub.event` and a volatile flag.

- [ ] **Step 1: Write the failing tests:**
  - `CapturePipelineTest`, `inert taps leave every output identical`: for each `InputMode` used in the existing tests, with and without a resampler, feed 500 random frames (seeded) through a pipeline with `taps = CaptureTaps(DiagnosticsHub(), …)` (never metered, never armed) and through one without taps. Every `CaptureFrame` (samples, transmit, probability) is equal. Build the preprocessor chains through `CapturePreprocessorFactory` with the fake APIs (`FakeWebRtcApmApi` and the Speex/RNNoise fakes), once with `taps` passed to `create` and once without: APM only, APM + Speex, APM + RNNoise, and Speex/RNNoise without APM. That puts the `TapStage`s inside the pinned path (T3, B5).
  - `CapturePipelineTest`, `armed taps leave every output identical`: the same with metering on and a recording armed.
  - `CaptureWiringTest`, `a missing denoiser is still reported with taps in the chain`: with the noise mode on and the fake factory failing, the log contains "is not running", with taps.
  - `CapturePreprocessorFactoryTest`, `tap stages follow APM and denoiser only when taps are given`.
  - `CaptureTapsTest`:
    - `inert taps copy nothing`: frames are not copied into scratch (the scratch stays zero), and allocation in 1000 frames is < 1024 bytes (ThreadMXBean as in Task 2).
    - `muted frames are silence on every send ring but measured on frames.csv`: arm, commit 10 frames with `muted=true`, then 10 with false. The rings for MIC_RAW..GATED hold zeros in the first 10 and the input in the next 10, and the `frames` rows have `send_allowed` 0 then 1.
    - `push-to-talk without the key records silence, voice activity with a closed gate does not`: PTT with `transmit=false` gives silent rings; VAD with `transmit=false` keeps APM/DENOISED/MIC_RAW content and a silent GATED ring.
    - `opus packets are recorded only while sending is allowed`.
    - `a rebuild at another rate during a recording splits the raw track` (Review Focus 1): arm, feed raw frames at 48000, call `hub.onPipelineBuilt` with a 16000 description, feed raw frames at 16000, then disarm, freeze and `TrackAssembler`. The result is two raw tracks at 48000 and 16000, and the events contain `pipeline_rebuilt`.
    - `apm statistics land in frames rows once per second`.
    - `apm polling stops when the hub goes inactive` (P1): open `meter()`, commit one frame (`statisticsEveryFrames == 100`), close it, commit one frame, and `statisticsEveryFrames == 0`.
    - `the hub reports its activity`: `active` is false, true after `meter()`, and false after the handle is closed (P9).
  - `VoiceTransmitterTest`, `commit sees the mute state and the decision of every frame`.
- [ ] **Step 2: RUN-TEST each class.** The new tests fail.
- [ ] **Step 3: Implement** the files and the wiring listed above. Hot-path rule: every tap begins with `if (!hub.isActive) return`.
- [ ] **Step 4: RUN-TEST** `CapturePipelineTest`, `CaptureWiringTest`, `CapturePreprocessorFactoryTest`, `CaptureTapsTest`, `VoiceTransmitterTest`, `AudioInputTest`, `AudioHandlerTest`, `AudioHandlerCaptureStateTest`. All pass, existing tests unchanged.
- [ ] **Step 5: `ide_diagnostics`** on all changed files.
- [ ] **Step 6: Commit** `feat(diagnostics): tap every capture stage without touching the signal`.

### Task 4: Playback, receive and network taps

**Files:**
- Create: `diagnostics/PlaybackTaps.kt`
- Extend: `diagnostics/DiagnosticsTypes.kt` (fill the `PlaybackReading`, `SpeakerReading` and `NetworkReading` placeholders from Task 3), `diagnostics/DiagnosticsHub.kt` (playback, mix (`7_mix`), speaker, received-packet and network rings, plus the speaker slot table in `ArmedRecording`; `snapshot` fills those readings)
- Modify: `audio/PlaybackMix.kt` (constructor `taps: PlaybackTaps? = null`; `taps?.onSpeaker(speech)` for each alive speech after `decode()`)
- Modify: `audio/AudioOutputSpeech.kt` (plain counters, playback-thread or `jitterLock` owned: `concealedFrames`, `decodeErrors`, `lostPackets`, `latePackets` from frame-number gaps on arrival (B14), `lastAvailablePackets`). Gaps are counted in packets, not frames (T4). In `addFrameToBuffer`, with `packetFrames = samples / AudioHandler.FRAME_SIZE` (already computed there) and `expectedNext = lastFrameNumber + lastPacketFrames`: a packet with `frameNumber > expectedNext` adds `(frameNumber - expectedNext) / packetFrames` to `lostPackets`, and one with `frameNumber < expectedNext` adds 1 to `latePackets` without moving `expectedNext` back.
- Modify: `audio/AudioOutput.kt` (constructor `taps: PlaybackTaps? = null`; after each `fetchAudio`, `taps?.onMix(pcm, bufferSize, played, track.underrunCount, track.bufferSizeInFrames)`; latency estimate from `track.getTimestamp` at most every 50 mixes while active)
- Modify: `audio/AudioHandler.kt` (`PlaybackTaps(hub)` into `AudioOutput`; `onVoicePacket` calls `taps?.onPacketReceived(packet.session, packet.frameNumber, packet.opusLength, packet.isTerminator)` before queueing)
- Modify (protocol): `libraries/protocol/src/main/kotlin/koeln/becker/nomla/mumble/net/UdpTransport.kt` (`val socketBuffers: SocketBuffers? get() = null`), `NomlaUdp.kt` (override from `socket?.sendBufferSize/receiveBufferSize`, catching `SocketException` → null), `ProtocolSession.kt` (`fun transportStats(socket: SocketBuffers?): TransportStats`), `NomlaConnection.kt` (`fun transportStats(): TransportStats?`, null unless synchronized)
- Create (protocol): `libraries/protocol/src/main/kotlin/koeln/becker/nomla/mumble/net/TransportStats.kt`
- Create: `diagnostics/DiagnosticsSampler.kt`
- Test: `diagnostics/PlaybackTapsTest.kt`, `audio/PlaybackMixTest.kt` (add), `audio/AudioOutputSpeechTest.kt` (add), `libraries/protocol/src/test/kotlin/koeln/becker/nomla/mumble/net/ProtocolSessionTransportStatsTest.kt`, `diagnostics/DiagnosticsSamplerTest.kt`

**Interfaces:**
- Produces:
  - `internal data class SocketBuffers(val sendBytes: Int, val receiveBytes: Int)`
  - `internal data class TransportStats(val usingUdp: Boolean, val tcpPingMs: Float, val tcpPingVarMs: Float, val udpPingMs: Float, val udpPingVarMs: Float, val good: Int, val late: Int, val lost: Int, val resync: Int, val remoteGood: Int, val remoteLate: Int, val remoteLost: Int, val remoteResync: Int, val socket: SocketBuffers?)`. Counters come from `CryptState`, pings from the two `PingStats`. Racy reads are accepted (documented).
  - `internal class PlaybackTaps(hub: DiagnosticsHub)`: `fun onSpeaker(speech: AudioOutputSpeech)`, `fun onMix(pcm: ShortArray, length: Int, played: Boolean, underruns: Int, trackFrames: Int)`, `fun onPacketReceived(session: Int, frameNumber: Long, bytes: Int, terminator: Boolean)`.
  - Speaker slots (P6, B4): the slot table (`AtomicIntegerArray(SPEAKER_SLOTS)` of sessions) and `speakersWithoutTrack` live in `ArmedRecording`, not in `PlaybackTaps`, so they survive a pipeline rebuild. Only `onPacketReceived` (protocol thread) assigns a slot, on a session's first packet; a fifth talker increments `speakersWithoutTrack` once. `onSpeaker` (playback thread) only reads the table and skips a speaker without a slot.
  - Packets (P3): received packets go into a `received` `ColumnRing` written only by `onPacketReceived` (protocol thread). Sent packets stay in Task 3's `SENT` `PacketRing`, written only by the capture thread. `packets.csv` is built at freeze by merging both, sorted by time.
  - The playback mix is recorded as track `7_mix` (receive side, 48 kHz) (P10).
  - `internal class DiagnosticsSampler(hub: DiagnosticsHub, scope: CoroutineScope, transport: () -> TransportStats?, extra: () -> Map<String, Float> = { emptyMap() })` with `start()` and `stop()`. `start()` collects `hub.active` and runs its sampling loop only while it is true. While the hub is inactive no coroutine is scheduled, so nothing ticks with debug off (P9). While active it writes a `network` row every `NETWORK_SAMPLE_MS`, with columns `using_udp, tcp_ping_ms, tcp_ping_var_ms, udp_ping_ms, udp_ping_var_ms, good, late, lost, resync, remote_good, remote_late, remote_lost, remote_resync, so_sndbuf, so_rcvbuf`. It adds a `transport_changed` event on a UDP↔TCP flip.
  - `playback` columns: `played, mix_rms_dbfs, mix_peak_dbfs, mix_clipped, underruns, track_buffer_frames, output_latency_ms, active_speakers`. `speakers` columns: `speaker, available_packets, concealed_frames, decode_errors, lost_packets, late_packets, rms_dbfs, peak_dbfs`. `packets` columns: `direction (0 sent, 1 received), speaker (0 for self), frame_number, bytes, terminator`.

- [ ] **Step 1: Write the failing tests:**
  - `PlaybackTapsTest`:
    - `a fifth talker is counted, not recorded` (Review Focus 2): sessions 7, 9, 11, 13, 15 give 4 speaker rings with content and `speakersWithoutTrack == 1`.
    - `a returning talker keeps their slot`: session 9 ends and returns, and it is still slot 2.
    - `a rebuild during a recording keeps the slots` (P6): assign sessions 7 and 9 through one `PlaybackTaps`, then create a new `PlaybackTaps` on the same hub (a rebuild), and session 9 is still slot 2 there.
    - `slots are assigned on first packet, not on playback`: `onSpeaker` for a session without a packet records nothing and assigns no slot.
    - `sent and received packets merge into one table`: sent packets via `CaptureTaps.onOpusPacket` and received via `onPacketReceived`, interleaved in time, freeze into one `packets` table sorted by `t_ms` with the right `direction` (P3).
    - `speaker slots hold no session id after freeze`: the frozen ids are `speaker_1..4`, and no frozen value equals 7, 9, 11 or 13 in a `speaker` column.
    - `inert playback taps allocate nothing`.
  - `PlaybackMixTest`, `mix output is identical with inert and armed taps`.
  - `AudioOutputSpeechTest`:
    - `a gap in frame numbers counts lost packets, an older frame counts late`, with 20 ms packets (2 frames each): arrivals 0, 4, 6, 2 give lost == 1 and late == 1 (T4).
    - `the same with 10 ms packets`: arrivals 0, 2, 3, 1 give lost == 1 and late == 1.
    - `consecutive 60 ms packets count nothing`: arrivals 0, 6, 12, 18 give lost == 0 and late == 0.
    - `concealed frames are counted`.
  - `ProtocolSessionTransportStatsTest`, `ping averages and crypt counters are reported`, using the existing `ProtocolSession` test seams (`Link` fake).
  - `DiagnosticsSamplerTest`, `samples every 100 ms only while active, and logs a transport change`, with `StandardTestDispatcher`: 1 s active gives 10 rows, inactive gives 0, and a flip gives the event.
  - `DiagnosticsSamplerTest`, `an inactive hub schedules nothing` (P9): after `start()` with the hub inactive, the test scheduler is idle (no pending task), and becomes busy only once the hub is active.
- [ ] **Step 2: RUN-TEST each.** The new tests fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: RUN-TEST** the new classes plus `AudioOutputTest`, `AudioOutputStopTest`, `AudioOutputSpeechTest`, `PlaybackMixTest`, `AudioHandlerTest`, and the protocol `NomlaUdp*`/`ProtocolSession*` tests. All pass.
- [ ] **Step 5: `ide_diagnostics`** on all changed files.
- [ ] **Step 6: Commit** `feat(diagnostics): tap playback, talkers and transport statistics`.

**Wave 1 ends; the orchestrator runs `verify`.** The app is unchanged for users: no hub is created yet (`AudioHost.diagnostics` stays null until Task 5), so every tap is null.

---

## Wave 2: Session API, recording control, export

### Task 5: `SessionDiagnostics` and the recording lifecycle

**Files:**
- Create: `diagnostics/SessionDiagnostics.kt`, `diagnostics/RecordingController.kt`, `diagnostics/export/SentTrackDecoder.kt`
- Extend: `diagnostics/DiagnosticsTypes.kt` (`RecordingState`, `RecordingStart`, `RecordingRefusal`, `AbortReason`, `DiagnosticCapture`, `RouteInfo`). `DiagnosticsSnapshot` already exists from Task 3 (P7) and is repeated below for reference only; do not redefine it.
- Modify (protocol): `protocol/ServerCommands.kt` (`fun setSelfRecording(recording: Boolean) = userState(Mumble.UserState.newBuilder().setRecording(recording))`)
- Modify: `libraries/core/src/main/java/koeln/becker/nomla/mumble/Session.kt` (`public val diagnostics: SessionDiagnostics`)
- Modify: `libraries/core/src/main/java/koeln/becker/nomla/mumble/NomlaSession.kt`:
  - one `DiagnosticsHub` passed in `AudioHost`;
  - a `RecordingController` with `commands = { commands.takeIf { connection?.isSynchronized == true } }`, `serverSettings = { serverModel.value?.serverSettings }`, `selfRecording = { serverModel.value?.self?.isRecording }` and `route = { RouteInfo.current(context, audioSession.activeDevice) }`, scheduled with `mainHandler`, with the session's main-thread scope and `exportDispatcher = Dispatchers.Default` (T5, P15);
  - `lost()` calls `recordings.onDisconnected()` before `audioSession.stop()`;
  - a `DiagnosticsSampler` over `connection?.transportStats()`, started once; it samples only while `hub.active` is true (P9);
  - hub events from `AudioSession.setRoute` (`route_changed`, value = the device type constant only, never a name, G2), `onConnectionWarning` (`connection_warning`), `AudioSession.apply` (`settings_changed`, value = the changed setting keys only), capture silenced (`capture_silenced`), and an `AudioManager` mode listener (`audio_mode`, B14). The mode listener is registered only while `hub.active` is true and unregistered when it turns false (P9);
  - hub events `talk_start`/`talk_end` from the session's own talking-state change (already on main via `onTalkingStateChanged`), and `connected`, `reconnecting` and `disconnected` from the lifecycle, each with an empty value (G1).
- Modify: `libraries/core/src/main/java/koeln/becker/nomla/mumble/session/AudioSession.kt` (an `onEvent: (String, String) -> Unit = { _, _ -> }` constructor parameter for the events above)
- Modify: `libraries/core/src/testFixtures/java/koeln/becker/nomla/mumble/testutil/FakeSession.kt` (`override val diagnostics: FakeSessionDiagnostics`)
- Test: `diagnostics/RecordingControllerTest.kt`, `diagnostics/export/SentTrackDecoderTest.kt`, `diagnostics/RouteInfoTest.kt`, `libraries/protocol/src/test/kotlin/koeln/becker/nomla/mumble/protocol/ServerCommandsTest.kt` (add), `libraries/core/src/test/java/koeln/becker/nomla/mumble/NomlaSessionDiagnosticsTest.kt` (with `testutil/NomlaSessionHarness`)

**Interfaces (produces; public unless noted):**
```kotlin
public interface SessionDiagnostics {
    /** Metering runs while at least one handle is open, or while a recording runs. Main thread. */
    public fun meter(): AutoCloseable
    /** A fresh reading; allocates; call at most ~20×/s from the UI. */
    public fun snapshot(): DiagnosticsSnapshot
    public val recording: StateFlow<RecordingState>
    public fun startRecording(seconds: Int): RecordingStart
    /** Ends a running recording without keeping it; a no-op otherwise. */
    public fun cancelRecording()
    /** Drops a finished capture. */
    public fun discardRecording()
}
public sealed interface RecordingState {
    public data class Idle(val aborted: AbortReason? = null) : RecordingState
    public data class Recording(val startedElapsedMs: Long, val seconds: Int) : RecordingState
    public class Finished(public val capture: DiagnosticCapture) : RecordingState
}
public enum class AbortReason { CANCELLED, DISCONNECTED }
public sealed interface RecordingStart {
    public data object Started : RecordingStart
    public data class Refused(val reason: RecordingRefusal) : RecordingStart
}
public enum class RecordingRefusal { NOT_CONNECTED, SERVER_FORBIDS, ALREADY_RECORDING, INVALID_DURATION, OUT_OF_MEMORY }
public class DiagnosticCapture(
    public val startedAtEpochMs: Long, public val durationMs: Long,
    public val tracks: List<CapturedTrack>, public val tables: List<TelemetryTable>,
    public val events: List<DiagnosticEvent>, public val pipelines: List<PipelineDescription>,
    public val serverShowedRecording: Boolean, public val speakersWithoutTrack: Int,
    public val droppedFrames: Int,
    /** Captured once at recording start (P15); Bluetooth without a name (G2). */
    public val route: RouteInfo?,
)
public data class RouteInfo(
    val deviceType: Int?, val productName: String?, val streamVolume: Int, val streamMaxVolume: Int, val audioMode: Int,
) {
    public companion object {
        /** productName is null for every Bluetooth type (SCO, BLE headset/speaker, A2DP, hearing aid). */
        public fun current(context: Context, device: CommunicationDevice?): RouteInfo
    }
}
// Defined in Task 3; repeated for reference.
public data class DiagnosticsSnapshot(
    val stages: List<StageReading>, val gateHistory: BooleanArray /* last 300 frames */, val gate: GateReading?,
    val echo: EchoReading?, val playback: PlaybackReading?, val speakers: List<SpeakerReading>,
    val network: NetworkReading?, val capture: CaptureDescription?,
)
public data class StageReading(val stage: SendStage, val active: Boolean, val rmsDbfs: Float, val peakDbfs: Float, val peakHoldDbfs: Float, val clippedSamples: Long, val changeDb: Float?)
```
`EchoReading` has ERL, ERLE, the delays, the divergent fraction and `levelChangeDb` (APM out − in, spec designer decision). `GateReading` mirrors `MeterReading` plus `probability`. `changeDb` is the stage RMS minus the previous active stage's. The `internal class RecordingController(hub, commands: () -> ServerCommands?, serverSettings: () -> ServerSettings?, selfRecording: () -> Boolean?, route: () -> RouteInfo?, postDelayed: (Long, () -> Unit) -> () -> Unit, elapsed: () -> Long, epochMs: () -> Long, decoder: SentTrackDecoder, scope: CoroutineScope, exportDispatcher: CoroutineDispatcher = Dispatchers.Default)` implements `startRecording`, `cancelRecording`, `discardRecording`, `recording` and `onDisconnected()`. It reads `route()` once at a successful start (P15). `SentTrackDecoder(api: OpusDecoderApi = OpusDecoderNative).decode(packets: FrozenPackets, t0Nanos: Long, durationNanos: Long): CapturedTrack` (id `6_sent`, 48 kHz) places each packet at its first captured frame, `nanos − packetFrames × FRAME_NANOS` (G6, B7). The freeze and decode run in `scope` on `exportDispatcher`; `Finished` is published back in `scope` on the main thread (T5).

- [ ] **Step 1: Write the failing tests** (`RecordingControllerTest` with a fake `ServerCommands` sender recording messages, a manual scheduler, and one `StandardTestDispatcher` passed as both `scope` and `exportDispatcher`, so `Finished` is reached with `advanceUntilIdle()`):
  - `refuses without a synchronized connection` → `Refused(NOT_CONNECTED)`, no message.
  - `refuses when the server forbids recording and never sends the flag`: `ServerSettings(recordingAllowed=false)` gives `Refused(SERVER_FORBIDS)` and 0 `UserState` messages.
  - `a server without the field counts as allowed`: `serverSettings = null` gives `Started`.
  - `start sends recording=true once, the end sends recording=false once`: after the scheduled 10 s the messages are `[true, false]` and the state is `Finished`.
  - `cancel twice sends false once` (Review Focus 4) gives messages `[true, false]` and `Idle(CANCELLED)`.
  - `disconnect sends nothing and discards`: `onDisconnected()` gives messages `[true]`, `Idle(DISCONNECTED)`, and `hub.armed == null`.
  - `an allocation failure refuses the recording without sending the flag` (Review Focus 5): `DiagnosticsHub(allocate = { _, _ -> throw OutOfMemoryError() })` gives `Refused(OUT_OF_MEMORY)` and 0 messages (P7).
  - `durations outside 5/10/20/30 are refused` → `INVALID_DURATION`.
  - `a second start while recording is refused` → `ALREADY_RECORDING`.
  - `a new recording drops the previous capture`.
  - `serverShowedRecording follows the model's self flag at the end`.
  - `the route is captured at the start`: `route` returns A at the start and B later, and the `Finished` capture has route A (P15).

  `ServerCommandsTest`, `setSelfRecording sends a UserState with only recording`: there is no session field, and `recording` equals the argument.

  `SentTrackDecoderTest` (fake `OpusDecoderApi` that returns ramp samples), `decoded packets land at their first captured frame, silence elsewhere`: a 20 ms packet sent at t0 + 60 ms fills 40–60 ms (G6).

  `RouteInfoTest` (Robolectric): `a bluetooth device has no product name`, for SCO, BLE headset, BLE speaker, A2DP and hearing aid; `a wired, usb or built-in device keeps its product name` (G2).

  `NomlaSessionDiagnosticsTest`, `a disconnect during a recording leaves the state idle-disconnected`, and `the session hands its hub to the audio host`. Also:
  - `route events carry the type only` (G2): a route change to a Bluetooth SCO device named "Felix's AirPods" logs `route_changed` with the type constant and no part of the name.
  - `no server, user, channel or secret text reaches the hub` (T6; this is the real-code-path privacy check): with `NomlaSessionHarness` on a config with server `secret.example.org`, user `Alice`, channel `Lounge`, password `hunter2` and token `tok-123`, connect, arm, change settings and the route, talk, warn, then disconnect. No event name or value and no `PipelineDescription.settings` key or value contains any of these strings or any session id.
  - `talk and connection events are logged` (G1): `connected`, `talk_start`, `talk_end` and `disconnected` appear in this order.
  - `the sampler and the mode listener run only while active` (P9): with the hub inactive, no `network` row is written and no mode listener is registered; after `meter()` both run; after the handle is closed both stop.
- [ ] **Step 2: RUN-TEST each.** They fail.
- [ ] **Step 3: Implement**, including `FakeSessionDiagnostics` in testFixtures: `recording: MutableStateFlow`, `var startResult: RecordingStart = Started`, `startCalls: MutableList<Int>`, `cancelCalls: Int`, `discardCalls: Int`, `meterHolders: Int` (the number of `meter()` handles open now), `var nextSnapshot`.
- [ ] **Step 4: RUN-TEST** the new classes plus `NomlaSessionAudioTest`, `NomlaSessionConnectionTest`, `NomlaSessionCharacterizationTest`, `AudioControllerTest` and `ServerCommandsTest`. All pass.
- [ ] **Step 5: `ide_diagnostics`** on all changed files.
- [ ] **Step 6: Commit** `feat(diagnostics): session API and recording flag lifecycle`.

### Task 6: ZIP export and share

**Files:**
- Create: `app/src/main/java/koeln/becker/nomla/diagnostics/AppInfo.kt`, `DiagnosticZip.kt`, `DiagnosticShare.kt`
- Modify (core, P11): `libraries/core/src/main/java/koeln/becker/nomla/mumble/diagnostics/DiagnosticsTypes.kt` (or a new `diagnostics/export/DiagnosticExport.kt` in the same package): public `DiagnosticCapture.writeTrack(track: CapturedTrack, out: OutputStream)`, `writeTable(table: TelemetryTable, out: Appendable)` and `writeEvents(out: Appendable)` (`events.csv`: `t_ms,name,value`, same number format as `TelemetryCsv`). `WavWriter`, `TelemetryCsv` and `EventLog` stay internal. Add a core test `DiagnosticCaptureExportTest` for the three functions.
- Modify: `app/src/main/res/xml/file_provider_paths.xml` (add `<cache-path name="shared_diagnostics" path="shared_diagnostics/" />`)
- Modify: `app/src/main/java/koeln/becker/nomla/app/AppContainer.kt` (init: `scope.launch(parts.ioDispatcher) { DiagnosticShare.clear(app) }`, B13)
- Test: `app/src/test/java/koeln/becker/nomla/diagnostics/DiagnosticZipTest.kt`, `DiagnosticShareTest.kt`

**Interfaces:**
- Consumes: `DiagnosticCapture` (including `route: RouteInfo?`, captured at recording start in Task 5, P15) and its public `writeTrack`, `writeTable` and `writeEvents` (added in this task, above). The internal helpers stay internal.
- Produces:
  - `data class AppInfo(val versionName: String, val versionCode: Int, val flavor: String, val manufacturer: String, val model: String, val release: String, val sdk: Int) { companion object { fun current(): AppInfo } }`
  - `enum class Rating { GOOD, MEDIUM, BAD }`
  - `data class CaptureReview(val ratings: Map<String, Rating>, val note: String)`
  - `RouteInfo` is the core type from Task 5 (`koeln.becker.nomla.mumble.diagnostics.RouteInfo`); the app defines none.
  - `object DiagnosticZip { fun write(capture: DiagnosticCapture, review: CaptureReview, info: AppInfo, out: OutputStream) }`. The route comes from `capture.route`. As a second guard (G2), `DiagnosticZip` writes `product_name: null` for every Bluetooth device type, whatever the capture holds.
  - `object DiagnosticShare { const val DIRECTORY = "shared_diagnostics"; fun export(context: Context, capture: DiagnosticCapture, review: CaptureReview): Uri /* blocking */; fun clear(context: Context); fun shareIntent(uri: Uri, subject: String): Intent }`
- ZIP entries: `README.txt`, `meta.json`, one `<track id>.wav` per track (send `1_mic_raw[_n]` … `6_sent`, receive `speaker_1..4` and `7_mix`, P10), `frames.csv`, `playback.csv`, `speakers.csv`, `packets.csv`, `network.csv`, `events.csv`. The file name is `nomla-diagnose-<yyyyMMdd-HHmmss>.zip` (UTC).
- `meta.json` top-level keys are exactly `format_version` (1), `app` {`version_name`, `version_code`, `flavor`}, `device` {`manufacturer`, `model`, `android_release`, `sdk`}, `route` {`device_type`, `product_name`, `stream_volume`, `stream_max_volume`, `audio_mode`} (`product_name` is null for Bluetooth, G2), `audio` (`pipelines`: list of `{capture, settings, stages}`), `started_at` (ISO-8601 UTC), `duration_ms`, `server_showed_recording`, `speakers_without_track`, `dropped_frames`, `tracks` (list of `{id, file, sample_rate, side, meaning, rating}` where `rating` is `good|medium|bad|null`), and `note`.
- `README.txt`: a fixed English text explaining every file and every CSV column (one line each), with times in ms from the recording start. It also says that every split raw track spans the whole recording (P5), that `6_sent` is placed at each packet's first captured frame (G6), that `vad_probability` is the capture chain's probability (RNNoise's when RNNoise runs, otherwise the APM level map) (G5), and lists every event name (`route_changed`, `connection_warning`, `settings_changed`, `capture_silenced`, `audio_mode`, `pipeline_rebuilt`, `transport_changed`, `talk_start`, `talk_end`, `connected`, `reconnecting`, `disconnected`) (G1).

- [ ] **Step 1: Write the failing tests** (Robolectric):
  - `DiagnosticZipTest`, `the zip holds exactly the documented entries`.
  - `meta.json has exactly the allowed keys`.
  - `only capture, review and app info reach the zip` (T6). The real-code-path check that producers never emit server/user/channel/secret text is in core (`NomlaSessionDiagnosticsTest`, Task 5). Here: build a capture with fixed events and settings, write the ZIP, and assert that every `meta.json` key is on the allow-list, that `note` is the only free text taken from the review, and that every CSV header equals the documented columns.
  - `a bluetooth headset is exported without its name` (G2): a capture whose `route` is Bluetooth SCO with product name "Felix's AirPods" gives `meta.json` `route.product_name == null`, and no entry's bytes contain "Felix". A wired headset keeps its product name.
  - `the mix is exported as 7_mix`: entry `7_mix.wav` at 48000 Hz (P10).
  - `README names every event`: every event name in a capture appears in README.
  - `speakers are numbered, not named`: entries `speaker_1.wav` and `speaker_2.wav`.
  - `ratings and note are carried`.
  - `README names every csv column`: every header column of every CSV appears in README.
  - `wav entries carry their real rate`: the raw track is at 44100.
  - `DiagnosticShareTest`, `export clears the directory and returns a provider uri for the one file`: two exports leave one file, and the `content://…` authority equals `packageName + ImageShareExporter.AUTHORITY_SUFFIX`.
  - `share intent grants read on exactly that uri`: `ACTION_SEND`, `FLAG_GRANT_READ_URI_PERMISSION`, `clipData` with 1 item, type `application/zip`.
  - `clear removes leftovers`.
- [ ] **Step 2: RUN-TEST each.** They fail.
- [ ] **Step 3: Implement** with `java.util.zip.ZipOutputStream` (`DEFLATED`) and `org.json.JSONObject`.
- [ ] **Step 4: RUN-TEST each.** They pass.
- [ ] **Step 5: `ide_diagnostics`** on all changed files.
- [ ] **Step 6: Commit** `feat(diagnostics): export a recording as a privacy-checked ZIP`.

**Wave 2 ends; the orchestrator runs `verify`.** The hub now exists per session, but nothing meters or arms it without UI, so taps stay inert. The `AudioManager` mode listener and the sampler only run while active.

---

## Wave 3: The app

### Task 7: Switch, strings, and debug pages in the channel view

**Files:**
- Modify: `app/src/main/java/koeln/becker/nomla/Settings.kt`:
  - `val DEBUG_FEATURES = Pref("debug_features", false)` and `val isDebugFeaturesEnabled: Boolean by pref(DEBUG_FEATURES)`;
  - `val DEBUG_LIVE_VIEW = EnumPref("debug_live_view", LiveView.CONSOLE, LiveView.entries, LiveView::preferenceValue)` and `var debugLiveView: LiveView by entry(DEBUG_LIVE_VIEW)`;
  - `val DEBUG_RECORDING_NOTICE_HIDDEN = Pref("debug_recording_notice_hidden", false)` and `var isRecordingNoticeHidden by pref(…)`.
- Create: `app/src/main/java/koeln/becker/nomla/diagnostics/LiveView.kt` (`enum class LiveView(val preferenceValue: String) { CONSOLE("console"), LIST("list") }`)
- Modify: `app/src/main/res/xml/settings_audio.xml` (new last `PreferenceCategory android:key="debug" android:title="@string/debug_category"` with a `SwitchPreferenceCompat android:key="debug_features" android:defaultValue="false"`, B1)
- Modify: `app/src/main/java/koeln/becker/nomla/session/SessionSettingsSync.kt` (add `Settings.DEBUG_FEATURES.key` to `OBSERVED_KEYS`; `onPreferenceChanged` gets only the key, so read `settings.isDebugFeaturesEnabled`, and when it is off, call `session.diagnostics.cancelRecording()`)
- Modify: `app/src/main/java/koeln/becker/nomla/channel/ChannelFragment.kt` (`TabsAdapter.getItemCount() = if (debug) 4 else 2`, with `getItemId`/`containsItem` for a changing count; add `DEBUG_FEATURES` to the collected changes; `tabTitle` for positions 2/3; wide: `DebugPages.attachSideTabs(...)` with B2)
- Create: `app/src/main/java/koeln/becker/nomla/diagnostics/DebugPages.kt` (page factory + side tabs only)
- Create: `app/src/main/java/koeln/becker/nomla/diagnostics/DiagnosticsLiveFragment.kt` and `app/src/main/java/koeln/becker/nomla/diagnostics/DiagnosticsRecordFragment.kt`, each a placeholder showing the empty state, as separate files that Tasks 8 and 9 fill (P18)
- Modify: `app/src/main/res/layout-sw600dp/fragment_channel.xml` (a `TabLayout` `@+id/side_tabs`, `gone` by default, above `@id/chat_fragment`)
- Modify: `values/strings.xml`, `values-de/strings.xml`, and the 29 other `values-*/strings.xml` files (all strings of Tasks 7–9, below)
- Test: `app/src/test/java/koeln/becker/nomla/channel/ChannelFragmentDebugPagesTest.kt`, `app/src/test/java/koeln/becker/nomla/session/SessionSettingsSyncTest.kt` (add); `SettingsDefaultsTest` must stay green

**Strings** (name: EN / DE). Translate them into the 29 other locales. `%d`/`%1$d` placeholders must stay as they are.

| name | EN | DE |
|---|---|---|
| debug_category | Diagnostics | Diagnose |
| debug_features | Enable debug features | Debug-Funktionen aktivieren |
| debug_features_sum | Adds a live view of the audio chain and diagnostic recordings to the channel view. | Zeigt in der Kanalansicht die Audiokette live und ermöglicht Diagnose-Aufnahmen. |
| debug_tab_live | Live | Live |
| debug_tab_record | Recording | Aufnahme |
| debug_empty | Connect to a server to see the audio chain. | Verbinde dich mit einem Server, um die Audiokette zu sehen. |
| debug_view_console | Console | Pult |
| debug_view_list | List | Liste |
| debug_stage_mic_raw | Microphone | Mikrofon |
| debug_stage_resampled | Resampler | Resampler |
| debug_stage_apm | Echo + gain | Echo + Pegel |
| debug_stage_denoised | Noise reduction | Rauschunterdrückung |
| debug_stage_gated | Gate | Gate |
| debug_stage_sent | Sent | Gesendet |
| debug_stage_off | Off | Aus |
| debug_block_echo | Echo | Echo |
| debug_block_receive | Receive | Empfang |
| debug_block_network | Network | Netz |
| debug_block_device | Device | Gerät |
| debug_change_db | %1$s dB | %1$s dB |
| debug_speaker | Speaker %d | Sprecher %d |
| debug_track_mix | Playback mix | Wiedergabe-Mix |
| debug_record_duration | Duration | Dauer |
| debug_record_seconds | %d s | %d s |
| debug_record_start | Record | Aufnehmen |
| debug_record_cancel | Cancel | Abbrechen |
| debug_record_progress | Recording … %1$d of %2$d s | Aufnahme läuft … %1$d von %2$d s |
| debug_record_refused_offline | Not connected. | Nicht verbunden. |
| debug_record_refused_server | This server does not allow recordings. | Dieser Server erlaubt keine Aufnahmen. |
| debug_record_refused_memory | Not enough memory for a recording this long. | Zu wenig Speicher für eine so lange Aufnahme. |
| debug_record_aborted_disconnect | Recording stopped: the connection ended. | Aufnahme abgebrochen: Die Verbindung wurde getrennt. |
| debug_record_note_hint | Note for the developers | Notiz für die Entwickler |
| debug_record_share | Share | Teilen |
| debug_record_discard | Discard | Verwerfen |
| debug_record_share_subject | Nomla diagnostic recording | Nomla-Diagnoseaufnahme |
| debug_record_playback_hint | On the loudspeaker, the others may hear the playback through your microphone. | Über den Lautsprecher können die anderen die Wiedergabe über dein Mikrofon hören. |
| debug_rating_good | Good | Gut |
| debug_rating_medium | So-so | Mittel |
| debug_rating_bad | Bad | Schlecht |
| debug_consent_title | Record only with consent | Nur mit Einverständnis aufnehmen |
| debug_consent_message | The recording also contains the voices of the others in the channel. Only record if everyone agrees. While recording, everyone on the server sees that you are recording. If the server does not allow recordings, it will not start. | Die Aufnahme enthält auch die Stimmen der anderen im Kanal. Nimm nur auf, wenn alle damit einverstanden sind. Während der Aufnahme sehen alle auf dem Server, dass du aufnimmst. Erlaubt der Server keine Aufnahmen, startet sie nicht. |
| debug_consent_dont_show | Don't show again | Nicht mehr anzeigen |
| debug_metric_peak | Peak | Spitze |
| debug_label_clipped | Clipped samples | Übersteuerte Samples |
| debug_label_level_change | Level change | Pegeländerung |
| debug_label_delay | Delay | Verzögerung |
| debug_label_divergent | Divergent filter | Divergenter Filter |
| debug_label_probability | Speech probability | Sprachwahrscheinlichkeit |
| debug_label_noise_floor | Noise floor | Rauschboden |
| debug_label_speech_level | Speech level | Sprachpegel |
| debug_label_threshold | Threshold | Schwelle |
| debug_label_hold | Hold | Halten |
| debug_label_too_close | Too close | Zu nah |
| debug_label_transport | Transport | Transport |
| debug_label_ping | Ping | Ping |
| debug_label_lost | Lost | Verloren |
| debug_label_late | Late | Verspätet |
| debug_label_resync | Resync | Resync |
| debug_label_jitter_buffer | Jitter buffer | Jitter-Puffer |
| debug_label_concealed | Concealed frames | Verdeckte Frames |
| debug_label_decode_errors | Decode errors | Dekodierfehler |
| debug_label_underruns | Underruns | Underruns |
| debug_label_buffer | Buffer | Puffer |
| debug_label_latency | Output latency | Ausgabelatenz |
| debug_label_socket_buffers | Socket buffers | Socket-Puffer |
| debug_label_source | Source | Quelle |
| debug_label_sample_rate | Sample rate | Abtastrate |
| debug_label_effects | Platform effects | Plattform-Effekte |
| debug_label_output | Output | Ausgabe |
| debug_label_volume | Volume | Lautstärke |
| debug_label_audio_mode | Audio mode | Audio-Modus |
| debug_lane_gate | Gate open | Gate offen |
| debug_lane_underruns | Underruns | Underruns |
| debug_lane_packet_gaps | Packet gaps | Paketlücken |
| debug_lane_transport | Transport changes | Transportwechsel |
| debug_track_play | Play | Abspielen |
| debug_track_stop | Stop | Stopp |
| debug_record_cancelled | Recording cancelled. | Aufnahme abgebrochen. |

Copy the DE consent strings **verbatim** from the spec (they are reproduced above). The acronyms (`RMS`, `ERL`, `ERLE`, `dBFS`, `UDP`, `TCP`, `SO_SNDBUF`, `SO_RCVBUF`) go into `values/strings.xml` as `debug_metric_*` with `translatable="false"` (G4). "Peak" is `debug_metric_peak` above and is translated. This table holds every string Tasks 8 and 9 use (P19). If Task 8 or 9 needs a label that is not here, it uses the CSV column name as a `translatable="false"` string in `values/strings_notranslate.xml`, never a new translated string.

- [ ] **Step 1: Write the failing tests** (`ChannelFragmentDebugPagesTest`, Robolectric, `hostWith(FakeSession(SessionState.Connected))` as in `ChannelFragmentTabsTest`):
  - `debug off shows two tabs`: tab count 2.
  - `debug on shows Live and Recording after chat`: the tab titles end with `debug_tab_live` and `debug_tab_record` (uppercased like the others).
  - `switching debug off while on a debug page returns to two tabs and the chat`.
  - `wide screens show side tabs only with debug on`: `@Config(qualifiers = "sw600dp-w900dp-h600dp")`, where `side_tabs` is visible ⇔ debug.
  - `the Live page shows the empty state without a connected session`: `FakeSession(SessionState.Disconnected())` shows `debug_empty` on the Live page (P19; the Recording page keeps a finished capture while disconnected, B12, tested in Task 9).

  `SessionSettingsSyncTest`:
  - `switching debug off cancels a recording`: `FakeSessionDiagnostics.cancelCalls == 1`.
  - `switching debug on does nothing to the session`.
- [ ] **Step 2: RUN-TEST each.** They fail.
- [ ] **Step 3: Implement** the files above and add the strings to all 31 files. In each locale file, put the block at the end, before `</resources>`.
- [ ] **Step 4: RUN-TEST** the two test classes, `ChannelFragmentTabsTest`, `ChannelFragmentAccessibilityTest` and `SettingsDefaultsTest`. All pass.
- [ ] **Step 5: `ide_diagnostics`** on the changed Kotlin/XML files.
- [ ] **Step 6: Commit** `feat(diagnostics): debug switch and debug pages in the channel view`.

### Task 8: Live page (Console | List)

**Files:**
- Create: `app/src/main/java/koeln/becker/nomla/diagnostics/DiagnosticsViewModel.kt`, `StageStripsView.kt`, `GateHistoryView.kt`, `StageListAdapter.kt`; fill `DiagnosticsLiveFragment.kt`
- Create: `app/src/main/res/layout/fragment_diagnostics_live.xml`, `item_diagnostics_stage.xml`
- Test: `app/src/test/java/koeln/becker/nomla/diagnostics/DiagnosticsLiveFragmentTest.kt`, `DiagnosticsViewModelTest.kt`

**Interfaces:**
- Consumes: `SessionDiagnostics.meter()` and `snapshot()`, `Settings.debugLiveView`.
- Produces:
  - `class DiagnosticsViewModel(sessions: SessionManager) : ViewModel` with `val live: Flow<DiagnosticsSnapshot?>`. It emits every `1000/15` ms while collected (B9), opens `meter()` on collection start and closes it on cancellation, and emits null without a connected session. It has no `recording` property; the Recording page reads `SessionDiagnostics.recording` itself (P20).
  - `StageStripsView.show(stages: List<StageReading>)`. One vertical strip per `SendStage`: LED (green = active, grey = inactive, amber = clipping in the last second), RMS bar, peak-hold line. `setOnStageClick((SendStage) -> Unit)`. Content description per strip: stage name + RMS.
  - `GateHistoryView.show(history: BooleanArray)`.
  - `StageListAdapter` with one row per stage (LED, bar, `debug_change_db`) and expandable metrics (the stage's `frames` columns as label/value). Then rows for the Echo, Receive, Network and Device blocks. `fun expand(stage: SendStage): Int` returns the adapter position.
  - The Device block comes from `session.audio.activeDevice` (`CommunicationDevice`) and `AudioManager` stream volume/mode. It may show the device's name, because the Live page never leaves the device (G2).
  - Labels come from Task 7's `debug_metric_*`, `debug_label_*` and `debug_block_*` strings (P19).

- [ ] **Step 1: Write the failing tests:**
  - `DiagnosticsViewModelTest` (with `FakeSessionDiagnostics`, `StandardTestDispatcher`):
    - `metering is held only while collected`: `meterHolders` is 1 during collection and 0 after.
    - `emits about 15 readings per second`: 1 s gives 15 ±1 emissions.
    - `no session gives null`.
  - `DiagnosticsLiveFragmentTest`:
    - `the toggle is remembered`: choose List, recreate, and List is shown with `Settings.debugLiveView == LIST`.
    - `inactive stages are greyed, not hidden`: a snapshot with RESAMPLED `active=false` shows the strip with alpha < 1 and the list row with `debug_stage_off`.
    - `tapping a strip opens the list at that stage, expanded`: tap APM, and the list view is shown with the APM row expanded and visible.
    - `metering stops when the page is stopped`: after `moveToState(CREATED)`, `meterHolders == 0`.
- [ ] **Step 2: RUN-TEST each.** They fail.
- [ ] **Step 3: Implement.** Collect with `whileViewStarted { viewModel.live.collect(::show) }`.
- [ ] **Step 4: RUN-TEST each** plus `ChannelFragmentDebugPagesTest`. All pass.
- [ ] **Step 5: `ide_diagnostics`** on all changed files.
- [ ] **Step 6: Commit** `feat(diagnostics): live console and list of the audio chain`.

### Task 9: Recording page (consent, progress, tracks, share)

**Files:**
- Create: `app/src/main/java/koeln/becker/nomla/diagnostics/RecordingConsentDialog.kt`, `TrackListAdapter.kt`; fill `DiagnosticsRecordFragment.kt`
- Create (core, T9): `libraries/core/src/main/java/koeln/becker/nomla/mumble/diagnostics/TrackPlayer.kt` (public). It needs `AudioWorker` and `releaseAfter`, which are `internal` in core, and the app has no friend path into core.
- Create: `app/src/main/res/layout/fragment_diagnostics_record.xml`, `item_diagnostics_track.xml`, `dialog_recording_consent.xml`
- Test: `app/src/test/java/koeln/becker/nomla/diagnostics/DiagnosticsRecordFragmentTest.kt`, `RecordingConsentDialogTest.kt`; `libraries/core/src/test/java/koeln/becker/nomla/mumble/diagnostics/TrackPlayerTest.kt`

**Interfaces:**
- Consumes: `SessionDiagnostics.startRecording`, `cancelRecording`, `discardRecording` and `recording`; `DiagnosticShare`; `AppContainer.parts.micPlayback`, `ioDispatcher` and `workDispatcher`; `Settings.isRecordingNoticeHidden`.
- Produces:
  - `class RecordingConsentDialog : DialogFragment`. Fragment-result key `"recording_consent"` with a boolean `"start"`. The "don't show again" checkbox writes `Settings.isRecordingNoticeHidden` only on confirm. Showing the dialog is skipped when hidden; the Mumble flag is set regardless (spec).
  - In core: `public class TrackPlayer(sinkFactory: PcmPlaybackSinkFactory, stream: Int)` with `fun play(track: CapturedTrack, fromMs: Long = 0, onPosition: (Long) -> Unit, onEnd: () -> Unit)` and `fun stop()`. One `AudioWorker("nomla-diagnostics-playback")`; `stop` joins with 500 ms and releases through `releaseAfter` (B11). The app constructs it with `AppContainer.parts.micPlayback` and `Settings.PLAYBACK_STREAM`.
  - Track rows: name (stage/track string; `7_mix` uses `debug_track_mix`), a waveform (min/max per pixel precomputed on `workDispatcher`), the shared playhead, play/stop with content descriptions `debug_track_play`/`debug_track_stop`, three rating toggles (`MaterialButtonToggleGroup`, deselectable, content descriptions `debug_rating_*`), and telemetry lanes under the audio, labelled `debug_lane_gate`, `debug_lane_underruns`, `debug_lane_packet_gaps` (gaps > 60 ms) and `debug_lane_transport`. `Idle(CANCELLED)` shows `debug_record_cancelled`.
  - Ratings and note are kept in a new `RecordReviewViewModel(state: SavedStateHandle)` (`review: StateFlow<CaptureReview>`, `fun rate(trackId: String, rating: Rating?)`, `fun setNote(text: String)`) in `DiagnosticsRecordFragment.kt`, not in the DB. They are reset when a new capture arrives.
  - Share: `DiagnosticShare.export(context, capture, review)` on `ioDispatcher` (the route comes from `capture.route`, recorded at the start, P15), then `startActivity(Intent.createChooser(shareIntent(uri, getString(R.string.debug_record_share_subject)), null))`.
  - The page shows a finished capture whatever the connection state; only "Record" needs a connected session and is disabled without one (B12, P19).
  - Discard: `discardRecording()` + `DiagnosticShare.clear` on `ioDispatcher`.

- [ ] **Step 1: Write the failing tests:**
  - `RecordingConsentDialogTest`:
    - `the German text is the approved wording`: under `@Config(qualifiers = "de")`, title, message and checkbox equal the spec text exactly.
    - `confirm with the box ticked hides it next time`.
    - `cancel starts nothing`.
  - `DiagnosticsRecordFragmentTest` (`FakeSession`, `FakeSessionDiagnostics`):
    - `default duration is 10 s and the choices are 5, 10, 20, 30`.
    - `record shows the dialog, then starts with the chosen duration`: `startCalls == [20]`.
    - `with the notice hidden, record starts directly`.
    - `confirming the dialog after debug was switched off starts nothing` (Review Focus 4): open the dialog, set `DEBUG_FEATURES` false, confirm, and `startCalls` is empty.
    - `a refusal shows its reason`, once for each `RecordingRefusal` that maps to a string.
    - `recording shows progress and cancel cancels`.
    - `recreating the activity during a recording shows its progress` (Review Focus 3).
    - `a finished capture lists every track with ratings, and share hands out one zip uri`: the intent is captured with `shadowOf(activity).nextStartedActivity`, its chooser wraps `ACTION_SEND` with one `clipData` item.
    - `aborted by disconnect shows the reason`.
    - `a finished capture stays visible after a disconnect` (B12, P19): with `Finished` in `recording` and the session moved to `SessionState.Disconnected()`, the track list and Share are shown, `debug_empty` is not, and Record is disabled.
    - `a cancelled recording says so`: `Idle(CANCELLED)` shows `debug_record_cancelled`.
  - `TrackPlayerTest` (core, fake sink):
    - `plays the whole track at its rate and reports the end`.
    - `stop ends playback and releases the sink once`.
- [ ] **Step 2: RUN-TEST each.** They fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: RUN-TEST each** plus `DiagnosticsLiveFragmentTest`, `ChannelFragmentDebugPagesTest` and `ChannelFragmentAccessibilityTest`. All pass.
- [ ] **Step 5: `ide_diagnostics`** on all changed files.
- [ ] **Step 6: Commit** `feat(diagnostics): record, review and share diagnostic recordings`.

**Wave 3 ends; the orchestrator runs `verify`** (lint covers the 31 string files and unused resources). This is the first wave with a visible change, and only behind the switch, default off.

---

## Wave 4: Analysis tool and website

### Task 10: Offline report tool

**Files:**
- Create: `tools/diagnostics-report/report.py` (executable, GPL header like `tools/room-test/room_test.py`), `tools/diagnostics-report/test_report.py`

**Interfaces:**
- Consumes: the ZIP format of Task 6 (`meta.json` `format_version` 1, the CSVs and WAVs).
- Produces: `python3 report.py <zip> [--out report.md]`, which writes Markdown (stdout by default). Sections (B15):
  1. Header: app, device, route, duration, whether the server showed the recording, ratings, note.
  2. Per track: RMS/peak dBFS, clipped samples, and 1/3-octave band levels 100 Hz–16 kHz (pure-Python radix-2 FFT, 4096-sample Hann blocks).
  3. Dropouts: runs of ≥ 30 ms digital silence on `5_gate` / `6_sent` while `frames.csv` has `transmit=1, send_allowed=1`.
  4. Cut syllable onsets: transmit starts where `5_gate` RMS over the first 30 ms is ≥ 10 dB below `4_denoised`.
  5. Echo vs speech loss per stage: in far-end-only phases (mix active, `vad_open=0`), stage level minus mix level (echo leak). In near-end-only phases, the level change per stage.
  6. Network and buffer correlation: packet gaps > 60 ms, underruns, concealment bursts and transport changes, each listed with the nearest dropout within ±200 ms.
- Only the standard library is used (`zipfile, json, csv, wave, array, math, argparse, sys, io`).

- [ ] **Step 1: Write the failing tests** (`unittest`, a synthetic ZIP built in `setUp` in a `tempfile` directory):
  - `test_report_runs_on_a_synthetic_zip` returns Markdown containing `"## 5_gate"`.
  - `test_finds_a_dropout`: a 100 ms zero run at 1.00 s on `5_gate` with `transmit=1` gives a line containing `1.00 s`.
  - `test_band_peak_is_the_tone`: a 1 kHz tone on `1_mic_raw` at 44100 Hz puts the highest band at 1000 Hz.
  - `test_correlates_packet_gap_with_dropout`.
  - `test_uses_only_the_standard_library`: `ast`-parse `report.py`; the imported top-level modules are a subset of the list above (no `socket`, `urllib`, `http`, `subprocess`, audio modules).
- [ ] **Step 2: Run** `python3 -I tools/diagnostics-report/test_report.py` via Bash (read-only execution; if the `ide-first` hook refuses, say so in the report and leave this check to the orchestrator). Expected: fail (no `report.py`).
- [ ] **Step 3: Implement** `report.py`.
- [ ] **Step 4: Run again.** All tests pass. Nothing is played, and no network is used.
- [ ] **Step 5: Commit** `feat(tools): offline report for diagnostic recordings`.

### Task 11: Website

**Files:**
- Modify: `website/technik.html`:
  - section `#funktionen` gets a new `<h3>` "Diagnose für Neugierige" / "Diagnostics for the curious" (DE+EN): the switch, Live (Console/List), Recording of every stage, consent dialog + Mumble recording indicator, the ZIP and what it never contains;
  - new section `#datenschutz` / "Privacy" (DE+EN) after `#sicherheit`, with nav entry if the page nav lists sections. Recordings stay on the device, in memory/cache only, until the user shares them through the Android share menu. There is no upload, nothing is kept across app restarts, and server/user/channel names, passwords, certificates, tokens and chat are never included;
  - section `#aufbau` "Getestet": update "Rund 240 Testklassen" to the count from the Wave 3 `verify` (number of `TEST-*.xml` in the three result directories of that run, rounded to tens as now), and mention the new native APM statistics test only if the paragraph lists tests individually (it does not today).
- Modify: `website/index.html` only if the setup guide touches the audio settings screen's last section (it does not today, so no change expected; check).

- [ ] **Step 1: Read** `website/technik.html` fully and check every value you write against the code (durations 5/10/20/30 s, 4 speaker tracks, file names).
- [ ] **Step 2: Edit** with `apply_patch`, keeping DE and EN `<p lang>` pairs side by side as the page does.
- [ ] **Step 3: Check** that the page has no broken anchors (`ide_search_text` for `href="#`) and that both languages have the same paragraphs.
- [ ] **Step 4: Commit** `docs(website): describe the debug diagnostics and their privacy`. The orchestrator deploys after the wave's `verify` (CLAUDE.md "Deploy after every change"; host from the owner's memory, never in the repo).

**Wave 4 ends; the orchestrator runs `verify`, deploys `website/`, and asks the owner before any acoustic device test.**

---

## Not covered by code tasks (for the orchestrator)

- **Play Data safety and the website privacy policy for 1.0.0:** the feature is in all builds, so the Play declaration must mention "audio recorded locally, shared only by the user". This lives in the Play Console, not in the repo.
- **Device/acoustic validation** (Moto G56 with echo cancellation, a local murmur): only after the owner's OK. No task plays sound.
