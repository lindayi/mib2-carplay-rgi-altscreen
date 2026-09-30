#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <assert.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <unistd.h>

#define WIDTH 1440
#define HEIGHT 455
static unsigned char baseline[WIDTH*HEIGHT*4],pixels[sizeof(baseline)];
static EGLDisplay display;
static EGLSurface surface;
static unsigned version=1;
static unsigned preview;
static unsigned long long now(void) {
    struct timeval t;gettimeofday(&t,NULL);return (unsigned long long)t.tv_sec*1000+t.tv_usec/1000;
}
static void publish(unsigned pid,int lifetime) {
    FILE *f=fopen("/ramdisk/carplay_vc_panel.control.new","w");assert(f);
    if(preview)assert(fprintf(f,"VCPANEL1 %u 700 %u %llu 2 5 1\nMap mascot\nFocus previews; OK applies\n"
        "3 1\tOff\t\n3 0\tRobot 1\t\n3 0\tRobot 2\t\n3 0\tRobot 3\t\n2 0\tNext page\t\n",
        pid,version,now()+lifetime)>0);
    else assert(fprintf(f,"VCPANEL1 %u 700 %u %llu 2 5 0\nCarplay Altscreen\n%s\n"
        "1 %u\tEnabled\t\n2 0\tDisplay mode\tMap + guidance\n2 0\tMap layout\tCard on top\n"
        "2 0\tMap mascot\tOff\n2 0\tMore settings\t\n",pid,version,now()+lifetime,
        version==1?"Hold roller: close":"Saved: test",version==1?1:0)>0);
    if(preview)assert(fprintf(f,"PREVIEW %u\n",preview)>0);
    assert(fclose(f)==0);assert(rename("/ramdisk/carplay_vc_panel.control.new","/ramdisk/carplay_vc_panel.control")==0);
}
static void frame(void) {
    glClear(GL_COLOR_BUFFER_BIT);assert(eglSwapBuffers(display,surface));assert(glGetError()==GL_NO_ERROR);
}
static int status(unsigned revision,int wanted) {
    unsigned pid,rev,connection;
    unsigned long long epoch,expires;
    int state;
    FILE *f=fopen("/ramdisk/carplay_vc_panel.status","r");
    if(!f)return 0;
    int n=fscanf(f,"VCPANEL1 %u %llu %u %llu %u %d",&pid,&epoch,&rev,&expires,&connection,&state);
    fclose(f);
    return n==6 && pid==(unsigned)getpid() && epoch==700 && (revision==0 || rev==revision)
        && connection==2 && state==wanted && (wanted!=1 || expires>now());
}
static void await(unsigned revision,int state,int renew) {
    unsigned long long end=now()+3000;
    do {
        if(renew)publish(getpid(),600);
        frame();
        if(status(revision,state))return;
        usleep(15000);
    } while(now()<end);
    fprintf(stderr,"VC panel status missing: rev=%u state=%d\n",revision,state);abort();
}
static void unchanged(void) {
    frame();glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    assert(!memcmp(pixels,baseline,sizeof(pixels)));
}
static void advance_during_swap(void) {
    version++;
    publish(getpid(),600);
    usleep(200000);
}
int main(int argc,char **argv) {
    assert(argc==3 && access("/.dockerenv",F_OK)==0);
    setenv("ALT111_MIRROR_BASE_READY_FILE","/tmp/vc-panel-ready",1);
    display=eglGetDisplay(EGL_DEFAULT_DISPLAY);assert(eglInitialize(display,NULL,NULL));
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig config;EGLint n;assert(eglChooseConfig(display,attrs,&config,1,&n) && n==1);
    EGLint size[]={EGL_WIDTH,WIDTH,EGL_HEIGHT,HEIGHT,EGL_NONE},api[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    surface=eglCreatePbufferSurface(display,config,size);
    EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,api);
    assert(eglMakeCurrent(display,surface,surface,context));
    glViewport(0,0,WIDTH,HEIGHT);glClearColor(.12f,.15f,.17f,.4f);glClear(GL_COLOR_BUFFER_BIT);
    glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,baseline);
    publish(getpid(),600);usleep(150000);unchanged();
    FILE *f=fopen("/tmp/vc-panel-ready","w");assert(f);fclose(f);
    await(1,1,1);
    glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    unsigned changed=0;
    for(unsigned y=0;y<HEIGHT;y++)for(unsigned x=0;x<WIDTH;x++) {
        unsigned at=(y*WIDTH+x)*4;
        assert(pixels[at+3]==baseline[at+3]);
        if(memcmp(pixels+at,baseline+at,4)) {
            assert(x>=510 && x<930 && y>=83 && y<371);changed++;
        }
    }
    fprintf(stderr,"VC_PANEL_TEST changed_pixels=%u\n",changed);
    assert(changed==420*288);
    f=fopen(argv[1],"wb");assert(f);fprintf(f,"P6\n%d %d\n255\n",WIDTH,HEIGHT);
    for(int y=HEIGHT-1;y>=0;y--)for(int x=0;x<WIDTH;x++)assert(fwrite(pixels+(y*WIDTH+x)*4,1,3,f)==3);
    assert(fclose(f)==0);
    preview=2;version++;await(version,1,1);
    glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    changed=0;
    for(unsigned y=0;y<HEIGHT;y++)for(unsigned x=0;x<WIDTH;x++) {
        unsigned at=(y*WIDTH+x)*4;
        assert(pixels[at+3]==baseline[at+3]);
        if(memcmp(pixels+at,baseline+at,4)) {
            assert(x>=400 && x<1040 && y>=83 && y<371);changed++;
        }
    }
    assert(changed==640*288);
    f=fopen(argv[2],"wb");assert(f);fprintf(f,"P6\n%d %d\n255\n",WIDTH,HEIGHT);
    for(int y=HEIGHT-1;y>=0;y--)for(int x=0;x<WIDTH;x++)assert(fwrite(pixels+(y*WIDTH+x)*4,1,3,f)==3);
    assert(fclose(f)==0);
    unsigned char *first=malloc(sizeof(pixels));assert(first);memcpy(first,pixels,sizeof(pixels));
    unsigned animated=0;
    for(unsigned tick=0;tick<12;tick++) {
        publish(getpid(),600);usleep(50000);frame();
        glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        for(unsigned y=0;y<HEIGHT;y++)for(unsigned x=0;x<WIDTH;x++) {
            unsigned at=(y*WIDTH+x)*4;
            if(memcmp(pixels+at,first+at,4)) {
                assert(x>820 && x<1040 && y>83 && y<371);animated++;
            }
            assert(pixels[at+3]==baseline[at+3]);
        }
    }
    assert(animated>100 && status(version,1));
    preview=1;version++;await(version,1,1);
    glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,first);
    for(unsigned tick=0;tick<5;tick++) {
        publish(getpid(),600);usleep(50000);frame();
        glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        assert(!memcmp(first,pixels,sizeof(pixels)));
    }
    free(first);
    preview=17;version++;await(version,1,1);
    preview=18;version++;await(version,1,1);
    preview=0;version++;await(version,1,1);
    void (*before_swap)(void (*)(void))=(void (*)(void (*)(void)))dlsym(RTLD_DEFAULT,"mascot_swap_before");
    assert(before_swap);
    for(unsigned i=0;i<8;i++) {
        unsigned presented=version;
        before_swap(advance_during_swap);
        frame();usleep(150000);
        assert(status(presented,1));
        await(version,1,1);
    }
    usleep(450000);assert(!status(version,1));
    publish(getpid(),600);await(version,1,1);
    void (*fail)(int)=(void (*)(int))dlsym(RTLD_DEFAULT,"mascot_swap_fail");assert(fail);
    fail(1);assert(!eglSwapBuffers(display,surface));usleep(150000);
    assert(status(version,-1));fail(0);await(version,1,1);
    assert(unlink("/tmp/vc-panel-ready")==0);await(0,0,0);unchanged();
    f=fopen("/tmp/vc-panel-ready","w");assert(f);fclose(f);
    publish(getpid()+1,600);usleep(180000);unchanged();
    f=fopen("/ramdisk/carplay_vc_panel.status","w");assert(f);fputs("FOREIGN_STATUS\n",f);fclose(f);
    usleep(180000);
    char foreign[64]={0};f=fopen("/ramdisk/carplay_vc_panel.status","r");assert(f);
    assert(fgets(foreign,sizeof(foreign),f));fclose(f);
    assert(!strcmp(foreign,"FOREIGN_STATUS\n"));
    publish(getpid(),600);await(version,1,1);
    assert(eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT));
    assert(eglDestroyContext(display,context));
    context=eglCreateContext(display,config,EGL_NO_CONTEXT,api);
    assert(eglMakeCurrent(display,surface,surface,context));
    glViewport(0,0,WIDTH,HEIGHT);glClearColor(.12f,.15f,.17f,.4f);
    await(version,1,1);
    glViewport(0,0,328,181);publish(getpid(),600);
    frame();usleep(150000);assert(status(version,-1));
    glViewport(0,0,WIDTH,HEIGHT);await(version,1,1);
    publish(getpid(),1);usleep(180000);unchanged();
    publish(getpid(),600);await(version,1,1);
    assert(mkfifo("/ramdisk/vc-panel-fifo",0600)==0);
    assert(rename("/ramdisk/vc-panel-fifo","/ramdisk/carplay_mascot.control")==0);
    usleep(650000);publish(getpid(),600);unchanged();assert(!status(version,1));
    unlink("/ramdisk/carplay_mascot.control");unlink("/ramdisk/carplay_vc_panel.control");
    unlink("/ramdisk/carplay_vc_panel.status");unlink("/tmp/vc-panel-ready");
    puts("VC panel real EGL: full-size pixels/alpha, successful-swap acknowledgements, revisions, stall/PID/readiness/expiry gates and context recovery PASS");
    return 0;
}
