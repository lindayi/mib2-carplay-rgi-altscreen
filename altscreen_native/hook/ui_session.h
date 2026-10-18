/* Metadata-only readiness publication; no URL selection or command submission. */
#ifndef ALTSCREEN_UI_SESSION_H
#define ALTSCREEN_UI_SESSION_H
#include <stdint.h>
uint32_t altscreen_ui_session_generation(void);
uint32_t alt_ui_session_begin(void *receiver);
void alt_ui_session_note(void *receiver, uint32_t epoch,
                         int main_ready, int started, int channel_ready);
void alt_ui_session_closing(void *receiver);
void alt_ui_session_forget(void *receiver);
#endif
