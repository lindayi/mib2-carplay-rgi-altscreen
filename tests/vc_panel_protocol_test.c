#include "../vc_menu/runtime.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
static char sample[]="VCPANEL1 42 7 3 1500 2 1 0\nCarplay Altscreen\nHold roller: close\n1 1\tEnabled\t\n";
int main(int argc,char **argv) {
    vc_panel_request request;
    char text[2048];
    strcpy(text,sample);assert(vc_panel_decode(text,1000,42,&request));
    assert(request.page.count==1 && request.page.rows[0].checked && request.epoch==7 && request.connection==2);
    strcpy(text,sample);assert(vc_panel_decode(text,1501,42,&request) && !request.page.count);
    strcpy(text,sample);assert(vc_panel_decode(text,1000,43,&request) && !request.page.count);
    for(unsigned preview=1;preview<=18;preview++) {
        snprintf(text,sizeof(text),"%sPREVIEW %u\n",sample,preview);
        assert(vc_panel_decode(text,1000,42,&request) && request.page.preview==preview);
    }
    const char *suffixes[]={"PREVIEW 0\n","PREVIEW 19\n","PREVIEW -1\n","PREVIEW 1","PREVIEW 2\nextra","PREVIEW 2\nPREVIEW 3\n"};
    for(unsigned i=0;i<sizeof(suffixes)/sizeof(suffixes[0]);i++) {
        snprintf(text,sizeof(text),"%s%s",sample,suffixes[i]);
        assert(!vc_panel_decode(text,1000,42,&request));
    }
    for(unsigned n=0;n<strlen(sample);n++) {
        memcpy(text,sample,n);text[n]=0;
        assert(!vc_panel_decode(text,1000,42,&request));
    }
    const char *bad[]={
        "VCPANEL1 -1 7 3 1500 2 0 0\n","VCPANEL1 42 7 3 1500 2 7 0\n",
        "VCPANEL1 42 7 3 1500 2 0 1\n","VCPANEL1 42 18446744073709551615 3 1500 2 0 0\n",
        "VCPANEL1 42 7 3 1500 2 0 0\nextra","VCPANEL1 42 7 3 1500 2 1 0\nx\nx\n4 1\tx\t\n",
        "VCPANEL1 42 7 3 1500 2 1 0\nx\nx\n1 2\tx\t\n"};
    for(unsigned i=0;i<sizeof(bad)/sizeof(bad[0]);i++) {
        strcpy(text,bad[i]);assert(!vc_panel_decode(text,1000,42,&request));
    }
    for(unsigned i=0;i<5000;i++) {
        strcpy(text,sample);text[rand()%(sizeof(sample)-1)]=(char)(1+rand()%126);
        vc_panel_decode(text,1000,42,&request);
    }
    if(argc==2) {
        FILE *f=fopen(argv[1],"rb");assert(f);
        size_t n=fread(text,1,sizeof(text)-1,f);assert(feof(f) && fclose(f)==0);text[n]=0;
        assert(vc_panel_decode(text,1000,42,&request) && request.page.count==5 && request.focus==0);
        assert(!strcmp(request.page.title,"Carplay Altscreen"));
    }
    puts("VC panel bounded protocol, expired/wrong PID leases, malformed input and Java snapshot: PASS");
    return 0;
}
