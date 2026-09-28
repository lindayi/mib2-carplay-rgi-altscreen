#include "../vc_menu/panel.h"
#include <assert.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define WIDTH 960
#define HEIGHT 400
static unsigned char pixels[WIDTH*HEIGHT*4],baseline[sizeof(pixels)];
static vc_panel_page root={
    "Carplay Altscreen","Settings preview - no vehicle input",5,{
        {"Enabled","",VC_ROW_TOGGLE,1},
        {"Display mode","Map + guidance",VC_ROW_LINK,0},
        {"Map layout","Card on top",VC_ROW_LINK,0},
        {"Map mascot","Off",VC_ROW_LINK,0},
        {"More settings","",VC_ROW_LINK,0}
    }
};
static vc_panel_page mascots={
    "Map mascot","Appears only on the CarPlay cluster map",3,{
        {"Off","",VC_ROW_CHOICE,1},
        {"Raccoon","",VC_ROW_CHOICE,0},
        {"Nian","",VC_ROW_CHOICE,0}
    }
};
static void background(void) {
    for(unsigned y=0;y<HEIGHT;y++)for(unsigned x=0;x<WIDTH;x++) {
        unsigned char *p=pixels+(y*WIDTH+x)*4;
        int road=(x+2*y)%180<8 || (2*x+y)%240<6;
        p[0]=road?54:31;p[1]=road?59:36;p[2]=road?62:40;p[3]=173;
        if(x>140 && x<154 && y>95 && y<300){p[0]=53;p[1]=139;p[2]=218;}
    }
    memcpy(baseline,pixels,sizeof(pixels));
}
static void save(const char *directory,const char *name) {
    char path[1024];
    assert(snprintf(path,sizeof(path),"%s/%s.ppm",directory,name)<(int)sizeof(path));
    FILE *file=fopen(path,"wb");assert(file);
    assert(fprintf(file,"P6\n%d %d\n255\n",WIDTH,HEIGHT)>0);
    for(unsigned i=0;i<WIDTH*HEIGHT;i++)assert(fwrite(pixels+i*4,1,3,file)==3);
    assert(fclose(file)==0);
}
int main(int argc,char **argv) {
    assert(argc==2);
    vc_panel panel={0};
    vc_panel_renderer renderer={0};
    assert(vc_panel_renderer_init(&renderer));
    assert(vc_panel_renderer_init(&renderer));
    background();
    assert(vc_panel_paint(&renderer,&panel,pixels,sizeof(pixels),WIDTH,HEIGHT,WIDTH*4));
    assert(!memcmp(pixels,baseline,sizeof(pixels)));
    assert(vc_panel_open(&panel,&root));
    assert(vc_panel_selected(&panel)==0);
    assert(vc_panel_activate(&panel)==0 && !panel.pages[0].rows[0].checked);
    assert(vc_panel_activate(&panel)==0 && panel.pages[0].rows[0].checked);
    assert(vc_panel_move(&panel,INT_MAX));assert(vc_panel_selected(&panel)==4);
    assert(vc_panel_move(&panel,INT_MIN));assert(vc_panel_selected(&panel)==0);
    assert(vc_panel_paint(&renderer,&panel,pixels,sizeof(pixels),WIDTH,HEIGHT,WIDTH*4));
    for(unsigned y=0;y<HEIGHT;y++)for(unsigned x=0;x<WIDTH;x++) {
        size_t at=(y*WIDTH+x)*4;
        assert(pixels[at+3]==baseline[at+3]);
        if(x<WIDTH-VC_PANEL_WIDTH-16 || x>=WIDTH-16 ||
                y<(HEIGHT-VC_PANEL_HEIGHT)/2 || y>=(HEIGHT+VC_PANEL_HEIGHT)/2)
            assert(!memcmp(pixels+at,baseline+at,4));
    }
    save(argv[1],"root");
    vc_panel_move(&panel,3);
    assert(vc_panel_enter(&panel,&mascots));
    vc_panel_move(&panel,1);
    assert(vc_panel_activate(&panel)==1);
    assert(!panel.pages[1].rows[0].checked && panel.pages[1].rows[1].checked);
    background();assert(vc_panel_paint(&renderer,&panel,pixels,sizeof(pixels),WIDTH,HEIGHT,WIDTH*4));
    save(argv[1],"mascot");
    vc_panel_back(&panel);assert(vc_panel_selected(&panel)==3);
    vc_panel_back(&panel);assert(vc_panel_selected(&panel)==-1);
    assert(!vc_panel_move(&panel,1));
    assert(vc_panel_activate(&panel)==-1);
    background();assert(vc_panel_paint(&renderer,&panel,pixels,sizeof(pixels),WIDTH,HEIGHT,WIDTH*4));
    assert(!memcmp(pixels,baseline,sizeof(pixels)));
    save(argv[1],"closed");
    vc_panel_page invalid=root;
    invalid.count=VC_PANEL_ROWS+1;assert(!vc_panel_open(&panel,&invalid));
    invalid=root;memset(invalid.rows[0].label,'x',sizeof(invalid.rows[0].label));
    assert(!vc_panel_open(&panel,&invalid));
    invalid=root;invalid.rows[0].label[0]='\n';assert(!vc_panel_open(&panel,&invalid));
    assert(vc_panel_open(&panel,&root));
    for(int i=1;i<VC_PANEL_DEPTH;i++)assert(vc_panel_enter(&panel,&mascots));
    assert(!vc_panel_enter(&panel,&mascots));
    memcpy(pixels,baseline,sizeof(pixels));
    assert(!vc_panel_paint(&renderer,&panel,pixels,sizeof(pixels)-1,WIDTH,HEIGHT,WIDTH*4));
    assert(!vc_panel_paint(&renderer,&panel,pixels,sizeof(pixels),328,181,WIDTH*4));
    assert(!vc_panel_paint(&renderer,&panel,pixels,sizeof(pixels),WIDTH,HEIGHT,WIDTH*4-1));
    assert(!memcmp(pixels,baseline,sizeof(pixels)));
    vc_panel_close(&panel);vc_panel_renderer_destroy(&renderer);
    assert(!renderer.font);
    puts("VC panel prototype: focus excludes title/help, open/select/back/close, bounds, alpha and small-surface rejection PASS");
    return 0;
}
