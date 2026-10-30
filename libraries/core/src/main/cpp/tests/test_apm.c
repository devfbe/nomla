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

/* Host test for the webrtc-audio-processing C wrapper.
 *
 * Deliberately C: if nomla_apm.h stopped being valid C, or extern "C" were dropped from
 * nomla_apm.cpp, this would fail to compile or link. A .cpp test would not notice.
 */
#include "nomla_apm.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static int failures = 0;
#define CHECK(cond, msg)                                              \
    do {                                                              \
        if (!(cond)) {                                                \
            fprintf(stderr, "FAIL: %s\n", (msg));                     \
            failures++;                                               \
        }                                                             \
    } while (0)

enum { kRate = 48000, kFrame = kRate / 100 };

static double energy(const int16_t *f, int n) {
    double e = 0;
    for (int i = 0; i < n; i++) e += (double)f[i] * (double)f[i];
    return e / n;
}

static double to_db(double ratio) {
    if (ratio <= 0.0) return -200.0;
    return 10.0 * log10(ratio);
}

/* Deterministic white noise. A sine is a poor excitation for an adaptive filter (rank-one and
 * periodic, so a misaligned reference still lines up with the echo). */
static uint32_t rng;
static void seed(uint32_t s) { rng = s; }
static int16_t noise_from(uint32_t *s) {
    *s = *s * 1664525u + 1013904223u;
    return (int16_t)(((int32_t)(*s >> 16) & 0x7fff) - 16384) / 2; /* +-4096 */
}
static int16_t noise(void) { return noise_from(&rng); }

/* Which of the two streams is fed, and in what relation to the near-end frame that carries its
 * echo. Getting it wrong is silent: the APM returns 0 from every call either way. */
typedef enum {
    kOrdered,     /* render(n) then capture(n): the reference precedes the echo. Correct. */
    kNoRender,    /* the far-end stream is never fed at all. */
    kMismatched,  /* the far-end stream is fed, but with audio that is not what was played. */
    kCaptureFirst,/* capture(n) then render(n): the reference arrives after its own echo. */
    kLateRender   /* the reference arrives 20 frames (200 ms) after its own echo. */
} order_t;

typedef struct {
    double input;   /* energy of the capture frame before processing */
    double output;  /* energy after processing */
    int err;
} aec_result;

/* Runs 10 s of a -6 dB echo path delayed by 30 ms and accumulates energies over the last second.
 *
 * A zero-delay echo path never converges (AEC3 models a real acoustic path), and nothing converges
 * within 5 s; both would leave only AEC3's uniform initial attenuation to measure.
 * set_stream_delay_ms stays 0: AEC3 estimates the delay itself. */
static aec_result run_aec(order_t order) {
    aec_result r = {0, 0, 0};
    nomla_apm_config cfg = {1, 0, 0, 0, 1};
    nomla_apm *apm = nomla_apm_create(kRate, &cfg);
    if (!apm) { r.err = -1; return r; }
    seed(12345u);
    uint32_t other = 777u;
    enum { kLag = 20, kEchoDelay = 3 };
    int16_t held[kLag][kFrame];
    int16_t echo[kEchoDelay][kFrame];
    memset(echo, 0, sizeof echo);
    for (int n = 0; n < 1000; n++) {
        int16_t render[kFrame], capture[kFrame], unrelated[kFrame];
        for (int i = 0; i < kFrame; i++) {
            render[i] = noise();
            unrelated[i] = noise_from(&other);
        }
        /* Echo path: -6 dB, delayed by kEchoDelay frames. The slot about to be overwritten
         * still holds the render frame from kEchoDelay ticks ago. */
        for (int i = 0; i < kFrame; i++) capture[i] = (int16_t)(echo[n % kEchoDelay][i] / 2);
        memcpy(echo[n % kEchoDelay], render, sizeof render);
        double ie = energy(capture, kFrame);

        if (order == kOrdered) {
            r.err |= nomla_apm_process_render(apm, render);
        } else if (order == kMismatched) {
            r.err |= nomla_apm_process_render(apm, unrelated);
        } else if (order == kLateRender) {
            if (n >= kLag) { int16_t old[kFrame];
                memcpy(old, held[n % kLag], sizeof old);
                r.err |= nomla_apm_process_render(apm, old); }
            memcpy(held[n % kLag], render, sizeof render);
        }
        nomla_apm_set_stream_delay_ms(apm, 0);

        if (order == kCaptureFirst) {
            r.err |= nomla_apm_process_capture(apm, capture);
            r.err |= nomla_apm_process_render(apm, render);
        } else {
            r.err |= nomla_apm_process_capture(apm, capture);
        }

        if (n >= 900) { r.input += ie; r.output += energy(capture, kFrame); }
    }
    nomla_apm_destroy(apm);
    return r;
}

/* 4 s of echo (-6 dB, 30 ms) with an unrelated near-end talker from 2 s on, through AEC3 created
 * with `tuning` (NULL: webrtc's default). Returns an FNV-1a hash of every output sample, and the
 * output energy of the double-talk half in *dt_energy; 0 if the APM could not be created. */
static uint32_t run_tuned(const float *tuning, double *dt_energy) {
    nomla_apm_config cfg = {1, 0, 0, 0, 1, tuning};
    nomla_apm *apm = nomla_apm_create(kRate, &cfg);
    if (!apm) return 0;
    uint32_t far = 12345u, near = 999u, hash = 2166136261u;
    enum { kEchoDelay = 3 };
    int16_t echo[kEchoDelay][kFrame];
    memset(echo, 0, sizeof echo);
    *dt_energy = 0;
    for (int n = 0; n < 400; n++) {
        int16_t render[kFrame], capture[kFrame];
        for (int i = 0; i < kFrame; i++) render[i] = noise_from(&far);
        for (int i = 0; i < kFrame; i++) {
            int v = echo[n % kEchoDelay][i] / 2 + (n >= 200 ? noise_from(&near) / 2 : 0);
            capture[i] = (int16_t)v;
        }
        memcpy(echo[n % kEchoDelay], render, sizeof render);
        nomla_apm_process_render(apm, render);
        nomla_apm_process_capture(apm, capture);
        for (int i = 0; i < kFrame; i++) {
            hash = (hash ^ (uint16_t)capture[i]) * 16777619u;
        }
        if (n >= 200) *dt_energy += energy(capture, kFrame);
    }
    nomla_apm_destroy(apm);
    return hash;
}

static void test_statistics_null(void) {
    nomla_apm_stats s;
    CHECK(nomla_apm_get_statistics(NULL, &s) == -5, "get_statistics(NULL, out) is kNullPointerError");
    nomla_apm_config cfg = {1, 0, 0, 0, 1};
    nomla_apm *h = nomla_apm_create(kRate, &cfg);
    CHECK(h != NULL, "create with AEC3 succeeds for the statistics test");
    if (h) {
        CHECK(nomla_apm_get_statistics(h, NULL) == -5, "get_statistics(h, NULL) is kNullPointerError");
        nomla_apm_destroy(h);
    }
}

static int finite_or_nan(float x) { return isnan(x) || isfinite(x); }

static void print_stats(const char *when, const nomla_apm_stats *s) {
    printf("APM statistics %s: ERL %.2f dB, ERLE %.2f dB, divergent %.2f, delay median %.1f ms, "
           "std %.1f ms, delay %.1f ms\n",
           when, s->erl_db, s->erle_db, s->divergent_filter_fraction, s->delay_median_ms,
           s->delay_std_ms, s->delay_ms);
}

/* The run_aec(kOrdered) scenario, resumable: far end before near end, -6 dB echo path delayed by
 * 30 ms. */
enum { kStatsEchoDelay = 3 };
typedef struct {
    uint32_t far;
    int n;
    int16_t echo[kStatsEchoDelay][kFrame];
} echo_state;

static void echo_init(echo_state *e) {
    memset(e, 0, sizeof *e);
    e->far = 12345u;
}

/* Feeds `frames` more 10 ms ticks. Returns the OR of every call's result. */
static int feed_echo(nomla_apm *h, echo_state *e, int frames) {
    int err = 0;
    for (int k = 0; k < frames; k++, e->n++) {
        int16_t *slot = e->echo[e->n % kStatsEchoDelay];
        int16_t render[kFrame], capture[kFrame];
        for (int i = 0; i < kFrame; i++) render[i] = noise_from(&e->far);
        for (int i = 0; i < kFrame; i++) capture[i] = (int16_t)(slot[i] / 2);
        memcpy(slot, render, sizeof render);
        err |= nomla_apm_process_render(h, render);
        err |= nomla_apm_process_capture(h, capture);
    }
    return err;
}

/* What AEC3 reports before it has learned anything. webrtc hands statistics over through a
 * one-slot queue: the first capture frame after a read fills it and later frames are dropped
 * until the next read, so a read returns the stats of the first frame after the previous read.
 * Here that is frame 1. test_jni_bridges.cpp pins the same values through JNI to fix the field
 * order. */
static void test_statistics_fresh(void) {
    nomla_apm_config cfg = {1, 0, 0, 0, 1};
    nomla_apm *h = nomla_apm_create(kRate, &cfg);
    CHECK(h != NULL, "create with AEC3 succeeds for the statistics test");
    if (!h) return;
    nomla_apm_stats s;
    CHECK(nomla_apm_get_statistics(h, &s) == 0, "get_statistics succeeds before any frame");
    print_stats("before any frame", &s);
    CHECK(isnan(s.erl_db) && isnan(s.erle_db) && isnan(s.divergent_filter_fraction) &&
              isnan(s.delay_median_ms) && isnan(s.delay_std_ms) && isnan(s.delay_ms),
          "before any capture frame every statistic is NAN");

    echo_state e;
    echo_init(&e);
    CHECK(feed_echo(h, &e, 5) == 0, "five echo ticks run without errors");
    CHECK(nomla_apm_get_statistics(h, &s) == 0, "get_statistics succeeds after five frames");
    printf("APM statistics of frame 1, exact: ERL %.6f dB, ERLE %.6f dB, delay %.6f ms\n",
           s.erl_db, s.erle_db, s.delay_ms);
    CHECK(s.erl_db == -30.0f, "ERL starts at AEC3's kMaxErl (1000, i.e. -30 dB)");
    CHECK(fabsf(s.erle_db - 0.18f) < 0.01f, "ERLE starts at AEC3's initial 0.18 dB");
    CHECK(isnan(s.divergent_filter_fraction) && isnan(s.delay_median_ms) && isnan(s.delay_std_ms),
          "this webrtc never writes divergent fraction, delay median or delay std");
    CHECK(s.delay_ms == 16.0f, "the render buffer starts with a delay of 4 blocks (16 ms)");
    nomla_apm_destroy(h);
}

/* 10 s of echo: long enough for AEC3's 2 s ERL startup phase and a converged refined filter. */
static void test_statistics_values(void) {
    nomla_apm_config cfg = {1, 0, 0, 0, 1};
    nomla_apm *h = nomla_apm_create(kRate, &cfg);
    CHECK(h != NULL, "create with AEC3 succeeds for the statistics test");
    if (!h) return;
    echo_state e;
    echo_init(&e);
    CHECK(feed_echo(h, &e, 1000) == 0, "the statistics scenario runs without errors");

    nomla_apm_stats s;
    /* Never read before, so this is still frame 1's snapshot (see test_statistics_fresh). */
    CHECK(nomla_apm_get_statistics(h, &s) == 0, "get_statistics succeeds on a live handle");
    CHECK(s.erl_db == -30.0f, "the first read returns the stats of the first frame after creation");

    /* The read emptied the queue; the next frame refills it with current values. */
    CHECK(feed_echo(h, &e, 1) == 0, "one more echo tick runs without errors");
    CHECK(nomla_apm_get_statistics(h, &s) == 0, "get_statistics succeeds on a live handle");
    print_stats("after 10 s of echo", &s);
    CHECK(finite_or_nan(s.erl_db), "erl_db is a number or NAN");
    CHECK(finite_or_nan(s.erle_db), "erle_db is a number or NAN");
    CHECK(finite_or_nan(s.divergent_filter_fraction), "divergent_filter_fraction is a number or NAN");
    CHECK(finite_or_nan(s.delay_median_ms), "delay_median_ms is a number or NAN");
    CHECK(finite_or_nan(s.delay_std_ms), "delay_std_ms is a number or NAN");
    CHECK(finite_or_nan(s.delay_ms), "delay_ms is a number or NAN");
    CHECK(s.erl_db > -29.0f, "ERL has left its starting value of -30 dB");
    /* AEC3's ERL is a minimum-tracking estimate, so it lands above the path's 6 dB (about 10 dB
     * here); the bounds only say "a plausible attenuation", not a calibrated value. */
    CHECK(s.erl_db >= 0.0f && s.erl_db <= 20.0f, "ERL of a -6 dB echo path is in [0, 20] dB");
    CHECK(s.erle_db > 3.0f, "AEC3 reports an ERLE above 3 dB after 10 s of echo");
    CHECK(s.delay_ms >= 0.0f && s.delay_ms <= 40.0f, "delay_ms is in [0, 40] ms");
    CHECK(fmodf(s.delay_ms, 4.0f) == 0.0f, "delay_ms is a whole number of 4 ms blocks");
    nomla_apm_destroy(h);
}

int main(void) {
    /* ---- 1. Argument validation. ---- */
    nomla_apm_config ns_only = {0, 1, 2, 0, 1};
    CHECK(nomla_apm_create(kRate, NULL) == NULL, "a NULL config is rejected");
    CHECK(nomla_apm_create(44100, &ns_only) == NULL, "44.1 kHz is rejected");
    CHECK(nomla_apm_create(0, &ns_only) == NULL, "0 Hz is rejected");
    CHECK(nomla_apm_create(-48000, &ns_only) == NULL, "a negative rate is rejected");
    static const int kRates[] = {8000, 16000, 32000, 48000};
    for (int i = 0; i < 4; i++) {
        nomla_apm *h = nomla_apm_create(kRates[i], &ns_only);
        CHECK(h != NULL, "every supported rate is accepted");
        if (h) {
            CHECK(nomla_apm_frame_size(h) == kRates[i] / 100, "frame size is 10 ms worth of samples");
            nomla_apm_destroy(h);
        }
    }
    /* Out-of-range NS levels are clamped, not rejected: the level comes from a user preference. */
    nomla_apm_config all_on = {1, 1, 3, 1, 1};
    nomla_apm *every = nomla_apm_create(kRate, &all_on);
    CHECK(every != NULL, "AEC3 + NS + AGC2 + HPF all enabled at once succeeds");
    if (every) {
        int16_t f[kFrame];
        memset(f, 0, sizeof f);
        CHECK(nomla_apm_process_render(every, f) == 0, "render succeeds with every stage on");
        CHECK(nomla_apm_process_capture(every, f) == 0, "capture succeeds with every stage on");
        nomla_apm_destroy(every);
    }

    nomla_apm_config wild = {0, 1, 99, 0, 1};
    nomla_apm *clamped = nomla_apm_create(kRate, &wild);
    CHECK(clamped != NULL, "an out-of-range NS level is clamped, not rejected");
    nomla_apm_destroy(clamped);

    /* ---- 2. Every entry point tolerates a NULL handle. ---- */
    int16_t dummy[kFrame];
    memset(dummy, 0, sizeof dummy);
    CHECK(nomla_apm_process_capture(NULL, dummy) != 0, "process_capture(NULL) reports an error");
    CHECK(nomla_apm_process_render(NULL, dummy) != 0, "process_render(NULL) reports an error");
    CHECK(nomla_apm_set_stream_delay_ms(NULL, 0) != 0, "set_stream_delay_ms(NULL) reports an error");
    CHECK(nomla_apm_last_capture_level_dbfs(NULL) <= -99.0f, "level(NULL) is -100 dBFS");
    CHECK(nomla_apm_frame_size(NULL) == 0, "frame_size(NULL) is 0");
    nomla_apm_destroy(NULL); /* must not crash */

    /* ---- 3. Noise suppression only: zeros in, zeros out, no error. ---- */
    nomla_apm *ns = nomla_apm_create(kRate, &ns_only);
    CHECK(ns != NULL, "create with NS+HPF succeeds at 48 kHz");
    if (ns) {
        int16_t frame[kFrame];
        int err = 0;
        CHECK(nomla_apm_process_capture(ns, NULL) != 0, "a NULL frame reports an error");
        for (int n = 0; n < 20 && err == 0; n++) {
            memset(frame, 0, sizeof frame);
            err = nomla_apm_process_capture(ns, frame);
        }
        CHECK(err == 0, "process_capture returns 0");
        CHECK(energy(frame, kFrame) == 0.0, "silence stays silent");
        CHECK(nomla_apm_last_capture_level_dbfs(ns) <= -99.0f, "silence reports -100 dBFS");
        nomla_apm_destroy(ns);
    }

    /* ---- 4. AEC3, and the relation between the near-end and far-end streams. ----
     *
     * process_render for the frame about to be played, then process_capture for the frame just
     * recorded. Getting that wrong still returns 0 from every call. Wrong wirings leave the echo
     * almost untouched (about -0.5 dB) while the correct one attenuates it by about -22 dB; the
     * thresholds below have wide margins.
     *
     * kCaptureFirst is deliberately NOT asserted as broken: it advances the reference by one
     * 10 ms frame, and with a 30 ms echo path the reference still precedes the echo, which AEC3's
     * delay estimator is built to handle. Asserting failure there would break as soon as the
     * estimator improved. */
    static const char *names[] = {"render then capture", "far-end never fed", "far-end mismatched",
                                  "capture before render", "far-end 200 ms late"};
    aec_result r[5];
    printf("AEC residual, last 1 s of 10 s, -6 dB echo path delayed 30 ms, no near-end talker:\n");
    for (int o = 0; o <= (int)kLateRender; o++) {
        r[o] = run_aec((order_t)o);
        CHECK(r[o].err == 0, "every AEC call returns 0 whatever the call order");
        printf("  %-22s : %+6.2f dB\n", names[o], to_db(r[o].output / r[o].input));
    }
    CHECK(r[kOrdered].output < r[kOrdered].input / 16.0,
          "render->capture in the right order attenuates the echo by at least 12 dB");
    CHECK(r[kNoRender].output > r[kNoRender].input / 2.0,
          "with the far-end stream never fed, the echo is NOT cancelled");
    CHECK(r[kMismatched].output > r[kMismatched].input / 2.0,
          "with the wrong audio on the far-end stream, the echo is NOT cancelled");
    CHECK(r[kLateRender].output > r[kLateRender].input / 2.0,
          "with the far-end stream 200 ms late, the echo is NOT cancelled");
    CHECK(r[kOrdered].output * 4.0 < r[kNoRender].output,
          "feeding the far-end stream is what buys the cancellation");
    CHECK(r[kOrdered].output * 4.0 < r[kMismatched].output,
          "feeding the *right* audio on the far-end stream is what buys the cancellation");

    /* process_render must not be mistaken for a capture frame: it feeds the reference, it does
     * not produce near-end output, and it must leave the reported capture level alone. */
    nomla_apm_config aec = {1, 0, 0, 0, 1};
    nomla_apm *a = nomla_apm_create(kRate, &aec);
    CHECK(a != NULL, "create with AEC3 succeeds");
    if (a) {
        int16_t loud[kFrame], quiet[kFrame];
        seed(4242u);
        for (int i = 0; i < kFrame; i++) { loud[i] = (int16_t)(noise() * 4); quiet[i] = 0; }
        CHECK(nomla_apm_process_capture(a, quiet) == 0, "capture of silence succeeds");
        float after_capture = nomla_apm_last_capture_level_dbfs(a);
        CHECK(nomla_apm_process_render(a, loud) == 0, "render of a loud frame succeeds");
        CHECK(nomla_apm_last_capture_level_dbfs(a) == after_capture,
              "process_render does not change the reported capture level");
        nomla_apm_destroy(a);
    }

    /* ---- 5. AEC3 tuning. ----
     * webrtc's defaults passed back in must give the very same canceller as no tuning at all (the
     * app's default path stays what it was), and a tuning must actually reach the suppressor. */
    float tuning[NOMLA_AEC3_PARAM_COUNT];
    nomla_apm_aec3_defaults(tuning);
    CHECK(tuning[NOMLA_AEC3_NORMAL_HF_ENR_SUPPRESS] == 0.1f, "defaults: normal hf enr_suppress is 0.1");
    CHECK(tuning[NOMLA_AEC3_DNE_HOLD_DURATION] == 50.f, "defaults: dominant near-end hold is 50 blocks");
    CHECK(tuning[NOMLA_AEC3_FILTER_REFINED_LENGTH_BLOCKS] == 13.f, "defaults: refined filter is 13 blocks");
    CHECK(tuning[NOMLA_AEC3_EP_ECHO_CAN_SATURATE] == 1.f, "defaults: booleans read as 1");
    CHECK(tuning[NOMLA_AEC3_COMFORT_NOISE_FLOOR_DBFS] < -96.f, "defaults: the last parameter is written");

    double e_null = 0, e_defaults = 0, e_tuned = 0;
    uint32_t h_null = run_tuned(NULL, &e_null);
    uint32_t h_defaults = run_tuned(tuning, &e_defaults);
    CHECK(h_null != 0 && h_defaults != 0, "AEC3 is created with and without a tuning");
    CHECK(h_null == h_defaults, "webrtc's defaults as a tuning give bit-identical output to no tuning");

    float transparent[NOMLA_AEC3_PARAM_COUNT];
    memcpy(transparent, tuning, sizeof tuning);
    transparent[NOMLA_AEC3_NORMAL_LF_ENR_TRANSPARENT] = 50.f;
    transparent[NOMLA_AEC3_NORMAL_LF_ENR_SUPPRESS] = 60.f;
    transparent[NOMLA_AEC3_NORMAL_HF_ENR_TRANSPARENT] = 50.f;
    transparent[NOMLA_AEC3_NORMAL_HF_ENR_SUPPRESS] = 60.f;
    uint32_t h_tuned = run_tuned(transparent, &e_tuned);
    printf("double-talk output: default %.1f dB, transparent normal masks %.1f dB\n",
           to_db(e_null), to_db(e_tuned));
    CHECK(h_tuned != 0 && h_tuned != h_null, "a tuning changes the output");
    CHECK(e_tuned > e_null, "more transparent masks let more through in double talk");

    nomla_apm_config tuned_cfg = {1, 0, 0, 0, 1, NULL};
    float bad[NOMLA_AEC3_PARAM_COUNT];
    memcpy(bad, tuning, sizeof tuning);
    bad[NOMLA_AEC3_DNE_ENR_THRESHOLD] = NAN;
    tuned_cfg.aec3_tuning = bad;
    CHECK(nomla_apm_create(kRate, &tuned_cfg) == NULL, "a NaN in the tuning is refused");
    memcpy(bad, tuning, sizeof tuning);
    bad[NOMLA_AEC3_NEAREND_HF_ENR_TRANSPARENT] = bad[NOMLA_AEC3_NEAREND_HF_ENR_SUPPRESS];
    CHECK(nomla_apm_create(kRate, &tuned_cfg) == NULL,
          "a masking pair with enr_transparent not below enr_suppress is refused");
    memcpy(bad, tuning, sizeof tuning);
    bad[NOMLA_AEC3_FILTER_REFINED_LENGTH_BLOCKS] = -1.f;
    CHECK(nomla_apm_create(kRate, &tuned_cfg) == NULL, "a negative filter length is refused");
    nomla_apm_config ns_tuned = {0, 1, 2, 0, 1, bad};
    nomla_apm *ignored = nomla_apm_create(kRate, &ns_tuned);
    CHECK(ignored != NULL, "without echo cancellation the tuning is not read");
    nomla_apm_destroy(ignored);
    nomla_apm_aec3_defaults(NULL); /* must not crash */

    /* ---- 6. Statistics. ---- */
    test_statistics_null();
    test_statistics_fresh();
    test_statistics_values();

    printf("%s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
