#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static bool deny_rename;
static int checked_rename(const char *from, const char *to) {
    if (deny_rename) { errno = EXDEV; return -1; }
    return rename(from, to);
}
#define rename checked_rename
#define ALTSCREEN_SNAPSHOT_HOST_TEST 1
#include "../mirror/src/state_snapshot.cpp"
#undef rename

int main() {
    const char *path = "published.state";
    assert(write_state_snapshot(path, "old\n", 4).published);
    deny_rename = true;
    StateSnapshotResult result = write_state_snapshot(path, "new\n", 4);
    assert(!result.published && result.error == EXDEV);
    char actual[8] = {0};
    FILE *file = fopen(path, "r");
    assert(file && fread(actual, 1, 4, file) == 4);
    fclose(file);
    assert(!strcmp(actual, "old\n")); /* no fallback truncation of the last good snapshot */
    deny_rename = false;
    assert(write_state_snapshot(path, "new\n", 4).published);
    assert(!write_state_snapshot(path, "", 0).published);
    unlink(path);
    puts("NATIVE_ATOMIC_SNAPSHOT=PASS");
}
