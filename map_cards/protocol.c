#include "cards.h"
#include <limits.h>
#include <string.h>

int cards_utf8_next(const char **text,uint32_t *cp) {
    const unsigned char *p=(const unsigned char *)*text;
    uint32_t c=*p++;
    if(c<128){*text=(const char *)p;*cp=c;return c!=0;}
    unsigned n;
    uint32_t minimum;
    if(c>=0xc2 && c<=0xdf){n=1;minimum=0x80;c&=31;}
    else if(c>=0xe0 && c<=0xef){n=2;minimum=0x800;c&=15;}
    else if(c>=0xf0 && c<=0xf4){n=3;minimum=0x10000;c&=7;}
    else return 0;
    while(n--) {
        if((*p&0xc0)!=0x80)return 0;
        c=(c<<6)|(*p++&63);
    }
    if(c<minimum || c>0x10ffff || (c>=0xd800 && c<=0xdfff))return 0;
    *text=(const char *)p;*cp=c;return 1;
}
static int number(const char **p,const char *end,uint64_t limit,char delimiter,uint64_t *out) {
    const char *start=*p;
    uint64_t n=0;
    while(*p<end && **p>='0' && **p<='9') {
        unsigned digit=(unsigned)(*(*p)++-'0');
        if(n>limit/10 || (n==limit/10 && digit>limit%10))return 0;
        n=n*10+digit;
    }
    if(*p==start || *p==end || **p!=delimiter)return 0;
    (*p)++;*out=n;return 1;
}
static int hex(unsigned char c) {
    if(c>='0' && c<='9')return c-'0';
    if(c>='a' && c<='f')return c-'a'+10;
    if(c>='A' && c<='F')return c-'A'+10;
    return -1;
}
static int arrival_valid(const char *text) {
    if(!*text)return 1;
    const char *p=text;
    unsigned hour=0,digits=0;
    while(*p>='0' && *p<='9' && digits<2){hour=hour*10+(unsigned)(*p++-'0');digits++;}
    if(!digits || *p++!=':')return 0;
    if(*p<'0' || *p>'5')return 0;
    p++;
    if(*p<'0' || *p>'9')return 0;
    p++;
    int twelve=!strncmp(p," AM",3) || !strncmp(p," PM",3);
    if(twelve){if(hour<1 || hour>12)return 0;p+=3;}
    else if(digits!=2 || hour>23)return 0;
    return !*p || !strcmp(p," dest");
}
int cards_decode(const char *data,size_t size,uint64_t now,unsigned pid,cards_request *out) {
    cards_request r={0};
    if(!data || !out || size>CARDS_MAX_SNAPSHOT || size<7 || memcmp(data,"CARDS",5)
            || data[5]!='0'+CARDS_PROTOCOL_VERSION || data[6]!=' ')return 0;
    const char *p=data+7,*end=data+size;
    uint64_t n;
    if(!number(&p,end,UINT_MAX,' ',&n) || n!=pid)return 0;
    r.pid=(unsigned)n;
    if(!number(&p,end,UINT64_MAX,' ',&r.expires) || r.expires<now ||
            r.expires-now>CARDS_CONTROL_LEASE_MS)return 0;
    if(!number(&p,end,INT64_MAX,' ',&r.connection))return 0;
    if(!number(&p,end,1,' ',&n))return 0;
    r.media=(unsigned)n;
    if(!number(&p,end,1,' ',&n))return 0;
    r.trip=(unsigned)n;
    if(end-p>=3 && !memcmp(p,"-1 ",3)){r.progress=-1;p+=3;}
    else {
        if(!number(&p,end,1000,' ',&n))return 0;
        r.progress=(int)n;
    }
    if(!number(&p,end,UINT32_MAX,' ',&n))return 0;
    r.art_crc=(uint32_t)n;
    if(!number(&p,end,INT64_MAX,'\n',&r.track))return 0;
    for(unsigned line=0;line<CARDS_TEXT_FIELDS;line++) {
        size_t bytes=0;
        while(p<end && *p!='\n') {
            if(end-p<2 || bytes==CARDS_TEXT_BYTES)return 0;
            int hi=hex((unsigned char)*p++),lo=hex((unsigned char)*p++);
            if(hi<0 || lo<0 || !(hi|lo))return 0;
            r.text[line][bytes++]=(char)((hi<<4)|lo);
        }
        if(p==end || *p++!='\n')return 0;
        const char *s=r.text[line];
        while(*s) {
            uint32_t cp;
            if(!cards_utf8_next(&s,&cp) || cp<32 || (cp>=127 && cp<160))return 0;
        }
    }
    if(p!=end)return 0;
    if(r.text[2][0] && strcmp(r.text[2],"Playing") && strcmp(r.text[2],"Paused") &&
            strcmp(r.text[2],"Stopped"))return 0;
    if(!arrival_valid(r.text[3]))return 0;
    *out=r;return 1;
}
