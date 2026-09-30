#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <dlfcn.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <unistd.h>

static char control[512],status_path[512],ready[512];
static EGLDisplay display;
static EGLSurface surface;
static unsigned char baseline[512*256*4],frame[sizeof(baseline)];
static unsigned long long now(void) {
    struct timeval t;gettimeofday(&t,NULL);return (unsigned long long)t.tv_sec*1000+t.tv_usec/1000;
}
static void publish(int choice,unsigned pid,int ttl) {
    char temporary[520];snprintf(temporary,sizeof(temporary),"%s.new",control);
    FILE *f=fopen(temporary,"w");assert(f);
    fprintf(f,"MASCOT2 %d %u %llu\n",choice,pid,now()+ttl);
    assert(fclose(f)==0);assert(rename(temporary,control)==0);
}
static void present(void) {
    glClear(GL_COLOR_BUFFER_BIT);assert(eglSwapBuffers(display,surface));assert(glGetError()==GL_NO_ERROR);
}
static void await(const char *state) {
    unsigned long long end=now()+3000;
    while(now()<end) {
        present();char text[256]={0};FILE *f=fopen(status_path,"r");
        if(f){size_t n=fread(text,1,sizeof(text)-1,f);assert(n<sizeof(text));fclose(f);}
        if(strstr(text,state))return;
        usleep(20000);
    }
    fprintf(stderr,"Missing mascot state: %s\n",state);abort();
}
static void unchanged(void) {
    present();glReadPixels(0,0,512,256,GL_RGBA,GL_UNSIGNED_BYTE,frame);
    assert(!memcmp(baseline,frame,sizeof(frame)));
}
int main(int argc,char **argv) {
    assert(argc==3);
    int missing=!strcmp(argv[2],"missing");
    int status_error=!strncmp(argv[2],"status-",7);
    char directory[]="/tmp/mascot-XXXXXX";assert(mkdtemp(directory));
    snprintf(control,sizeof(control),"/ramdisk/carplay_mascot.control");
    snprintf(status_path,sizeof(status_path),"/ramdisk/carplay_mascot.status");
    snprintf(ready,sizeof(ready),"%s/ready",directory);
    unsetenv("CARPLAY_MASCOT_CONFIG");unsetenv("CARPLAY_MASCOT_STATUS");
    if(status_error) {
        snprintf(status_path,sizeof(status_path),"%s/%sstatus",directory,
            !strcmp(argv[2],"status-open")?"missing/":"");
        setenv("CARPLAY_MASCOT_STATUS",status_path,1);
    }
    setenv("ALT111_MIRROR_BASE_READY_FILE",ready,1);setenv("CARPLAY_MASCOT_ATLAS",missing?"/missing-mascot-atlas":argv[1],1);
    int (*count)(void)=(int (*)(void))dlsym(RTLD_DEFAULT,"mascot_swap_count");
    void (*fail)(int)=(void (*)(int))dlsym(RTLD_DEFAULT,"mascot_swap_fail");assert(count && fail);
    display=eglGetDisplay(EGL_DEFAULT_DISPLAY);assert(eglInitialize(display,NULL,NULL));
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig config;EGLint n;assert(eglChooseConfig(display,attrs,&config,1,&n) && n==1);
    EGLint size[]={EGL_WIDTH,512,EGL_HEIGHT,256,EGL_NONE},version[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    surface=eglCreatePbufferSurface(display,config,size);
    EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,version);
    assert(eglMakeCurrent(display,surface,surface,context));
    glViewport(0,0,512,256);glClearColor(.1,.2,.3,1);glClear(GL_COLOR_BUFFER_BIT);
    glReadPixels(0,0,512,256,GL_RGBA,GL_UNSIGNED_BYTE,baseline);
    publish(1,getpid(),4000);
    if(!status_error){await("state=OFF\n");unchanged();}
    FILE *f=fopen(ready,"w");assert(f);fclose(f);
    if(status_error) {
        unsigned long long end=now()+2500;
        while(now()<end){present();usleep(20000);}
        glReadPixels(0,0,512,256,GL_RGBA,GL_UNSIGNED_BYTE,frame);
        assert(memcmp(baseline,frame,sizeof(frame)));
    } else if(missing) {
        await("state=ASSET_ERROR\n");unchanged();
    } else {
        await("state=ACTIVE\n");
        int swaps=count();usleep(1000000);assert(count()==swaps);
        present();glReadPixels(0,0,512,256,GL_RGBA,GL_UNSIGNED_BYTE,frame);
        assert(memcmp(baseline,frame,sizeof(frame)));
        swaps=count();fail(1);assert(!eglSwapBuffers(display,surface));assert(count()==swaps+1);fail(0);
        glClear(GL_COLOR_BUFFER_BIT);assert(!eglSwapBuffers(display,EGL_NO_SURFACE));
        assert(eglGetError()!=EGL_SUCCESS);
        glReadPixels(0,0,512,256,GL_RGBA,GL_UNSIGNED_BYTE,frame);assert(!memcmp(baseline,frame,sizeof(frame)));
        publish(2,getpid(),4000);await("state=ACTIVE\n");
        publish(3,getpid(),4000);await("state=ACTIVE\n");
        publish(4,getpid(),4000);await("state=ACTIVE\n");
        assert(eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT));
        assert(eglDestroyContext(display,context));
        context=eglCreateContext(display,config,EGL_NO_CONTEXT,version);
        assert(eglMakeCurrent(display,surface,surface,context));
        glViewport(0,0,512,256);glClearColor(.1,.2,.3,1);
        present();usleep(1000000);present();
        glReadPixels(0,0,512,256,GL_RGBA,GL_UNSIGNED_BYTE,frame);assert(memcmp(baseline,frame,sizeof(frame)));
        publish(1,getpid()+1,4000);await("state=CONTROL_STALE\n");unchanged();
        publish(1,getpid(),4000);await("state=ACTIVE\n");
        publish(1,getpid(),-1);await("state=CONTROL_STALE\n");unchanged();
        publish(1,getpid(),4000);await("state=ACTIVE\n");
        assert(unlink(ready)==0);await("state=OFF\n");unchanged();
        f=fopen(ready,"w");assert(f);fclose(f);
        publish(1,getpid(),4000);await("state=ACTIVE\n");
        char fifo[520];snprintf(fifo,sizeof(fifo),"%s.fifo",control);
        assert(mkfifo(fifo,0600)==0);assert(rename(fifo,control)==0);
        usleep(1300000);unchanged();
    }
    assert(eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT));
    assert(eglDestroyContext(display,context));assert(eglDestroySurface(display,surface));assert(eglTerminate(display));
    unlink(control);unlink(status_path);unlink(ready);rmdir(directory);
    puts(status_error?"Mascot status I/O failure does not block real EGL rendering: PASS":
        missing?"Mascot missing-assets isolation: PASS":
        "Mascot real EGL interposition, QNX volatile contract, stalled-worker expiry, PID/ready gates and context recreation: PASS");
    return 0;
}
