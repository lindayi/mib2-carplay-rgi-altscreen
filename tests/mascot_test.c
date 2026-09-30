#include "../mascot/mascot.h"
#include <EGL/egl.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

static int fail_shader,fail_texture;
GLuint __real_glCreateShader(GLenum type);
GLuint __wrap_glCreateShader(GLenum type) {return fail_shader?0:__real_glCreateShader(type);}
void __real_glTexImage2D(GLenum,GLint,GLint,GLsizei,GLsizei,GLint,GLenum,GLenum,const void *);
void __wrap_glTexImage2D(GLenum t,GLint l,GLint i,GLsizei w,GLsizei h,GLint b,GLenum f,GLenum y,const void *p) {
    if(!fail_texture)__real_glTexImage2D(t,l,i,w,h,b,f,y,p);
}
static unsigned char before[512*256*4],after[sizeof(before)];
static void pixels(unsigned char *p) {glReadPixels(0,0,512,256,GL_RGBA,GL_UNSIGNED_BYTE,p);}
static GLuint caller_program(void) {
    GLuint vs=glCreateShader(GL_VERTEX_SHADER),fs=glCreateShader(GL_FRAGMENT_SHADER),p=glCreateProgram();
    const char *v="attribute vec4 position;void main(){gl_Position=position;}";
    const char *f="precision mediump float;void main(){gl_FragColor=vec4(1.);}";
    glShaderSource(vs,1,&v,NULL);glCompileShader(vs);glShaderSource(fs,1,&f,NULL);glCompileShader(fs);
    glAttachShader(p,vs);glAttachShader(p,fs);glLinkProgram(p);
    GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);assert(ok);
    glDeleteShader(vs);glDeleteShader(fs);glUseProgram(p);return p;
}
static void ppm(const char *path) {
    FILE *f=fopen(path,"wb");assert(f);fprintf(f,"P6\n512 256\n255\n");
    for(int y=255;y>=0;y--)for(int x=0;x<512;x++)assert(fwrite(after+(y*512+x)*4,1,3,f)==3);
    assert(fclose(f)==0);
}
static void bounds(int changed_expected) {
    int changed=0;
    for(int y=0;y<256;y++)for(int x=0;x<512;x++) {
        int p=(y*512+x)*4;
        assert(before[p+3]==after[p+3]);
        if(memcmp(before+p,after+p,4)) {
            assert(x>=31 && x<351 && y>=44 && y<124);changed++;
        }
    }
    assert(!changed_expected || changed>40);
}
static const GLenum keys[]={GL_CURRENT_PROGRAM,GL_ARRAY_BUFFER_BINDING,GL_ELEMENT_ARRAY_BUFFER_BINDING,
    GL_ACTIVE_TEXTURE,GL_UNPACK_ALIGNMENT,GL_BLEND_SRC_RGB,GL_BLEND_DST_RGB,GL_BLEND_SRC_ALPHA,
    GL_BLEND_DST_ALPHA,GL_BLEND_EQUATION_RGB,GL_BLEND_EQUATION_ALPHA,GL_FRAMEBUFFER_BINDING};
typedef struct {GLint scalar[12],viewport[4],scissor[4],attrib[7],textures[2];GLboolean flags[9];void *pointer;} state;
static void snapshot(state *s) {
    memset(s,0,sizeof(*s));
    for(unsigned i=0;i<12;i++)glGetIntegerv(keys[i],s->scalar+i);
    glGetIntegerv(GL_VIEWPORT,s->viewport);glGetIntegerv(GL_SCISSOR_BOX,s->scissor);
    const GLenum ak[]={GL_VERTEX_ATTRIB_ARRAY_ENABLED,GL_VERTEX_ATTRIB_ARRAY_SIZE,GL_VERTEX_ATTRIB_ARRAY_TYPE,
        GL_VERTEX_ATTRIB_ARRAY_NORMALIZED,GL_VERTEX_ATTRIB_ARRAY_STRIDE,GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING,
        GL_CURRENT_VERTEX_ATTRIB};
    for(unsigned i=0;i<6;i++)glGetVertexAttribiv(0,ak[i],s->attrib+i);
    glGetVertexAttribiv(1,GL_VERTEX_ATTRIB_ARRAY_ENABLED,s->attrib+6);
    glGetVertexAttribPointerv(0,GL_VERTEX_ATTRIB_ARRAY_POINTER,&s->pointer);
    const GLenum caps[]={GL_BLEND,GL_DEPTH_TEST,GL_STENCIL_TEST,GL_SCISSOR_TEST,GL_CULL_FACE};
    for(unsigned i=0;i<5;i++)s->flags[i]=glIsEnabled(caps[i]);
    glGetBooleanv(GL_COLOR_WRITEMASK,s->flags+5);
    glActiveTexture(GL_TEXTURE0);glGetIntegerv(GL_TEXTURE_BINDING_2D,s->textures);
    glActiveTexture(GL_TEXTURE3);glGetIntegerv(GL_TEXTURE_BINDING_2D,s->textures+1);
    glActiveTexture(s->scalar[3]);
}
static void parsers(const char *atlas,const char *bad) {
    assert(mascot_config("MASCOT2 1 42 4000\n",1000,42)==1);
    assert(mascot_config("MASCOT2 2 42 4000\n",1000,42)==2);
    assert(mascot_config("MASCOT2 3 42 4000\n",1000,42)==3);
    assert(mascot_config("MASCOT2 4 42 4000\n",1000,42)==4);
    assert(mascot_config("MASCOT2 16 42 4000\n",1000,42)==16);
    assert(mascot_config("MASCOT2 0 0 4000\n",1000,42)==0);
    const char *invalid[]={"MASCOT1 1 4000\n","MASCOT2 1 43 4000\n","MASCOT2 1 0 4000\n",
        "MASCOT2 1 42 999\n","MASCOT2 1 42 6001\n","MASCOT2 17 42 4000\n","MASCOT2 1 42 4000\nx",
        "MASCOT2 1 42 -4000\n","MASCOT2 1 42 184467440737095516160\n","MASCOT2 1 42 4000",""};
    for(unsigned i=0;i<sizeof(invalid)/sizeof(invalid[0]);i++)assert(mascot_config(invalid[i],1000,42)==-1);
    FILE *f=fopen(atlas,"rb");assert(f);assert(fseek(f,0,SEEK_END)==0);long n=ftell(f);assert(n>100);
    rewind(f);unsigned char *data=malloc(n+1);assert(data);assert(fread(data,1,n,f)==(size_t)n);fclose(f);
    unsigned offsets[]={0,8,12,16,20,24};
    for(unsigned i=0;i<6;i++) {
        unsigned char original=data[offsets[i]];data[offsets[i]]=offsets[i]==24?0:255;
        f=fopen(bad,"wb");assert(f);assert(fwrite(data,1,n,f)==(size_t)n);fclose(f);
        mascot_animation a[MASCOT_COUNT]={{0}};assert(!mascot_load(bad,a));assert(a[0].pixels==NULL);
        data[offsets[i]]=original;
    }
    for(unsigned i=0;i<5;i++) {
        size_t length=i==4?(size_t)n+1:i==3?(size_t)n-1:i*13;
        data[n]=0;f=fopen(bad,"wb");assert(f);assert(fwrite(data,1,length,f)==length);fclose(f);
        mascot_animation a[MASCOT_COUNT]={{0}};assert(!mascot_load(bad,a));
    }
    free(data);assert(remove(bad)==0);
}
int main(int argc,char **argv) {
    assert(argc==3);char path[512];
    snprintf(path,sizeof(path),"%s/bad.rgba",argv[2]);parsers(argv[1],path);
    mascot_animation a[MASCOT_COUNT]={{0}};assert(mascot_load(argv[1],a));
    for(unsigned i=0;i<MASCOT_COUNT;i++) {
        if(!a[i].count)continue;
        assert(mascot_frame(a+i,0)==0);assert(mascot_frame(a+i,a[i].delay[0])==1);
        assert(mascot_frame(a+i,a[i].duration)==0);
    }
    EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);assert(eglInitialize(d,NULL,NULL));
    EGLint attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig config;EGLint count;assert(eglChooseConfig(d,attributes,&config,1,&count) && count==1);
    EGLint size[]={EGL_WIDTH,512,EGL_HEIGHT,256,EGL_NONE},version[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    EGLSurface surface=eglCreatePbufferSurface(d,config,size);
    EGLContext c=eglCreateContext(d,config,EGL_NO_CONTEXT,version);assert(eglMakeCurrent(d,surface,surface,c));
    glViewport(31,19,320,160);glClearColor(.08f,.17f,.23f,.37f);glClear(GL_COLOR_BUFFER_BIT);pixels(before);
    GLuint program=caller_program();
    GLuint buffers[3],textures[2];glGenBuffers(3,buffers);glGenTextures(2,textures);
    glBindBuffer(GL_ARRAY_BUFFER,buffers[0]);glBufferData(GL_ARRAY_BUFFER,128,NULL,GL_STATIC_DRAW);
    glVertexAttribPointer(0,2,GL_FLOAT,GL_TRUE,16,(void *)8);glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);glBindBuffer(GL_ARRAY_BUFFER,buffers[1]);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,buffers[2]);glPixelStorei(GL_UNPACK_ALIGNMENT,8);
    glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,textures[0]);
    glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,textures[1]);
    glEnable(GL_BLEND);glBlendFuncSeparate(GL_ONE,GL_ZERO,GL_ONE,GL_ZERO);
    glBlendEquationSeparate(GL_FUNC_REVERSE_SUBTRACT,GL_FUNC_SUBTRACT);
    glEnable(GL_DEPTH_TEST);glEnable(GL_STENCIL_TEST);glEnable(GL_CULL_FACE);glEnable(GL_SCISSOR_TEST);
    glScissor(200,200,1,1);glColorMask(GL_FALSE,GL_TRUE,GL_FALSE,GL_FALSE);
    state initial,final;snapshot(&initial);
    mascot_graphics g={0};assert(mascot_draw(&g,a,0,3000));assert(!g.initialized);
    pixels(after);assert(!memcmp(before,after,sizeof(before)));
    assert(!mascot_draw(&g,a,MASCOT_COUNT+1,3000));
    GLuint blocked;glGenFramebuffers(1,&blocked);glBindFramebuffer(GL_FRAMEBUFFER,blocked);
    assert(!mascot_draw(&g,a,1,3000));assert(!g.initialized);
    GLint bound;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&bound);assert((GLuint)bound==blocked);
    glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(1,&blocked);
    glViewport(0,0,50,50);assert(!mascot_draw(&g,a,1,3000));assert(!g.initialized);
    glViewport(0,0,320,90);assert(!mascot_draw(&g,a,1,3000));assert(!g.initialized);
    glViewport(31,19,320,160);
    for(int failure=1;failure<=2;failure++) {
        fail_shader=failure==1;fail_texture=failure==2;
        assert(!mascot_draw(&g,a,1,3000));assert(!g.initialized);
        snapshot(&final);assert(!memcmp(&initial,&final,sizeof(initial)));
        pixels(after);assert(!memcmp(before,after,sizeof(before)));assert(glGetError()==GL_NO_ERROR);
    }
    fail_shader=fail_texture=0;
    for(unsigned mascot=1;mascot<=MASCOT_COUNT;mascot++) {
        if(!a[mascot-1].count)continue;
        glDisable(GL_SCISSOR_TEST);glColorMask(1,1,1,1);glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);glColorMask(0,1,0,0);
        assert(mascot_draw(&g,a,mascot,3000));snapshot(&final);
        assert(!memcmp(&initial,&final,sizeof(initial)));assert(glGetError()==GL_NO_ERROR);
        pixels(after);bounds(1);snprintf(path,sizeof(path),"%s/mascot-%u.ppm",argv[2],mascot);ppm(path);
        for(unsigned t=0;t<20000;t+=137) {
            glDisable(GL_SCISSOR_TEST);glColorMask(1,1,1,1);glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_SCISSOR_TEST);glColorMask(0,1,0,0);
            assert(mascot_draw(&g,a,mascot,t));pixels(after);bounds(0);
        }
    }
    mascot_graphics_destroy(&g);
    unsigned char solid[20*40*4];memset(solid,255,sizeof(solid));
    mascot_animation fixture[MASCOT_COUNT]={{.width=20,.height=40,.count=1,.duration=100,.delay={100},.pixels=solid}};
    for(unsigned i=1;i<MASCOT_COUNT;i++){fixture[i]=fixture[0];fixture[i].faces_left=1;}
    for(unsigned selected=1;selected<=MASCOT_COUNT;selected++) {
        glDisable(GL_SCISSOR_TEST);glColorMask(1,1,1,1);glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);glColorMask(0,1,0,0);
        assert(mascot_draw(&g,fixture,selected,3000));pixels(after);
        int changed=0,minx=512,maxx=-1,miny=256,maxy=-1;
        for(int y=0;y<256;y++)for(int x=0;x<512;x++)if(memcmp(before+(y*512+x)*4,after+(y*512+x)*4,4)) {
            changed++;if(x<minx)minx=x;if(x>maxx)maxx=x;if(y<miny)miny=y;if(y>maxy)maxy=y;
        }
        assert(changed==40*80 && maxx-minx+1==40 && maxy-miny+1==80);
        assert(miny==44 && minx==(selected==1?175:167));
        snapshot(&final);assert(!memcmp(&initial,&final,sizeof(initial)));
    }
    mascot_graphics_destroy(&g);
    /* Asymmetric colors prove the rendered pixels flip, not just the position. */
    for(unsigned y=0;y<40;y++)for(unsigned x=0;x<20;x++) {
        unsigned char *p=solid+(y*20+x)*4;
        p[0]=x<10?255:0;p[1]=0;p[2]=x<10?0:255;p[3]=255;
    }
    glViewport(31,19,328,160);
    snapshot(&initial);
    const unsigned times[]={0,1,1500,3000,5999,6000,6001,9000,11999,12000,12001,25000};
    const float distances[]={0,.048f,72,144,287.952f,288,287.952f,144,.048f,0,.048f,48};
    for(unsigned selected=1;selected<=MASCOT_COUNT;selected++) {
        for(unsigned i=0;i<sizeof(times)/sizeof(times[0]);i++) {
            glDisable(GL_SCISSOR_TEST);glColorMask(1,1,1,1);glClear(GL_COLOR_BUFFER_BIT);
            glEnable(GL_SCISSOR_TEST);glColorMask(0,1,0,0);
            assert(mascot_draw(&g,fixture,selected,times[i]));
            snapshot(&final);assert(!memcmp(&initial,&final,sizeof(initial)));
            GLfloat r[4];glGetUniformfv(g.program,g.rectangle,r);
            int flipped=times[i]%12000>=6000;
            float expected=selected==1?distances[i]:288-distances[i];
            float left=(r[0]+1)*328/2+(r[2]<0?r[2]*328/2:0);
            assert(fabsf(left-expected)<0.001f);
            assert(fabsf(r[2]*328/2-(flipped?-40:40))<0.001f);
            assert(left>=-.001f && left+40<=328.001f);
            pixels(after);
            int lx=31+(int)roundf(expected)+5,rx=lx+30,y=70;
            assert(after[(y*512+lx)*4+(flipped?2:0)]==255);
            assert(after[(y*512+rx)*4+(flipped?0:2)]==255);
            for(unsigned p=3;p<sizeof(after);p+=4)assert(after[p]==before[p]);
        }
    }
    mascot_graphics_destroy(&g);
    glViewport(31,19,320,160);
    for(unsigned selected=1;selected<=MASCOT_COUNT;selected++) {
        double span=320-2*a[selected-1].width;
        if(!a[selected-1].count)continue;
        unsigned t=(unsigned)round(1.5*span/.048);
        glDisable(GL_SCISSOR_TEST);glColorMask(1,1,1,1);glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);glColorMask(0,1,0,0);
        assert(mascot_draw(&g,a,selected,t));pixels(after);bounds(1);
        snprintf(path,sizeof(path),"%s/mascot-%u-return.ppm",argv[2],selected);ppm(path);
    }
    mascot_graphics_destroy(&g);
    glViewport(0,0,128,160);
    unsigned char wide[80*40*4];memset(wide,255,sizeof(wide));
    mascot_animation narrow[MASCOT_COUNT];
    for(unsigned i=0;i<MASCOT_COUNT;i++){narrow[i]=fixture[i];narrow[i].pixels=wide;narrow[i].width=64;}
    for(unsigned w=64;w<=80;w+=16) {
        for(unsigned i=0;i<MASCOT_COUNT;i++)narrow[i].width=w;
        for(unsigned selected=1;selected<=MASCOT_COUNT;selected++) {
            assert(mascot_draw(&g,narrow,selected,6000));
            GLfloat r[4];glGetUniformfv(g.program,g.rectangle,r);
            assert(fabsf((r[0]+1)*128/2-(128-2*(int)w)/2.f)<0.001f);
            assert(fabsf(r[2]*128/2-2*w)<0.001f);
        }
        mascot_graphics_destroy(&g);
    }
    glViewport(0,0,1440,455);
    assert(mascot_draw(&g,fixture,1,3000));
    GLfloat rectangle[4];glGetUniformfv(g.program,g.rectangle,rectangle);
    assert(fabsf((rectangle[1]+1)*455/2-66.5f)<0.001f);
    assert(fabsf(rectangle[2]*1440/2-40)<0.001f && fabsf(rectangle[3]*455/2-80)<0.001f);
    assert(mascot_draw(&g,fixture,1,40000));
    glGetUniformfv(g.program,g.rectangle,rectangle);assert(rectangle[2]<0);
    assert(mascot_draw_image(&g,solid,20,40,1));
    glGetUniformfv(g.program,g.rectangle,rectangle);
    assert(fabsf((rectangle[0]+1)*1440/2-710)<0.001f);
    assert(fabsf((rectangle[1]+1)*455/2-207)<0.001f);
    assert(fabsf(rectangle[2]*1440/2-20)<0.001f);
    snapshot(&initial);fail_texture=1;
    assert(!mascot_draw_image(&g,solid,20,40,1));
    snapshot(&final);assert(!memcmp(&initial,&final,sizeof(initial)));
    fail_texture=0;assert(mascot_draw_image(&g,solid,20,40,1));
    mascot_graphics_destroy(&g);mascot_free(a);glUseProgram(0);glDeleteProgram(program);
    assert(eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT));
    assert(eglDestroyContext(d,c));assert(eglDestroySurface(d,surface));assert(eglTerminate(d));
    puts("Mascot parsers, real GLES pixels/clipping/alpha/state, edge turns/flipping, frames, and graphics failures: PASS");
    return 0;
}
