#include "runtime.h"
#include "../mascot/mascot.h"
#include <EGL/egl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

#define IMAGE_WIDTH (VC_PANEL_PREVIEW_WIDTH+32)
#define IMAGE_HEIGHT (VC_PANEL_HEIGHT+32)
#define IMAGE_BYTES (IMAGE_WIDTH*IMAGE_HEIGHT*4)
static pthread_mutex_t mutex=PTHREAD_MUTEX_INITIALIZER;
static vc_panel_request current;
static vc_panel_renderer font;
static unsigned char buffers[2][IMAGE_BYTES];
static unsigned front;
static uint64_t deadline,frame_expiry;
static unsigned frame_revision,texture_revision;
static uint64_t texture_epoch;
static uint64_t image_serial,texture_serial,preview_started;
static unsigned image_width=VC_PANEL_WIDTH+32,preview_frame;
static int state;
static mascot_graphics graphics;
static EGLContext context=EGL_NO_CONTEXT;
static uint64_t wall_ms(void) {
    struct timeval t;gettimeofday(&t,NULL);
    return (uint64_t)t.tv_sec*1000+t.tv_usec/1000;
}
static uint64_t mono_ms(void) {
    struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);
    return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
void vc_overlay_poll(void) {
    char text[2048];
    vc_panel_request request={0};
    int error=0,valid=0;
    int fd=open("/ramdisk/carplay_vc_panel.control",O_RDONLY|O_NONBLOCK);
    if(fd>=0) {
        struct stat info;
        if(fstat(fd,&info)==0 && S_ISREG(info.st_mode) && info.st_size>0 && info.st_size<(off_t)sizeof(text)) {
            ssize_t n=read(fd,text,sizeof(text)-1);
            if(n==info.st_size && !memchr(text,0,(size_t)n)) {
                text[n]=0;valid=vc_panel_decode(text,wall_ms(),(unsigned)getpid(),&request);
            }
        }
        if(close(fd)!=0)valid=0;
        if(!valid)error=1;
    } else if(errno!=ENOENT)error=1;
    const char *ready=getenv("ALT111_MIRROR_BASE_READY_FILE");
    if(access(ready && ready[0]?ready:"/tmp/mmi-mirror-basevideo.ready",F_OK)!=0)request.page.count=0;
    pthread_mutex_lock(&mutex);
    int repaint=request.page.count && (!current.page.count || request.epoch!=current.epoch || request.revision!=current.revision);
    int preview_changed=request.epoch!=current.epoch || request.page.preview!=current.page.preview || !current.page.count;
    unsigned back=1-front;
    pthread_mutex_unlock(&mutex);
    unsigned width=vc_panel_width(&request.page)+32,animation_frame=0;
    const mascot_animation *animation=NULL;
    const char *preview_message=NULL;
    if(request.page.count && request.page.preview) {
        if(preview_changed)preview_started=mono_ms();
        if(request.page.preview==VC_PANEL_PREVIEW_PAGE)preview_message="Choose a mascot";
        else if(request.page.preview==1)preview_message="Mascot Off";
        else {
            const mascot_animation *assets=mascot_worker_assets();
            if(assets && assets[request.page.preview-2].count) {
                animation=assets+request.page.preview-2;
                animation_frame=mascot_frame(animation,mono_ms()-preview_started);
                if(animation_frame!=preview_frame)repaint=1;
            } else preview_message="Preview unavailable";
        }
    }
    if(repaint) {
        vc_panel panel={0};
        memset(buffers[back],0,IMAGE_BYTES);
        for(unsigned y=16;y<VC_PANEL_HEIGHT+16;y++)for(unsigned x=16;x<width-16;x++)
            buffers[back][(y*width+x)*4+3]=255;
        if(!vc_panel_renderer_init(&font) || !vc_panel_open(&panel,&request.page))error=1;
        else {
            panel.focus[0]=request.focus;
            if(!vc_panel_paint(&font,&panel,buffers[back],IMAGE_BYTES,width,IMAGE_HEIGHT,width*4))error=1;
            else vc_panel_preview(&font,&request.page,buffers[back],width,IMAGE_HEIGHT,width*4,
                animation?animation->pixels+(size_t)animation_frame*animation->width*animation->height*4:NULL,
                animation?animation->width:0,animation?animation->height:0,preview_message);
        }
    }
    pthread_mutex_lock(&mutex);
    if(error) {request.page.count=0;state=-1;frame_expiry=0;}
    else if(!request.page.count){state=0;frame_expiry=0;}
    else if(!current.page.count || request.epoch!=current.epoch){state=0;frame_expiry=0;}
    if(valid)current=request;
    else current.page.count=0;
    if(repaint && !error){front=back;image_width=width;image_serial++;preview_frame=animation_frame;}
    uint64_t now=wall_ms(),remaining=current.expires>now?current.expires-now:0;
    deadline=current.page.count && !error?mono_ms()+(remaining<400?remaining:400):0;
    uint64_t expiry=frame_expiry,epoch=current.epoch;
    unsigned version=frame_revision,connection=current.connection;
    int reported=state;
    pthread_mutex_unlock(&mutex);
    if(valid && request.pid>1 && request.pid!=(unsigned)getpid())return;
    static int last_error;
    if(error!=last_error)fprintf(stderr,"VC_PANEL=%s\n",error?"CONTROL_ERROR":"CONTROL_READY");
    last_error=error;
    char temporary[128];
    snprintf(temporary,sizeof(temporary),"/ramdisk/carplay_vc_panel.status.%u.new",(unsigned)getpid());
    FILE *output=fopen(temporary,"w");
    int io_error=0;
    if(!output)io_error=errno;
    else {
        if(fprintf(output,"VCPANEL1 %u %llu %u %llu %u %d\n",(unsigned)getpid(),
                (unsigned long long)epoch,version,(unsigned long long)expiry,connection,reported)<0)io_error=errno;
        if(fclose(output)!=0 && !io_error)io_error=errno;
        if(!io_error && rename(temporary,"/ramdisk/carplay_vc_panel.status")!=0)io_error=errno;
    }
    static int last_io_error;
    if(io_error!=last_io_error)fprintf(stderr,"VC_PANEL=STATUS_IO errno=%d\n",io_error);
    last_io_error=io_error;
}
void vc_overlay_context_lost(void *destroyed) {
    pthread_mutex_lock(&mutex);
    if(context!=destroyed){pthread_mutex_unlock(&mutex);return;}
    memset(&graphics,0,sizeof(graphics));context=EGL_NO_CONTEXT;texture_revision=0;texture_epoch=0;
    frame_expiry=0;state=0;pthread_mutex_unlock(&mutex);
}
void vc_overlay_draw(vc_panel_frame *frame) {
    frame->drawn=0;
    /* The worker holds this only to exchange metadata/buffers, never during I/O or painting. */
    pthread_mutex_lock(&mutex);
    if(!current.page.count || mono_ms()>=deadline){pthread_mutex_unlock(&mutex);return;}
    EGLContext active=eglGetCurrentContext();
    if(active!=context) {
        memset(&graphics,0,sizeof(graphics));context=active;texture_revision=0;texture_epoch=0;frame_expiry=0;
    }
    frame->epoch=current.epoch;frame->revision=current.revision;
    int refresh=texture_epoch!=frame->epoch || texture_revision!=frame->revision || texture_serial!=image_serial;
    frame->drawn=mascot_draw_image(&graphics,buffers[front],image_width,IMAGE_HEIGHT,refresh);
    if(frame->drawn){texture_epoch=frame->epoch;texture_revision=frame->revision;texture_serial=image_serial;}
    else {state=-1;frame_expiry=0;}
    pthread_mutex_unlock(&mutex);
}
void vc_overlay_presented(const vc_panel_frame *frame,int success) {
    pthread_mutex_lock(&mutex);
    /* A newer request during swap does not undo the older frame actually presented. */
    if(frame->drawn && success && current.epoch==frame->epoch && frame->revision<=current.revision
            && current.page.count && mono_ms()<deadline) {
        frame_expiry=wall_ms()+300;frame_revision=frame->revision;state=1;
    } else if(!success){frame_expiry=0;state=-1;}
    pthread_mutex_unlock(&mutex);
}
