/*
 * Placement veil adapted from yuedizhibo V3.8 Fix1 main.cpp.
 * Copyright (c) 2026 yuedizhibo; PolyForm Noncommercial and Additional Terms.
 * Java still moves the plane. This only hides old-view pixels during the move.
 */
#ifndef ALTSCREEN_PLACEMENT_VEIL_H
#define ALTSCREEN_PLACEMENT_VEIL_H
#include "cluster_video_display.h"
#include <string.h>

struct PlacementVeil {
    bool active, fading, restore_pending;
    unsigned long long start_us, fade_start_us, last_present_us;
    unsigned frames;

    PlacementVeil() { reset(); }
    void reset() { memset(this, 0, sizeof(*this)); }
    void cancel() {
        if (active) restore_pending = true;
        active = fading = false;
    }
    bool needs_present() const { return active || restore_pending; }
    void begin(unsigned long long now) {
        reset();
        active = true;
        start_us = now;
    }
    bool present(ClusterVideoDisplay &display, const VideoFrame *frame,
                 unsigned long long now) {
        if (restore_pending) {
            if (!(frame ? display.present_frame(*frame) : display.present_overlay_frame())) return false;
            restore_pending = false;
            return true;
        }
        if (!active) return frame ? display.present_frame(*frame) : true;
        const unsigned long long held = now >= start_us ? now - start_us : 450000ULL;
        if (frame) ++frames;
        if (!fading && ((held >= 150000ULL && frames >= 2) || held >= 450000ULL)) {
            fading = true;
            fade_start_us = now;
        }
        const unsigned long long elapsed =
            now >= fade_start_us ? now - fade_start_us : 150000ULL;
        if (!frame && last_present_us && !fading) return true;
        if (!frame && last_present_us && now >= last_present_us &&
                now - last_present_us < 16000ULL) return true;
        const bool done = fading && elapsed >= 150000ULL;
        const float opacity = !fading ? 0.0f : done ? 1.0f : (float)elapsed / 150000.0f;
        if (!(frame ? display.present_frame_faded(*frame, opacity) : display.present_faded(opacity)))
            return false;
        last_present_us = now;
        if (done) active = false;
        return true;
    }
};
#endif
