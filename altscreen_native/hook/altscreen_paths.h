/*
 * altscreen_paths.h - one state root for every marker, profile and log file.
 *
 * Local integration: only /mnt/app/root/carplay-altscreen/state is authoritative.
 * SD state is deliberately disarmed by the installed launcher. Neither a stale
 * card nor diagnostic /tmp markers can enable the native transport.
 *
 * Every pointer returned here points at a compile-time string literal: it
 * remains valid and unchanged for the whole process lifetime, and two callers
 * may hold two different paths at the same time. No returned path is built in a
 * shared buffer, and the resolver uses no heap, TLS, thread, or lock.
 */
#ifndef ALTSCREEN_PATHS_H
#define ALTSCREEN_PATHS_H

#include <stddef.h>

#define ALTSCREEN_PATH_MAX 160

/* Directory holding ACTIVE, ARMED* and IAP2_PROFILE. Logging uses the
 * dedicated volatile namespace returned by altscreen_log_path(). */
const char *altscreen_state_root(void);

/* 1 only while the fixed installed runtime directory exists without symlinks. */
int altscreen_state_root_is_authoritative(void);

/* Build root/leaf into out. Returns 1 on success, 0 if the name does not fit. */
int altscreen_state_path(const char *leaf, char *out, size_t cap);

/* 1 when root/leaf exists. Never consults a second namespace. */
int altscreen_marker_present(const char *leaf);

/* Log the selected root and why. Safe to call repeatedly and from any thread;
 * reports once per root. */
void altscreen_paths_report(void);

/* Observe the existing log namespace; never create a new volatile directory. */
int altscreen_prepare_volatile_root(void);

/* Canonical volatile log and profile locations, so nothing hardcodes them. */
const char *altscreen_log_path(void);
/* Retained upstream API; local integration returns NULL (no automatic SD logs). */
const char *altscreen_sd_log_path(void);
const char *altscreen_profile_path(void);
#endif
