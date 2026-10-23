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
 * JNI bridge for the WebRTC audio processing module; the Kotlin side is WebRtcApmNative.kt.
 *
 * nomla_apm writes exactly nomla_apm_frame_size() samples into the buffer it is given, so the
 * frame size is read from the instance and shorter arrays are refused. Swapping processRender and
 * processCapture fails silently (every call still returns 0, echo cancellation just stops);
 * tests/test_jni_bridges.cpp catches that.
 *
 * Every function is noexcept, and every nomla_apm_* function catches (...) itself, so no
 * exception can reach the JVM.
 *
 * Threads: processCapture and processRender take no lock and may run concurrently on the capture
 * and playback threads; create and destroy take the handle table's mutex and belong to the owning
 * thread. Order per tick: render for the frame about to be played, then capture.
 */
#include <jni.h>

#include <cstdint>

#include "jni_bridges.h"
#include "jni_common.h"
#include "jni_native_handle.h"
#include "webrtc_apm/nomla_apm.h"

namespace {

/* webrtc::AudioProcessing::Error values, spelled out so the webrtc C++ headers stay out of the
 * JNI layer (see audio_processing.h in third_party/webrtc-audio-processing). */
constexpr jint kNullPointerError = -5;
constexpr jint kBadDataLengthError = -8;

/* 10 ms at 48 kHz, the highest rate nomla_apm accepts. */
constexpr std::size_t kMaxFrame = 480;

nomla::HandleTable& processors() { return nomla::handleTable<nomla_apm>(); }

jint process(JNIEnv* env, jlong handle, jshortArray frame,
             int (*fn)(nomla_apm*, int16_t*)) noexcept {
    auto* apm = static_cast<nomla_apm*>(processors().get(handle));
    if (apm == nullptr || frame == nullptr) return kNullPointerError;
    if (env->GetArrayLength(frame) < nomla_apm_frame_size(apm)) return kBadDataLengthError;
    jsize samples = nomla_apm_frame_size(apm);
    nomla::RegionBuffer<jshort, kMaxFrame> data(samples);
    if (data.data() == nullptr) return kNullPointerError;
    data.read(env, frame, 0, samples);
    jint err = fn(apm, reinterpret_cast<int16_t*>(data.data()));
    // Written back for both streams: the APM may modify the render buffer too.
    data.write(env, frame, 0, samples);
    return err;
}

/* aec3Tuning: null for webrtc's default AEC3 config, else exactly NOMLA_AEC3_PARAM_COUNT values
 * (Aec3Param in Aec3Tuning.kt); any other length is refused with 0 rather than read short. */
jlong create(JNIEnv* env, jobject, jint sampleRate, jboolean aec, jboolean ns, jint nsLevel, jboolean agc,
             jboolean highPass, jfloatArray aec3Tuning) noexcept {
    float tuning[NOMLA_AEC3_PARAM_COUNT];
    const float* tuningOrNull = nullptr;
    if (aec3Tuning != nullptr) {
        if (env->GetArrayLength(aec3Tuning) != NOMLA_AEC3_PARAM_COUNT) return 0;
        env->GetFloatArrayRegion(aec3Tuning, 0, NOMLA_AEC3_PARAM_COUNT, tuning);
        tuningOrNull = tuning;
    }
    nomla_apm_config cfg{aec ? 1 : 0, ns ? 1 : 0, nsLevel, agc ? 1 : 0, highPass ? 1 : 0, tuningOrNull};
    nomla_apm* apm = nomla_apm_create(sampleRate, &cfg);
    if (apm == nullptr) return 0;
    jlong handle = processors().add(apm);
    if (handle == 0) nomla_apm_destroy(apm);  // the table could not take ownership
    return handle;
}

jint frameSize(JNIEnv*, jobject, jlong handle) noexcept {
    return nomla_apm_frame_size(static_cast<nomla_apm*>(processors().get(handle)));
}

jint processCapture(JNIEnv* env, jobject, jlong handle, jshortArray frame) noexcept {
    return process(env, handle, frame, nomla_apm_process_capture);
}

jint processRender(JNIEnv* env, jobject, jlong handle, jshortArray frame) noexcept {
    return process(env, handle, frame, nomla_apm_process_render);
}

// nomla_apm_set_stream_delay_ms is intentionally not bridged: AEC3 estimates the delay itself.

jfloat lastCaptureLevelDbfs(JNIEnv*, jobject, jlong handle) noexcept {
    return nomla_apm_last_capture_level_dbfs(static_cast<nomla_apm*>(processors().get(handle)));
}

void destroy(JNIEnv*, jobject, jlong handle) noexcept {
    // release() hands the instance to exactly one caller, so destroying twice frees once.
    nomla_apm_destroy(static_cast<nomla_apm*>(processors().release(handle)));
}

}  // namespace

bool nomla::registerWebRtcApmNatives(JNIEnv* env) {
    const std::array<JNINativeMethod, 6> methods = {
        nomla::nativeMethod("create", create),
        nomla::nativeMethod("frameSize", frameSize),
        nomla::nativeMethod("processCapture", processCapture),
        nomla::nativeMethod("processRender", processRender),
        nomla::nativeMethod("lastCaptureLevelDbfs", lastCaptureLevelDbfs),
        nomla::nativeMethod("destroy", destroy),
    };
    return nomla::registerNatives(env, "koeln/becker/nomla/mumble/audio/native/WebRtcApmNative", methods);
}
