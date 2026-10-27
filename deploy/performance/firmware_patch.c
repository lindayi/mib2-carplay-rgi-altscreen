/* Exact MU1316 adapter for LuKa a63d5474 binpatch.py transformations (GPL-3.0).
 * No firmware bytes are embedded. Unknown/partial images are never patched.
 * The native adapter avoids adding a Python runtime to the Toolbox/HU. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <errno.h>
#include <limits.h>

#define LIMIT (1024u * 1024u)
static uint32_t rotr(uint32_t x, unsigned n) { return (x >> n) | (x << (32-n)); }
static void sha256(const unsigned char *data, size_t size, char out[65]) {
    static const uint32_t k[64] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
    };
    uint32_t h[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    size_t total=(size+9+63)/64*64;
    for(size_t offset=0;offset<total;offset+=64) {
        uint32_t w[64],v[8]; unsigned char block[64]={0};
        for(unsigned i=0;i<64;i++) {
            size_t pos=offset+i;
            if(pos<size) block[i]=data[pos];
            else if(pos==size) block[i]=0x80;
            if(pos>=total-8) block[i]=(unsigned char)((uint64_t)size*8 >> ((total-1-pos)*8));
        }
        for(unsigned i=0;i<16;i++) w[i]=(uint32_t)block[i*4]<<24|(uint32_t)block[i*4+1]<<16|
            (uint32_t)block[i*4+2]<<8|block[i*4+3];
        for(unsigned i=16;i<64;i++) {
            uint32_t a=w[i-15],b=w[i-2];
            w[i]=w[i-16]+(rotr(a,7)^rotr(a,18)^(a>>3))+w[i-7]+(rotr(b,17)^rotr(b,19)^(b>>10));
        }
        memcpy(v,h,sizeof(v));
        for(unsigned i=0;i<64;i++) {
            uint32_t t1=v[7]+(rotr(v[4],6)^rotr(v[4],11)^rotr(v[4],25))+
                ((v[4]&v[5])^(~v[4]&v[6]))+k[i]+w[i];
            uint32_t t2=(rotr(v[0],2)^rotr(v[0],13)^rotr(v[0],22))+
                ((v[0]&v[1])^(v[0]&v[2])^(v[1]&v[2]));
            for(unsigned j=7;j;j--) v[j]=v[j-1];
            v[4]+=t1;v[0]=t1+t2;
        }
        for(unsigned i=0;i<8;i++) h[i]+=v[i];
    }
    for(unsigned i=0;i<8;i++) sprintf(out+i*8,"%08x",(unsigned)h[i]);
}
struct profile { const char *name,*stock,*patched; };
static const struct profile profiles[]={
    {"decoder","01c116b1674215daa0602d556a6d9b2eb48c2113ca294ea1f5d58787e3f656f2",
               "a527b77800f690a33a8856e79e7f9d5a89143bfdd7108903837a444976be25b6"},
    {"mcd","f11d13ffec1322aefcfb431966180cff46c314320eeef7ed12470bfc441dd593",
           "e18f3055f633c99b3f10022c1f0a9c61a9e597d8b9a95831e5578a2e5c5387ee"},
    {"priority","c653517d19107e6594c175d4a6553f1c57d81c1c6982e22b0f4964bf74272092",
                "d0a33ae25acd0af1fd2f0fe84637c41d230d2b1a2933209e8bd800f0868a8033"}
};
int main(int argc,char **argv) {
    int result=1; FILE *input=NULL; unsigned char *bytes=NULL; size_t length;
    if(argc==2 && !strcmp(argv[1],"--self-test")) {
        char hash[65]; sha256((const unsigned char *)"abc",3,hash);
        if(strcmp(hash,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")) goto done;
        puts("PERFORMANCE_HELPER=READY"); return 0;
    }
    if(argc==4 && !strcmp(argv[1],"same-file")) {
        struct stat a,b;
        return stat(argv[2],&a) || stat(argv[3],&b) ||
            !S_ISREG(a.st_mode) || !S_ISREG(b.st_mode) ||
            a.st_nlink!=1 || b.st_nlink!=1 || a.st_dev!=b.st_dev || a.st_ino!=b.st_ino;
    }
    if(argc==2 && !strcmp(argv[1],"lock-held")) {
        const char *value=getenv("CARPLAY_PERFORMANCE_LOCK_FD");
        char *end=NULL;
        if(!value || !*value) goto done;
        errno=0;
        long fd=strtol(value,&end,10);
        if(errno || *end || fd<10 || fd>INT_MAX) goto done;
        struct sockaddr_in address;
        socklen_t size=sizeof(address);
        if(getsockname((int)fd,(struct sockaddr *)&address,&size)!=0 ||
                size!=sizeof(address) || address.sin_family!=AF_INET ||
                address.sin_port!=htons(19804) || address.sin_addr.s_addr!=htonl(0x7f000001u)) goto done;
        return 0;
    }
    if(argc>=3 && !strcmp(argv[1],"lock-exec")) {
        /* Bound, non-listening loopback token; inherited across exec/children.
         * If a parent dies, a still-running installer child keeps the token. */
        int fd=socket(AF_INET,SOCK_STREAM,0);
        struct sockaddr_in address;
        memset(&address,0,sizeof(address));
        address.sin_family=AF_INET;address.sin_port=htons(19804);
#ifdef __QNXNTO__
        address.sin_len=sizeof(address);
#endif
        address.sin_addr.s_addr=htonl(0x7f000001u);
        if(fd<0) goto done;
        int acquired=0;
        for(unsigned attempt=0;attempt<30;attempt++) {
            if(bind(fd,(struct sockaddr *)&address,sizeof(address))==0){acquired=1;break;}
            if(errno!=EADDRINUSE)break;
            usleep(100000);
        }
        if(!acquired){close(fd);goto done;}
        int token=fcntl(fd,F_DUPFD,10);
        close(fd);fd=token;
        if(fd<0)goto done;
        if(fcntl(fd,F_SETFD,0)!=0){close(fd);goto done;}
        char owner[32],descriptor[32];snprintf(owner,sizeof(owner),"%ld",(long)getpid());
        snprintf(descriptor,sizeof(descriptor),"%d",fd);
        if(setenv("CARPLAY_PERFORMANCE_LOCK_PID",owner,1)!=0 ||
                setenv("CARPLAY_PERFORMANCE_LOCK_FD",descriptor,1)!=0){close(fd);goto done;}
        execv(argv[2],argv+2);
        close(fd);goto done;
    }
    if(argc!=4 && argc!=5) goto done;
    const struct profile *p=NULL;
    for(unsigned i=0;i<sizeof(profiles)/sizeof(profiles[0]);i++)
        if(!strcmp(argv[2],profiles[i].name)) p=&profiles[i];
    if(!p || (strcmp(argv[1],"check") && strcmp(argv[1],"apply"))) goto done;
    if((!strcmp(argv[1],"check") && argc!=4) || (!strcmp(argv[1],"apply") && argc!=5)) goto done;
    input=fopen(argv[3],"rb"); if(!input) goto done;
    struct stat st;
    if(fstat(fileno(input),&st)!=0 || !S_ISREG(st.st_mode) || st.st_size<=0 ||
            (uint64_t)st.st_size>LIMIT) goto done;
    length=(size_t)st.st_size;
    bytes=malloc(length+1); if(!bytes) goto done;
    if(fread(bytes,1,length,input)!=length || fgetc(input)!=EOF || ferror(input)) goto done;
    bytes[length]=0;
    char hash[65]; sha256(bytes,length,hash);
    int stock=!strcmp(hash,p->stock), patched=!strcmp(hash,p->patched);
    if(!stock && !patched) goto done;
    if(!strcmp(argv[1],"check")) { puts(stock?"stock":"optimized"); result=0; goto done; }
    if(stock) {
        if(p==&profiles[0]) { bytes[0x8c694]=2;bytes[0x8c6f4]=2; }
        else if(p==&profiles[1]) {
            static const unsigned char replacement[]={0xc8,0,0xa0,0xe3,0,0x10,0xa0,0xe3,0xf7,0xeb,0xff,0xeb};
            memcpy(bytes+0x7730,replacement,sizeof(replacement));
        } else {
            unsigned edits=0;
            for(size_t i=0;i+8<length;i++) if((i==0 || bytes[i-1]=='\n') &&
                    !memcmp(bytes+i,"Priority",8)) {
                size_t at=i+8;
                while(at<length && (bytes[at]==' ' || bytes[at]=='\t'))at++;
                if(at==length || bytes[at++]!='=')continue;
                while(at<length && (bytes[at]==' ' || bytes[at]=='\t'))at++;
                if(at+6>length || memcmp(bytes+at,"11,10\n",6))continue;
                bytes[at+3]='3';
                memmove(bytes+at+4,bytes+at+5,length-at-5);
                length--;edits++;
            }
            if(edits!=13) goto done;
        }
        sha256(bytes,length,hash);
        if(strcmp(hash,p->patched)) goto done;
    }
    int fd=open(argv[4],O_WRONLY|O_CREAT|O_EXCL,0600);
    if(fd<0) goto done;
    size_t written=0;
    while(written<length) {
        ssize_t n=write(fd,bytes+written,length-written);
        if(n<=0) break;
        written+=(size_t)n;
    }
    int flushed=written==length && fsync(fd)==0;
    int closed=close(fd)==0;
    if(!flushed || !closed) goto done;
    result=0;
done:
    if(input && fclose(input)!=0) result=1;
    free(bytes);
    if(result) fprintf(stderr,"PERFORMANCE_PATCH=REFUSED unknown input, invalid operation or I/O failure\n");
    return result;
}
