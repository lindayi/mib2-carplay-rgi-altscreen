#include "mascot.h"
#include "../vc_menu/runtime.h"
#include <EGL/egl.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>
#include <unistd.h>
#include <errno.h>

typedef EGLBoolean (*swap_function)(EGLDisplay,EGLSurface);
typedef EGLBoolean (*destroy_function)(EGLDisplay,EGLContext);
static swap_function real_swap;
static destroy_function real_destroy;
static pthread_once_t once=PTHREAD_ONCE_INIT;
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static mascot_animation animations[MASCOT_COUNT];
static int selected,loaded,render_state;
static mascot_graphics graphics;
static EGLContext graphics_context=EGL_NO_CONTEXT;
static unsigned previous;
static uint64_t animation_start;
static int graphics_failed;
static uint64_t retry_after;
static uint64_t control_deadline;

static uint64_t monotonic_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC,&t);
    return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static uint64_t wall_ms(void) {
    struct timeval t;
    gettimeofday(&t,NULL);
    return (uint64_t)t.tv_sec*1000+t.tv_usec/1000;
}
static const char *setting_path(const char *key,const char *fallback) {
    const char *value=getenv(key);
    return value && value[0]?value:fallback;
}
static void status(const char *state,int announce) {
    if(announce)fprintf(stderr,"MASCOT=%s\n",state);
    const char *path=setting_path("CARPLAY_MASCOT_STATUS","/ramdisk/carplay_mascot.status");
    char temporary[512];
    int n=snprintf(temporary,sizeof(temporary),"%s.%ld.new",path,(long)getpid());
    if(n<0 || (size_t)n>=sizeof(temporary)) {
        fprintf(stderr,"MASCOT=STATUS_PATH_ERROR\n");return;
    }
    static int last_error;
    static const char *last_stage="";
    static uint64_t last_report;
    int error=0;
    const char *stage="open";
    FILE *file=fopen(temporary,"w");
    if(!file)error=errno;
    else {
        stage="write";
        if(fprintf(file,"pid=%ld\nstate=%s\nexpires=%llu\n",(long)getpid(),state,
            (unsigned long long)(wall_ms()+4000))<0)error=errno;
        if(fclose(file)!=0 && !error){stage="close";error=errno;}
        if(!error && rename(temporary,path)!=0){stage="rename";error=errno;}
        if(error)unlink(temporary);
    }
    uint64_t now=monotonic_ms();
    if(error && (error!=last_error || strcmp(stage,last_stage) || now-last_report>=30000)) {
        fprintf(stderr,"MASCOT=STATUS_WRITE_ERROR stage=%s errno=%d path=%s\n",stage,error,path);
        last_report=now;
    } else if(!error && last_error)fprintf(stderr,"MASCOT=STATUS_WRITE_RECOVERED\n");
    last_error=error;last_stage=stage;
}
static void *control_loop(void *unused) {
    (void)unused;
    const char *config=setting_path("CARPLAY_MASCOT_CONFIG","/ramdisk/carplay_mascot.control");
    const char *ready=setting_path("ALT111_MIRROR_BASE_READY_FILE","/tmp/mmi-mirror-basevideo.ready");
    const char *atlas=setting_path("CARPLAY_MASCOT_ATLAS","/mnt/app/root/carplay-altscreen/bin/mirror/mascots.rgba");
    int attempted=0;
    const char *last="";
    uint64_t last_status=0;
    for(;;) {
        vc_overlay_poll();
        int next=-1;
        char text[96];
        FILE *file=fopen(config,"r");
        if(file) {
            size_t size=fread(text,1,sizeof(text)-1,file);
            int complete=!ferror(file) && feof(file);
            if(fclose(file)!=0)complete=0;
            text[size]=0;
            if(complete && !memchr(text,0,size))next=mascot_config(text,wall_ms(),(unsigned)getpid());
        }
        if(access(ready,F_OK)!=0)next=0;
        if(next>0 && !attempted) {
            attempted=1;
            int valid=mascot_load(atlas,animations);
            pthread_mutex_lock(&lock);loaded=valid;pthread_mutex_unlock(&lock);
        }
        pthread_mutex_lock(&lock);
        int choice=next>0 && loaded?next:0;
        if(choice!=selected)render_state=0;
        selected=choice;
        control_deadline=choice?monotonic_ms()+1000:0;
        int rendered=render_state;
        pthread_mutex_unlock(&lock);
        const char *state=next<0?"CONTROL_STALE":next==0?"OFF":!loaded?"ASSET_ERROR":
            rendered<0?"GRAPHICS_ERROR":rendered==1?"RACCOON":rendered==2?"NIAN":"QUEUED";
        uint64_t now=monotonic_ms();
        if(strcmp(last,state) || now-last_status>=1000) {
            status(state,strcmp(last,state)!=0);last=state;last_status=now;
        }
        struct timespec wait={0,100000000};
        nanosleep(&wait,NULL);
    }
    return NULL;
}
static void initialize(void) {
    real_swap=(swap_function)dlsym(RTLD_NEXT,"eglSwapBuffers");
    real_destroy=(destroy_function)dlsym(RTLD_NEXT,"eglDestroyContext");
    if(!real_swap || !real_destroy) {
        fprintf(stderr,"MASCOT=EGL_RESOLUTION_ERROR %s\n",dlerror());
        return;
    }
    pthread_t thread;
    int error=pthread_create(&thread,NULL,control_loop,NULL);
    if(error)fprintf(stderr,"MASCOT=WORKER_ERROR code=%d\n",error);
    else pthread_detach(thread);
}
__attribute__((visibility("default")))
EGLBoolean eglSwapBuffers(EGLDisplay display,EGLSurface surface) {
    pthread_once(&once,initialize);
    if(!real_swap)return EGL_FALSE;
    pthread_mutex_lock(&lock);
    unsigned choice=monotonic_ms()<control_deadline?(unsigned)selected:0;
    pthread_mutex_unlock(&lock);
    EGLContext context=eglGetCurrentContext();
    if(choice && context!=EGL_NO_CONTEXT && surface!=EGL_NO_SURFACE
            && eglGetCurrentDisplay()==display && eglGetCurrentSurface(EGL_DRAW)==surface) {
        if(context!=graphics_context) {
            /* The old context owns its old object names; never delete those in a new context. */
            memset(&graphics,0,sizeof(graphics));
            graphics_context=context;graphics_failed=0;previous=0;
        }
        if(choice!=previous) {animation_start=monotonic_ms();graphics_failed=0;}
        if(!graphics_failed || monotonic_ms()>=retry_after) {
            if(!mascot_draw(&graphics,animations,choice,monotonic_ms()-animation_start)) {
                graphics_failed=1;retry_after=monotonic_ms()+5000;
            } else graphics_failed=0;
            pthread_mutex_lock(&lock);
            if(selected==(int)choice)render_state=graphics_failed?-1:(int)choice;
            pthread_mutex_unlock(&lock);
        }
    }
    previous=choice;
    vc_panel_frame frame={0};
    if(context!=EGL_NO_CONTEXT && surface!=EGL_NO_SURFACE
            && eglGetCurrentDisplay()==display && eglGetCurrentSurface(EGL_DRAW)==surface)
        vc_overlay_draw(&frame);
    EGLBoolean result=real_swap(display,surface);
    vc_overlay_presented(&frame,result==EGL_TRUE);
    return result;
}
__attribute__((visibility("default")))
EGLBoolean eglDestroyContext(EGLDisplay display,EGLContext context) {
    pthread_once(&once,initialize);
    if(!real_destroy)return EGL_FALSE;
    EGLBoolean result=real_destroy(display,context);
    if(result && context==graphics_context) {
        memset(&graphics,0,sizeof(graphics));
        graphics_context=EGL_NO_CONTEXT;graphics_failed=0;previous=0;
    }
    if(result)vc_overlay_context_lost(context);
    return result;
}
