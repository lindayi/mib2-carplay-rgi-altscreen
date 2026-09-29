#include "runtime.h"
#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>

static int number(char **text,char delimiter,uint64_t *value) {
    char *start=*text,*end;
    if(*start<'0' || *start>'9')return 0;
    errno=0;
    unsigned long long parsed=strtoull(start,&end,10);
    if(errno || end==start || *end!=delimiter || parsed>INT64_MAX)return 0;
    *text=end+1;*value=parsed;return 1;
}
static int string(char **text,char delimiter,char *output,size_t capacity) {
    char *end=strchr(*text,delimiter);
    if(!end || (size_t)(end-*text)>=capacity)return 0;
    for(char *p=*text;p<end;p++)if((unsigned char)*p<32 || (unsigned char)*p>126)return 0;
    memcpy(output,*text,(size_t)(end-*text));output[end-*text]=0;*text=end+1;return 1;
}
int vc_panel_decode(char *text,uint64_t now,unsigned pid,vc_panel_request *request) {
    if(!text || !request || strncmp(text,"VCPANEL1 ",9))return 0;
    vc_panel_request r={0};
    uint64_t fields[7];char *p=text+9;
    for(unsigned i=0;i<7;i++)if(!number(&p,i==6?'\n':' ',fields+i))return 0;
    if(fields[0]>INT_MAX || fields[2]>INT_MAX || fields[4]>INT_MAX ||
            fields[5]>VC_PANEL_ROWS || fields[6]>=VC_PANEL_ROWS)return 0;
    r.pid=fields[0];r.epoch=fields[1];r.revision=fields[2];r.expires=fields[3];
    r.connection=fields[4];r.page.count=fields[5];r.focus=fields[6];
    if(!r.page.count) {
        if(*p || r.focus)return 0;
        *request=r;return 1;
    }
    if(!r.epoch || !r.revision || r.focus>=r.page.count ||
            !string(&p,'\n',r.page.title,sizeof(r.page.title)) || !r.page.title[0] ||
            !string(&p,'\n',r.page.hint,sizeof(r.page.hint)))return 0;
    for(unsigned i=0;i<r.page.count;i++) {
        uint64_t kind,checked;
        if(!number(&p,' ',&kind) || !number(&p,'\t',&checked) || kind>VC_ROW_CHOICE || checked>1 ||
                !string(&p,'\t',r.page.rows[i].label,sizeof(r.page.rows[i].label)) || !r.page.rows[i].label[0] ||
                !string(&p,'\n',r.page.rows[i].value,sizeof(r.page.rows[i].value)))return 0;
        r.page.rows[i].kind=kind;r.page.rows[i].checked=checked;
    }
    if(*p)return 0;
    if(r.pid!=pid || r.expires<=now || r.expires-now>1000)r.page.count=0;
    *request=r;return 1;
}
