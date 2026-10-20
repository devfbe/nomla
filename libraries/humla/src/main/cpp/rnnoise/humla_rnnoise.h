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

#ifndef HUMLA_RNNOISE_H
#define HUMLA_RNNOISE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define HUMLA_RNNOISE_FRAME_SIZE 480
typedef struct humla_rnnoise humla_rnnoise;
/* Allocates a denoiser using the embedded model. Returns NULL on failure. */
humla_rnnoise *humla_rnnoise_create(void);
/* Denoises one 10 ms 48 kHz mono frame in place and returns the VAD probability [0,1].
   h must be non-NULL and frame must hold exactly HUMLA_RNNOISE_FRAME_SIZE samples; both are
   preconditions, not checked. A humla_rnnoise is not thread-safe, but separate instances are
   independent and may run concurrently. */
float humla_rnnoise_process(humla_rnnoise *h, int16_t *frame);
/* Frees the denoiser. h may be NULL. */
void humla_rnnoise_destroy(humla_rnnoise *h);
#ifdef __cplusplus
}
#endif
#endif
