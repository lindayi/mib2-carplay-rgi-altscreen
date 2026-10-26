#define ALTSCREEN_HMI_STATE "placement-state.fixture"
#define main unused_mirror_main
#include "../mirror/src/main.cpp"
#undef main
#include <assert.h>

static void record(unsigned pid, unsigned epoch, long expiry_delta, const char *extra) {
    struct timeval tv; assert(gettimeofday(&tv,NULL)==0);
    const unsigned long long expiry=(unsigned long long)tv.tv_sec*1000+tv.tv_usec/1000+expiry_delta;
    FILE *f=fopen(ALTSCREEN_HMI_STATE,"w"); assert(f);
    fprintf(f,"view=SMALL\nlayout_name=LayoutMIB2HighB9Sport\nsmall_stage_dx=-476\nsmall_stage_dy=0\n"
        "receiver_pid=%u\nreceiver_epoch=%u\nexpiry_ms=%llu\n%s",pid,epoch,expiry,extra);
    assert(fclose(f)==0);
}
int main() {
    record(42,3,1200,"");
    OemMapPlacement p=load_map_state(42,7);
    assert(p.recognized && p.dx==-476 && p.epoch==3 && p.stream==7);
    assert(!load_map_state(43,7).recognized);
    record(42,3,-1,""); assert(!load_map_state(42,7).recognized);
    record(42,3,5000,""); assert(!load_map_state(42,7).recognized);
    record(42,0,1200,""); assert(!load_map_state(42,7).recognized);
    record(42,3,1200,"view=FULL\n"); assert(!load_map_state(42,7).recognized);
    record(42,3,1200,"unexpected=1\n"); assert(!load_map_state(42,7).recognized);
    assert(remove(ALTSCREEN_HMI_STATE)==0);
    puts("PLACEMENT_STATE_PID_EPOCH_LEASE=PASS");
}
