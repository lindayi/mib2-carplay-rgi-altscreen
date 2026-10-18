/* Metadata-only readiness publication; no URL selection or command submission. */
#ifndef ALTSCREEN_UI_SESSION_H
#define ALTSCREEN_UI_SESSION_H
#include <stdint.h>
#include <stddef.h>

uint32_t alt_ui_session_epoch(void *receiver);
int altscreen_queue_ui(const char *url, uint32_t epoch);
uint32_t alt_ui_accepted_revision(void *receiver);
int alt_ui_stop(void *receiver);
void alt_ui_resume(void *receiver);
void alt_ui_reapply(void *receiver);
uint64_t alt_ui_realtime_ms(void);
void alt_ui_hmi_publish(uint32_t epoch, uint32_t pid, uint64_t expiry,
                        const char *view, const char *layout, int dx, int dy);
int alt_ui_hmi_snapshot(char *view, size_t view_cap, char *layout, size_t layout_cap, int *dx, int *dy);
uint32_t altscreen_ui_session_generation(void);
uint32_t alt_ui_session_begin(void *receiver);
void alt_ui_session_note(void *receiver, uint32_t epoch,
                         int main_ready, int started, int channel_ready);
void alt_ui_session_closing(void *receiver);
void alt_ui_session_forget(void *receiver);
#endif
