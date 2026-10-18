/* alt_cluster_test: the real hook/altcluster module against a fake libairplay
 * (alt_cluster_fake_airplay.c, a separate shared library).  Checks the session
 * tracking through the platform hooks, the exact command dictionaries and that
 * nothing is sent to a finalized session. */
#include "framework/bus.h"
#include "framework/bus_protocol.h"
#include "altcluster/alt_cluster.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern int fake_live_objects, fake_platform_init_calls, fake_platform_fini_calls, fake_send_calls;
extern int fake_oem_ready_calls;
extern uint32_t fake_ui_session_generation;
extern int fake_ui_queue_calls;
extern uint32_t fake_ui_queue_epoch;
extern char fake_ui_queue_url[512];
extern void* fake_last_session;
extern char fake_last_request[1024];

int32_t AirPlayReceiverSessionPlatformInitialize(void* session);
void AirPlayReceiverSessionPlatformFinalize(void* session);

/* ---- framework stubs ---- */
static bus_handler_t handlers[0x0200];
int hook_process_is_dio_manager(void) { return 1; }
hook_result_t bus_on(uint16_t type, bus_handler_t h, void* ctx) { (void)ctx; handlers[type] = h; return HOOK_OK; }
void bus_off(uint16_t type) { handlers[type] = NULL; }
static int session_events;
static uint32_t last_session_epoch;
static uint32_t last_receiver_pid;
hook_result_t bus_send(uint16_t type, uint8_t flags, const uint8_t *payload, uint32_t len) {
    if (type != EVT_ALT_SESSION || flags != (BUS_FLAG_BINARY | BUS_FLAG_STICKY) || len != 8)
        abort();
    last_session_epoch = ((uint32_t)payload[0] << 24) | ((uint32_t)payload[1] << 16)
        | ((uint32_t)payload[2] << 8) | payload[3];
    last_receiver_pid = ((uint32_t)payload[4] << 24) | ((uint32_t)payload[5] << 16)
        | ((uint32_t)payload[6] << 8) | payload[7];
    if (last_receiver_pid != (uint32_t)getpid()) abort();
    session_events++;
    return HOOK_OK;
}

static int failures;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); failures++; } } while (0)

static void deliver(uint16_t type, const void* payload, uint32_t len) {
    CHECK(handlers[type] != NULL, "handler registered");
    if (handlers[type]) handlers[type](type, 0, payload, len, NULL);
}

static void zoom(int8_t step) { deliver(CMD_ALT_ZOOM, &step, 1); }

int main(void) {
    const char* uuid = ALT_CLUSTER_DISPLAY_UUID;
    char expect[512];
    int session_a, session_b;

    alt_cluster_module_def.on_init();
    uint8_t ready = 1;
    CHECK(handlers[CMD_OEM_MAP_READY] != NULL, "OEM readiness registered on existing command module");
    handlers[CMD_OEM_MAP_READY](CMD_OEM_MAP_READY, BUS_FLAG_BINARY, &ready, 1, NULL);
    CHECK(fake_oem_ready_calls == 1, "OEM readiness forwarded to source hook");
    handlers[CMD_OEM_MAP_READY](CMD_OEM_MAP_READY, 0, &ready, 1, NULL);
    handlers[CMD_OEM_MAP_READY](CMD_OEM_MAP_READY, BUS_FLAG_BINARY, NULL, 1, NULL);
    ready = 0;
    handlers[CMD_OEM_MAP_READY](CMD_OEM_MAP_READY, BUS_FLAG_BINARY, &ready, 1, NULL);
    CHECK(fake_oem_ready_calls == 1, "malformed or false readiness cannot open startup gate");

    /* Before any AirPlay session nothing is sent. */
    zoom(1);
    CHECK(fake_send_calls == 0, "no session -> no command");
    alt_cluster_module_def.on_tick();
    CHECK(session_events == 1 && last_session_epoch == 0, "initial unusable session snapshot");
    alt_cluster_module_def.on_tick();
    CHECK(session_events == 1, "duplicate readiness is not published");

    CHECK(AirPlayReceiverSessionPlatformInitialize(&session_a) == 0, "stock init result forwarded");
    CHECK(fake_platform_init_calls == 1, "stock init called");
    alt_cluster_module_def.on_tick();
    CHECK(session_events == 1, "platform init alone is not a usable command channel");
    fake_ui_session_generation = 0xFEDCBA98;
    alt_cluster_module_def.on_tick();
    CHECK(session_events == 2 && last_session_epoch == 0xFEDCBA98, "usable source epoch forwarded intact");

    /* +1 MapScale step = zoom index up = further out. */
    zoom(1);
    snprintf(expect, sizeof(expect),
             "{type=changeMapZoomLevel,params={uuid=%s,zoomDirection=1}}", uuid);
    CHECK(fake_send_calls == 1, "one step -> one command");
    CHECK(fake_last_session == &session_a, "sent to the live session");
    CHECK(strcmp(fake_last_request, expect) == 0, "zoom out request");

    /* -2 steps = two zoom-in commands; a burst is capped. */
    zoom(-2);
    snprintf(expect, sizeof(expect),
             "{type=changeMapZoomLevel,params={uuid=%s,zoomDirection=0}}", uuid);
    CHECK(fake_send_calls == 3, "two steps -> two commands");
    CHECK(strcmp(fake_last_request, expect) == 0, "zoom in request");
    zoom(-100);
    CHECK(fake_send_calls == 7, "burst capped at 4 commands");
    zoom(0);
    CHECK(fake_send_calls == 7, "zero step ignored");
    deliver(CMD_ALT_ZOOM, "", 0);
    CHECK(fake_send_calls == 7, "empty payload ignored");

    /* The bus passes the unchanged URL into the source-native queue. It must
     * never issue a second direct AirPlay showUI. */
    const char* url = "maps:/car/instrumentcluster/map?maneuverLayout=topaligned";
    deliver(CMD_ALT_UICTX, url, (uint32_t)strlen(url));
    CHECK(fake_send_calls == 7 && fake_ui_queue_calls == 1, "native UI queue is the only UI sender");
    CHECK(strcmp(fake_ui_queue_url, url) == 0 && fake_ui_queue_epoch == fake_ui_session_generation,
          "raw URL and current native epoch forwarded");
    char big[600];
    memset(big, 'a', sizeof(big));
    deliver(CMD_ALT_UICTX, big, sizeof(big));
    CHECK(fake_send_calls == 7 && fake_ui_queue_calls == 1, "oversized URL rejected");
    const char embedded[] = "maps:/car/instrumentcluster\0?hidden=1";
    deliver(CMD_ALT_UICTX, embedded, sizeof(embedded)-1);
    CHECK(fake_ui_queue_calls == 1, "embedded NUL rejected before native queue");

    /* A newer session replaces the old one; finalizing the old one keeps it. */
    AirPlayReceiverSessionPlatformInitialize(&session_b);
    AirPlayReceiverSessionPlatformFinalize(&session_a);
    zoom(1);
    CHECK(fake_last_session == &session_b, "stale finalize does not drop the new session");

    /* After the live session is finalized nothing reaches it. */
    AirPlayReceiverSessionPlatformFinalize(&session_b);
    alt_cluster_module_def.on_tick();
    CHECK(session_events == 3 && last_session_epoch == 0, "finalize withdraws source readiness");
    CHECK(fake_platform_fini_calls == 2, "stock finalize forwarded");
    int before = fake_send_calls;
    zoom(1);
    deliver(CMD_ALT_UICTX, url, (uint32_t)strlen(url));
    CHECK(fake_send_calls == before, "no command after finalize");
    CHECK(fake_ui_queue_calls == 1, "no queued UI after finalize");

    CHECK(fake_live_objects == 0, "every CF object released");
    alt_cluster_module_def.on_shutdown();
    CHECK(handlers[CMD_ALT_ZOOM] == NULL && handlers[CMD_ALT_UICTX] == NULL, "handlers removed");
    CHECK(handlers[CMD_OEM_MAP_READY] == NULL, "OEM readiness handler removed");

    if (failures) { printf("alt_cluster_test: %d FAILED\n", failures); return 1; }
    printf("alt_cluster_test: session tracking, native UI queue bridge, zoom dictionaries, finalize gate PASS\n");
    return 0;
}
