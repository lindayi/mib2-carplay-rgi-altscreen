#ifndef ALTSCREEN_LOCAL_UI_CONTRACT_H
#define ALTSCREEN_LOCAL_UI_CONTRACT_H
#include "../integration.h"
#include "ui_session.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Exec-time environment is immutable. Do not consult preferences during /info. */
static inline int alt_local_phone_profile(void) {
    const char *profile = getenv("ALTSCREEN_PHONE_PROFILE");
    if (profile && !strcmp(profile, "map")) return 0;
    if (profile && !strcmp(profile, "card")) return 1;
    return -1;
}

static inline int alt_local_decimal(const char *text, uint64_t max, uint64_t *out) {
    uint64_t value = 0;
    unsigned digits = 0;
    if (!text || !*text) return 0;
    while (*text) {
        unsigned digit = (unsigned char)*text++ - '0';
        if (digit > 9u || ++digits > 20u || value > UINT64_MAX / 10u) return 0;
        value *= 10u;
        if (value > UINT64_MAX - digit) return 0;
        value += digit;
        if (value > max) return 0;
    }
    *out = value;
    return 1;
}

static inline int alt_local_offset(const char *text, int *out) {
    uint64_t value;
    int negative = *text == '-';
    if (negative) ++text;
    if (!alt_local_decimal(text, 8192u, &value)) return 0;
    *out = negative ? -(int)value : (int)value;
    return 1;
}

static inline int alt_local_hmi(char *view, size_t view_cap, char *layout,
                         size_t layout_cap, int *dx, int *dy) {
    FILE *f;
    char line[256];
    uint64_t epoch = 0, pid = 0, expiry = 0, now;
    int seen = 0, valid = 1;
    uint32_t current = altscreen_ui_session_generation();
    uint32_t current_pid = (uint32_t)getpid();
    if (!view || !view_cap || !layout || !layout_cap || !dx || !dy || !current) return 0;
    f = fopen(ALTSCREEN_HMI_STATE, "r");
    if (!f) { alt_ui_hmi_publish(current, current_pid, 0, NULL, NULL, 0, 0); return 0; }
    view[0] = layout[0] = 0;
    *dx = *dy = 0;
    while (fgets(line, sizeof(line), f)) {
        int bit = 0;
        size_t n = strlen(line);
        if (n == sizeof(line) - 1u) { valid = 0; break; }
        while (n && (line[n-1] == '\n' || line[n-1] == '\r')) line[--n] = 0;
        if (!strncmp(line, "view=", 5)) {
            if (strlen(line + 5) >= view_cap) { valid = 0; break; }
            memcpy(view, line + 5, strlen(line + 5) + 1); bit = 1;
        } else if (!strncmp(line, "layout_name=", 12)) {
            if (strlen(line + 12) >= layout_cap) { valid = 0; break; }
            memcpy(layout, line + 12, strlen(line + 12) + 1); bit = 2;
        } else if (!strncmp(line, "small_stage_dx=", 15) && alt_local_offset(line + 15, dx)) bit = 4;
        else if (!strncmp(line, "small_stage_dy=", 15) && alt_local_offset(line + 15, dy)) bit = 8;
        else if (!strncmp(line, "receiver_epoch=", 15) && alt_local_decimal(line + 15, UINT32_MAX, &epoch)) bit = 16;
        else if (!strncmp(line, "receiver_pid=", 13) && alt_local_decimal(line + 13, UINT32_MAX, &pid)) bit = 32;
        else if (!strncmp(line, "expiry_ms=", 10) && alt_local_decimal(line + 10, UINT64_MAX, &expiry)) bit = 64;
        if (!bit || (seen & bit)) { valid = 0; break; }
        seen |= bit;
    }
    fclose(f);
    now = alt_ui_realtime_ms();
    valid = valid && seen == 127 && epoch == current && pid == current_pid &&
        current_pid == (uint32_t)getpid() && now && expiry > now &&
        expiry - now <= ALT_LOCAL_HMI_LEASE_MS &&
        current == altscreen_ui_session_generation() &&
        (!strcmp(view, "FULL") || !strcmp(view, "SMALL")) &&
        (strstr(layout, "LayoutMIB2HighB9") || strstr(layout, "LayoutMIB2HighQ7")) &&
        *dx >= -8192 && *dx <= 8192 && *dy >= -8192 && *dy <= 8192;
    alt_ui_hmi_publish(current, current_pid, expiry,
        valid ? view : NULL, valid ? layout : NULL, *dx, *dy);
    return valid;
}
#endif
