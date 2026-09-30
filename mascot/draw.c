#include "mascot.h"
#include <stdio.h>
#include <string.h>
#include <math.h>

typedef struct {
    GLint program, buffer, active, texture, unpack, scissor_box[4];
    GLint blend_src_rgb, blend_dst_rgb, blend_src_alpha, blend_dst_alpha, equation_rgb, equation_alpha;
    GLint attribute_enabled, attribute_size, attribute_type, attribute_normalized, attribute_stride, attribute_buffer;
    void *attribute_pointer;
    GLboolean blend, depth, stencil, scissor, cull, mask[4];
} saved_state;

static void save(saved_state *s) {
    glGetIntegerv(GL_CURRENT_PROGRAM,&s->program);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING,&s->buffer);
    glGetIntegerv(GL_ACTIVE_TEXTURE,&s->active);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D,&s->texture);
    glGetIntegerv(GL_UNPACK_ALIGNMENT,&s->unpack);
    glGetIntegerv(GL_SCISSOR_BOX,s->scissor_box);
    glGetIntegerv(GL_BLEND_SRC_RGB,&s->blend_src_rgb);glGetIntegerv(GL_BLEND_DST_RGB,&s->blend_dst_rgb);
    glGetIntegerv(GL_BLEND_SRC_ALPHA,&s->blend_src_alpha);glGetIntegerv(GL_BLEND_DST_ALPHA,&s->blend_dst_alpha);
    glGetIntegerv(GL_BLEND_EQUATION_RGB,&s->equation_rgb);glGetIntegerv(GL_BLEND_EQUATION_ALPHA,&s->equation_alpha);
    glGetVertexAttribiv(0,GL_VERTEX_ATTRIB_ARRAY_ENABLED,&s->attribute_enabled);
    glGetVertexAttribiv(0,GL_VERTEX_ATTRIB_ARRAY_SIZE,&s->attribute_size);
    glGetVertexAttribiv(0,GL_VERTEX_ATTRIB_ARRAY_TYPE,&s->attribute_type);
    glGetVertexAttribiv(0,GL_VERTEX_ATTRIB_ARRAY_NORMALIZED,&s->attribute_normalized);
    glGetVertexAttribiv(0,GL_VERTEX_ATTRIB_ARRAY_STRIDE,&s->attribute_stride);
    glGetVertexAttribiv(0,GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING,&s->attribute_buffer);
    glGetVertexAttribPointerv(0,GL_VERTEX_ATTRIB_ARRAY_POINTER,&s->attribute_pointer);
    s->blend=glIsEnabled(GL_BLEND);s->depth=glIsEnabled(GL_DEPTH_TEST);
    s->stencil=glIsEnabled(GL_STENCIL_TEST);s->scissor=glIsEnabled(GL_SCISSOR_TEST);s->cull=glIsEnabled(GL_CULL_FACE);
    glGetBooleanv(GL_COLOR_WRITEMASK,s->mask);
}
static void enabled(GLenum cap,GLboolean on) {if(on)glEnable(cap);else glDisable(cap);}
static void restore(const saved_state *s) {
    glBindBuffer(GL_ARRAY_BUFFER,s->attribute_buffer);
    glVertexAttribPointer(0,s->attribute_size,s->attribute_type,s->attribute_normalized,s->attribute_stride,s->attribute_pointer);
    if(s->attribute_enabled)glEnableVertexAttribArray(0);else glDisableVertexAttribArray(0);
    glBindBuffer(GL_ARRAY_BUFFER,s->buffer);
    glBindTexture(GL_TEXTURE_2D,s->texture);glActiveTexture(s->active);
    glPixelStorei(GL_UNPACK_ALIGNMENT,s->unpack);
    glUseProgram(s->program);
    glBlendFuncSeparate(s->blend_src_rgb,s->blend_dst_rgb,s->blend_src_alpha,s->blend_dst_alpha);
    glBlendEquationSeparate(s->equation_rgb,s->equation_alpha);
    enabled(GL_BLEND,s->blend);enabled(GL_DEPTH_TEST,s->depth);enabled(GL_STENCIL_TEST,s->stencil);
    enabled(GL_SCISSOR_TEST,s->scissor);enabled(GL_CULL_FACE,s->cull);
    glScissor(s->scissor_box[0],s->scissor_box[1],s->scissor_box[2],s->scissor_box[3]);
    glColorMask(s->mask[0],s->mask[1],s->mask[2],s->mask[3]);
}
static GLuint shader(GLenum type,const char *source,int quiet) {
    GLuint id=glCreateShader(type);
    if(!id)return 0;
    glShaderSource(id,1,&source,NULL);glCompileShader(id);
    GLint good=0;glGetShaderiv(id,GL_COMPILE_STATUS,&good);
    if(!good) {
        if(!quiet) {
            char log[512];GLsizei length=0;
            glGetShaderInfoLog(id,sizeof(log),&length,log);
            fprintf(stderr,"MASCOT=SHADER_ERROR %.*s\n",(int)length,log);
        }
        glDeleteShader(id);return 0;
    }
    return id;
}
void mascot_graphics_destroy(mascot_graphics *g) {
    int quiet=g->quiet;
    glDeleteTextures(MASCOT_COUNT*MASCOT_MAX_FRAMES,&g->textures[0][0]);
    if(g->buffer)glDeleteBuffers(1,&g->buffer);
    if(g->program)glDeleteProgram(g->program);
    memset(g,0,sizeof(*g));g->quiet=quiet;
}
static int initialize(mascot_graphics *g,const mascot_animation a[MASCOT_COUNT]) {
    GLuint probe=0;
    const char *vertex="attribute vec4 point;uniform vec4 rectangle;varying vec2 uv;"
        "void main(){gl_Position=vec4(rectangle.xy+point.xy*rectangle.zw,0.,1.);uv=point.zw;}";
    const char *fragment="precision mediump float;uniform sampler2D image;varying vec2 uv;"
        "void main(){gl_FragColor=texture2D(image,uv);}";
    GLuint vs=shader(GL_VERTEX_SHADER,vertex,g->quiet),fs=shader(GL_FRAGMENT_SHADER,fragment,g->quiet);
    if(vs && fs) {
        g->program=glCreateProgram();
        if(g->program) {
            glAttachShader(g->program,vs);glAttachShader(g->program,fs);
            glBindAttribLocation(g->program,0,"point");glLinkProgram(g->program);
        }
    }
    if(vs)glDeleteShader(vs);
    if(fs)glDeleteShader(fs);
    GLint good=0;
    if(g->program)glGetProgramiv(g->program,GL_LINK_STATUS,&good);
    if(!good)goto fail;
    g->rectangle=glGetUniformLocation(g->program,"rectangle");g->sampler=glGetUniformLocation(g->program,"image");
    if(g->rectangle<0 || g->sampler<0)goto fail;
    glGenBuffers(1,&g->buffer);
    if(!g->buffer)goto fail;
    const GLfloat points[]={0,0,0,1, 1,0,1,1, 0,1,0,0, 1,1,1,0};
    glBindBuffer(GL_ARRAY_BUFFER,g->buffer);glBufferData(GL_ARRAY_BUFFER,sizeof(points),points,GL_STATIC_DRAW);
    GLint bytes=0;
    glGetBufferParameteriv(GL_ARRAY_BUFFER,GL_BUFFER_SIZE,&bytes);
    if(bytes!=(GLint)sizeof(points))goto fail;
    glGenFramebuffers(1,&probe);
    if(!probe)goto fail;
    glBindFramebuffer(GL_FRAMEBUFFER,probe);
    glPixelStorei(GL_UNPACK_ALIGNMENT,1);
    for(unsigned i=0;i<MASCOT_COUNT;i++)for(unsigned f=0;f<a[i].count;f++) {
        glGenTextures(1,&g->textures[i][f]);
        if(!g->textures[i][f])goto fail;
        glBindTexture(GL_TEXTURE_2D,g->textures[i][f]);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,a[i].width,a[i].height,0,GL_RGBA,GL_UNSIGNED_BYTE,
            a[i].pixels+(size_t)f*a[i].width*a[i].height*4);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,g->textures[i][f],0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)goto fail;
    }
    glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(1,&probe);
    g->initialized=1;
    return 1;
fail:
    if(probe){glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(1,&probe);}
    if(!g->quiet)fprintf(stderr,"MASCOT=GRAPHICS_ERROR initialization failed\n");
    mascot_graphics_destroy(g);
    return 0;
}
static int refresh_image(mascot_graphics *g,const mascot_animation *image) {
    GLuint texture=0,probe=0;
    glGenTextures(1,&texture);glGenFramebuffers(1,&probe);
    int good=texture && probe;
    if(good) {
        glBindTexture(GL_TEXTURE_2D,texture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT,1);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,image->width,image->height,0,GL_RGBA,GL_UNSIGNED_BYTE,image->pixels);
        glBindFramebuffer(GL_FRAMEBUFFER,probe);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
        good=glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE;
    }
    glBindFramebuffer(GL_FRAMEBUFFER,0);
    if(probe)glDeleteFramebuffers(1,&probe);
    if(good){glDeleteTextures(1,&g->textures[0][0]);g->textures[0][0]=texture;}
    else {
        if(texture)glDeleteTextures(1,&texture);
        if(!g->quiet)fprintf(stderr,"MASCOT=GRAPHICS_ERROR image upload failed\n");
    }
    return good;
}
static int draw(mascot_graphics *g,const mascot_animation a[MASCOT_COUNT],unsigned selected,uint64_t elapsed_ms,
                int image,int refresh,int fixed,int fixed_x,int fixed_top) {
    if(selected==0)return 1;
    if(selected>MASCOT_COUNT)return 0;
    GLint viewport[4],framebuffer;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING,&framebuffer);glGetIntegerv(GL_VIEWPORT,viewport);
    if(framebuffer!=0 || viewport[2]<(fixed?1:128) || viewport[3]<(fixed?1:64))return 0;
    const mascot_animation *sprite=&a[selected-1];
    if(!sprite->pixels || !sprite->count || !sprite->duration)return 0;
    float width=(image?1.f:2.f)*sprite->width,height=(image?1.f:2.f)*sprite->height;
    /* Trial clearance for the VC-local street/Trip overlay, not a measured bar boundary. */
    float bottom=image?floorf((viewport[3]-height)/2.f):(ceilf(viewport[3]*0.20f)+4.f)*0.70f;
    if(fixed)bottom=viewport[3]-fixed_top-height;
    if(!fixed && image && (viewport[2]<(int)width || viewport[3]<(int)height))return 0;
    if(!fixed && bottom+height>viewport[3])return 0;
    saved_state state;save(&state);
    int good;
    if(image && refresh && g->initialized)good=refresh_image(g,sprite);
    else good=g->initialized || initialize(g,a);
    if(good) {
        float x=floorf((viewport[2]-width)/2.f),draw_width=width;
        if(fixed)x=(float)fixed_x;
        if(!image && viewport[2]>width) {
            double span=viewport[2]-width;
            double travel=fmod(elapsed_ms*0.048,2*span);
            int returning=travel>=span;
            x=(float)(returning?2*span-travel:travel);
            if(sprite->faces_left)x=(float)span-x;
            /* Reflect the quad, not frame order, so the return leg faces forward. */
            if(returning){x+=width;draw_width=-width;}
        }
        glUseProgram(g->program);glUniform1i(g->sampler,0);
        glUniform4f(g->rectangle,2.f*x/viewport[2]-1.f,2.f*bottom/viewport[3]-1.f,
            2.f*draw_width/viewport[2],2.f*height/viewport[3]);
        glBindTexture(GL_TEXTURE_2D,g->textures[selected-1][mascot_frame(sprite,elapsed_ms)]);
        glBindBuffer(GL_ARRAY_BUFFER,g->buffer);
        glVertexAttribPointer(0,4,GL_FLOAT,GL_FALSE,0,0);glEnableVertexAttribArray(0);
        glEnable(GL_BLEND);glBlendEquationSeparate(GL_FUNC_ADD,GL_FUNC_ADD);
        glBlendFuncSeparate(fixed?GL_ONE:GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ZERO,GL_ONE);
        glDisable(GL_DEPTH_TEST);glDisable(GL_STENCIL_TEST);glDisable(GL_CULL_FACE);
        glEnable(GL_SCISSOR_TEST);glScissor(viewport[0],viewport[1],viewport[2],viewport[3]);
        glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);
    }
    restore(&state);
    return good;
}
int mascot_draw(mascot_graphics *g,const mascot_animation a[MASCOT_COUNT],unsigned selected,uint64_t elapsed_ms) {
    return draw(g,a,selected,elapsed_ms,0,0,0,0,0);
}
int mascot_draw_image(mascot_graphics *g,const unsigned char *rgba,unsigned width,unsigned height,int refresh) {
    mascot_animation a[MASCOT_COUNT]={{0}};
    a[0].width=width;a[0].height=height;a[0].count=1;a[0].duration=1000;a[0].delay[0]=1000;
    a[0].pixels=(unsigned char *)rgba;
    return draw(g,a,1,0,1,refresh,0,0,0);
}
int mascot_draw_image_at(mascot_graphics *g,const unsigned char *rgba,unsigned width,unsigned height,
                         int x,int top,int refresh) {
    mascot_animation a[MASCOT_COUNT]={{0}};
    a[0].width=width;a[0].height=height;a[0].count=1;a[0].duration=1000;a[0].delay[0]=1000;
    a[0].pixels=(unsigned char *)rgba;
    return draw(g,a,1,0,1,refresh,1,x,top);
}
