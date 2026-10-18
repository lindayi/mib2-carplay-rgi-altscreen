#define ENABLE_LOGGING 0
#include "../hook/routeguidance/rgd_hook.c"
#include <assert.h>

static unsigned published_lanes;
static hook_context_t context;
static hook_inject_completion_fn completion;
static uint64_t completion_token;
static unsigned queued;

hook_context_t *hook_framework_get_context(void) { return &context; }
bool hook_is_ready(void) { return true; }
void bus_set_periodic_tick(bus_tick_cb_t callback) { (void)callback; }
hook_result_t hook_inject_message(uint16_t type, const uint8_t *payload, size_t length) {
    (void)type; (void)payload; (void)length;
    return HOOK_OK;
}
hook_result_t hook_inject_message_tracked(uint16_t type, const uint8_t *payload, size_t length,
                                         hook_inject_completion_fn done, uint64_t token) {
    assert(type == IAP2_MSG_ROUTE_GUIDANCE_START && payload && length);
    completion = done;
    completion_token = token;
    ++queued;
    return HOOK_OK;
}
void bus_text_begin_with(bus_text_builder_t *b, const char *name, uint8_t *buffer, uint32_t capacity) {
    (void)name; (void)buffer; (void)capacity;
    memset(b, 0, sizeof(*b));
}

hook_result_t bus_text_begin_heap(bus_text_builder_t *b, const char *name, uint32_t capacity) {
    (void)name; (void)capacity;
    memset(b, 0, sizeof(*b));
    return HOOK_OK;
}
void bus_text_int(bus_text_builder_t *b, const char *key, int64_t value) {
    (void)b;
    if (!strncmp(key, "lg", 2) && strstr(key, "_index") && value >= 0) ++published_lanes;
}
void bus_text_uint(bus_text_builder_t *b, const char *key, uint64_t value) {
    bus_text_int(b, key, (int64_t)value);
}
void bus_text_str(bus_text_builder_t *b, const char *key, const char *value) {
    (void)b; (void)key; (void)value;
}
hook_result_t bus_send_text(uint16_t type, uint8_t flags, bus_text_builder_t *b) {
    (void)type; (void)flags; (void)b;
    return HOOK_OK;
}

int main(void) {
    rgd_maneuver_map_reset();
    for (int index = 2; index <= 3; ++index) {
        int slot = rgd_lane_slot_for_iap_index((uint16_t)index, true);
        g_rgd.lane_cache[slot].present = RGD_LANE_GUIDANCE_INDEX | RGD_LANE_INFORMATIONS;
        g_rgd.lane_cache[slot].lane_guidance_index = (uint16_t)index;
    }
    g_rgd.current_list_present = true;
    g_rgd.current_list_count = 1;
    g_rgd.current_list[0] = 100;
    write_bus_snapshot_from_cache(-1, NULL, NULL);
    assert(rgd_lane_slot_for_iap_index(2, false) >= 0);
    assert(rgd_lane_slot_for_iap_index(3, false) >= 0);
    assert(published_lanes == 2);

    rgd_maneuver_map_reset();
    for (int index = 100; index < 132; ++index)
        assert(rgd_lane_slot_for_iap_index((uint16_t)index, true) >= 0);
    g_rgd.lane_slot_to_iap_idx[31] = 1;
    g_rgd.update_cache.present = RGD_UPD_LANE_INDEX;
    g_rgd.update_cache.lane_guidance_index = 110;
    assert(rgd_lane_slot_for_iap_index(132, true) == 0);
    assert(rgd_lane_slot_for_iap_index(110, false) >= 0);
    /* With only a newer passed event left, retain older prefetched future lanes. */
    g_rgd.update_cache.lane_guidance_index = 2;
    assert(rgd_lane_slot_for_iap_index(133, true) == 31);
    assert(rgd_lane_slot_for_iap_index(101, false) >= 0);
    rgd_maneuver_map_reset();
    assert(rgd_lane_slot_for_iap_index(133, false) < 0);
    g_rgd.component_valid = true;
    assert(rgd_request_updates() == HOOK_OK);
    assert(queued == 1 && !g_rgd.sent_5200 && !g_rgd.active);
    assert(rgd_request_updates() == HOOK_OK && queued == 1);
    completion(completion_token, HOOK_ERR_IO);
    assert(!g_rgd.sent_5200 && g_rgd.start_5200_attempts == 0);
    assert(rgd_request_updates() == HOOK_OK && queued == 2);
    uint64_t old_token = completion_token;
    rgd_clear_state("test disconnect");
    g_rgd.component_valid = true;
    assert(rgd_request_updates() == HOOK_OK && queued == 3);
    completion(old_token, HOOK_OK);
    assert(!g_rgd.sent_5200 && !g_rgd.active);
    completion(completion_token, HOOK_OK);
    assert(g_rgd.sent_5200 && g_rgd.active && g_rgd.start_5200_attempts == 1);
    completion(completion_token, HOOK_OK);
    assert(g_rgd.start_5200_attempts == 1);
    puts("RGI lane cache: separate IDs, future retention, active protection and route reset PASS");
    puts("RGI start: queued is not sent, failure retry and stale completion rejection PASS");
    return 0;
}
