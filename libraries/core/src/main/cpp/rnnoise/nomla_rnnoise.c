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

#include "nomla_rnnoise.h"
#include <rnnoise.h>
#include <math.h>
#include <stdlib.h>

extern const unsigned char nomla_rnnoise_model_blob[];
extern const unsigned int nomla_rnnoise_model_blob_size;

struct nomla_rnnoise {
    RNNModel *model;
    DenoiseState *st;
    float in[HUMLA_RNNOISE_FRAME_SIZE];
    float out[HUMLA_RNNOISE_FRAME_SIZE];
};

nomla_rnnoise *nomla_rnnoise_create(void) {
    if (rnnoise_get_frame_size() != HUMLA_RNNOISE_FRAME_SIZE) return NULL;
    nomla_rnnoise *h = calloc(1, sizeof *h);
    if (!h) return NULL;
    h->model = rnnoise_model_from_buffer(nomla_rnnoise_model_blob, (int)nomla_rnnoise_model_blob_size);
    if (!h->model) { free(h); return NULL; }
    h->st = rnnoise_create(h->model);
    /* NOTE: free(), never rnnoise_model_free() — see nomla_rnnoise_destroy. */
    if (!h->st) { free(h->model); free(h); return NULL; }
    return h;
}

/* h and frame are preconditions (see nomla_rnnoise.h) and are dereferenced unconditionally,
   unlike in nomla_rnnoise_destroy, which tolerates NULL. The JNI bridge guarantees both. */
float nomla_rnnoise_process(nomla_rnnoise *h, int16_t *frame) {
    for (int i = 0; i < HUMLA_RNNOISE_FRAME_SIZE; i++) h->in[i] = (float)frame[i];
    float p = rnnoise_process_frame(h->st, h->out, h->in);
    for (int i = 0; i < HUMLA_RNNOISE_FRAME_SIZE; i++) {
        float v = h->out[i];
        if (v > 32767.f) v = 32767.f; else if (v < -32768.f) v = -32768.f;
        frame[i] = (int16_t)lrintf(v);
    }
    if (p < 0.f) p = 0.f; else if (p > 1.f) p = 1.f;
    return p;
}

void nomla_rnnoise_destroy(nomla_rnnoise *h) {
    if (!h) return;
    rnnoise_destroy(h->st);
    /* rnnoise_model_free() would fclose(model->file), which rnnoise_model_from_buffer never
       initialises (upstream src/denoise.c:235-242 vs :271-275). The struct is a plain malloc
       with blob == NULL and rnnoise_init keeps no pointer to it, so free() is the whole job. */
    free(h->model);
    free(h);
}
