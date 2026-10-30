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

/*
 * C interface to webrtc-audio-processing (AEC3, noise suppression, AGC2, high-pass filter).
 *
 * C linkage and C types only; no function here can throw or let an exception escape (see
 * nomla_apm.cpp).
 *
 * The two audio streams have to be fed in a fixed relation:
 *
 *   for every 10 ms tick:
 *       nomla_apm_process_render(h, far_end_frame_about_to_be_played);
 *       nomla_apm_process_capture(h, near_end_frame_just_recorded);
 *
 * The far-end frame must be handed over before the near-end frame that will contain its echo.
 * Getting this wrong produces no error: every call still returns 0 and echo cancellation silently
 * stops working. tests/test_apm.c covers this.
 */
#ifndef NOMLA_APM_H
#define NOMLA_APM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Every function below is noexcept in C++ (see nomla_apm.cpp): no exception can cross this
 * boundary. The macro keeps the header valid C. */
#ifdef __cplusplus
#  define NOMLA_APM_NOEXCEPT noexcept
#else
#  define NOMLA_APM_NOEXCEPT
#endif

typedef struct nomla_apm nomla_apm;

/* Indices into nomla_apm_config.aec3_tuning: the webrtc::EchoCanceller3Config fields a caller may
 * set. Each value is a float; integer fields are rounded, boolean fields are true unless 0.
 * Aec3Param in Aec3Tuning.kt lists the same names in the same order (Aec3ParamHeaderTest pins it). */
enum {
    /* suppressor.normal_tuning: used while the dominant near-end detector says "echo". */
    NOMLA_AEC3_NORMAL_LF_ENR_TRANSPARENT,
    NOMLA_AEC3_NORMAL_LF_ENR_SUPPRESS,
    NOMLA_AEC3_NORMAL_LF_EMR_TRANSPARENT,
    NOMLA_AEC3_NORMAL_HF_ENR_TRANSPARENT,
    NOMLA_AEC3_NORMAL_HF_ENR_SUPPRESS,
    NOMLA_AEC3_NORMAL_HF_EMR_TRANSPARENT,
    NOMLA_AEC3_NORMAL_MAX_INC_FACTOR,
    NOMLA_AEC3_NORMAL_MAX_DEC_FACTOR_LF,
    /* suppressor.nearend_tuning: used while it says "near end dominates". */
    NOMLA_AEC3_NEAREND_LF_ENR_TRANSPARENT,
    NOMLA_AEC3_NEAREND_LF_ENR_SUPPRESS,
    NOMLA_AEC3_NEAREND_LF_EMR_TRANSPARENT,
    NOMLA_AEC3_NEAREND_HF_ENR_TRANSPARENT,
    NOMLA_AEC3_NEAREND_HF_ENR_SUPPRESS,
    NOMLA_AEC3_NEAREND_HF_EMR_TRANSPARENT,
    NOMLA_AEC3_NEAREND_MAX_INC_FACTOR,
    NOMLA_AEC3_NEAREND_MAX_DEC_FACTOR_LF,
    /* suppressor, other */
    NOMLA_AEC3_NEAREND_AVERAGE_BLOCKS,
    NOMLA_AEC3_LAST_LF_BAND,
    NOMLA_AEC3_FIRST_HF_BAND,
    NOMLA_AEC3_FLOOR_FIRST_INCREASE,
    NOMLA_AEC3_CONSERVATIVE_HF_SUPPRESSION,
    /* suppressor.dominant_nearend_detection */
    NOMLA_AEC3_DNE_ENR_THRESHOLD,
    NOMLA_AEC3_DNE_ENR_EXIT_THRESHOLD,
    NOMLA_AEC3_DNE_SNR_THRESHOLD,
    NOMLA_AEC3_DNE_HOLD_DURATION,
    NOMLA_AEC3_DNE_TRIGGER_THRESHOLD,
    NOMLA_AEC3_DNE_USE_DURING_INITIAL_PHASE,
    NOMLA_AEC3_DNE_USE_UNBOUNDED_ECHO_SPECTRUM,
    /* suppressor.use_subband_nearend_detection and suppressor.subband_nearend_detection */
    NOMLA_AEC3_USE_SUBBAND_NEAREND_DETECTION,
    NOMLA_AEC3_SUBBAND_NEAREND_AVERAGE_BLOCKS,
    NOMLA_AEC3_SUBBAND1_LOW,
    NOMLA_AEC3_SUBBAND1_HIGH,
    NOMLA_AEC3_SUBBAND2_LOW,
    NOMLA_AEC3_SUBBAND2_HIGH,
    NOMLA_AEC3_SUBBAND_NEAREND_THRESHOLD,
    NOMLA_AEC3_SUBBAND_SNR_THRESHOLD,
    /* suppressor.high_bands_suppression */
    NOMLA_AEC3_HIGH_BANDS_ENR_THRESHOLD,
    NOMLA_AEC3_HIGH_BANDS_MAX_GAIN_DURING_ECHO,
    NOMLA_AEC3_ANTI_HOWLING_ACTIVATION_THRESHOLD,
    NOMLA_AEC3_ANTI_HOWLING_GAIN,
    /* ep_strength */
    NOMLA_AEC3_EP_DEFAULT_GAIN,
    NOMLA_AEC3_EP_DEFAULT_LEN,
    NOMLA_AEC3_EP_NEAREND_LEN,
    NOMLA_AEC3_EP_ECHO_CAN_SATURATE,
    NOMLA_AEC3_EP_BOUNDED_ERL,
    NOMLA_AEC3_EP_ERLE_ONSET_COMPENSATION_IN_DOMINANT_NEAREND,
    NOMLA_AEC3_EP_USE_CONSERVATIVE_TAIL_FREQUENCY_RESPONSE,
    /* erle */
    NOMLA_AEC3_ERLE_MIN,
    NOMLA_AEC3_ERLE_MAX_L,
    NOMLA_AEC3_ERLE_MAX_H,
    NOMLA_AEC3_ERLE_ONSET_DETECTION,
    NOMLA_AEC3_ERLE_NUM_SECTIONS,
    /* echo_audibility */
    NOMLA_AEC3_AUDIBILITY_LOW_RENDER_LIMIT,
    NOMLA_AEC3_AUDIBILITY_NORMAL_RENDER_LIMIT,
    NOMLA_AEC3_AUDIBILITY_FLOOR_POWER,
    NOMLA_AEC3_AUDIBILITY_THRESHOLD_LF,
    NOMLA_AEC3_AUDIBILITY_THRESHOLD_MF,
    NOMLA_AEC3_AUDIBILITY_THRESHOLD_HF,
    NOMLA_AEC3_AUDIBILITY_USE_STATIONARITY_PROPERTIES,
    NOMLA_AEC3_AUDIBILITY_USE_STATIONARITY_PROPERTIES_AT_INIT,
    /* filter lengths, in 64-sample blocks at 16 kHz (4 ms each) */
    NOMLA_AEC3_FILTER_REFINED_LENGTH_BLOCKS,
    NOMLA_AEC3_FILTER_COARSE_LENGTH_BLOCKS,
    NOMLA_AEC3_FILTER_REFINED_INITIAL_LENGTH_BLOCKS,
    NOMLA_AEC3_FILTER_COARSE_INITIAL_LENGTH_BLOCKS,
    /* comfort_noise */
    NOMLA_AEC3_COMFORT_NOISE_FLOOR_DBFS,
    NOMLA_AEC3_PARAM_COUNT
};

typedef struct {
    int echo_cancellation;        /* AEC3, full (non-mobile) mode */
    int noise_suppression;
    int noise_suppression_level;  /* 0 low, 1 moderate, 2 high, 3 very high; clamped */
    int gain_control;             /* AGC2 adaptive digital */
    int high_pass;
    /* NULL: AEC3 with webrtc's built-in default config, exactly as before tuning existed.
     * Otherwise NOMLA_AEC3_PARAM_COUNT values indexed by the enum above; only read by
     * nomla_apm_create, and ignored unless echo_cancellation is set. */
    const float *aec3_tuning;
} nomla_apm_config;

/* Writes webrtc's default value of every tunable AEC3 field into out[NOMLA_AEC3_PARAM_COUNT].
 * Passing these back as aec3_tuning gives the same echo canceller as passing NULL. */
void nomla_apm_aec3_defaults(float *out) NOMLA_APM_NOEXCEPT;

/* sample_rate_hz must be 8000, 16000, 32000 or 48000. Returns NULL on any other rate, on a NULL
 * config, on an aec3_tuning with a non-finite value, a negative count or length, or a masking
 * threshold pair whose enr_transparent is not below its enr_suppress, and on allocation failure.
 * Other out-of-range tuning values are clamped by EchoCanceller3Config::Validate. */
nomla_apm *nomla_apm_create(int sample_rate_hz, const nomla_apm_config *cfg) NOMLA_APM_NOEXCEPT;

/* Samples per 10 ms frame at the rate this instance was created with, i.e. exactly how many
 * int16_t nomla_apm_process_capture and nomla_apm_process_render read and write. Returns 0 for
 * a NULL handle. Callers must make sure their buffer is this long: a short buffer is an
 * out-of-bounds read and write, not an error code. */
int nomla_apm_frame_size(const nomla_apm *h) NOMLA_APM_NOEXCEPT;

/* Process one 10 ms mono near-end frame in place. Returns 0 on success
 * (webrtc::AudioProcessing::kNoError), a negative webrtc::AudioProcessing::Error otherwise. */
int nomla_apm_process_capture(nomla_apm *h, int16_t *frame) NOMLA_APM_NOEXCEPT;

/* Feed one 10 ms mono far-end (playback) frame; the buffer may be modified. Returns 0 on
 * success. Must be called before the capture frame that carries its echo. */
int nomla_apm_process_render(nomla_apm *h, int16_t *frame) NOMLA_APM_NOEXCEPT;

/* Optional hint about the capture-to-render round trip. AEC3 estimates the delay itself, so
 * this exists for the host test and is deliberately not exported through JNI. */
int nomla_apm_set_stream_delay_ms(nomla_apm *h, int delay_ms) NOMLA_APM_NOEXCEPT;

/* RMS level in dBFS of the last frame returned by nomla_apm_process_capture, -100 for digital
 * silence and for a NULL handle. */
float nomla_apm_last_capture_level_dbfs(const nomla_apm *h) NOMLA_APM_NOEXCEPT;

/* webrtc::AudioProcessingStats, flattened to floats. A field webrtc leaves empty is NAN: all six
 * before the first capture frame and with AEC3 off.
 *
 * ERL, ERLE and delay_ms are point-in-time snapshots of AEC3's state, not aggregates. ERL starts
 * at -30 dB (AEC3's kMaxErl) and only moves after a 2 s startup phase with a converged filter.
 * divergent_filter_fraction, delay_median_ms and delay_std_ms are never written by the vendored
 * webrtc version (nothing in AudioProcessingImpl fills them), so they are always NAN. They keep
 * their slots so the layout stays stable across a webrtc update.
 *
 * Staleness: webrtc passes stats through a one-slot queue. The first capture frame after a read
 * fills it and later frames are dropped until the next read, so a read returns the snapshot of the
 * first capture frame after the previous read (on a handle never read before, frame 1). Polled
 * every N frames, the values are about N - 1 frames old. */
typedef struct {
    float erl_db;                    /* echo return loss, 10 log10(P_far / P_echo); -30 at start */
    float erle_db;                   /* echo return loss enhancement, 10 log10(P_echo / P_out) */
    float divergent_filter_fraction; /* always NAN in this webrtc version */
    float delay_median_ms;           /* always NAN in this webrtc version */
    float delay_std_ms;              /* always NAN in this webrtc version */
    float delay_ms;                  /* render buffer delay in 4 ms blocks; starts at 16 ms */
} nomla_apm_stats;

/* Fills *out with the APM's statistics (see the staleness note above). Returns 0, -5
 * (kNullPointerError) for a NULL handle or out, or -1 (kUnspecifiedError) if webrtc threw.
 * Allocates nothing on our side; takes webrtc's stats mutex, which the capture thread also takes
 * once per frame. */
int nomla_apm_get_statistics(nomla_apm *h, nomla_apm_stats *out) NOMLA_APM_NOEXCEPT;

/* Releases the instance. A NULL handle is accepted and ignored. */
void nomla_apm_destroy(nomla_apm *h) NOMLA_APM_NOEXCEPT;

#ifdef __cplusplus
}
#endif

#endif /* NOMLA_APM_H */
