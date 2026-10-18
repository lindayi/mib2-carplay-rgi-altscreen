#define getpid contract_getpid
#define clock_gettime contract_clock_gettime
#define ALTSCREEN_HMI_STATE "hmi-contract-state"
#include <assert.h>
#include "../hook/ui_session.c"
#include "../hook/local_ui_contract.h"

static uint64_t now_ms = 2000000;
static pid_t receiver_pid = 1001;
static int pid_calls, change_pid_after;
pid_t contract_getpid(void) {
    if (change_pid_after && ++pid_calls == change_pid_after) ++receiver_pid;
    return receiver_pid;
}
int contract_clock_gettime(clockid_t id, struct timespec *out) {
    assert(id == CLOCK_REALTIME);
    out->tv_sec = (time_t)(now_ms / 1000u);
    out->tv_nsec = (long)(now_ms % 1000u) * 1000000L;
    return 0;
}
static char view[16], layout[128];
static int dx, dy;
static void record(uint32_t epoch, unsigned pid, uint64_t expiry, const char *extra) {
    FILE *f = fopen(ALTSCREEN_HMI_STATE ".new", "w");
    assert(f);
    fprintf(f, "view=SMALL\nlayout_name=LayoutMIB2HighB9Sport\nsmall_stage_dx=-476\nsmall_stage_dy=0\nreceiver_epoch=%u\n", epoch);
    if (pid) fprintf(f, "receiver_pid=%u\nexpiry_ms=%llu\n", pid, (unsigned long long)expiry);
    if (extra) fputs(extra, f);
    assert(!fclose(f));
    assert(!rename(ALTSCREEN_HMI_STATE ".new", ALTSCREEN_HMI_STATE));
}
static int read_hmi(void) {
    return alt_local_hmi(view, sizeof(view), layout, sizeof(layout), &dx, &dy);
}
static int cached(void) {
    return alt_ui_hmi_snapshot(view, sizeof(view), layout, sizeof(layout), &dx, &dy);
}
int main(void) {
    void *receiver = (void *)1;
    uint32_t epoch = alt_ui_session_begin(receiver);
    uint64_t value;
    alt_ui_session_note(receiver, epoch, 1, 1, 1);
    record(epoch, receiver_pid, now_ms + 1500, NULL);
    assert(read_hmi() && cached() && !strcmp(view, "SMALL") && dx == -476 && !dy);
    now_ms += 1500;
    assert(!cached()); /* expires even if the file-reading worker stalls */
    record(epoch, receiver_pid, now_ms, NULL);
    assert(!read_hmi() && !cached());
    record(epoch, receiver_pid, now_ms + 1501, NULL);
    assert(!read_hmi());
    record(epoch, receiver_pid + 1, now_ms + 1000, NULL);
    assert(!read_hmi() && !cached()); /* same epoch, different receiver process */
    record(epoch, 0, 0, NULL);
    assert(!read_hmi()); /* legacy five-field record is no longer accepted */
    record(epoch + 1, receiver_pid, now_ms + 1000, NULL);
    assert(!read_hmi());
    record(epoch, receiver_pid, now_ms + 1000, "receiver_pid=1001\n");
    assert(!read_hmi()); /* duplicate fields and trailing malformed data fail closed */
    record(epoch, receiver_pid, now_ms + 1000, "garbage\n");
    assert(!read_hmi());
    record(epoch, receiver_pid, now_ms + 1000, NULL);
    assert(read_hmi() && cached());
    --now_ms; now_ms -= 501;
    assert(!cached()); /* backward wall jump cannot extend the lease beyond 1500ms */
    record(epoch, receiver_pid, now_ms + 1000, NULL);
    pid_calls = 0; change_pid_after = 2;
    assert(!read_hmi()); /* before/after PID checks */
    change_pid_after = 0;
    record(epoch, receiver_pid, now_ms + 1000, NULL);
    assert(read_hmi());
    ++receiver_pid; assert(!cached()); /* cached metadata is PID-bound too */
    assert(!alt_local_decimal("18446744073709551616", UINT64_MAX, &value));
    assert(!alt_local_decimal("-1", UINT32_MAX, &value));
    assert(!alt_local_decimal("1001junk", UINT32_MAX, &value));
    alt_ui_session_closing(receiver);
    assert(!read_hmi() && !cached());
    assert(!remove(ALTSCREEN_HMI_STATE));
    puts("NATIVE_HMI_PID_EPOCH_REALTIME_LEASE=PASS");
    return 0;
}
