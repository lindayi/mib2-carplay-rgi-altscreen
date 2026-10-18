#include <assert.h>
#define ALTSCREEN_HMI_STATE "recovery-hmi-state"
#include "../hook/p1404_cockpit_native.c"

static int progress_available, mapping_live=1;
static struct p111_frame_progress_snapshot sample;
static unsigned requests;
static uint32_t request_seq;
static void *receiver=(void *)0x11,*stream=(void *)0x22;
void altscreen_log(const char *format, ...) { (void)format; }
uint32_t altscreen_ui_session_generation(void) { return 7; }
int p111_frame_tap_get_progress(void *s, struct p111_frame_progress_snapshot *out) {
    assert(s==stream);*out=sample;return progress_available;
}
int alt_state_snapshot(void *r,int alt,struct altscreen_ctx *out) {
    assert(r==receiver && alt);memset(out,0,sizeof(*out));
    out->generation=mapping_live?9:10;out->alt_screen_stream=stream;return 1;
}
int alt_send_cluster_recovery(void *r,void *s,uint32_t generation,uint32_t seq) {
    assert(r==receiver && s==stream && generation==7 && seq);
    ++requests;request_seq=seq;
    p1404_cockpit_native_recovery_result(r,s,generation,seq,0,1);
    return 0;
}
static void poll(uint32_t now) {
    native_check_stream_recovery(receiver,stream,7,9,now,0);
}
int main(void) {
    struct native_slot *slot=&g_native[0];
    uint32_t now=100;
    struct alt111_stream_recovery local;
    unsigned i;
    memset(slot,0,sizeof(*slot));
    slot->receiver=receiver;slot->stream=stream;slot->generation=7;
    slot->state_generation=9;slot->processing_live=1;
    slot->ui_event_state=2;slot->preconfig_rewritten=1;slot->config_ok=1;
    /* No packet, no decoded frame, no first-frame marker: bounded startup probe
     * still reaches the real stream recovery path after actual showUI ACK. */
    poll(now);poll(now+=ALT111_RECOVERY_IDLE_US);
    assert(requests==1 && slot->recovery.waiting_frame);
    assert(!slot->first_real_frame_posted && !slot->recovery.inflight_seq);
    poll(now+=ALT111_RECOVERY_RETRY_US);
    assert(requests==2 && slot->recovery.waiting_frame);
    poll(now+=ALT111_RECOVERY_RETRY_US);
    assert(requests==2 && slot->recovery.exhausted_logged);
    p1404_cockpit_native_recovery_result(receiver,stream,8,request_seq,0,1);
    assert(slot->recovery.waiting_frame); /* ACK and stale generations are not frames */
    progress_available=1;sample.active=1;sample.generation=7;sample.frame_count=1;
    poll(now+=1000);assert(!slot->recovery.waiting_frame && slot->recovery.attempts==2);
    for(i=0;i<31;i++) { ++sample.frame_count;poll(now+=100000); }
    assert(slot->recovery.attempts==0);
    mapping_live=0;poll(now+=ALT111_RECOVERY_IDLE_US);assert(requests==2);
    mapping_live=1;slot->processing_live=0;
    poll(now+=ALT111_RECOVERY_IDLE_US);assert(requests==2);
    slot->processing_live=1;poll(now+=ALT111_RECOVERY_IDLE_US);assert(requests==3);

    memset(&local,0,sizeof(local));
    assert(!alt111_recovery_poll(&local,100,1,0,1));
    now=100;
    for(i=0;i<3;i++) {
        now+=ALT111_RECOVERY_RETRY_US;
        assert(alt111_recovery_poll(&local,now,1,0,1)&ALT111_RECOVERY_REQUEST);
        assert(alt111_recovery_submit_failed(&local,local.command_seq));
    }
    assert(alt111_recovery_poll(&local,now+ALT111_RECOVERY_RETRY_US,1,0,1)&ALT111_RECOVERY_EXHAUSTED);
    {
        struct native_consumer_keyframe_request request;
        memset(&request,0,sizeof(request));
        request.pid=42;request.generation=3;request.cookie=0x22;request.seq=1;
        /* Malformed/foreign consumer identity cannot force a recovery request. */
        assert(!native_consumer_keyframe_locked(slot,&request,43,3,0x22,now));
        assert(!native_consumer_keyframe_locked(slot,&request,42,4,0x22,now));
        assert(!native_consumer_keyframe_locked(slot,&request,42,3,0x23,now));
    }
    puts("NATIVE_ZERO_FRAME_BOUNDED_RECOVERY=PASS");
    return 0;
}
