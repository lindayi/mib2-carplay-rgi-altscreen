#include "cards.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <assert.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <unistd.h>

static EGLDisplay display;
static EGLSurface surface;
static unsigned char pixels[1440*455*4];
static int (*swap_count)(void);
static unsigned long long now(void) {
    struct timeval t;gettimeofday(&t,NULL);
    return (unsigned long long)t.tv_sec*1000+t.tv_usec/1000;
}
static void publish(unsigned media,unsigned trip) {
    FILE *f=fopen(CARDS_CONTROL_PATH ".new","w");assert(f);
    assert(fprintf(f,"CARDS1 %u %llu 9 %u %u 500 0\n"
        "436166c3a9206d75736963\n417274697374\n506175736564\n31343a3332\n312068\n3530206d69\n",
        (unsigned)getpid(),now()+4000,media,trip)>0);
    assert(!fclose(f));assert(!rename(CARDS_CONTROL_PATH ".new",CARDS_CONTROL_PATH));
}
static void menu(unsigned preview) {
    FILE *f=fopen("/ramdisk/carplay_vc_panel.control.new","w");assert(f);
    assert(fprintf(f,"VCPANEL1 %u 700 %u %llu 9 1 0\nCarplay Altscreen\nPassive cards test\n"
        "2 0\tReturn\t\n",(unsigned)getpid(),preview+1,now()+600)>0);
    if(preview)assert(fprintf(f,"PREVIEW %u\n",preview)>0);
    assert(!fclose(f));
    assert(!rename("/ramdisk/carplay_vc_panel.control.new","/ramdisk/carplay_vc_panel.control"));
}
static void frame(void) {
    glClear(GL_COLOR_BUFFER_BIT);
    int before=swap_count();
    assert(eglSwapBuffers(display,surface));
    assert(swap_count()==before+1 && glGetError()==GL_NO_ERROR);
    glReadPixels(0,0,1440,455,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
}
static int changed(int x,int top) {
    unsigned char *p=pixels+((454-top)*1440+x)*4;
    return p[0]!=102 || p[1]!=153 || p[2]!=204;
}
static void wait_cards(int media,int trip) {
    unsigned long long until=now()+2000;
    do {
        frame();
        if(changed(250,90)==media && changed(250,222)==trip)return;
        usleep(20000);
    } while(now()<until);
    fprintf(stderr,"Map card interpose state missing %d/%d\n",media,trip);assert(0);
}
static void preview(const char *name) {
    char path[256];snprintf(path,sizeof(path),"build/map-card-tests/%s.ppm",name);
    FILE *f=fopen(path,"wb");assert(f);fprintf(f,"P6\n1440 455\n255\n");
    for(int y=454;y>=0;y--)for(int x=0;x<1440;x++)
        assert(fwrite(pixels+(y*1440+x)*4,1,3,f)==3);
    assert(!fclose(f));
}
int main(void) {
    assert(access("/.dockerenv",F_OK)==0);
    unsetenv("CARPLAY_MASCOT_CONFIG");unsetenv("CARPLAY_MASCOT_STATUS");
    assert(!setenv("ALT111_MIRROR_BASE_READY_FILE","/ramdisk/cards-interpose.ready",1));
    unlink("/ramdisk/carplay_mascot.control");unlink("/ramdisk/carplay_vc_panel.control");
    FILE *f=fopen("/ramdisk/cards-interpose.ready","wb");assert(f);assert(!fclose(f));
    swap_count=(int (*)(void))dlsym(RTLD_DEFAULT,"mascot_swap_count");assert(swap_count);
    display=eglGetDisplay(EGL_DEFAULT_DISPLAY);assert(eglInitialize(display,NULL,NULL));
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig config;EGLint n;assert(eglChooseConfig(display,attrs,&config,1,&n) && n==1);
    EGLint size[]={EGL_WIDTH,1440,EGL_HEIGHT,455,EGL_NONE},version[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    surface=eglCreatePbufferSurface(display,config,size);
    EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,version);
    assert(eglMakeCurrent(display,surface,surface,context));
    glViewport(0,0,1440,455);glClearColor(.4f,.6f,.8f,.37f);
    for(unsigned flags=0;flags<4;flags++) {publish(flags&1,flags>>1);wait_cards(flags&1,flags>>1);}
    preview("hook-both");
    int before=swap_count();usleep(300000);assert(swap_count()==before);
    for(unsigned preview_page=0;preview_page<=1;preview_page++) {
        unsigned long long until=now()+2000;
        do {
            menu(preview_page);frame();
            if(!changed(250,90) && !changed(250,222) && changed(720,100))break;
            usleep(20000);
        } while(now()<until);
        assert(!changed(250,90) && !changed(250,222) && changed(720,100));
        preview(preview_page?"hook-chooser-suppression":"hook-menu-suppression");
        assert(!unlink("/ramdisk/carplay_vc_panel.control"));publish(1,1);wait_cards(1,1);
    }
    assert(eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT));
    assert(eglDestroyContext(display,context));
    context=eglCreateContext(display,config,EGL_NO_CONTEXT,version);
    assert(eglMakeCurrent(display,surface,surface,context));
    glViewport(0,0,1440,455);glClearColor(.4f,.6f,.8f,.37f);publish(1,1);wait_cards(1,1);
    assert(!unlink("/ramdisk/cards-interpose.ready"));wait_cards(0,0);
    f=fopen("/ramdisk/cards-interpose.ready","wb");assert(f);assert(!fclose(f));
    publish(1,1);wait_cards(1,1);
    assert(!mkfifo("/ramdisk/cards-stall.fifo",0600));
    assert(!rename("/ramdisk/cards-stall.fifo","/ramdisk/carplay_mascot.control"));
    /* The shared worker blocks in the legacy mascot reader while video swaps continue. */
    unsigned long long until=now()+1400;
    do {frame();usleep(20000);} while(now()<until);
    assert(!changed(250,90) && !changed(250,222));
    unlink("/ramdisk/carplay_mascot.control");unlink(CARDS_CONTROL_PATH);
    unlink("/ramdisk/cards-interpose.ready");
    assert(eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT));
    assert(eglDestroyContext(display,context));assert(eglDestroySurface(display,surface));assert(eglTerminate(display));
    puts("Map cards hook: real swaps, no autonomous swaps, menu/chooser suppression, readiness, context recovery, stalled-worker withdrawal PASS");
    return 0;
}
