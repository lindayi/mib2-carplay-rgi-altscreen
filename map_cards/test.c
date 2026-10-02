#include "cards.h"
#include "../mascot/mascot.h"
#include <EGL/egl.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <sys/time.h>
#include <pthread.h>
#include <errno.h>

static uint64_t mono=10000,wall=100000;
static unsigned uploads,queries;
static int fail_texture;
static int allocations,fail_after=-1;
static cards_painter geometry;
void *__real_malloc(size_t size);
void *__wrap_malloc(size_t size) {
    allocations++;
    if(fail_after==0)return NULL;
    if(fail_after>0)fail_after--;
    return __real_malloc(size);
}
static size_t pixel_index(int x,int y) {
    return ((size_t)(y+CARDS_SHADOW_PAD)*CARDS_IMAGE_WIDTH+x+CARDS_SHADOW_PAD)*4;
}
static pthread_mutex_t stall_mutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t stall_cond=PTHREAD_COND_INITIALIZER;
static int stall,entered,released;
int __wrap_clock_gettime(clockid_t id,struct timespec *t) {
    (void)id;t->tv_sec=mono/1000;t->tv_nsec=(mono%1000)*1000000;return 0;
}
int __wrap_gettimeofday(struct timeval *t,void *zone) {
    (void)zone;t->tv_sec=wall/1000;t->tv_usec=(wall%1000)*1000;return 0;
}
ssize_t __real_read(int fd,void *buffer,size_t size);
ssize_t __wrap_read(int fd,void *buffer,size_t size) {
    pthread_mutex_lock(&stall_mutex);
    if(stall) {
        entered=1;pthread_cond_broadcast(&stall_cond);
        while(!released)pthread_cond_wait(&stall_cond,&stall_mutex);
    }
    pthread_mutex_unlock(&stall_mutex);
    return __real_read(fd,buffer,size);
}
void __real_glGetIntegerv(GLenum key,GLint *value);
void __wrap_glGetIntegerv(GLenum key,GLint *value) {queries++;__real_glGetIntegerv(key,value);}
void __real_glTexImage2D(GLenum,GLint,GLint,GLsizei,GLsizei,GLint,GLenum,GLenum,const void *);
void __wrap_glTexImage2D(GLenum t,GLint l,GLint i,GLsizei w,GLsizei h,GLint b,GLenum f,GLenum y,const void *p) {
    uploads++;
    if(!fail_texture)__real_glTexImage2D(t,l,i,w,h,b,f,y,p);
}
static size_t encode(char *out,const cards_request *r) {
    size_t n=(size_t)sprintf(out,"CARDS%d %u %llu %llu %u %u %d %u %llu\n",CARDS_PROTOCOL_VERSION,r->pid,
        (unsigned long long)r->expires,(unsigned long long)r->connection,r->media,r->trip,r->progress,r->art_crc,
        (unsigned long long)r->track);
    for(unsigned i=0;i<CARDS_TEXT_FIELDS;i++) {
        for(const unsigned char *s=(const unsigned char *)r->text[i];*s;s++)
            n+=(size_t)sprintf(out+n,"%02x",*s);
        out[n++]='\n';
    }
    if(r->speed.enabled)n+=(size_t)sprintf(out+n,"SPEED 1 %d %d %d %u %u %llu\n",r->speed.speed,
        r->speed.limit,r->speed.unit,r->speed.source,r->speed.wide,(unsigned long long)r->speed.expires);
    else n+=(size_t)sprintf(out+n,"SPEED 0 -1 -1 -1 0 0 0\n");
    return n;
}
static void write_control(const cards_request *r) {
    char bytes[2049];size_t n=encode(bytes,r);
    FILE *f=fopen(CARDS_CONTROL_PATH ".new","wb");assert(f);
    assert(fwrite(bytes,1,n,f)==n);assert(!fclose(f));
    assert(!rename(CARDS_CONTROL_PATH ".new",CARDS_CONTROL_PATH));
}
static void parse_tests(void) {
    const char *valid="CARDS3 42 104000 0 1 1 -1 4294967295 9223372036854775807\n\n\n\n\n\n\nSPEED 0 -1 -1 -1 0 0 0\n";
    cards_request r;
    assert(cards_decode(valid,strlen(valid),100000,42,&r));
    assert(r.art_crc==UINT32_MAX && r.progress==-1 && r.track==INT64_MAX);
    assert(cards_decode(valid,strlen(valid),104000,42,&r));
    assert(!cards_decode(valid,strlen(valid),104001,42,&r));
    assert(!cards_decode(valid,strlen(valid),99999,42,&r));
    assert(!cards_decode(valid,strlen(valid),100000,43,&r));
    const char *bad[]={
        "CARDS1 42 104000 0 1 1 0 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 -1 1 1 0 0 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 9223372036854775808 1 1 0 0 0\n\n\n\n\n\n\n",
        "CARDS2 +42 104000 0 1 1 0 0 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 2 1 0 0 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 2 0 0 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 -2 0 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 1001 0 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 +1 0 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 ffffffff 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 4294967296 0\n\n\n\n\n\n\n",
        "CARDS2 42 18446744073709551616 0 1 1 0 0 0\n\n\n\n\n\n\n",
        "CARDS2  42 104000 0 1 1 0 0 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0 \n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\r\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 -1\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 +1\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 9223372036854775808\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0\n\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\n00\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\n0a\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\n7f\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\nc080\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\neda080\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\nf4908080\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\ne280\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\n0\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\ngg\n\n\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\n\n\n416374697665\n\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\n\n\n\n32343a3030\n\n\n",
        "CARDS2 42 104000 0 1 1 0 0 0\n\n\n\n31323a3630\n\n\n"
    };
    for(unsigned i=0;i<sizeof(bad)/sizeof(bad[0]);i++) {
        char malformed[512];
        snprintf(malformed,sizeof(malformed),"%sSPEED 0 -1 -1 -1 0 0 0\n",bad[i]);
        if(malformed[5]=='2')malformed[5]='3';
        assert(!cards_decode(malformed,strlen(malformed),100000,42,&r));
    }
    assert(!cards_decode(bad[1],strlen(bad[1]),100000,42,&r));
    for(size_t i=0;i<strlen(valid);i++)assert(!cards_decode(valid,i,100000,42,&r));
    char bytes[4096];strcpy(bytes,valid);strcat(bytes,"\n");
    assert(!cards_decode(bytes,strlen(bytes),100000,42,&r));
    strcpy(bytes,valid);bytes[strlen(valid)]=0;
    assert(!cards_decode(bytes,strlen(valid)+1,100000,42,&r));
    memset(bytes,'x',sizeof(bytes));assert(!cards_decode(bytes,sizeof(bytes),100000,42,&r));
    cards_request source={.pid=42,.expires=104000,.media=1,.progress=1000};
    memset(source.text[0],'a',128);
    size_t size=encode(bytes,&source);
    assert(cards_decode(bytes,size,100000,42,&r));
    char *line=strchr(bytes,'\n')+1;memmove(line+2,line,size-(size_t)(line-bytes));
    memcpy(line,"61",2);assert(!cards_decode(bytes,size+2,100000,42,&r));
    strcpy(source.text[0],"\xc3\xa9 \xce\xa9 \xd0\x9f");
    strcpy(source.text[2],"Paused");strcpy(source.text[3],"23:59");
    size=encode(bytes,&source);assert(cards_decode(bytes,size,100000,42,&r));
    assert(!strcmp(r.text[0],source.text[0]));
    const char *clocks[]={"00:00","23:59 dest","5:15 AM dest","12:59 PM","1:00 AM","12:00 AM dest"};
    for(unsigned i=0;i<sizeof(clocks)/sizeof(clocks[0]);i++) {
        strcpy(source.text[3],clocks[i]);size=encode(bytes,&source);
        assert(cards_decode(bytes,size,100000,42,&r) && !strcmp(r.text[3],clocks[i]));
    }
    const char *bad_clocks[]={"0:00 AM","13:00 PM","5:00","23:59 extra","12:60 PM","12:00 am",
        ":00","1","12:","12:0","12:00 PM dest extra","12:00PM","12:00 AM "};
    for(unsigned i=0;i<sizeof(bad_clocks)/sizeof(bad_clocks[0]);i++) {
        strcpy(source.text[3],bad_clocks[i]);size=encode(bytes,&source);
        assert(!cards_decode(bytes,size,100000,42,&r));
    }
}
static void check_shared_fixture(const char *bytes,size_t size) {
    cards_request r;
    assert(cards_decode(bytes,size,0,123,&r));
    assert(r.pid==123 && r.expires==4000 && r.connection==7);
    assert(r.media==1 && r.trip==1 && r.progress==250 && r.art_crc==0 && r.track==33);
    const char *expected[]={"Test song","Test artist","Paused","12:34","25 min","10 mi"};
    for(unsigned i=0;i<6;i++)assert(!strcmp(r.text[i],expected[i]));
    assert(cards_decode(bytes,size,4000,123,&r));
    assert(!cards_decode(bytes,size,4001,123,&r));
    assert(!cards_decode(bytes,size,0,124,&r));
}
static void shared_fixture_tests(void) {
    cards_request source={.pid=123,.expires=4000,.connection=7,.media=1,.trip=1,.progress=250,.track=33};
    const char *text[]={"Test song","Test artist","Paused","12:34","25 min","10 mi"};
    for(unsigned i=0;i<6;i++)strcpy(source.text[i],text[i]);
    char bytes[CARDS_MAX_SNAPSHOT+1];
    check_shared_fixture(bytes,encode(bytes,&source));
    puts("CARDS3 native-generated shared fixture: PASS");
    const char *path="build/mmi-tests/map-cards-control.txt";
    FILE *f=fopen(path,"rb");
    if(!f) {
        if(errno==ENOENT) {
            puts("CARDS3 Java fixture absent: standalone native fixture checked");
            return;
        }
        perror(path);abort();
    }
    size_t size=fread(bytes,1,sizeof(bytes),f);
    assert(!ferror(f) && feof(f) && size<=CARDS_MAX_SNAPSHOT);
    assert(!fclose(f));
    check_shared_fixture(bytes,size);
    puts("CARDS3 Java-generated build/mmi-tests/map-cards-control.txt: PASS");
    const char *names[]={"24h","12h"},*expected[]={"05:15 dest","5:15 AM dest"};
    for(unsigned i=0;i<2;i++) {
        char path[128];snprintf(path,sizeof(path),"build/mmi-tests/map-cards-arrival-%s.txt",names[i]);
        FILE *arrival=fopen(path,"rb");
        if(!arrival && errno==ENOENT){printf("Java clock fixture %s absent; parser cases checked\n",names[i]);continue;}
        assert(arrival);
        size=fread(bytes,1,sizeof(bytes),arrival);
        assert(!ferror(arrival) && feof(arrival) && !fclose(arrival));
        cards_request request;assert(cards_decode(bytes,size,0,123,&request));
        assert(request.trip && !strcmp(request.text[3],expected[i]));
        printf("Java formatter -> CARDS3 -> native %s destination-clock fixture: PASS\n",names[i]);
    }
}
static void speed_protocol_tests(void) {
    cards_request r={.pid=42,.expires=104000,.progress=-1,
        .speed={.enabled=1,.speed=52,.limit=50,.unit=0,.source=2,.wide=1,.expires=102000}};
    cards_request decoded;
    char bytes[CARDS_MAX_SNAPSHOT+1];
    size_t n=encode(bytes,&r);
    assert(cards_decode(bytes,n,100000,42,&decoded) && decoded.speed.speed==52 && decoded.speed.source==2);
    assert(cards_decode(bytes,n,102000,42,&decoded) && decoded.speed.speed==-1 && decoded.speed.limit==50);
    const cards_speed invalid[]={
        {.enabled=1,.speed=400,.limit=50,.unit=0,.source=2,.expires=102000},
        {.enabled=1,.speed=-2,.limit=50,.unit=0,.source=2,.expires=102000},
        {.enabled=1,.speed=52,.limit=0,.unit=0,.source=2,.expires=102000},
        {.enabled=1,.speed=52,.limit=50,.unit=2,.source=2,.expires=102000},
        {.enabled=1,.speed=52,.limit=50,.unit=-1,.source=2,.expires=102000},
        {.enabled=1,.speed=52,.limit=50,.unit=0,.source=0,.expires=102000},
        {.enabled=1,.speed=52,.limit=-1,.unit=0,.source=2,.expires=102000},
        {.enabled=1,.speed=52,.limit=50,.unit=0,.source=2,.wide=2,.expires=102000},
        {.enabled=1,.speed=52,.limit=50,.unit=0,.source=2,.expires=102001},
        {.enabled=1,.speed=52,.limit=50,.unit=0,.source=2,.expires=0},
        {.enabled=1,.speed=-1,.limit=50,.unit=0,.source=2,.expires=102000}
    };
    for(unsigned i=0;i<sizeof(invalid)/sizeof(invalid[0]);i++) {
        r.speed=invalid[i];n=encode(bytes,&r);
        assert(!cards_decode(bytes,n,100000,42,&decoded));
    }
    r.speed=(cards_speed){.enabled=1,.speed=0,.limit=50,.unit=0,.source=1,.expires=115000};
    n=encode(bytes,&r);assert(cards_decode(bytes,n,100000,42,&decoded));
    r.speed=(cards_speed){.enabled=1,.speed=-1,.limit=-1,.unit=-1};
    n=encode(bytes,&r);assert(cards_decode(bytes,n,100000,42,&decoded));
    const char *path="build/mmi-tests/speed-badge-control.txt";
    FILE *f=fopen(path,"rb");
    if(f) {
        n=fread(bytes,1,sizeof(bytes),f);assert(!ferror(f) && feof(f) && !fclose(f));
        assert(cards_decode(bytes,n,0,123,&decoded));
        assert(decoded.connection==7 && decoded.speed.enabled && decoded.speed.source==2 &&
            decoded.speed.speed==52 && decoded.speed.limit==50 && decoded.speed.unit==0 &&
            decoded.speed.wide && decoded.speed.expires==2000);
        puts("Java speed badge -> CARDS3 -> native: PASS");
    } else {assert(errno==ENOENT);puts("Java speed badge fixture absent; native parser cases checked");}
}
static uint32_t file_crc(const char *path) {
    FILE *f=fopen(path,"rb");assert(f);
    assert(!fseek(f,0,SEEK_END));long n=ftell(f);assert(n>0);rewind(f);
    unsigned char *bytes=malloc((size_t)n);assert(bytes);
    assert(fread(bytes,1,(size_t)n,f)==(size_t)n);assert(!fclose(f));
    uint32_t crc=cards_crc32(bytes,(size_t)n);free(bytes);return crc;
}
static void artwork_tests(uint32_t crc) {
    assert(cards_crc32((const unsigned char *)"123456789",9)==0xcbf43926);
    cards_art art={0};
    assert(!cards_art_load(CARDS_ART_PATH,crc,&art));assert(art.width==64 && art.height==64);
    assert(!strcmp(cards_art_load(CARDS_ART_PATH,crc^1,&art),"ART_CRC_MISMATCH"));assert(!art.pixels);
    assert(!strcmp(cards_art_load("build/map-card-tests/oversized.png",
        file_crc("build/map-card-tests/oversized.png"),&art),"ART_DIMENSION_ERROR"));
    assert(!strcmp(cards_art_load("build/map-card-tests/huge.png",1,&art),"ART_SIZE_ERROR"));
    assert(!strcmp(cards_art_load("build/map-card-tests/absent.png",1,&art),"ART_OPEN_ERROR"));
    assert(!strcmp(cards_art_load(CARDS_ART_PATH,0,&art),"ART_OFF"));
    FILE *f=fopen("build/map-card-tests/broken.png","wb");assert(f);
    const unsigned char broken[]={137,80,78,71,13,10,26,10,0,0,0,13,'I','H','D','R',
        0,0,0,64,0,0,0,64,8,6,0,0,0,0,0,0,0};
    assert(fwrite(broken,1,sizeof(broken),f)==sizeof(broken));assert(!fclose(f));
    assert(!strcmp(cards_art_load("build/map-card-tests/broken.png",
        file_crc("build/map-card-tests/broken.png"),&art),"ART_DECODE_ERROR"));
    cards_art_free(&art);
}
static const GLenum keys[]={GL_CURRENT_PROGRAM,GL_ARRAY_BUFFER_BINDING,GL_ELEMENT_ARRAY_BUFFER_BINDING,
    GL_ACTIVE_TEXTURE,GL_UNPACK_ALIGNMENT,GL_BLEND_SRC_RGB,GL_BLEND_DST_RGB,GL_BLEND_SRC_ALPHA,
    GL_BLEND_DST_ALPHA,GL_BLEND_EQUATION_RGB,GL_BLEND_EQUATION_ALPHA,GL_FRAMEBUFFER_BINDING};
typedef struct {
    GLint scalar[12],viewport[4],scissor[4],attrib[7],textures[2];
    GLboolean flags[9];void *pointer;
} state;
static void snapshot(state *s) {
    memset(s,0,sizeof(*s));
    for(unsigned i=0;i<12;i++)glGetIntegerv(keys[i],s->scalar+i);
    glGetIntegerv(GL_VIEWPORT,s->viewport);glGetIntegerv(GL_SCISSOR_BOX,s->scissor);
    const GLenum ak[]={GL_VERTEX_ATTRIB_ARRAY_ENABLED,GL_VERTEX_ATTRIB_ARRAY_SIZE,GL_VERTEX_ATTRIB_ARRAY_TYPE,
        GL_VERTEX_ATTRIB_ARRAY_NORMALIZED,GL_VERTEX_ATTRIB_ARRAY_STRIDE,GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING};
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
static GLuint caller_program(void) {
    GLuint vs=glCreateShader(GL_VERTEX_SHADER),fs=glCreateShader(GL_FRAGMENT_SHADER),p=glCreateProgram();
    const char *v="attribute vec4 position;void main(){gl_Position=position;}";
    const char *f="precision mediump float;void main(){gl_FragColor=vec4(1.);}";
    glShaderSource(vs,1,&v,NULL);glCompileShader(vs);glShaderSource(fs,1,&f,NULL);glCompileShader(fs);
    glAttachShader(p,vs);glAttachShader(p,fs);glLinkProgram(p);
    GLint good=0;glGetProgramiv(p,GL_LINK_STATUS,&good);assert(good);
    glDeleteShader(vs);glDeleteShader(fs);glUseProgram(p);return p;
}
static unsigned char pixels[1440*455*4];
static void clear(void) {
    glDisable(GL_SCISSOR_TEST);glColorMask(1,1,1,1);
    glClearColor(.4f,.6f,.8f,.37f);glClear(GL_COLOR_BUFFER_BIT);
}
static const unsigned char *at(int x,int top) {return pixels+((454-top)*1440+x)*4;}
static void capture(const char *name) {
    glReadPixels(0,0,1440,455,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    if(!name)return;
    char path[256];snprintf(path,sizeof(path),"build/map-card-tests/%s.ppm",name);
    FILE *f=fopen(path,"wb");assert(f);fprintf(f,"P6\n1440 455\n255\n");
    for(int y=0;y<455;y++)for(int x=0;x<1440;x++)assert(fwrite(at(x,y),1,3,f)==3);
    assert(!fclose(f));
}
static int changed(int x,int y) {return at(x,y)[0]!=102 || at(x,y)[1]!=153 || at(x,y)[2]!=204;}
static void bounds(int media,int trip,int viewport_x,int viewport_top,int width,int height) {
    capture(NULL);
    unsigned changed_count=0;
    for(int y=0;y<455;y++)for(int x=0;x<1440;x++) {
        assert(at(x,y)[3]==94);
        int local_x=x-viewport_x,local_y=y-viewport_top;
        int card_x=local_x-CARDS_X,card_y=local_y-CARDS_TOP;
        int in_image=card_x>=-CARDS_SHADOW_PAD && card_x<CARDS_WIDTH+CARDS_SHADOW_PAD &&
            card_y>=-CARDS_SHADOW_PAD && card_y<CARDS_HEIGHT+CARDS_SHADOW_PAD;
        int expected=(media || trip) && in_image && local_x>=0 && local_x<width &&
            local_y>=0 && local_y<height && geometry.frame[pixel_index(card_x,card_y)+3]!=0;
        assert(changed(x,y)==expected);
        if((media || trip) && card_x>=4 && card_x<226 && card_y>=48 && card_y<252 &&
                local_x>=0 && local_x<width && local_y>=0 && local_y<height)assert(changed(x,y));
        changed_count+=changed(x,y);
    }
    assert(changed_count || (!media && !trip));
}
static void no_gl(int menu) {
    unsigned before=queries,uploaded=uploads;
    cards_overlay_draw(menu);
    assert(queries==before && uploads==uploaded);
}
static void *blocked_worker(void *unused) {(void)unused;cards_overlay_poll();return NULL;}
static void frame_tests(void) {
    assert(CARDS_X==119 && CARDS_WIDTH==230 && CARDS_X+CARDS_WIDTH==349);
    assert(CARDS_HEIGHT==256 && CARDS_TOP==70);
    for(int failure=0;failure<2;failure++) {
        cards_painter failed={0};fail_after=failure;
        assert(!cards_painter_init(&failed) && !failed.font && !failed.frame);
        fail_after=-1;cards_painter_destroy(&failed);
    }
    assert(cards_painter_init(&geometry));
    unsigned char *frame=geometry.frame,*font=geometry.font;
    int before=allocations;
    assert(cards_painter_init(&geometry) && allocations==before);
    assert(geometry.frame==frame && geometry.font==font);
    uint32_t crc=cards_crc32(frame,CARDS_IMAGE_BYTES);
    for(int y=205;y<252;y++)for(int x=4;x<226;x++) {
        const unsigned char *p=frame+pixel_index(x,y);
        assert(!p[0] && !p[1] && !p[2] && p[3]==255);
    }
    assert(!cards_inside(0,0) && cards_inside(0,44) && cards_inside(115,255));
    assert(!frame[pixel_index(-6,-6)+3]);
    assert(frame[pixel_index(20,31)+3]>0 && frame[pixel_index(20,31)+3]<255);
    unsigned prior=255;
    for(int x=230;x<=233;x++) {
        const unsigned char *p=frame+pixel_index(x,100);
        assert(!p[0] && !p[1] && !p[2] && p[3]>0 && p[3]<prior);
        prior=p[3];
    }
    assert(!frame[pixel_index(235,100)+3]);
    assert(frame[pixel_index(1,100)]>frame[pixel_index(228,100)]);
    assert(frame[pixel_index(100,1)]>frame[pixel_index(100,254)]);
    cards_request r={.media=1,.trip=1,.progress=630};
    strcpy(r.text[3],"6:57 PM");strcpy(r.text[4],"7 min");strcpy(r.text[5],"1.9 km");
    cards_scroll zero={0};
    unsigned char with[CARDS_IMAGE_BYTES],without[CARDS_IMAGE_BYTES];
    assert(!cards_paint(&geometry,&r,NULL,zero,with));
    r.progress=-1;assert(!cards_paint(&geometry,&r,NULL,zero,without));
    int minx=230,maxx=0,miny=256,maxy=0;
    for(int y=-CARDS_SHADOW_PAD;y<CARDS_HEIGHT+CARDS_SHADOW_PAD;y++)
        for(int x=-CARDS_SHADOW_PAD;x<CARDS_WIDTH+CARDS_SHADOW_PAD;x++) {
            size_t i=pixel_index(x,y);
            if(!memcmp(with+i,without+i,4))continue;
            assert(x>=12 && x<218 && ((y>=176 && y<192) || (y>=194 && y<197)));
            if(y<192) {
                if(x<minx)minx=x;
                if(x>maxx)maxx=x;
                if(y<miny)miny=y;
                if(y>maxy)maxy=y;
            }
        }
    assert(minx>=12 && minx<=14 && maxx-minx>95 && maxy-miny>=8 && maxy<=191);
    for(int y=194;y<197;y++)for(int x=12;x<218;x++) {
        const unsigned char *p=with+pixel_index(x,y);
        assert(p[0]==(x<12+206*630/1000?217:104) && p[3]==255);
    }
    const char *headings[]={"Playing","Paused","Stopped",""};
    for(unsigned i=0;i<5;i++) {
        r.media=i<4;strcpy(r.text[2],headings[i%4]);
        assert(!cards_paint(&geometry,&r,NULL,zero,with));
        minx=230;maxx=0;miny=44;maxy=0;
        for(int y=0;y<44;y++)for(int x=70;x<230;x++) {
            const unsigned char *p=with+pixel_index(x,y);
            if(p[0]<210 || p[1]<210 || p[2]<210)continue;
            if(x<minx)minx=x;
            if(x>maxx)maxx=x;
            if(y<miny)miny=y;
            if(y>maxy)maxy=y;
        }
        assert(maxx>minx && maxy>miny);
        assert(abs(minx+maxx-299)<=2 && abs(miny+maxy-43)<=2);
    }
    assert(allocations==before && cards_crc32(frame,CARDS_IMAGE_BYTES)==crc);
}
static void scroll_tests(cards_painter *painter,cards_request r) {
    assert(cards_scroll_offset(0,UINT64_MAX)==0 && cards_scroll_offset(-12,3000)==0);
    assert(cards_scroll_offset(100,0)==0 && cards_scroll_offset(100,1800)==0);
    assert(cards_scroll_offset(100,1850)==1 && cards_scroll_offset(100,4300)==50);
    assert(cards_scroll_offset(100,6800)==100 && cards_scroll_offset(100,8599)==100);
    assert(cards_scroll_offset(100,8600)==0);
    unsigned char start[CARDS_IMAGE_BYTES],moving[CARDS_IMAGE_BYTES],empty[CARDS_IMAGE_BYTES];
    cards_scroll zero={0};
    r.art_crc=0;
    assert(!cards_paint(painter,&r,NULL,zero,start));
    cards_scroll step=cards_scroll_at(&r,NULL,3800);
    assert(step.title>0 && step.artist>0);
    assert(!cards_paint(painter,&r,NULL,step,moving));
    unsigned title_changed=0,artist_changed=0;
    for(int y=-CARDS_SHADOW_PAD;y<CARDS_HEIGHT+CARDS_SHADOW_PAD;y++)
        for(int x=-CARDS_SHADOW_PAD;x<CARDS_WIDTH+CARDS_SHADOW_PAD;x++) {
        size_t i=pixel_index(x,y);
        if(memcmp(start+i,moving+i,4)) {
            assert(x>=12 && x<218 && y>=53 && y<108);
            if(y<81)title_changed++;else artist_changed++;
        }
    }
    assert(title_changed>30 && artist_changed>30);
    strcpy(r.text[0],"Song");strcpy(r.text[1],"Artist");
    for(unsigned t=0;t<30000;t+=100) {
        step=cards_scroll_at(&r,NULL,t);
        assert(!step.title && !step.artist);
    }
    r.text[0][0]=0;assert(!cards_paint(painter,&r,NULL,zero,empty));
    strcpy(r.text[0],"W");assert(!cards_paint(painter,&r,NULL,zero,start));
    int x0=218,x1=0,y0=81,y1=53;
    for(int y=53;y<81;y++)for(int x=12;x<218;x++) {
        size_t i=pixel_index(x,y);
        if(memcmp(start+i,empty+i,4)) {
            if(x<x0)x0=x;
            if(x>x1)x1=x;
            if(y<y0)y0=y;
            if(y>y1)y1=y;
        }
    }
    assert(x1-x0>10 && y1-y0>10);
    memset(r.text[0],'i',110);r.text[0][0]='j';r.text[0][110]='W';r.text[0][111]=0;
    cards_scroll end={0};
    for(unsigned t=0;t<120000;t+=50) {
        step=cards_scroll_at(&r,NULL,t);if(step.title>end.title)end=step;
    }
    assert(end.title>100);assert(!cards_paint(painter,&r,NULL,end,moving));
    int found=0;
    for(int x=180;x+(x1-x0)<218;x++) {
        int matches=1;
        for(int y=y0;y<=y1 && matches;y++)for(int dx=0;dx<=x1-x0;dx++)
            if(memcmp(start+pixel_index(x0+dx,y),moving+pixel_index(x+dx,y),4)){matches=0;break;}
        if(matches)found=1;
    }
    assert(found); /* The complete last glyph, including its ink bearing, is visible at the endpoint. */
}
static void assert_frame(cards_painter *painter,const cards_request *r,const cards_art *art,uint64_t elapsed) {
    unsigned char expected[CARDS_IMAGE_BYTES];
    assert(!cards_paint(painter,r,art,cards_scroll_at(r,art,elapsed),expected));
    clear();cards_overlay_draw(0);capture(NULL);
    const unsigned background[]={102,153,204};
    for(int y=0;y<CARDS_IMAGE_HEIGHT;y++)for(int x=0;x<CARDS_IMAGE_WIDTH;x++) {
        const unsigned char *src=expected+((size_t)y*CARDS_IMAGE_WIDTH+x)*4;
        const unsigned char *actual=at(CARDS_X-CARDS_SHADOW_PAD+x,CARDS_TOP-CARDS_SHADOW_PAD+y);
        for(unsigned c=0;c<3;c++) {
            int composed=src[c]+(background[c]*(255-src[3])+127)/255;
            assert(abs((int)actual[c]-composed)<=1);
        }
        assert(actual[3]==94);
    }
}
static void runtime_scroll_tests(cards_request r,uint32_t crc) {
    cards_painter painter={0};assert(cards_painter_init(&painter));
    cards_art art={0};assert(!cards_art_load(CARDS_ART_PATH,crc,&art));
    r.media=r.trip=1;r.art_crc=0;r.track=100;r.progress=630;
    uint64_t start=mono,elapsed=0;
    cards_scroll previous={0};
    for(unsigned t=0;t<=10000;t+=100) {
        mono=start+t;wall+=100;r.expires=wall+4000;write_control(&r);
        unsigned before=uploads;
        cards_overlay_poll();assert_frame(&painter,&r,NULL,t);
        cards_scroll current=cards_scroll_at(&r,NULL,t);
        if(t)assert(uploads==before+(current.title!=previous.title || current.artist!=previous.artist));
        previous=current;
        if(t==0)capture("scroll-start");
        if(t==3800)capture("scroll-moving");
        if(t==10000)capture("scroll-late");
        elapsed=t;
    }
    assert(previous.title!=previous.artist);
    r.progress=100;strcpy(r.text[2],"Paused");
    write_control(&r);cards_overlay_poll();assert_frame(&painter,&r,NULL,elapsed);
    r.art_crc=crc;write_control(&r);cards_overlay_poll();assert_frame(&painter,&r,&art,elapsed);
    r.trip=0;write_control(&r);cards_overlay_poll();assert_frame(&painter,&r,&art,elapsed);
    r.trip=1;r.track++;write_control(&r);cards_overlay_poll();assert_frame(&painter,&r,&art,0);
    /* Keep the worker live through the initial pause instead of hiding a lease expiry. */
    for(unsigned t=100;t<=2600;t+=100) {
        mono+=100;wall+=100;r.expires=wall+4000;write_control(&r);cards_overlay_poll();
    }
    assert_frame(&painter,&r,&art,2600);
    r.media=0;write_control(&r);cards_overlay_poll();assert_frame(&painter,&r,&art,0);
    r.media=1;write_control(&r);cards_overlay_poll();assert_frame(&painter,&r,&art,0);
    mono+=1001;wall+=1001;no_gl(0);
    r.expires=wall+4000;write_control(&r);cards_overlay_poll();assert_frame(&painter,&r,&art,0);
    strcpy(r.text[0],"Song");strcpy(r.text[1],"Artist");r.track++;
    write_control(&r);cards_overlay_poll();assert_frame(&painter,&r,&art,0);
    unsigned uploaded=uploads;
    for(unsigned t=0;t<10000;t+=100) {
        mono+=100;wall+=100;r.expires=wall+4000;write_control(&r);cards_overlay_poll();cards_overlay_draw(0);
    }
    assert(uploads==uploaded);
    strcpy(r.text[0],"Purple Rain");strcpy(r.text[1],"Prince");strcpy(r.text[2],"Playing");
    strcpy(r.text[3],"12:15");strcpy(r.text[4],"35 min");strcpy(r.text[5],"24.6 mi");r.progress=300;r.track++;
    const char *names[]={"both-off","music-only","trip-only","both-enabled"};
    for(unsigned flags=0;flags<4;flags++) {
        r.media=flags&1;r.trip=flags>>1;r.expires=wall+4000;write_control(&r);cards_overlay_poll();
        assert_frame(&painter,&r,&art,0);capture(names[flags]);
        unsigned char bitmap[CARDS_IMAGE_BYTES];cards_scroll zero={0};
        assert(!cards_paint(&painter,&r,&art,zero,bitmap));
        char path[160];snprintf(path,sizeof(path),"build/map-card-tests/%s.rgba",names[flags]);
        FILE *f=fopen(path,"wb");assert(f);
        assert(fwrite(bitmap,1,sizeof(bitmap),f)==sizeof(bitmap));assert(!fclose(f));
    }
    strcpy(r.text[3],"5:15 AM dest");strcpy(r.text[4],"1193046 h 28 min");strcpy(r.text[5],"999999 mi");
    write_control(&r);cards_overlay_poll();assert_frame(&painter,&r,&art,0);capture("long-trip-values");
    assert(at(CARDS_X+12,CARDS_TOP+193)[0]==at(CARDS_X+12,CARDS_TOP+50)[0]);
    r.trip=0;r.art_crc=0;write_control(&r);cards_overlay_poll();assert_frame(&painter,&r,NULL,0);
    capture("music-only-no-art");
    cards_art_free(&art);cards_painter_destroy(&painter);
}
static void speed_frame(const cards_speed *speed,const char *name) {
    unsigned char expected[SPEED_IMAGE_BYTES];
    cards_speed_paint(&geometry,speed,expected);
    clear();
    state before,after;snapshot(&before);cards_overlay_draw(0);snapshot(&after);
    assert(!memcmp(&before,&after,sizeof(before)));
    capture(name);
    int xpos=speed->wide?SPEED_X_WIDE:SPEED_X_INSET;
    unsigned red=0,ink=0;
    for(int y=0;y<SPEED_HEIGHT;y++)for(int x=0;x<SPEED_WIDTH;x++) {
        const unsigned char *src=expected+(y*SPEED_WIDTH+x)*4,*actual=at(xpos+x,SPEED_Y+y);
        const unsigned background[]={102,153,204};
        for(unsigned c=0;c<3;c++) {
            assert(src[c]<=src[3]);
            int expected_value=src[c]+(background[c]*(255-src[3])+127)/255;
            if(abs((int)actual[c]-expected_value)>1) {
                fprintf(stderr,"speed frame=%s xy=%d,%d channel=%u actual=%u expected=%d mono=%llu wall=%llu\n",
                    name?name:"unnamed",x,y,c,actual[c],expected_value,
                    (unsigned long long)mono,(unsigned long long)wall);
                assert(0);
            }
        }
        assert(actual[3]==94);
        if(x<75 && y<50) {
            if(actual[0]>180 && actual[0]>actual[1]+50)red++;
            if(actual[0]>180)ink++;
        }
    }
    assert(ink>(speed->speed<0?10u:15u));
    assert((red>15)==(speed->speed>=0 && speed->limit>0 && speed->speed>speed->limit));
    assert(!changed(xpos-1,SPEED_Y+30) && !changed(xpos+SPEED_WIDTH,SPEED_Y+30));
    assert(!changed(CARDS_X+12,CARDS_TOP+50));
}
static void speed_runtime_tests(EGLContext context) {
    cards_request r={.pid=(unsigned)getpid(),.expires=wall+4000,.connection=9,.progress=-1,
        .speed={.enabled=1,.speed=52,.limit=50,.unit=0,.source=2,.wide=1}};
    const int speeds[]={52,50,48,123,-1,52,-1};
    const int limits[]={50,50,50,100,50,-1,-1};
    const char *names[]={"speed-above","speed-equal","speed-below","speed-three-digits",
        "speed-stale","speed-unknown-limit","speed-unknown-both"};
    for(unsigned i=0;i<7;i++) {
        r.speed.speed=speeds[i];r.speed.limit=limits[i];
        r.speed.expires=r.speed.speed<0?0:wall+2000;
        r.speed.source=r.speed.limit<0?0:i&1?1:2;
        r.speed.unit=i==6?-1:(int)(i&1);
        char packet[CARDS_MAX_SNAPSHOT+1];cards_request decoded;
        size_t size=encode(packet,&r);
        if(!cards_decode(packet,size,wall,(unsigned)getpid(),&decoded)) {
            fprintf(stderr,"speed fixture rejected: %.*s",(int)size,packet);assert(0);
        }
        write_control(&r);cards_overlay_poll();speed_frame(&r.speed,names[i]);
    }
    r.speed=(cards_speed){.enabled=1,.speed=52,.limit=50,.unit=0,.source=2,.wide=1,.expires=wall+2000};
    write_control(&r);cards_overlay_poll();speed_frame(&r.speed,NULL);
    unsigned uploaded=uploads;
    r.speed.wide=0;write_control(&r);cards_overlay_poll();speed_frame(&r.speed,"speed-large-dials-inset");
    assert(uploads==uploaded);
    for(int i=0;i<4;i++) {
        mono+=100;wall+=100;r.expires=wall+4000;r.speed.expires=wall+2000;
        write_control(&r);cards_overlay_poll();cards_overlay_draw(0);
    }
    assert(uploads==uploaded);
    no_gl(1);
    r.speed.speed=53;r.speed.expires=wall+2000;write_control(&r);cards_overlay_poll();
    fail_texture=1;clear();cards_overlay_draw(0);capture(NULL);
    assert(!changed(SPEED_X_INSET+30,SPEED_Y+30));
    fail_texture=0;speed_frame(&r.speed,NULL);
    uploaded=uploads;cards_overlay_context_lost(context);speed_frame(&r.speed,NULL);
    assert(uploads==uploaded+1);
    assert(!unlink("/ramdisk/cards-test.ready"));cards_overlay_poll();no_gl(0);
    FILE *ready=fopen("/ramdisk/cards-test.ready","wb");assert(ready);assert(!fclose(ready));
    cards_overlay_poll();speed_frame(&r.speed,NULL);
    mono+=1001;wall+=1001;no_gl(0);
    cards_overlay_poll();speed_frame(&r.speed,NULL);
    mono+=1000;wall+=1000;cards_overlay_poll();
    r.speed.speed=-1;r.speed.expires=0;speed_frame(&r.speed,"speed-sample-expired");
    r.speed.enabled=0;write_control(&r);cards_overlay_poll();no_gl(0);
    r.speed.enabled=1;write_control(&r);cards_overlay_poll();speed_frame(&r.speed,NULL);
    mono+=4001;wall+=4001;cards_overlay_poll();no_gl(0);
}
int main(void) {
    frame_tests();
    parse_tests();
    speed_protocol_tests();
    shared_fixture_tests();
    uint32_t crc=file_crc(CARDS_ART_PATH);artwork_tests(crc);
    cards_request r={.pid=(unsigned)getpid(),.expires=wall+4000,.connection=7,.media=1,.trip=1,.progress=630};
    strcpy(r.text[0],"Long title: Caf\xc3\xa9, \xce\xa9 and \xd0\x9f\xd1\x80\xd0\xb8\xd0\xb2\xd0\xb5\xd1\x82");
    strcpy(r.text[1],"DejaVu artist with a long name");strcpy(r.text[2],"Playing");
    strcpy(r.text[3],"14:32");strcpy(r.text[4],"1 h 24 min");strcpy(r.text[5],"62.5 mi");
    cards_painter painter={0};assert(cards_painter_init(&painter));
    unsigned char bitmap[CARDS_IMAGE_BYTES],other[CARDS_IMAGE_BYTES];
    cards_scroll zero={0};
    assert(!cards_paint(&painter,&r,NULL,zero,bitmap));
    const unsigned char *fill=bitmap+pixel_index(12,50);
    assert(fill[3]==220 && fill[0]==18 && fill[1]==21 && fill[2]==22);
    assert(!bitmap[3] && bitmap[pixel_index(105,191)+3]);
    assert(bitmap[pixel_index(115,250)+3]==255);
    int opaque=0;
    for(size_t i=0;i<sizeof(bitmap);i+=4) {
        assert(bitmap[i]<=bitmap[i+3] && bitmap[i+1]<=bitmap[i+3] && bitmap[i+2]<=bitmap[i+3]);
        if(bitmap[i+3]==255 && bitmap[i]>240)opaque++;
    }
    assert(opaque>20);
    cards_request unsupported=r;strcpy(unsupported.text[0],"\xf0\x9f\x98\x80");
    assert(cards_paint(&painter,&unsupported,NULL,zero,bitmap)==1);
    unsupported.text[0][0]=0;assert(!cards_paint(&painter,&unsupported,NULL,zero,other));
    assert(!memcmp(bitmap,other,sizeof(bitmap)));
    scroll_tests(&painter,r);
    cards_painter_destroy(&painter);

    FILE *ready=fopen("/ramdisk/cards-test.ready","wb");assert(ready);assert(!fclose(ready));
    assert(!setenv("ALT111_MIRROR_BASE_READY_FILE","/ramdisk/cards-test.ready",1));
    unlink(CARDS_CONTROL_PATH);cards_overlay_poll();no_gl(0);
    EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);assert(eglInitialize(d,NULL,NULL));
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig config;EGLint count;assert(eglChooseConfig(d,attrs,&config,1,&count) && count==1);
    EGLint size[]={EGL_WIDTH,1440,EGL_HEIGHT,455,EGL_NONE},version[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    EGLSurface surface=eglCreatePbufferSurface(d,config,size);
    EGLContext c=eglCreateContext(d,config,EGL_NO_CONTEXT,version);assert(eglMakeCurrent(d,surface,surface,c));
    glViewport(0,0,1440,455);clear();
    GLuint program=caller_program(),buffers[3],textures[2];
    glGenBuffers(3,buffers);glGenTextures(2,textures);
    glBindBuffer(GL_ARRAY_BUFFER,buffers[0]);glBufferData(GL_ARRAY_BUFFER,128,NULL,GL_STATIC_DRAW);
    glVertexAttribPointer(0,2,GL_FLOAT,GL_TRUE,16,(void *)8);glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);glBindBuffer(GL_ARRAY_BUFFER,buffers[1]);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,buffers[2]);glPixelStorei(GL_UNPACK_ALIGNMENT,8);
    glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,textures[0]);
    glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,textures[1]);
    glEnable(GL_BLEND);glBlendFuncSeparate(GL_ONE,GL_ZERO,GL_ONE,GL_ZERO);
    glBlendEquationSeparate(GL_FUNC_REVERSE_SUBTRACT,GL_FUNC_SUBTRACT);
    glEnable(GL_DEPTH_TEST);glEnable(GL_STENCIL_TEST);glEnable(GL_CULL_FACE);glEnable(GL_SCISSOR_TEST);
    glScissor(1200,200,1,1);glColorMask(0,1,0,0);
    state before,after;snapshot(&before);
    write_control(&r);cards_overlay_poll();cards_overlay_draw(0);
    snapshot(&after);assert(!memcmp(&before,&after,sizeof(before)));
    assert(glGetError()==GL_NO_ERROR);bounds(1,1,0,0,1440,455);capture("both-no-art");
    const unsigned char *body=at(CARDS_X+12,CARDS_TOP+50);
    assert(body[0]>=31 && body[0]<=33 && body[1]>=41 && body[1]<=43 && body[2]>=49 && body[2]<=51);
    unsigned uploaded=uploads;
    for(unsigned i=0;i<20;i++)cards_overlay_draw(0);
    assert(uploads==uploaded);
    mono+=100;wall+=100;r.expires=wall+4000;write_control(&r);cards_overlay_poll();cards_overlay_draw(0);
    assert(uploads==uploaded);
    no_gl(1);
    for(unsigned flags=0;flags<4;flags++) {
        r.media=flags&1;r.trip=(flags>>1)&1;write_control(&r);cards_overlay_poll();clear();
        if(flags)cards_overlay_draw(0);else no_gl(0);
        bounds(r.media,r.trip,0,0,1440,455);
    }
    r.art_crc=crc;write_control(&r);cards_overlay_poll();clear();cards_overlay_draw(0);capture("both-art");
    bounds(1,1,0,0,1440,455);
    r.art_crc^=1;write_control(&r);cards_overlay_poll();clear();cards_overlay_draw(0);
    bounds(1,1,0,0,1440,455);capture("art-failure-text");
    r.art_crc=0;r.progress=-1;write_control(&r);cards_overlay_poll();clear();cards_overlay_draw(0);
    capture("unknown-progress");
    assert(at(CARDS_X+12,CARDS_TOP+194)[0]==at(CARDS_X+12,CARDS_TOP+50)[0]);

    glViewport(0,0,400,300);clear();cards_overlay_draw(0);
    bounds(1,1,0,155,400,300);capture("small-viewport-fixed");
    uploaded=uploads;cards_overlay_draw(0);assert(uploads==uploaded);
    glViewport(30,15,600,400);clear();cards_overlay_draw(0);bounds(1,1,30,40,600,400);
    glViewport(0,0,1440,455);
    uploaded=uploads;cards_overlay_draw(0);assert(uploads==uploaded+1);

    mono+=1001;wall+=1001;clear();no_gl(0);bounds(0,0,0,0,1440,455);
    cards_overlay_poll();cards_overlay_draw(0);bounds(1,1,0,0,1440,455);
    pthread_mutex_lock(&stall_mutex);stall=1;entered=0;released=0;pthread_mutex_unlock(&stall_mutex);
    pthread_t thread;assert(!pthread_create(&thread,NULL,blocked_worker,NULL));
    pthread_mutex_lock(&stall_mutex);
    while(!entered)pthread_cond_wait(&stall_cond,&stall_mutex);
    mono+=1001;wall+=1001;clear();no_gl(0);
    released=1;pthread_cond_broadcast(&stall_cond);pthread_mutex_unlock(&stall_mutex);
    assert(!pthread_join(thread,NULL));stall=0;
    cards_overlay_draw(0);bounds(1,1,0,0,1440,455);
    wall=r.expires;mono+=4000;cards_overlay_poll();no_gl(0);
    r.expires=wall+4000;write_control(&r);cards_overlay_poll();
    assert(!unlink("/ramdisk/cards-test.ready"));cards_overlay_poll();no_gl(0);
    ready=fopen("/ramdisk/cards-test.ready","wb");assert(ready);assert(!fclose(ready));
    cards_overlay_poll();clear();cards_overlay_draw(0);bounds(1,1,0,0,1440,455);
    assert(!unlink(CARDS_CONTROL_PATH));cards_overlay_poll();no_gl(0);
    write_control(&r);FILE *f=fopen(CARDS_CONTROL_PATH,"ab");assert(f);assert(fputc('x',f)=='x');assert(!fclose(f));
    cards_overlay_poll();no_gl(0);
    write_control(&r);cards_overlay_poll();clear();
    fail_texture=1;r.progress=1;write_control(&r);cards_overlay_poll();cards_overlay_draw(0);
    bounds(0,0,0,0,1440,455);
    fail_texture=0;cards_overlay_draw(0);bounds(1,1,0,0,1440,455);

    r.art_crc=crc;write_control(&r);cards_overlay_poll();cards_overlay_draw(0);
    assert(!rename(CARDS_ART_PATH,CARDS_ART_PATH ".held"));
    r.connection++;write_control(&r);cards_overlay_poll();clear();cards_overlay_draw(0);
    capture("generation-art-withdrawn");
    assert(at(CARDS_X+15,CARDS_TOP+55)[0]!=229);
    assert(!rename(CARDS_ART_PATH ".held",CARDS_ART_PATH));

    uploaded=uploads;
    cards_overlay_context_lost((void *)((uintptr_t)c+1));cards_overlay_draw(0);assert(uploads==uploaded);
    /* Force the same-handle cleanup path, then exercise real context destruction/recreation. */
    cards_overlay_context_lost(c);cards_overlay_draw(0);assert(uploads==uploaded+1);
    cards_overlay_context_lost(c);
    glUseProgram(0);glDeleteProgram(program);
    assert(eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT));assert(eglDestroyContext(d,c));
    c=eglCreateContext(d,config,EGL_NO_CONTEXT,version);assert(eglMakeCurrent(d,surface,surface,c));
    glViewport(0,0,1440,455);clear();uploaded=uploads;cards_overlay_draw(0);
    assert(uploads==uploaded+1);bounds(1,1,0,0,1440,455);
    runtime_scroll_tests(r,crc);
    speed_runtime_tests(c);
    cards_overlay_context_lost(c);
    assert(eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT));assert(eglDestroyContext(d,c));
    assert(eglDestroySurface(d,surface));assert(eglTerminate(d));
    unlink(CARDS_CONTROL_PATH);unlink("/ramdisk/cards-test.ready");
    cards_painter_destroy(&geometry);
    puts("Map cards: strict protocol/artwork, real GLES alpha/state/fixed placement/cache, toggles, stale/stalled leases, context reuse PASS");
    return 0;
}
