#include "cards.h"
#include "../mascot/mascot.h"
#include <EGL/egl.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

static pthread_mutex_t mutex=PTHREAD_MUTEX_INITIALIZER;
static cards_painter painter;
static cards_art artwork;
static cards_request painted;
static unsigned char buffers[2][CARDS_IMAGE_BYTES];
static unsigned front;
static uint64_t deadline,image_serial,texture_serial;
static mascot_graphics graphics;
static EGLContext context=EGL_NO_CONTEXT;
static GLint texture_viewport[4];
static int graphics_error,lease_expired;
static uint64_t scroll_origin,media_track,media_connection;
static int media_active;
static cards_scroll painted_scroll;
static unsigned char speed_buffers[2][SPEED_IMAGE_BYTES];
static unsigned speed_front;
static uint64_t speed_deadline,speed_serial,speed_texture_serial;
static cards_speed painted_speed;
static mascot_graphics speed_graphics;
static GLint speed_viewport[4];
static unsigned speed_wide;

static uint64_t mono_ms(void) {
    struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);
    return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static uint64_t wall_ms(void) {
    struct timeval t;gettimeofday(&t,NULL);
    return (uint64_t)t.tv_sec*1000+t.tv_usec/1000;
}
static void diagnostic(unsigned slot,const char *message,unsigned detail) {
    static uint64_t next[4];
    uint64_t now=mono_ms();
    if(message && now>=next[slot]) {
        fprintf(stderr,"CARDS=%s detail=%u\n",message,detail);
        next[slot]=now+30000;
    }
}
static const char *read_control(cards_request *request) {
    char bytes[CARDS_MAX_SNAPSHOT+1];
    int fd=open(CARDS_CONTROL_PATH,O_RDONLY|O_NONBLOCK);
    if(fd<0)return "CONTROL_MISSING_OR_OPEN_ERROR";
    struct stat info;
    const char *error=NULL;
    if(fstat(fd,&info)!=0 || !S_ISREG(info.st_mode) ||
            info.st_size<=0 || info.st_size>CARDS_MAX_SNAPSHOT)error="CONTROL_SIZE_ERROR";
    else {
        ssize_t n=read(fd,bytes,sizeof(bytes));
        if(n!=info.st_size || !cards_decode(bytes,(size_t)(n>0?n:0),wall_ms(),(unsigned)getpid(),request))
            error="CONTROL_INVALID_OR_EXPIRED";
    }
    if(close(fd)!=0)error="CONTROL_CLOSE_ERROR";
    return error;
}
static int same_content(const cards_request *a,const cards_request *b) {
    return a->connection==b->connection && a->track==b->track && a->media==b->media && a->trip==b->trip &&
        a->progress==b->progress && a->art_crc==b->art_crc && !memcmp(a->text,b->text,sizeof(a->text));
}
void cards_overlay_poll(void) {
    cards_request request={0};
    const char *error=read_control(&request);
    const char *ready=getenv("ALT111_MIRROR_BASE_READY_FILE");
    if(!error && access(ready && *ready?ready:"/tmp/mmi-mirror-basevideo.ready",F_OK)!=0)
        error="VIDEO_NOT_READY";
    if(error) {
        diagnostic(0,error,0);
        request.media=request.trip=0;
        request.speed.enabled=0;
    }
    pthread_mutex_lock(&mutex);
    unsigned back=1-front;
    int expired=mono_ms()>=deadline,failed=graphics_error,stalled=lease_expired;
    unsigned speed_back=1-speed_front;
    int speed_repaint=request.speed.enabled && (mono_ms()>=speed_deadline ||
        !painted_speed.enabled || request.speed.speed!=painted_speed.speed ||
        request.speed.limit!=painted_speed.limit || request.speed.unit!=painted_speed.unit ||
        request.speed.source!=painted_speed.source);
    lease_expired=0;
    pthread_mutex_unlock(&mutex);
    if(failed)diagnostic(3,"GRAPHICS_ERROR",0);
    if(stalled)diagnostic(0,"WORKER_LEASE_EXPIRED",0);

    static uint32_t attempted_crc;
    static uint64_t attempted_connection,next_art_attempt;
    uint64_t now=mono_ms();
    if(request.media && (!media_active || expired || request.track!=media_track ||
            request.connection!=media_connection))scroll_origin=now;
    media_active=request.media;media_track=request.track;media_connection=request.connection;
    uint32_t wanted=request.media?request.art_crc:0;
    int art_changed=wanted!=attempted_crc || request.connection!=attempted_connection;
    if(art_changed || !wanted) {
        cards_art_free(&artwork);next_art_attempt=0;
        attempted_crc=wanted;attempted_connection=request.connection;
    }
    if(wanted && !artwork.pixels && now>=next_art_attempt) {
        const char *art_error=cards_art_load(CARDS_ART_PATH,wanted,&artwork);
        diagnostic(1,art_error,0);
        next_art_attempt=mono_ms()+1000;
        if(!art_error)art_changed=1;
    }
    cards_scroll scroll=cards_scroll_at(&request,&artwork,now-scroll_origin);
    int repaint=(request.media || request.trip) &&
        (expired || art_changed || !same_content(&request,&painted) ||
         scroll.title!=painted_scroll.title || scroll.artist!=painted_scroll.artist);
    if(repaint) {
        if(!cards_painter_init(&painter)) {
            error="PAINTER_ALLOCATION_ERROR";diagnostic(0,error,0);
        } else {
            unsigned omitted=cards_paint(&painter,&request,&artwork,scroll,buffers[back]);
            if(omitted)diagnostic(2,"UNSUPPORTED_TEXT_OMITTED",omitted);
            painted=request;painted_scroll=scroll;
        }
    }
    if(speed_repaint) {
        if(!cards_painter_init(&painter)) {
            error="PAINTER_ALLOCATION_ERROR";diagnostic(0,error,0);
        } else cards_speed_paint(&painter,&request.speed,speed_buffers[speed_back]);
    }
    painted_speed=request.speed;
    uint64_t wall=wall_ms(),remaining=request.expires>wall?request.expires-wall:0;
    pthread_mutex_lock(&mutex);
    if(repaint && !error){front=back;image_serial++;}
    deadline=!error && (request.media || request.trip) && remaining?
        mono_ms()+(remaining<CARDS_WORKER_LEASE_MS?remaining:CARDS_WORKER_LEASE_MS):0;
    if(speed_repaint && !error){speed_front=speed_back;speed_serial++;}
    if(request.speed.speed>=0) {
        uint64_t fresh=request.speed.expires>wall?request.speed.expires-wall:0;
        if(fresh<remaining)remaining=fresh;
    }
    speed_deadline=!error && request.speed.enabled && remaining?
        mono_ms()+(remaining<CARDS_WORKER_LEASE_MS?remaining:CARDS_WORKER_LEASE_MS):0;
    speed_wide=request.speed.wide;
    pthread_mutex_unlock(&mutex);
}
void cards_overlay_draw(int menu_visible) {
    pthread_mutex_lock(&mutex);
    uint64_t now=mono_ms();
    if((deadline && now>=deadline) || (speed_deadline && now>=speed_deadline))lease_expired=1;
    int card=now<deadline,speed=now<speed_deadline;
    if(menu_visible || (!card && !speed)){pthread_mutex_unlock(&mutex);return;}
    EGLContext active=eglGetCurrentContext();
    if(active==EGL_NO_CONTEXT){pthread_mutex_unlock(&mutex);return;}
    if(active!=context) {
        memset(&graphics,0,sizeof(graphics));graphics.quiet=1;
        memset(&speed_graphics,0,sizeof(speed_graphics));speed_graphics.quiet=1;
        speed_texture_serial=0;
        context=active;texture_serial=0;
    }
    GLint viewport[4];glGetIntegerv(GL_VIEWPORT,viewport);
    int good=1;
    if(card) {
        int refresh=texture_serial!=image_serial || memcmp(viewport,texture_viewport,sizeof(viewport));
        good=mascot_draw_image_at(&graphics,buffers[front],CARDS_IMAGE_WIDTH,CARDS_IMAGE_HEIGHT,
                                 CARDS_X-CARDS_SHADOW_PAD,CARDS_TOP-CARDS_SHADOW_PAD,refresh);
        if(good){texture_serial=image_serial;memcpy(texture_viewport,viewport,sizeof(viewport));}
    }
    if(speed) {
        int refresh=speed_texture_serial!=speed_serial || memcmp(viewport,speed_viewport,sizeof(viewport));
        int result=mascot_draw_image_at(&speed_graphics,speed_buffers[speed_front],SPEED_WIDTH,SPEED_HEIGHT,
            speed_wide?SPEED_X_WIDE:SPEED_X_INSET,SPEED_Y,refresh);
        if(result){speed_texture_serial=speed_serial;memcpy(speed_viewport,viewport,sizeof(viewport));}
        good=good && result;
    }
    graphics_error=!good;
    pthread_mutex_unlock(&mutex);
}
void cards_overlay_context_lost(void *destroyed) {
    pthread_mutex_lock(&mutex);
    if(context==destroyed) {
        memset(&graphics,0,sizeof(graphics));context=EGL_NO_CONTEXT;texture_serial=0;
        memset(&speed_graphics,0,sizeof(speed_graphics));speed_texture_serial=0;
    }
    pthread_mutex_unlock(&mutex);
}
