/* Shared NmeArray growth, adapted from LuKa a63d5474 (GPL-3.0).
 * The exact MU1316 Encode implementation uses libc realloc for this buffer. */
#ifndef CARPLAY_IDENTIFY_BUFFER_H
#define CARPLAY_IDENTIFY_BUFFER_H
#include "hook_framework.h"
#include "logging.h"
#include <stddef.h>

#define HOOK_IDENTIFY_MAX 8192u
typedef struct {
    uint8_t *base;
    unsigned int length, capacity;
} hook_identify_array_t;
#if defined(__arm__)
typedef char identify_layout_check[(offsetof(hook_identify_array_t,length)==4 &&
                                    offsetof(hook_identify_array_t,capacity)==8)?1:-1];
#endif
static inline uint8_t *hook_identify_grow(hook_context_t *ctx, size_t len,
                                         size_t new_len, size_t *out_cap) {
    if (!ctx || !ctx->_priv) return NULL;
    hook_identify_array_t *array = (hook_identify_array_t *)ctx->_priv;
    uint8_t *base = array->base;
    unsigned int arr_len = array->length, cap = array->capacity;
    if (!base || !cap || cap > HOOK_IDENTIFY_MAX || arr_len != len ||
            len > cap || new_len < len || new_len > HOOK_IDENTIFY_MAX) return NULL;
    if (cap >= new_len) { if (out_cap) *out_cap = cap; return base; }
    size_t alloc = new_len > HOOK_IDENTIFY_MAX - 64 ? HOOK_IDENTIFY_MAX : new_len + 64;
    uint8_t *next = (uint8_t *)realloc(base, alloc);
    if (!next) return NULL;
    array->base = next;
    array->capacity = (unsigned int)alloc;
    if (out_cap) *out_cap = alloc;
    return next;
}
#endif
