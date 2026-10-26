#include <assert.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <unistd.h>
#define ALTSCREEN_HMI_STATE "hmi-state"
#define ALT111_TRACKED_BOOTSTRAP 1
#define ALT_BOOTSTRAP_POLL_US 1000u
#define ALT_BOOTSTRAP_ACK_POLLS 20u
static int bootstrap_command_current(void *, uint32_t);
static void bootstrap_event_result(void *, uint32_t, int, int, int);
static int bootstrap_selected_url(void *, uint32_t, char *, size_t);
#include "../hook/p1404_airplay.c"
#include "../hook/p1404_lock_wait.h"
#include "../hook/p1404_bootstrap.h"

struct object {
    int refs, kind, count;
    int64_t number;
    char text[512], keys[48][64];
    struct object *values[48];
};
static struct object *obj(int kind) {
    struct object *o = calloc(1, sizeof(*o)); assert(o);
    o->kind = kind; o->refs = 1; return o;
}
static cf_obj string(const char *s, int n) {
    (void)n; struct object *o = obj(1);
    assert(s && strlen(s) < sizeof(o->text)); strcpy(o->text, s); return o;
}
static cf_obj number(const void *p, int64_t n) {
    (void)p; struct object *o = obj(2); o->number = n; return o;
}
static cf_obj dictionary(cf_obj a, unsigned b, const void *c, const void *d, const void *e) {
    (void)a;(void)b;(void)c;(void)d;(void)e; return obj(3);
}
static cf_obj array(cf_obj a, unsigned b, const void *c, const void *d) {
    (void)a;(void)b;(void)c;(void)d; return obj(4);
}
static void release(cf_obj p) {
    struct object *o = p; int i;
    if (!o || --o->refs) return;
    for (i=0;i<o->count;i++) release(o->values[i]);
    free(o);
}
static void set(cf_obj p, const void *k, const void *v) {
    struct object *o=p, *key=(void *)k, *value=(void *)v; int i;
    assert(o && key && value);
    for (i=0;i<o->count;i++) if (!strcmp(o->keys[i], key->text)) break;
    if (i==o->count) { assert(o->count<48); ++o->count; }
    else release(o->values[i]);
    strcpy(o->keys[i], key->text); o->values[i]=value; ++value->refs;
}
static struct object *get(struct object *o, const char *key) {
    int i; assert(o);
    for (i=0;i<o->count;i++) if (!strcmp(o->keys[i],key)) return o->values[i];
    assert(!"missing CF key"); return NULL;
}
static void append(cf_obj p, const void *v) {
    struct object *o=p, *value=(void *)v;
    assert(o->count<48); o->values[o->count++]=value; ++value->refs;
}
static struct altscreen_display display;
const struct altscreen_display *altscreen_cluster_display(void) { return &display; }
int p1404_cockpit_native_refresh_geometry(void) { return 1; }
int p1404_cockpit_native_get_geometry(uint32_t *w, uint32_t *h) { *w=1440;*h=542;return 1; }
int altscreen_set_cluster_geometry(uint32_t w,uint32_t h) { (void)w;(void)h; return 1; }
void altscreen_log(const char *format, ...) { (void)format; }
static volatile unsigned sends, holds, retained, view_acks;
static int send_mode, live=1;
static char types[32][32], urls[32][512];
static void (*held_callback)(int,void *,void *);
static void *held_context;
static int send_command(void *r, void *p, void (*cb)(int,void *,void *), void *ctx) {
    struct object *o=p, *params=get(o,"params");
    unsigned i=__sync_fetch_and_add(&sends,0);
    assert(i<32); strcpy(types[i], get(o,"type")->text);
    if (!strcmp(types[i],"showUI")) strcpy(urls[i],get(params,"url")->text);
    if (!strcmp(types[i],"updateViewArea")) {
        int index=(int)get(params,"viewAreaIndex")->number;
        assert(index==0 || index==1);
        assert(get(params,"animationDurationMillis")->number==0);
        assert(get(params,"adjacentViewAreas")->values[0]->number==1-index);
    }
    if (send_mode==1 && !strcmp(types[i],"showUI")) {
        held_callback=cb; held_context=ctx;
        __sync_add_and_fetch(&holds,1);
    } else if (send_mode==2 && !strcmp(types[i],"showUI")) {
        cb(-1,p,ctx);
    } else if (send_mode==3 && !strcmp(types[i],"showUI")) {
        alt_ui_session_closing(r);
        held_callback=cb; held_context=ctx;
        __sync_add_and_fetch(&holds,1);
    } else cb(0,p,ctx);
    __sync_add_and_fetch(&sends,1);
    return 0;
}
void *p1404_stock_symbol_named(const char *name) {
    assert(!strcmp(name,"AirPlayReceiverSessionSendCommand"));return send_command;
}
int alt_airplay_retain_object(void *p) { assert(p); __sync_add_and_fetch(&retained,1);return 1; }
void alt_airplay_release_object(void *p) { assert(p); assert(__sync_fetch_and_sub(&retained,1)); }
int alt_session_guard_enter(void *r, enum alt_session_operation op, struct alt_session_lease *lease) {
    (void)r;(void)op;memset(lease,0,sizeof(*lease));return ALT_SESSION_ENTERED;
}
void alt_session_guard_leave(struct alt_session_lease *lease,int full) { (void)lease;(void)full; }
int p1404_cockpit_native_command_current(void *r,void *s,uint32_t g) {
    (void)r;(void)s;return live && g==7;
}
int p1404_cockpit_native_command_acquire(void *r,void *s,uint32_t g) {
    return p1404_cockpit_native_command_current(r,s,g) && alt_airplay_retain_object(r);
}
void p1404_cockpit_native_event_result(void *r,void *s,uint32_t g,int e,uint32_t q,int c,int status,int response) {
    (void)r;(void)s;(void)g;(void)e;(void)q;(void)c;(void)status;(void)response;
}
void p1404_cockpit_native_recovery_result(void *r,void *s,uint32_t g,uint32_t q,int status,int response) {
    (void)r;(void)s;(void)g;(void)q;(void)status;(void)response;
}
void p1404_cockpit_native_view_area_result(void *r,void *s,uint32_t g,uint32_t q,int index,int status,int response) {
    (void)r;(void)s;assert(g==7 && q && (index==0 || index==1) && status==0 && response);
    ++view_acks;
}
void p1404_cockpit_native_zoom_result(void *r,void *s,uint32_t g,uint32_t q,int d,int status,int response) {
    (void)r;(void)s;(void)g;(void)q;(void)d;(void)status;(void)response;abort();
}
static void wait_sends(unsigned wanted) {
    unsigned i;
    for(i=0;i<2000 && __sync_fetch_and_add(&sends,0)<wanted;i++) usleep(1000);
    assert(sends==wanted);
}
static uint32_t start(void *r) {
    uint32_t epoch=alt_ui_session_begin(r);assert(epoch);
    alt_ui_session_note(r,epoch,1,1,1); bootstrap_note(r,1); return epoch;
}
static void finish(void *r) {
    unsigned i;
    bootstrap_forget(r);alt_ui_session_closing(r);alt_ui_session_forget(r);
    for(i=0;i<2000 && __sync_fetch_and_add(&retained,0);i++) usleep(1000);
    assert(!retained);
}
static void hmi(uint32_t epoch,const char *view) {
    char v[16],l[128];int x,y;
    FILE *f=fopen(ALTSCREEN_HMI_STATE ".new","w");assert(f);
    fprintf(f,"view=%s\nlayout_name=LayoutMIB2HighB9Sport\nsmall_stage_dx=-476\nsmall_stage_dy=0\nreceiver_epoch=%u\nreceiver_pid=%u\nexpiry_ms=%llu\n",
            view,epoch,(unsigned)getpid(),(unsigned long long)(alt_ui_realtime_ms()+1500u));
    assert(!fclose(f));
    assert(!rename(ALTSCREEN_HMI_STATE ".new",ALTSCREEN_HMI_STATE));
    assert(alt_local_hmi(v,sizeof(v),l,sizeof(l),&x,&y) ==
           (epoch == altscreen_ui_session_generation()));
}
int main(void) {
    void *r=(void *)0x111,*s=(void *)0x222;
    uint32_t epoch;
    unsigned before, i;
    const char *top="maps:/car/instrumentcluster?maneuverLayout=topaligned&showSpeedLimit=user";
    const char *right="maps:/car/instrumentcluster?maneuverLayout=trailing&showSpeedLimit=no";
    cf_dict_create_mutable=dictionary;cf_array_create_mutable=array;
    cf_str_new=string;cf_num_new=number;cf_dict_set=set;cf_array_append=append;cf_release=release;
    display.uuid="cluster";display.width_pixels=1440;display.height_pixels=542;display.stream_type=111;
    unsetenv("ALTSCREEN_PHONE_PROFILE"); assert(alt_build_cluster_display()==NULL);
    setenv("ALTSCREEN_PHONE_PROFILE","invalid",1);assert(alt_build_cluster_display()==NULL);
    /* Geometry assertions require a quiescent published snapshot. /info
     * deliberately fails closed rather than waiting for a concurrent writer. */
    epoch=alt_ui_session_begin(r);assert(epoch);
    alt_ui_session_note(r,epoch,1,1,1);
    altscreen_oem_map_ready(1);
    for(i=0;i<2;i++) {
        struct object *info,*areas,*safe;
        setenv("ALTSCREEN_PHONE_PROFILE",i ? "card":"map",1);
        hmi(epoch,"SMALL");
        info=alt_build_cluster_display();assert(info);
        assert(get(info,"initialViewArea")->number==1);
        areas=get(info,"viewAreas");assert(areas->count==2);
        safe=get(areas->values[0],"safeArea");
        assert(get(safe,"originXPixels")->number==(i?300:370));
        assert(get(safe,"widthPixels")->number==(i?840:700));
        safe=get(areas->values[1],"safeArea");
        assert(get(safe,"originXPixels")->number==490);
        assert(get(safe,"widthPixels")->number==460);
        release(info);
        hmi(epoch,"FULL");info=alt_build_cluster_display();
        assert(get(info,"initialViewArea")->number==0);release(info);
    }
    hmi(epoch,"FULL");
    assert(alt_send_cluster_view_area(r,s,7,1,0)==0);
    before=sends;
    assert(alt_send_cluster_view_area(r,s,7,2,1)!=0 && sends==before);
    hmi(epoch,"SMALL");
    assert(alt_send_cluster_view_area(r,s,7,2,1)==0);assert(view_acks==2);
    before=sends;live=0;
    assert(alt_send_cluster_view_area(r,s,7,3,1)!=0 && sends==before);
    live=1;assert(alt_send_cluster_zoom(r,s,7,1,0)!=0 && sends==before);
    hmi(epoch+1,"SMALL");
    { char v[16],l[128];int x,y; assert(!alt_local_hmi(v,sizeof(v),l,sizeof(l),&x,&y)); }
    assert(!retained);
    sends=0;
    bootstrap_note(r,1);
    usleep(20000);assert(!sends); /* running worker invents no auto-start URL */
    assert(altscreen_queue_ui("https://invalid",epoch)<0);
    assert(altscreen_queue_ui(top,epoch+1)<0);
    send_mode=1;
    assert(altscreen_queue_ui(top,epoch)==0);wait_sends(1);
    assert(!strcmp(urls[0],top) && !alt_ui_accepted_revision(r));
    assert(altscreen_queue_ui(top,epoch)==0);assert(sends==1);
    held_callback(0,(void *)1,held_context);send_mode=0;
    for(i=0;i<100 && !alt_ui_accepted_revision(r);i++)usleep(1000);
    assert(alt_ui_accepted_revision(r)==1);
    usleep(50000);assert(sends==1); /* successful UI never timer-resends */
    assert(altscreen_queue_ui(right,epoch)==0);wait_sends(2);
    assert(!strcmp(urls[1],right));
    assert(alt_ui_stop(r)==0);assert(!strcmp(types[sends-1],"stopUI"));
    before=sends;assert(alt_ui_stop(r)==0 && sends==before);
    assert(altscreen_queue_ui(top,epoch)<0);
    alt_ui_resume(r);wait_sends(before+1);
    assert(!strcmp(urls[before],right)); /* replacement stream, same cached URL */
    usleep(20000);assert(sends==before+1);
    assert(alt_ui_stop(r)==0);finish(r);
    epoch=start(r);send_mode=2;before=sends;
    assert(altscreen_queue_ui(top,epoch)==0);wait_sends(before+3);
    usleep(100000);assert(sends==before+3 && !alt_ui_accepted_revision(r));
    finish(r); /* exactly three failed remote attempts */
    epoch=start(r);send_mode=3;before=sends;
    assert(altscreen_queue_ui(top,epoch)==0);wait_sends(before+1);
    assert(!alt_ui_accepted_revision(r));
    held_callback(0,(void *)1,held_context);assert(!alt_ui_accepted_revision(r));
    finish(r);
    /* Model separate exec-time profiles. Only each profile's exact base plus
     * an optional query can enter its queue; no extra path is inferred. */
    {
        const char *bases[2] = {
            "maps:/car/instrumentcluster/map",
            "maps:/car/instrumentcluster"
        };
        unsigned profile;
        send_mode=0;
        for(profile=0;profile<2;profile++) {
            char selected[128], wrong[128], subpath[128];
            setenv("ALTSCREEN_PHONE_PROFILE",profile ? "card" : "map",1);
            epoch=start(r);before=sends;
            snprintf(selected,sizeof(selected),"%s?showETA=no&showSpeedLimit=user",bases[profile]);
            snprintf(wrong,sizeof(wrong),"%s?showETA=no",bases[1-profile]);
            snprintf(subpath,sizeof(subpath),"%s/invented",bases[profile]);
            assert(altscreen_queue_ui(bases[1-profile],epoch)<0);
            assert(altscreen_queue_ui(wrong,epoch)<0);
            assert(altscreen_queue_ui(subpath,epoch)<0);
            assert(altscreen_queue_ui("maps:/car/instrumentcluster/mapExtra",epoch)<0);
            assert(sends==before);
            assert(altscreen_queue_ui(bases[profile],epoch)==0);
            wait_sends(before+1);
            for(i=0;i<100 && !alt_ui_accepted_revision(r);i++)usleep(1000);
            assert(alt_ui_accepted_revision(r)==1);
            assert(altscreen_queue_ui(selected,epoch)==0);
            wait_sends(before+2);
            assert(!strcmp(urls[before],bases[profile]));
            assert(!strcmp(urls[before+1],selected));
            finish(r);
        }
    }
    assert(!unlink(ALTSCREEN_HMI_STATE));
    puts("NATIVE_UI_PAYLOAD_COMPLETION_SESSION_PROFILE=PASS");
    return 0;
}
