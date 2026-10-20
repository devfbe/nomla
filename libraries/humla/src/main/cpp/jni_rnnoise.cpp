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
 * JNI bridge for RNNoise; the Kotlin side is RnnoiseNative.kt.
 *
 * humla_rnnoise_process writes exactly HUMLA_RNNOISE_FRAME_SIZE samples into a bare int16_t*, so
 * shorter arrays are refused here. Handles may arrive after release() (see jni_native_handle.h).
 *
 * Every function is noexcept as a backstop: an exception unwinding into the JVM is undefined.
 *
 * Threads: processFrame is the audio-thread entry point and takes no lock. create and destroy take
 * the handle table's mutex and belong to the owning thread. One handle must not be processed from
 * two threads at once; separate handles may.
 */
#include <jni.h>

#include "jni_bridges.h"
#include "jni_common.h"
#include "jni_native_handle.h"
#include "rnnoise/humla_rnnoise.h"

namespace {

humla::HandleTable& denoisers() { return humla::handleTable<humla_rnnoise>(); }

jlong create(JNIEnv*, jobject) noexcept {
    humla_rnnoise* denoiser = humla_rnnoise_create();
    if (denoiser == nullptr) return 0;
    jlong handle = denoisers().add(denoiser);
    if (handle == 0) humla_rnnoise_destroy(denoiser);  // the table could not take ownership
    return handle;
}

jfloat processFrame(JNIEnv* env, jobject, jlong handle, jshortArray frame) noexcept {
    auto* denoiser = static_cast<humla_rnnoise*>(denoisers().get(handle));
    if (denoiser == nullptr || frame == nullptr) return -1.0f;
    // A short frame would be an out-of-bounds write inside rnnoise.
    if (env->GetArrayLength(frame) < HUMLA_RNNOISE_FRAME_SIZE) return -1.0f;
    jshort data[HUMLA_RNNOISE_FRAME_SIZE];
    env->GetShortArrayRegion(frame, 0, HUMLA_RNNOISE_FRAME_SIZE, data);
    float probability = humla_rnnoise_process(denoiser, reinterpret_cast<int16_t*>(data));
    env->SetShortArrayRegion(frame, 0, HUMLA_RNNOISE_FRAME_SIZE, data);
    return probability;
}

void destroy(JNIEnv*, jobject, jlong handle) noexcept {
    // release() hands the denoiser to exactly one caller, so destroying twice frees once.
    humla_rnnoise_destroy(static_cast<humla_rnnoise*>(denoisers().release(handle)));
}

}  // namespace

bool humla::registerRnnoiseNatives(JNIEnv* env) {
    const std::array<JNINativeMethod, 3> methods = {
        humla::nativeMethod("create", create),
        humla::nativeMethod("processFrame", processFrame),
        humla::nativeMethod("destroy", destroy),
    };
    return humla::registerNatives(env, "se/lublin/humla/audio/native/RnnoiseNative", methods);
}
