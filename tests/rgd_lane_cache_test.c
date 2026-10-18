#define ENABLE_LOGGING 0
#include "../hook/routeguidance/rgd_hook.c"
#include <assert.h>
#include <errno.h>

static unsigned published_lanes;
static hook_context_t context;
static hook_inject_completion_fn completion;
static uint64_t completion_token;
static unsigned queued;
static int checking_order,attempted,order[2],order_count;
static pthread_mutex_t order_lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t order_condition=PTHREAD_COND_INITIALIZER;
static pthread_t active_thread;
static int64_t published_distance,published_eta,published_remaining,published_sample;

static void *publish_active(void *unused) {
    (void)unused;
    int blocked=pthread_mutex_trylock(&g_rgd_debounce_lock);
    assert(blocked==0 || blocked==EBUSY);
    if(!blocked)pthread_mutex_unlock(&g_rgd_debounce_lock);
    if(blocked) {
        pthread_mutex_lock(&order_lock);attempted=1;
        pthread_cond_signal(&order_condition);pthread_mutex_unlock(&order_lock);
    }
    rgd_cancel_pending_zero("test active update");
    rgd_update_t active={0};
    active.present=RGD_UPD_ROUTE_STATE;active.route_state=1;
    write_bus_update_partial(&active);
    if(!blocked) {
        pthread_mutex_lock(&order_lock);attempted=1;
        pthread_cond_signal(&order_condition);pthread_mutex_unlock(&order_lock);
    }
    return NULL;
}

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
    if(!strcmp(key,"route_state"))b->len=(uint32_t)value;
    if(!strcmp(key,"dist_dest_m"))published_distance=value;
    if(!strcmp(key,"eta_seconds"))published_eta=value;
    if(!strcmp(key,"time_remaining_seconds"))published_remaining=value;
    if(!strcmp(key,"time_remaining_sample_ms"))published_sample=value;
}
void bus_text_uint(bus_text_builder_t *b, const char *key, uint64_t value) {
    bus_text_int(b, key, (int64_t)value);
}
void bus_text_str(bus_text_builder_t *b, const char *key, const char *value) {
    (void)b; (void)key; (void)value;
}
hook_result_t bus_send_text(uint16_t type, uint8_t flags, bus_text_builder_t *b) {
    (void)type; (void)flags; (void)b;
    if(checking_order) {
        int state=(int)b->len;
        if(!state) {
            assert(!pthread_create(&active_thread,NULL,publish_active,NULL));
            pthread_mutex_lock(&order_lock);
            while(!attempted)pthread_cond_wait(&order_condition,&order_lock);
            pthread_mutex_unlock(&order_lock);
        }
        pthread_mutex_lock(&order_lock);
        assert(order_count<2);order[order_count++]=state;
        pthread_mutex_unlock(&order_lock);
    }
    return HOOK_OK;
}

int main(void) {
    rgd_update_t trip={0};
    trip.present=RGD_UPD_DESTINATION|RGD_UPD_SOURCE_NAME|RGD_UPD_ETA|RGD_UPD_TIME_REMAINING|RGD_UPD_DISTANCE_REMAINING;
    strcpy(trip.destination,"First");strcpy(trip.source_name,"Maps");
    trip.eta=1900000000;trip.time_remaining=900;trip.distance_remaining=10000;
    write_bus_update_partial(&trip);
    assert(published_sample>0 && published_remaining==900 && published_distance==10000);
    int64_t receipt=published_sample;
    g_rgd.remaining_sample_ms-=60000;
    write_bus_snapshot_from_cache(-1,NULL,NULL);
    assert(published_sample==receipt-60000 && published_remaining==900);
    trip.present=RGD_UPD_DESTINATION;strcpy(trip.destination,"Second");
    write_bus_update_partial(&trip);
    assert(published_distance==-1 && published_eta==-1 && published_remaining==-1 && published_sample==0);
    trip.present=RGD_UPD_DISTANCE_REMAINING|RGD_UPD_TIME_REMAINING;
    trip.distance_remaining=4000;trip.time_remaining=300;
    write_bus_update_partial(&trip);
    trip.present=RGD_UPD_SOURCE_NAME;strcpy(trip.source_name,"Other Maps");
    write_bus_update_partial(&trip);
    assert(published_distance==-1 && published_remaining==-1 && published_sample==0);
    g_rgd.emitted_route_state=1;
    g_rgd.state_zero_started_ms=now_monotonic_ms()-2;
    g_rgd.state_zero_deadline_ms=now_monotonic_ms()-1;
    checking_order=1;
    rgd_periodic_tick();
    assert(!pthread_join(active_thread,NULL));
    checking_order=0;
    assert(order_count==2 && order[0]==0 && order[1]==1);
    rgd_update_cache_reset();
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
    rgd_update_t current;
    memset(&current, 0, sizeof(current));
    current.present = RGD_UPD_LANE_INDEX;
    current.lane_guidance_index = 3;
    published_lanes = 0;
    write_bus_update_partial(&current);
    assert(rgd_lane_slot_for_iap_index(2, false) < 0);
    assert(rgd_lane_slot_for_iap_index(3, false) >= 0);
    assert(published_lanes == 1);

    rgd_maneuver_map_reset();
    for (int index = 100; index < 132; ++index)
        assert(rgd_lane_slot_for_iap_index((uint16_t)index, true) >= 0);
    g_rgd.update_cache.present = RGD_UPD_LANE_INDEX;
    g_rgd.update_cache.lane_guidance_index = 110;
    for (int n = 0; n < 5; ++n) assert(rgd_lane_slot_for_iap_index(100, false) == 0);
    assert(rgd_lane_slot_for_iap_index(132, true) == 0);
    assert(rgd_lane_slot_for_iap_index(110, false) >= 0);
    assert(rgd_lane_slot_for_iap_index(133, true) == 1);
    assert(rgd_lane_slot_for_iap_index(101, false) < 0);
    /* A reroute can precache a newly numbered window before activating it. */
    for (int index = 0; index < 6; ++index)
        assert(rgd_lane_slot_for_iap_index((uint16_t)index, true) >= 0);
    for (int index = 0; index < 6; ++index)
        assert(rgd_lane_slot_for_iap_index((uint16_t)index, false) >= 0);
    assert(rgd_lane_slot_for_iap_index(110, false) >= 0);
    current.lane_guidance_index = 3;
    write_bus_update_partial(&current);
    assert(rgd_lane_slot_for_iap_index(2, false) < 0);
    assert(rgd_lane_slot_for_iap_index(3, false) >= 0);
    assert(rgd_lane_slot_for_iap_index(5, false) >= 0);
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
    puts("RGI lane cache: separate IDs, precache then activation, insertion-order eviction, active protection and reroute PASS");
    puts("RGI start: queued is not sent, failure retry and stale completion rejection PASS");
    return 0;
}
