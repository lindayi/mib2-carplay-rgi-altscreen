/* Wire handoff only: no GPL objects linked into the independent native component. */
#include "../hook/p1404_iap2.h"
#include "../hook/altscreen_profile.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
void altscreen_log(const char *fmt, ...) { (void)fmt; }
void altscreen_mark_theme_advertised(void) {}
static unsigned be(const unsigned char *p){return (unsigned)p[0]*256+p[1];}
static void put(unsigned char *p,unsigned n){p[0]=n>>8;p[1]=n;}
int main(int argc,char **argv) {
    assert(argc==2);
    unsigned char src[256]={0},dst[512]={0};
    FILE *f=fopen(argv[1],"rb");assert(f);
    size_t bytes=fread(src+9,1,200,f);assert(bytes==51 && fclose(f)==0);
    size_t length=bytes+10;
    src[0]=0xff;src[1]=0x5a;put(src+2,length);
    src[8]=iap2_link_checksum(src,8);
    src[length-1]=iap2_link_checksum(src+9,bytes);
    altscreen_profile_set(&altscreen_prof_observe);
    assert(iap2_patch_outgoing(src,length,dst,sizeof(dst))==0);
    assert(!memcmp(src,dst,length));
    altscreen_profile_set(&altscreen_prof_theme_21_17);
    int patched=iap2_patch_outgoing(src,length,dst,sizeof(dst));
    assert(patched==(int)length+20); /* legacy transport identity cloned into modern component */
    assert(iap2_link_checksum_ok(dst,9));
    assert(iap2_link_checksum_ok(dst+9,patched-9));
    unsigned rgd=0,ppid=0,theme=0;
    for(unsigned off=15;off<(unsigned)patched-1;off+=be(dst+off)) {
        unsigned size=be(dst+off),id=be(dst+off+2);
        assert(size>=4 && off+size<=(unsigned)patched-1);
        if(id==30){rgd++;assert(size==8);}
        if(id==34){ppid++;assert(size==21 && !memcmp(dst+off+4,"bf889dfdaea54096",17));}
        if(id==21)theme++;
    }
    assert(rgd==1 && ppid==1 && theme==1);
    assert(iap2_patch_outgoing(src,length,dst,length)==0);
    puts("RGI_MAPS30_NATIVE_IDENTIFY_HANDOFF=PASS");
    return 0;
}
