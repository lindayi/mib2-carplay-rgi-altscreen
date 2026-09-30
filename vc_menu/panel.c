#include "panel.h"
#include "../maneuver_render/route_font_data.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int text_valid(const char *text,size_t capacity) {
    for(size_t i=0;i<capacity;i++) {
        unsigned c=(unsigned char)text[i];
        if(!c)return 1;
        if(c<32 || c>126)return 0;
    }
    return 0;
}
static int page_valid(const vc_panel_page *page) {
    if(!page || !page->count || page->count>VC_PANEL_ROWS || page->preview>VC_PANEL_PREVIEW_PAGE ||
            !text_valid(page->title,sizeof(page->title)) || !page->title[0] ||
            !text_valid(page->hint,sizeof(page->hint)))return 0;
    for(unsigned i=0;i<page->count;i++) {
        const vc_panel_row *row=&page->rows[i];
        if(!row->label[0] || !text_valid(row->label,sizeof(row->label)) ||
                !text_valid(row->value,sizeof(row->value)) ||
                row->kind<VC_ROW_VALUE || row->kind>VC_ROW_CHOICE ||
                (row->checked!=0 && row->checked!=1))return 0;
    }
    return 1;
}
int vc_panel_open(vc_panel *panel,const vc_panel_page *page) {
    if(!panel || !page_valid(page)) {
        fprintf(stderr,"VC_MENU=INVALID_PAGE\n");return 0;
    }
    memset(panel,0,sizeof(*panel));
    panel->pages[0]=*page;panel->depth=1;
    return 1;
}
int vc_panel_enter(vc_panel *panel,const vc_panel_page *page) {
    if(!panel || !panel->depth || panel->depth>=VC_PANEL_DEPTH || !page_valid(page)) {
        fprintf(stderr,"VC_MENU=INVALID_SUBPAGE\n");return 0;
    }
    panel->pages[panel->depth]=*page;
    panel->focus[panel->depth]=0;panel->depth++;
    return 1;
}
int vc_panel_move(vc_panel *panel,int steps) {
    if(!panel || !panel->depth || panel->depth>VC_PANEL_DEPTH)return 0;
    unsigned index=panel->depth-1;
    int64_t next=(int64_t)panel->focus[index]+steps;
    if(next<0)next=0;
    if(next>=(int64_t)panel->pages[index].count)next=panel->pages[index].count-1;
    panel->focus[index]=(unsigned)next;
    return 1;
}
int vc_panel_selected(const vc_panel *panel) {
    if(!panel || !panel->depth || panel->depth>VC_PANEL_DEPTH)return -1;
    return (int)panel->focus[panel->depth-1];
}
int vc_panel_activate(vc_panel *panel) {
    int selected=vc_panel_selected(panel);
    if(selected<0)return -1;
    vc_panel_page *page=&panel->pages[panel->depth-1];
    if((unsigned)selected>=page->count)return -1;
    vc_panel_row *row=&page->rows[selected];
    if(row->kind==VC_ROW_TOGGLE)row->checked=!row->checked;
    else if(row->kind==VC_ROW_CHOICE) {
        for(unsigned i=0;i<page->count;i++)if(page->rows[i].kind==VC_ROW_CHOICE)page->rows[i].checked=0;
        row->checked=1;
    }
    return selected;
}
void vc_panel_back(vc_panel *panel) {
    if(panel && panel->depth)panel->depth--;
}
void vc_panel_close(vc_panel *panel) {
    if(panel)panel->depth=0;
}
int vc_panel_renderer_init(vc_panel_renderer *renderer) {
    if(!renderer){fprintf(stderr,"VC_MENU=INVALID_RENDERER\n");return 0;}
    if(renderer->font)return 1;
    renderer->font=malloc(ROUTE_FONT_WIDTH*ROUTE_FONT_HEIGHT);
    if(!renderer->font){fprintf(stderr,"VC_MENU=FONT_ALLOCATION_FAILED\n");return 0;}
    size_t pos=0,capacity=ROUTE_FONT_WIDTH*ROUTE_FONT_HEIGHT;
    for(size_t i=0;i<sizeof(route_font_rle);i+=2) {
        unsigned count=route_font_rle[i];
        if(pos+count>capacity)break;
        memset(renderer->font+pos,route_font_rle[i+1],count);pos+=count;
    }
    if(pos!=capacity) {
        vc_panel_renderer_destroy(renderer);
        fprintf(stderr,"VC_MENU=INVALID_FONT\n");return 0;
    }
    return 1;
}
void vc_panel_renderer_destroy(vc_panel_renderer *renderer) {
    if(renderer){free(renderer->font);renderer->font=NULL;}
}
typedef struct {
    unsigned char *rgba;
    unsigned width,height,stride;
    int origin_x,origin_y;
    unsigned panel_width;
} canvas;
unsigned vc_panel_width(const vc_panel_page *page) {
    return page->preview?VC_PANEL_PREVIEW_WIDTH:VC_PANEL_WIDTH;
}
static void pixel(canvas *c,int x,int y,unsigned rgb,unsigned alpha) {
    if(x<0 || y<0 || (unsigned)x>=c->panel_width || y>=VC_PANEL_HEIGHT)return;
    x+=c->origin_x;y+=c->origin_y;
    if(x<0 || y<0 || (unsigned)x>=c->width || (unsigned)y>=c->height)return;
    unsigned char *p=c->rgba+(size_t)y*c->stride+(size_t)x*4;
    for(unsigned component=0;component<3;component++) {
        unsigned shade=(rgb>>(16-component*8))&255;
        p[component]=(unsigned char)((shade*alpha+p[component]*(255-alpha)+127)/255);
    }
}
static void rect(canvas *c,int x,int y,int w,int h,unsigned rgb,unsigned alpha) {
    for(int row=y;row<y+h;row++)for(int column=x;column<x+w;column++)
        pixel(c,column,row,rgb,alpha);
}
static const route_glyph_t *glyph(unsigned c) {
    return c>=32 && c<=126?&route_glyphs[c-32]:NULL;
}
static int text_width(const char *text,float scale) {
    float width=0;
    for(;*text;text++) {
        const route_glyph_t *g=glyph((unsigned char)*text);
        if(g)width+=g->advance*scale/64.f;
    }
    return (int)(width+.5f);
}
static void text(canvas *c,const unsigned char *font,const char *label,
                 int x,int y,int limit,float scale,unsigned color) {
    float advance=0;
    int truncated=text_width(label,scale)>limit;
    int available=truncated?limit-text_width("...",scale):limit;
    const char *p=label;
    while(*p) {
        const route_glyph_t *g=glyph((unsigned char)*p++);
        if(!g)continue;
        float next=advance+g->advance*scale/64.f;
        if((int)(next+.5f)>available)break;
        for(int dy=0;dy<(int)(g->h*scale+.5f);dy++)
            for(int dx=0;dx<(int)(g->w*scale+.5f);dx++) {
                float sx=dx/scale,sy=dy/scale;
                unsigned ix=(unsigned)sx,iy=(unsigned)sy;
                float fx=sx-ix,fy=sy-iy,alpha=0;
                for(unsigned yy=0;yy<2;yy++)for(unsigned xx=0;xx<2;xx++)
                    if(ix+xx<g->w && iy+yy<g->h)
                        alpha+=font[(g->y+iy+yy)*ROUTE_FONT_WIDTH+g->x+ix+xx]*
                            (xx?fx:1-fx)*(yy?fy:1-fy);
                pixel(c,x+(int)(advance+g->left*scale)+dx,y+(int)(g->top*scale)+dy,color,alpha);
            }
        advance=next;
    }
    if(truncated && limit>=text_width("...",scale))
        text(c,font,"...",x+(int)(advance+.5f),y,limit-(int)(advance+.5f),scale,color);
}
static void chevron(canvas *c,int x,int y,unsigned color) {
    for(int i=0;i<6;i++) {
        rect(c,x+i,y+i,2,2,color,255);
        rect(c,x+i,y+10-i,2,2,color,255);
    }
}
int vc_panel_paint(const vc_panel_renderer *renderer,const vc_panel *panel,
                  unsigned char *rgba,size_t bytes,unsigned width,unsigned height,unsigned stride) {
    if(!panel || !panel->depth)return 1;
    if(!renderer || !renderer->font || !rgba || panel->depth>VC_PANEL_DEPTH ||
            width<vc_panel_width(&panel->pages[panel->depth-1])+32 || height<VC_PANEL_HEIGHT+32 ||
            width>4096 || height>2160 || stride<(size_t)width*4 ||
            bytes<(size_t)stride*height || !page_valid(&panel->pages[panel->depth-1]) ||
            panel->focus[panel->depth-1]>=panel->pages[panel->depth-1].count) {
        fprintf(stderr,"VC_MENU=INVALID_SURFACE_OR_STATE\n");return 0;
    }
    const vc_panel_page *page=&panel->pages[panel->depth-1];
    unsigned panel_width=vc_panel_width(page);
    canvas c={rgba,width,height,stride,(int)width-(int)panel_width-16,((int)height-VC_PANEL_HEIGHT)/2,panel_width};
    rect(&c,0,0,panel_width,VC_PANEL_HEIGHT,0x121519,248);
    rect(&c,0,0,panel_width,2,0xaeb2b6,180);
    if(page->preview) {
        rect(&c,VC_PANEL_WIDTH,44,1,192,0x44494e,255);
        text(&c,renderer->font,"Preview",VC_PANEL_WIDTH+20,48,180,.95f,0xdfe1e3);
        text(&c,renderer->font,"OK to apply",VC_PANEL_WIDTH+20,216,180,.8f,0xadb2b8);
    }
    text(&c,renderer->font,page->title,20,8,380,1.35f,0xf4f4f4);
    rect(&c,20,37,380,1,0x52565a,255);
    for(unsigned i=0;i<page->count;i++) {
        const vc_panel_row *row=&page->rows[i];
        int y=44+(int)i*32;
        int selected=i==panel->focus[panel->depth-1];
        if(selected) {
            for(int x=0;x<VC_PANEL_WIDTH;x++)
                rect(&c,x,y,1,30,x<4?0xf23843:0x681a23,x<4?255:235);
        }
        unsigned color=selected?0xffffff:0xdfe1e3;
        int value_width=text_width(row->value,.95f);
        int icon=row->kind==VC_ROW_LINK || row->kind==VC_ROW_TOGGLE || row->kind==VC_ROW_CHOICE?24:0;
        int value_x=VC_PANEL_WIDTH-20-icon-value_width;
        int label_limit=row->value[0]?value_x-36:VC_PANEL_WIDTH-40-icon;
        text(&c,renderer->font,row->label,20,y+4,label_limit,1.05f,color);
        text(&c,renderer->font,row->value,value_x,y+5,value_width,.95f,color);
        if(row->kind==VC_ROW_LINK)chevron(&c,VC_PANEL_WIDTH-28,y+10,color);
        if(row->kind==VC_ROW_CHOICE) {
            int cx=VC_PANEL_WIDTH-27,cy=y+15;
            for(int yy=-8;yy<=8;yy++)for(int xx=-8;xx<=8;xx++) {
                int distance=xx*xx+yy*yy;
                if((distance>=42 && distance<=64) || (row->checked && distance<=10))
                    pixel(&c,cx+xx,cy+yy,color,255);
            }
        }
        if(row->kind==VC_ROW_TOGGLE) {
            int x=VC_PANEL_WIDTH-35;
            rect(&c,x,y+7,17,17,color,255);
            rect(&c,x+1,y+8,15,15,selected?0x681a23:0x121519,255);
            if(row->checked) {
                for(int n=0;n<5;n++)rect(&c,x+3+n,y+14+n/2,2,2,color,255);
                for(int n=0;n<7;n++)rect(&c,x+7+n,y+16-n,2,2,color,255);
            }
        }
    }
    rect(&c,20,244,380,1,0x44494e,255);
    text(&c,renderer->font,page->hint,20,250,380,.8f,0xadb2b8);
    text(&c,renderer->font,"OK  Select     Back  Return",20,270,380,.7f,0x92989f);
    return 1;
}
void vc_panel_preview(const vc_panel_renderer *renderer,const vc_panel_page *page,
                     unsigned char *rgba,unsigned width,unsigned height,unsigned stride,
                     const unsigned char *pixels,unsigned sprite_width,unsigned sprite_height,const char *message) {
    canvas c={rgba,width,height,stride,(int)width-VC_PANEL_PREVIEW_WIDTH-16,
        ((int)height-VC_PANEL_HEIGHT)/2,VC_PANEL_PREVIEW_WIDTH};
    if(!page->preview)return;
    if(message) {
        text(&c,renderer->font,message,VC_PANEL_WIDTH+20,130,180,.85f,0xadb2b8);
        return;
    }
    if(!pixels || !sprite_width || !sprite_height)return;
    float scale=2.f;
    if(sprite_width*scale>180)scale=180.f/sprite_width;
    unsigned w=(unsigned)(sprite_width*scale),h=(unsigned)(sprite_height*scale);
    int x=VC_PANEL_WIDTH+(220-(int)w)/2,y=92+(100-(int)h)/2;
    for(unsigned dy=0;dy<h;dy++)for(unsigned dx=0;dx<w;dx++) {
        const unsigned char *p=pixels+((unsigned)(dy/scale)*sprite_width+(unsigned)(dx/scale))*4;
        pixel(&c,x+dx,y+dy,((unsigned)p[0]<<16)|((unsigned)p[1]<<8)|p[2],p[3]);
    }
}
