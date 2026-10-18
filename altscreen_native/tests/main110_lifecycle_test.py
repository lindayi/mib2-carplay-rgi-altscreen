#!/usr/bin/env python3
"""Exercise production lifecycle wrappers with fixture stock/CF/private111 calls."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
source = (root / "hook/p1404_airplay_fullchain.c").read_text()


def function(signature):
    start = source.index(signature)
    return source[start:source.index("\n}", start)+2] + "\n"


fixture = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "ui_session.h"
#include "p1404_session_guard.h"
typedef void *cf_obj;
struct object {
    int kind;
    int64_t value;
    unsigned count;
    struct object *child, *items[2];
};
static int alt_cf_is_dict(cf_obj p) { return ((struct object *)p)->kind==1; }
static int alt_cf_is_array(cf_obj p) { return ((struct object *)p)->kind==2; }
static cf_obj cf_dict_get_cstr(cf_obj p,const char *key) {
    (void)key; return ((struct object *)p)->child;
}
static int alt_cf_int64(cf_obj p,int64_t *n) {
    if(!p || ((struct object *)p)->kind!=3)return 0;
    *n=((struct object *)p)->value;return 1;
}
static unsigned count(cf_obj p) { return ((struct object *)p)->count; }
static const void *at(cf_obj p,unsigned i) { return ((struct object *)p)->items[i]; }
static void *cf_dict_get=(void *)1, *cf_release=(void *)1;
static unsigned (*cf_array_count)(cf_obj)=count;
static const void *(*cf_array_at)(cf_obj,unsigned)=at;
#define CP_STREAM_MAIN_SCREEN 110
#define CP_STREAM_ALT_SCREEN 111
#define TEARDOWN_MAIN 1u
#define TEARDOWN_ALT 2u
#define TEARDOWN_FULL (TEARDOWN_MAIN|TEARDOWN_ALT)
struct p1404_pf_lease { int a,b; };
static int retained,allow_retain=1,cleanup_ok=1,private_stops,retries,stock_calls;
static int stock_complete,stock_start_result,stock_setup_result,try_reentrant;
static int p1404_identity_ok=1,p1404_armed=1,p1404_mutate_armed=1,alt_flag_create111=1;
static uint32_t before_stop;
static void altscreen_log(const char *format,...) { (void)format; }
static void bind_cf(void) {}
static int alt_airplay_retain_object(void *p) {
    assert(p);if(!allow_retain)return 0;++retained;return 1;
}
static void alt_airplay_release_object(void *p) { assert(p && retained>0);--retained; }
static void p1404_alt111_firewall_maintenance_start(void) {}
static int fullchain_unregistered_stop_pending(void *p) { (void)p;return 0; }
static int alt_runtime_negotiation_ready(const char *p) { (void)p;return 1; }
static int alt_private111_backend_ready(void) { return 1; }
static void bootstrap_note(void *p,int ready) { (void)p;(void)ready; }
static void bootstrap_forget(void *p) { (void)p; }
int alt_ui_stop(void *p) { (void)p;return 1; }
static int alt_private111_teardown_deferred(void *p,const char *s,struct p1404_pf_lease *l) {
    (void)p;(void)s;(void)l;++private_stops;return cleanup_ok;
}
static void alt_private111_finish_cleanup(struct p1404_pf_lease *l) { (void)l; }
static int fullchain_audio_snapshot(const void *p,const struct alt_session_lease *l,uint32_t *e) {
    (void)p;(void)l;(void)e;return 0;
}
static int fullchain_audio_filter(const void *p,const struct alt_session_lease *l,
                                 const uint32_t *e,const void **r,void **o) {
    (void)p;(void)l;(void)e;(void)r;(void)o;assert(0);return 0;
}
static void fullchain_retry_cleanup(void *p,const void *q,int r,struct alt_session_lease *l) {
    (void)p;(void)q;(void)r;(void)l;++retries;
}
static int fullchain_defer_teardown(void *p,const void *q,int r,struct alt_session_lease *l) {
    (void)p;(void)q;(void)r;(void)l;return 0;
}
static void *missing_symbol(const char *name) { (void)name;return NULL; }
static void *(*p1404_direct_stock_symbol_named)(const char *)=missing_symbol;
static void *p1404_stock_symbol_named(const char *name) { return missing_symbol(name); }
int AirPlayReceiverSessionStart(void *,const void *);
int AirPlayReceiverSessionSetup(void *,const void *,void **);
static int stock_start(void *p,const void *q) { (void)p;(void)q;return stock_start_result; }
static void stock_teardown(void *p,const void *q,int reason,unsigned char *done) {
    (void)q;(void)reason;++stock_calls;
    if(try_reentrant) {
        void *response=(void *)1;
        alt_ui_session_note(p,before_stop,1,1,1);
        assert(!altscreen_ui_session_generation());
        assert(AirPlayReceiverSessionStart(p,NULL)==-6752);
        assert(AirPlayReceiverSessionSetup(p,NULL,&response)==-6752 && !response);
    }
    *done=(unsigned char)stock_complete;
}
typedef void (*session_teardown_fn)(void *,const void *,int,unsigned char *);
typedef int (*session_start_fn)(void *,const void *);
static session_teardown_fn real_session_teardown=stock_teardown;
static session_start_fn real_session_start=stock_start;
'''
for signature in (
    "static unsigned fullchain_teardown_scope(",
    "static int fullchain_lifecycle_enter(",
    "static void fullchain_lifecycle_leave(",
    "static void fullchain_ui_note(",
    "static int fullchain_session_start(",
    "int AirPlayReceiverSessionStart(",
    "static int fullchain_teardown_admitted(",
    "void AirPlayReceiverSessionTearDown(",
):
    fixture += function(signature)
fixture += r'''
/* The private111 negotiation inside stock SETUP is outside this fixture.
 * Keep its successful Main110 readiness callback and the real outer wrapper. */
static int fullchain_session_setup(void *p,const void *q,void **response,
                                   struct alt_session_lease *l,uint32_t epoch) {
    (void)q;assert(l->entered);
    *response=stock_setup_result?NULL:(void *)1;
    if(!stock_setup_result)fullchain_ui_note(p,epoch,1,0);
    return stock_setup_result;
}
'''
fixture += function("int AirPlayReceiverSessionSetup(")
fixture += r'''
static uint32_t activate(void *receiver) {
    void *response=NULL;
    assert(!AirPlayReceiverSessionSetup(receiver,NULL,&response) && response);
    assert(!AirPlayReceiverSessionStart(receiver,NULL));
    assert(!retained);
    uint32_t epoch=altscreen_ui_session_generation();
    assert(epoch && epoch==alt_ui_session_epoch(receiver));
    return epoch;
}
int main(void) {
    unsigned char receiver[1024]={0},done;
    void *client=(void *)1,*response=NULL;
    memcpy(receiver+0x2c0,&client,sizeof(client));
    struct object main_type={.kind=3,.value=110},alt_type={.kind=3,.value=111},audio_type={.kind=3,.value=100};
    struct object main={.kind=1,.child=&main_type},alt={.kind=1,.child=&alt_type},audio={.kind=1,.child=&audio_type};
    struct object streams={.kind=2,.count=1,.items={&main,NULL}};
    struct object request={.kind=1,.child=&streams};
    before_stop=activate(receiver);
    try_reentrant=1;
    AirPlayReceiverSessionTearDown(receiver,&request,0,&done);
    assert(!done && stock_calls==1 && !private_stops && !retained);
    assert(!altscreen_ui_session_generation());
    try_reentrant=0;
    uint32_t next=alt_ui_session_begin(receiver);
    assert(next>before_stop);
    alt_ui_session_note(receiver,before_stop,1,1,1);
    assert(!altscreen_ui_session_generation());
    stock_setup_result=-1;
    assert(AirPlayReceiverSessionSetup(receiver,NULL,&response)==-1 && !response);
    stock_setup_result=0;
    assert(!AirPlayReceiverSessionSetup(receiver,NULL,&response));
    assert(!altscreen_ui_session_generation());
    stock_start_result=-1;
    assert(AirPlayReceiverSessionStart(receiver,NULL)==-1 && !altscreen_ui_session_generation());
    stock_start_result=0;
    assert(activate(receiver)==next);
    puts("MAIN110_SAME_RECEIVER_RESTART=PASS");

    for(unsigned i=0;i<2;i++) {
        streams.items[0]=i?&alt:&audio;
        AirPlayReceiverSessionTearDown(receiver,&request,0,&done);
        assert(!done && altscreen_ui_session_generation()==next);
        assert(alt_ui_session_begin(receiver)==next);
    }
    assert(private_stops==1);
    streams.count=2;streams.items[0]=&main;streams.items[1]=&alt;
    cleanup_ok=0;int calls=stock_calls;
    AirPlayReceiverSessionTearDown(receiver,&request,0,&done);
    assert(!done && stock_calls==calls && retries==1 && !retained);
    assert(!altscreen_ui_session_generation() && alt_ui_session_begin(receiver)==next);
    cleanup_ok=1;
    AirPlayReceiverSessionTearDown(receiver,&request,0,&done);
    assert(!done && stock_calls==calls+1 && !retained);
    assert(activate(receiver)>next);
    puts("PARTIAL_STREAM_SCOPE_AND_CLEANUP_FAILURE=PASS");

    next=altscreen_ui_session_generation();
    AirPlayReceiverSessionTearDown(receiver,NULL,0,&done);
    assert(!done && !altscreen_ui_session_generation());
    assert(alt_ui_session_begin(receiver)==next);
    assert(!AirPlayReceiverSessionSetup(receiver,NULL,&response));
    assert(!AirPlayReceiverSessionStart(receiver,NULL));
    assert(!altscreen_ui_session_generation());
    stock_complete=1;
    AirPlayReceiverSessionTearDown(receiver,NULL,0,&done);
    assert(done && !retained);
    assert(activate(receiver)>next);

    streams.count=1;streams.items[0]=&main;
    next=altscreen_ui_session_generation();
    real_session_teardown=NULL;calls=stock_calls;
    AirPlayReceiverSessionTearDown(receiver,&request,0,&done);
    assert(!done && stock_calls==calls && !retained);
    assert(alt_ui_session_begin(receiver)==next && !altscreen_ui_session_generation());
    real_session_teardown=stock_teardown;stock_complete=0;
    AirPlayReceiverSessionTearDown(receiver,&request,0,&done);
    assert(activate(receiver)>next);

    alt_ui_session_forget(receiver);
    allow_retain=0;
    AirPlayReceiverSessionTearDown(receiver,&request,0,&done);
    assert(!done && !retained);
    allow_retain=1;
    assert(!AirPlayReceiverSessionStart(receiver,NULL));
    assert(!AirPlayReceiverSessionSetup(receiver,NULL,&response));
    assert(!altscreen_ui_session_generation());
    AirPlayReceiverSessionTearDown(receiver,&request,0,&done);
    assert(activate(receiver));
    alt_ui_session_forget(receiver);
    puts("FULL_OR_UNFINISHED_TEARDOWN_FENCES=PASS");
    return 0;
}
'''
test = out / "main110_lifecycle_test.c"
test.write_text(fixture)
binary = out / "main110_lifecycle_test"
subprocess.run([
    "cc", "-D_GNU_SOURCE", "-std=gnu99", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
    "-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-I"+str(root/"hook"),
    str(test), str(root/"hook/ui_session.c"), str(root/"hook/p1404_session_guard.c"),
    "-pthread", "-o", str(binary),
], check=True)
subprocess.run([str(binary)], check=True)
