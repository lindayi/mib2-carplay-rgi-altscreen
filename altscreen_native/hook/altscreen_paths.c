/*
 * Adapted from yuedizhibo's V3.8 path resolver; see ../PROVENANCE.md and NOTICE.
 * The installed runtime, not a removable Toolbox card, is the arming authority.
 */
#include "altscreen_paths.h"
#include "altscreen_core.h"
#include <string.h>
#include <sys/stat.h>

#define ALTSCREEN_RUNTIME_ROOT "/mnt/app/root/carplay-altscreen/state"
#define ALTSCREEN_RUNTIME_PROFILE ALTSCREEN_RUNTIME_ROOT "/IAP2_PROFILE"
#define ALTSCREEN_RUNTIME_LOG "/tmp/MMI-Cockpit-Carplay.altscreen_hook.log"
static unsigned g_reported;

#ifndef S_ISLNK
#define S_ISLNK(m) (((m) & 0170000) == 0120000)
#endif

/* Reject symlinks in every arming path component, not just at the leaf. */
static int path_kind(const char *path, int directory) {
    char part[ALTSCREEN_PATH_MAX];
    struct stat st;
    size_t i, n;
    memset(&st, 0, sizeof(st));
    if (!path || path[0] != '/') return 0;
    n = strlen(path);
    if (n < 2u || n >= sizeof(part)) return 0;
    for (i = 1u; i <= n; ++i) {
        if (path[i] != '/' && path[i] != 0) continue;
        memcpy(part, path, i);
        part[i] = 0;
        if (lstat(part, &st) != 0 || S_ISLNK(st.st_mode)) return 0;
        if (path[i] != 0 && !S_ISDIR(st.st_mode)) return 0;
    }
    return directory ? S_ISDIR(st.st_mode) : S_ISREG(st.st_mode);
}

const char *altscreen_state_root(void) { return ALTSCREEN_RUNTIME_ROOT; }

int altscreen_state_root_is_authoritative(void) {
    return path_kind(ALTSCREEN_RUNTIME_ROOT, 1);
}

int altscreen_state_path(const char *leaf, char *out, size_t cap) {
    const size_t root_length = sizeof(ALTSCREEN_RUNTIME_ROOT) - 1u;
    size_t leaf_length;
    if (!leaf || !*leaf || !out || !cap) return 0;
    out[0] = 0;
    /* Every existing caller supplies one marker name, never a relative path. */
    if (strchr(leaf, '/') || !strcmp(leaf, ".") || !strcmp(leaf, "..")) return 0;
    leaf_length = strlen(leaf);
    if (root_length + leaf_length + 2u > cap) return 0;
    memcpy(out, ALTSCREEN_RUNTIME_ROOT, root_length);
    out[root_length] = '/';
    memcpy(out + root_length + 1u, leaf, leaf_length + 1u);
    return 1;
}

int altscreen_marker_present(const char *leaf) {
    char path[ALTSCREEN_PATH_MAX];
    if (!altscreen_state_root_is_authoritative() ||
        !altscreen_state_path(leaf, path, sizeof(path))) return 0;
    return path_kind(path, 0);
}

int altscreen_prepare_volatile_root(void) {
    struct stat st;
    /* Logging may use QNX's shared-memory-backed /tmp; arming never does. */
    return stat("/tmp", &st) == 0 && S_ISDIR(st.st_mode);
}

const char *altscreen_log_path(void) { return ALTSCREEN_RUNTIME_LOG; }
const char *altscreen_sd_log_path(void) { return NULL; }
const char *altscreen_profile_path(void) { return ALTSCREEN_RUNTIME_PROFILE; }

void altscreen_paths_report(void) {
    const int authoritative = altscreen_state_root_is_authoritative();
    const unsigned bit = authoritative ? 2u : 1u;
    if (__atomic_fetch_or(&g_reported, bit, __ATOMIC_ACQ_REL) & bit) return;
    altscreen_log("STATE_ROOT=%s runtime_authoritative=%d sd_authoritative=0 log=%s profile=%s",
                  ALTSCREEN_RUNTIME_ROOT, authoritative, ALTSCREEN_RUNTIME_LOG,
                  ALTSCREEN_RUNTIME_PROFILE);
}
