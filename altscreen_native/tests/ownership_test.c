#include <assert.h>
#include "../hook/p1404_airplay.c"

static struct altscreen_display display;
const struct altscreen_display *altscreen_cluster_display(void) { return &display; }

/* Every external effect is fatal: blocked requests must stop before resolving
 * AirPlay, taking a lifecycle lease or claiming a UI acknowledgement. */
void *p1404_stock_symbol_named(const char *name) { (void)name; abort(); }
int alt_oem_map_startup_ready(void) { abort(); }
int alt_airplay_retain_object(void *p) { (void)p; abort(); }
void alt_airplay_release_object(void *p) { (void)p; abort(); }
void altscreen_log(const char *format, ...) { (void)format; abort(); }
int alt_session_guard_enter(void *r, enum alt_session_operation op,
                            struct alt_session_lease *lease) {
    (void)r; (void)op; (void)lease; abort();
}
void alt_session_guard_leave(struct alt_session_lease *lease, int full) {
    (void)lease; (void)full; abort();
}
int p1404_cockpit_native_command_current(void *r, void *s, uint32_t g) {
    (void)r; (void)s; (void)g; abort();
}
int p1404_cockpit_native_command_acquire(void *r, void *s, uint32_t g) {
    (void)r; (void)s; (void)g; abort();
}
void p1404_cockpit_native_event_result(void *r, void *s, uint32_t g,
                                      int e, uint32_t q, int c, int status, int response) {
    (void)r; (void)s; (void)g; (void)e; (void)q; (void)c;
    (void)status; (void)response; abort();
}
void p1404_cockpit_native_recovery_result(void *r, void *s, uint32_t g,
                                         uint32_t q, int status, int response) {
    (void)r; (void)s; (void)g; (void)q; (void)status; (void)response; abort();
}
void p1404_cockpit_native_view_area_result(void *r, void *s, uint32_t g,
                                         uint32_t q, int index, int status, int response) {
    (void)r; (void)s; (void)g; (void)q; (void)index; (void)status; (void)response; abort();
}
void p1404_cockpit_native_zoom_result(void *r, void *s, uint32_t g,
                                    uint32_t q, int direction, int status, int response) {
    (void)r; (void)s; (void)g; (void)q; (void)direction; (void)status; (void)response; abort();
}

int main(void) {
    const char *layout = NULL;
    char view[16], name[128];
    int x = 0, y = 0;
    void *receiver = (void *)1, *stream = (void *)2;
    /* Even valid identities/requests cannot turn this DSO into another UI owner. */
    display.uuid = "test";
    assert(alt_send_cluster_zoom(receiver, stream, 1, 1, 0) == -1);
    assert(alt_send_cluster_view_area(receiver, stream, 1, 1, 1) == -1);
    assert(alt_send_cluster_card_ui(receiver, stream, 1, 1, 2) == -1);
    assert(alt_send_cluster_event(receiver, stream, 1, ALT111_EVENT_SHOW_UI) == -1);
    assert(alt_send_cluster_event(receiver, stream, 1, ALT111_EVENT_STOP_UI) == -1);
    assert(alt_cluster_url_for_card(2, &layout) == NULL);
    assert(alt_phone_card_mode() == 0);
    assert(!alt_load_hmi_layout(view, sizeof(view), name, sizeof(name), &x, &y));
    puts("NATIVE_UI_OWNERSHIP=PASS");
    return 0;
}
