#include "mascot.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

static int number(FILE *file, unsigned *value) {
    unsigned char bytes[4];
    if (fread(bytes, 1, 4, file) != 4) return 0;
    *value = (unsigned)bytes[0] | ((unsigned)bytes[1]<<8) |
        ((unsigned)bytes[2]<<16) | ((unsigned)bytes[3]<<24);
    return 1;
}
void mascot_free(mascot_animation animations[MASCOT_COUNT]) {
    for (unsigned i=0;i<MASCOT_COUNT;i++) free(animations[i].pixels);
    memset(animations,0,sizeof(mascot_animation)*MASCOT_COUNT);
}
int mascot_load(const char *path, mascot_animation animations[MASCOT_COUNT]) {
    mascot_animation loaded[MASCOT_COUNT]={{0}};
    FILE *file=fopen(path,"rb");
    unsigned count;
    char magic[8];
    int valid=file && fread(magic,1,8,file)==8 && !memcmp(magic,"MASCOT01",8)
        && number(file,&count) && count==MASCOT_COUNT;
    for(unsigned i=0;valid && i<MASCOT_COUNT;i++) {
        mascot_animation *a=&loaded[i];
        valid=number(file,&a->width) && number(file,&a->height) && number(file,&a->count)
            && a->width>0 && a->width<=128 && a->height>0 && a->height<=64
            && a->count>=2 && a->count<=MASCOT_MAX_FRAMES;
        for(unsigned f=0;valid && f<a->count;f++) {
            valid=number(file,&a->delay[f]) && a->delay[f]>=20 && a->delay[f]<=2000;
            a->duration+=a->delay[f];
        }
        if(valid) {
            size_t bytes=(size_t)a->width*a->height*a->count*4;
            a->pixels=malloc(bytes);
            valid=a->pixels && fread(a->pixels,1,bytes,file)==bytes;
        }
    }
    if(valid) valid=fgetc(file)==EOF && !ferror(file);
    if(file && fclose(file)!=0) valid=0;
    if(!valid) {
        fprintf(stderr,"MASCOT=ASSET_ERROR invalid or unavailable atlas\n");
        mascot_free(loaded);
        return 0;
    }
    memcpy(animations,loaded,sizeof(loaded));
    return 1;
}
int mascot_config(const char *text, uint64_t now_ms, unsigned pid) {
    /* Canonical control snapshot, produced by the Java settings worker. */
    const char prefix[]="MASCOT2 ";
    if(strncmp(text,prefix,sizeof(prefix)-1))return -1;
    const char *p=text+sizeof(prefix)-1;
    if(*p<'0' || *p>'0'+MASCOT_COUNT || p[1]!=' ')return -1;
    int selected=*p-'0';
    p+=2;
    if(*p<'0' || *p>'9')return -1;
    errno=0;
    char *end;
    unsigned long long owner=strtoull(p,&end,10);
    if(errno || *end!=' ' || (owner!=pid && (owner!=0 || selected!=0)))return -1;
    p=end+1;
    if(*p<'0' || *p>'9')return -1;
    unsigned long long expiry=strtoull(p,&end,10);
    if(errno || end==p || strcmp(end,"\n"))return -1;
    if(expiry<now_ms || expiry-now_ms>5000)return -1;
    return selected;
}
unsigned mascot_frame(const mascot_animation *a, uint64_t elapsed_ms) {
    unsigned t=(unsigned)(elapsed_ms%a->duration);
    for(unsigned i=0;i<a->count;i++) {
        if(t<a->delay[i])return i;
        t-=a->delay[i];
    }
    return a->count-1;
}
