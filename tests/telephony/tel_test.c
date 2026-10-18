/* GPL-3.0-only: regression of the actual telephony module and native ABI. */
#define ENABLE_LOGGING 0
#include "../../Toolbox/carplay_alt_screen/rgi_native/src/telephony/tel_hook.c"
#include <assert.h>

static unsigned injections;
static uint16_t injected_id;
static uint8_t injected[128];
static size_t injected_len;
static bool inject_fail;
static bus_handler_t handler;
static hook_inject_completion_fn completed;
static uint64_t completion_epoch;

hook_result_t hook_inject_message(uint16_t msgid, const uint8_t* payload, size_t len) {
    if (inject_fail) return HOOK_ERR_PARAM;
    assert(len <= sizeof(injected));
    injected_id = msgid; injected_len = len;
    if (len) memcpy(injected, payload, len);
    injections++;
    return HOOK_OK;
}
hook_result_t hook_inject_message_tracked(uint16_t msgid, const uint8_t* payload, size_t len,
                                         hook_inject_completion_fn completion, uint64_t epoch) {
    hook_result_t result = hook_inject_message(msgid, payload, len);
    if (result == HOOK_OK) { completed = completion; completion_epoch = epoch; }
    return result;
}
int hook_process_is_dio_manager(void) { return 1; }
void bus_set_sticky(uint16_t type, bool sticky) { (void)type; assert(sticky); }
hook_result_t bus_on(uint16_t type, bus_handler_t h, void* ctx) {
    assert(type == CMD_TEL); assert(!ctx); handler = h; return HOOK_OK;
}
void bus_off(uint16_t type) { assert(type == CMD_TEL); handler = NULL; }
hook_result_t bus_text_begin_heap(bus_text_builder_t* b, const char* name, uint32_t cap) {
    (void)name; memset(b, 0, sizeof(*b)); b->cap = cap; return HOOK_OK;
}
void bus_text_int(bus_text_builder_t* b, const char* key, int64_t n) { (void)b;(void)key;(void)n; }
void bus_text_uint(bus_text_builder_t* b, const char* key, uint64_t n) { (void)b;(void)key;(void)n; }
void bus_text_str(bus_text_builder_t* b, const char* key, const char* n) { (void)b;(void)key;(void)n; }
void bus_text_bool(bus_text_builder_t* b, const char* key, bool n) { (void)b;(void)key;(void)n; }
hook_result_t bus_send_text(uint16_t type, uint8_t flags, bus_text_builder_t* b) {
    (void)b; assert(type == EVT_TEL_LISTS || type == EVT_TEL_COMM);
    assert(flags == BUS_FLAG_STICKY); return HOOK_OK;
}

static void parsers(void) {
    uint8_t p[256], entry[100];
    size_t n, e;
    tel_lists_t lists, last;
    tel_comm_t comm;
    tel_power_t power;
    uint8_t count[2] = {0,2}, index[2] = {0,1};
    memset(&last, 0, sizeof(last));
    n = iap2_build_tlv(p, sizeof(p), 2, count, 2);
    e = iap2_build_tlv(entry, sizeof(entry), 0, index, 2);
    e += iap2_build_tlv(entry+e, sizeof(entry)-e, 1, (const uint8_t*)"123", 3);
    e += iap2_build_tlv(entry+e, sizeof(entry)-e, 2, (const uint8_t*)"Name\ninjected:i:9", 17);
    n += iap2_build_tlv(p+n, sizeof(p)-n, 1, entry, e);
    assert(tel_parse_list_update(p, n, &lists));
    assert(lists.recents.count == 2 && lists.recents.n == 1);
    assert(!strcmp(lists.recents.e[0].b, "Name"));
    tel_merge_lists(&last, &lists);
    assert(last.recents.n == 2 && !strcmp(last.recents.e[1].a, "123"));
    /* A count starts a replacement snapshot, not a resize exposing old entries. */
    n = iap2_build_tlv(p, sizeof(p), 2, count, 2);
    assert(tel_parse_list_update(p, n, &lists)); tel_merge_lists(&last, &lists);
    assert(last.recents.e[1].a[0] == 0);
    count[1] = 0;
    n = iap2_build_tlv(p, sizeof(p), 2, count, 2);
    n += iap2_build_tlv(p+n, sizeof(p)-n, 1, entry, e);
    assert(tel_parse_list_update(p, n, &lists)); tel_merge_lists(&last, &lists);
    assert(last.recents.n == 0);
    /* An outer-valid entry with a truncated nested TLV is malformed too. */
    entry[e++] = 0;
    n = iap2_build_tlv(p, sizeof(p), 1, entry, e);
    assert(!tel_parse_list_update(p, n, &lists));
    n = iap2_build_tlv(p, sizeof(p), 1, (const uint8_t*)"\0\4\0\1", 4);
    assert(!tel_parse_list_update(p, n, &lists));
    n = iap2_build_tlv(p, sizeof(p), 2, count, 1);
    assert(!tel_parse_list_update(p, n, &lists));
    n = iap2_build_tlv(p, sizeof(p), 0, (const uint8_t*)"\4", 1);
    assert(tel_parse_comm_update(p, n, &comm) && comm.has_signal && comm.signal == 4);
    p[n++] = 0;
    assert(!tel_parse_comm_update(p, n, &comm));
    n = iap2_build_tlv(p, sizeof(p), 0, (const uint8_t*)"\6", 1);
    assert(!tel_parse_comm_update(p, n, &comm));
    n = iap2_build_tlv(p, sizeof(p), 2, (const uint8_t*)"\2", 1);
    assert(!tel_parse_comm_update(p, n, &comm));
    n = iap2_build_tlv(p, sizeof(p), 6, (const uint8_t*)"\0\101", 2);
    assert(tel_parse_power_update(p, n, &power) && power.level == 65);
    n = tel_build_status(p, sizeof(p), true);
    assert(n == 5 && !memcmp(p, "\0\5\0\0\1", 5));
    assert(tel_build_status(p, 4, true) == 0);
    n = tel_build_start_list_updates(p, sizeof(p), 20, 20);
    assert(n == 89);
    assert(tel_build_start_list_updates(p, n-1, 20, 20) == 0);
}

int main(void) {
    hook_context_t ctx;
    iap2_frame_t frame;
    uint8_t cmd[3] = {3,0,0};
    unsigned before;
    memset(&ctx, 0, sizeof(ctx)); memset(&frame, 0, sizeof(frame));
    parsers();
    tel_module_def.on_init();
    assert(handler);
    handler(CMD_TEL, 0, cmd, 2, NULL);
    assert(injections == 0); /* never act before a stock telephone session */
    ctx.direction = MSG_DIR_OUTGOING; frame.msgid = MSG_START_CALL_STATE;
    inject_fail = true;
    assert(!tel_module_def.on_message(&ctx, &frame));
    assert(!tel_lists_requested);
    inject_fail = false;
    assert(!tel_module_def.on_message(&ctx, &frame));
    assert(injections == 1 && injected_id == MSG_START_LIST_UPDATES);
    tel_module_def.on_message(&ctx, &frame);
    assert(injections == 1); /* no duplicate subscription */
    completed(completion_epoch, HOOK_ERR_PARAM);
    assert(!tel_lists_requested); /* queued is not sent */
    tel_module_def.on_tick();
    assert(injections == 2);
    handler(CMD_TEL, 0, cmd, 2, NULL);
    assert(injections == 3 && injected_id == MSG_ACCEPT_CALL && injected_len == 5);
    assert(!memcmp(injected, "\0\5\0\0\0", 5)); /* param zero */
    cmd[0] = 5; handler(CMD_TEL, 0, cmd, 2, NULL);
    assert(injected_id == MSG_SWAP_CALLS && injected_len == 0);
    before = injections;
    cmd[0] = 1; cmd[1] = 2; handler(CMD_TEL, 0, cmd, 2, NULL);
    cmd[1] = 1; handler(CMD_TEL, 0, cmd, 3, NULL);
    handler(CMD_TEL, 0, NULL, 2, NULL);
    assert(injections == before);
    tel_module_def.on_state(&ctx, HOOK_EVENT_DISCONNECT, NULL);
    handler(CMD_TEL, 0, cmd, 2, NULL);
    assert(injections == before && !tel_session && !tel_lists_requested);
    assert(tel_last.recents.n == 0 && !tel_comm.has_signal && !tel_power.has_level);
    tel_module_def.on_message(&ctx, &frame);
    assert(tel_lists_requested);
    lists_sent(completion_epoch - 1, HOOK_ERR_PARAM);
    assert(tel_lists_requested); /* old sender cannot invalidate the replacement */
    completed(completion_epoch, HOOK_ERR_PARAM);
    tel_module_def.on_tick();
    completed(completion_epoch, HOOK_ERR_PARAM);
    tel_module_def.on_tick();
    completed(completion_epoch, HOOK_ERR_PARAM);
    before = injections;
    tel_module_def.on_tick();
    assert(injections == before); /* bounded failure recovery, never retry forever */
    tel_module_def.on_shutdown(); assert(!handler);
    puts("tel_test: PASS (wire, malformed nested TLVs, list replacement, lifecycle, commands)");
    return 0;
}
