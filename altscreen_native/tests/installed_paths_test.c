#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

static int runtime_present = 1, armed = 1, symlink_marker, sd_probes;
static int fixture_stat(const char *path, struct stat *st) {
    memset(st, 0, sizeof(*st));
    if (strstr(path, "/fs/") || strstr(path, "/net/")) ++sd_probes;
    if (!strcmp(path, "/mnt/app/root/carplay-altscreen/state") && !runtime_present)
        return -1;
    st->st_mode = S_IFDIR | 0700;
    if (!strcmp(path, "/mnt/app/root/carplay-altscreen/state/ARMED_CREATE111")) {
        if (!armed) return -1;
        st->st_mode = (symlink_marker ? S_IFLNK : S_IFREG) | 0600;
    }
    return 0;
}
#define lstat fixture_stat
#define stat(path, value) fixture_stat(path, value)
#include "../hook/altscreen_paths.c"
#undef stat
#undef lstat
void altscreen_log(const char *format, ...) { (void)format; }

int main(void) {
    char path[ALTSCREEN_PATH_MAX];
    assert(altscreen_state_root_is_authoritative());
    assert(altscreen_marker_present("ARMED_CREATE111"));
    assert(altscreen_state_path("IAP2_PROFILE", path, sizeof(path)));
    assert(!strcmp(path, "/mnt/app/root/carplay-altscreen/state/IAP2_PROFILE"));
    assert(!strcmp(path, altscreen_profile_path()));
    assert(!altscreen_state_path("../ARMED_CREATE111", path, sizeof(path)));
    armed = 0;
    assert(!altscreen_marker_present("ARMED_CREATE111"));
    runtime_present = 0; armed = 1;
    assert(!altscreen_marker_present("ARMED_CREATE111")); /* SD presence never arms */
    runtime_present = 1; symlink_marker = 1;
    assert(!altscreen_marker_present("ARMED_CREATE111"));
    assert(sd_probes == 0 && altscreen_sd_log_path() == NULL);
    puts("NATIVE_INSTALLED_STATE_AUTHORITY=PASS");
}
