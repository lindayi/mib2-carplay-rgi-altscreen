#include "cards.h"
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <string.h>

#define ART_MAX_BYTES (1024*1024)
#define ART_MAX_DIMENSION 512
#define ART_MAX_ALLOCATION (4*1024*1024)
static void *bounded_malloc(size_t size) {return size<=ART_MAX_ALLOCATION?malloc(size):NULL;}
static void *bounded_realloc(void *p,size_t size) {return size<=ART_MAX_ALLOCATION?realloc(p,size):NULL;}
#define STBI_MALLOC(n) bounded_malloc(n)
#define STBI_REALLOC(p,n) bounded_realloc(p,n)
#define STBI_FREE(p) free(p)
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#define STBI_NO_HDR
#define STBI_NO_LINEAR
/* This decoder runs only on the control worker; QNX must not link emutls. */
#define STBI_NO_THREAD_LOCALS
#define STB_IMAGE_IMPLEMENTATION
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
#include "../hook/coverart/stb_image.h"
#pragma GCC diagnostic pop

uint32_t cards_crc32(const unsigned char *data,size_t size) {
    uint32_t crc=UINT32_MAX;
    for(size_t i=0;i<size;i++) {
        crc^=data[i];
        for(unsigned bit=0;bit<8;bit++)crc=(crc>>1)^(0xedb88320u&-(crc&1));
    }
    return ~crc;
}
void cards_art_free(cards_art *art) {
    free(art->pixels);memset(art,0,sizeof(*art));
}
static uint32_t be32(const unsigned char *p) {
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}
const char *cards_art_load(const char *path,uint32_t crc,cards_art *art) {
    cards_art_free(art);
    if(!crc)return "ART_OFF";
    int fd=open(path,O_RDONLY|O_NONBLOCK);
    if(fd<0)return "ART_OPEN_ERROR";
    struct stat info;
    if(fstat(fd,&info)!=0 || !S_ISREG(info.st_mode) || info.st_size<33 || info.st_size>ART_MAX_BYTES) {
        close(fd);return "ART_SIZE_ERROR";
    }
    size_t size=(size_t)info.st_size;
    unsigned char *bytes=malloc(size+1);
    if(!bytes){close(fd);return "ART_ALLOCATION_ERROR";}
    size_t used=0;
    while(used<size+1) {
        ssize_t n=read(fd,bytes+used,size+1-used);
        if(n<=0)break;
        used+=(size_t)n;
    }
    int closed=close(fd);
    const char *error=NULL;
    if(used!=size || closed!=0)error="ART_READ_ERROR";
    else if(cards_crc32(bytes,size)!=crc)error="ART_CRC_MISMATCH";
    else if(memcmp(bytes,"\211PNG\r\n\032\n",8) || be32(bytes+8)!=13 || memcmp(bytes+12,"IHDR",4))
        error="ART_PNG_ERROR";
    else if(!be32(bytes+16) || !be32(bytes+20) ||
            be32(bytes+16)>ART_MAX_DIMENSION || be32(bytes+20)>ART_MAX_DIMENSION)
        error="ART_DIMENSION_ERROR";
    else {
        int w,h,channels;
        art->pixels=stbi_load_from_memory(bytes,(int)size,&w,&h,&channels,4);
        if(!art->pixels)error="ART_DECODE_ERROR";
        else {art->width=(unsigned)w;art->height=(unsigned)h;}
    }
    free(bytes);
    return error;
}
