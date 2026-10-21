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
 * The registration entry point of each JNI bridge. JNI_OnLoad (jni_onload.cpp) calls all of them;
 * the host tests call them one at a time.
 *
 * Each returns false, with a Java exception pending, if a class or method it binds is missing or a
 * signature does not match the Kotlin `external fun`. jni_registrations.txt lists every binding.
 */
#ifndef HUMLA_JNI_BRIDGES_H
#define HUMLA_JNI_BRIDGES_H

#include <jni.h>

namespace nomla {

bool registerOpusNatives(JNIEnv* env);
bool registerSpeexdspNatives(JNIEnv* env);
bool registerRnnoiseNatives(JNIEnv* env);
bool registerWebRtcApmNatives(JNIEnv* env);

}  // namespace nomla

#endif  // HUMLA_JNI_BRIDGES_H
