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
 * The only exported symbol of libhumla_native.so. Binds every bridge's native methods by name, so a
 * missing class or a signature mismatch fails System.loadLibrary instead of the first call.
 */
#include <jni.h>

#include "jni_bridges.h"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    bool ok = humla::registerOpusNatives(env) && humla::registerSpeexdspNatives(env) &&
              humla::registerRnnoiseNatives(env) && humla::registerWebRtcApmNatives(env);
    return ok ? JNI_VERSION_1_6 : JNI_ERR;
}
