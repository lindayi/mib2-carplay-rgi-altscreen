#include "cards.h"
#include "../maneuver_render/route_font_data.h"
#include <stdio.h>
#include <string.h>
#include <math.h>

int cards_painter_init(cards_painter *painter) {
    if(painter->font)return 1;
    size_t capacity=ROUTE_FONT_WIDTH*ROUTE_FONT_HEIGHT,pos=0;
    painter->font=malloc(capacity);
    if(!painter->font)return 0;
    for(size_t i=0;i<sizeof(route_font_rle);i+=2) {
        unsigned count=route_font_rle[i];
        if(count>capacity-pos)break;
        memset(painter->font+pos,route_font_rle[i+1],count);pos+=count;
    }
    if(pos==capacity)return 1;
    cards_painter_destroy(painter);return 0;
}
void cards_painter_destroy(cards_painter *painter) {free(painter->font);painter->font=NULL;}
static const route_glyph_t *glyph(uint32_t cp) {
    size_t low=0,high=sizeof(route_glyphs)/sizeof(route_glyphs[0]);
    while(low<high) {
        size_t mid=low+(high-low)/2;
        if(route_glyphs[mid].cp<cp)low=mid+1;else high=mid;
    }
    return low<sizeof(route_glyphs)/sizeof(route_glyphs[0]) && route_glyphs[low].cp==cp?route_glyphs+low:NULL;
}
static int supported(const char *text) {
    while(*text) {
        uint32_t cp;
        if(!cards_utf8_next(&text,&cp) || !glyph(cp))return 0;
    }
    return 1;
}
int cards_inside(int x,int y) {
    if(x<0 || x>=CARDS_WIDTH || y<0 || y>=CARDS_IMAGE_HEIGHT)return 0;
    if(x<99 && y*99<(99-x)*40)return 0;
    int dx=2*x+1-210,dy=2*y+1-630;
    return dx*dx+dy*dy>=248*248;
}
static void pixel(unsigned char *rgba,int x,int y,unsigned rgb,unsigned alpha) {
    if(!cards_inside(x,y))return;
    unsigned char *p=rgba+((size_t)y*CARDS_WIDTH+x)*4;
    for(unsigned i=0;i<3;i++)
        p[i]=(unsigned char)((((rgb>>(16-8*i))&255)*alpha+p[i]*(255-alpha)+127)/255);
    p[3]=(unsigned char)(alpha+(p[3]*(255-alpha)+127)/255);
}
static void rect(unsigned char *rgba,int x,int y,int w,int h,unsigned rgb,unsigned alpha) {
    for(int yy=y;yy<y+h;yy++)for(int xx=x;xx<x+w;xx++)pixel(rgba,xx,yy,rgb,alpha);
}
typedef struct { int x,y,w,h; } text_area;
typedef struct { text_area title,artist; float title_scale,artist_scale; } media_layout;
static media_layout media_areas(const cards_request *request,const cards_art *art) {
    int has_art=art && art->pixels && art->width && art->height;
    int x=request->trip && has_art?66:12;
    media_layout layout={
        {x,request->trip?53:has_art?128:80,198-x,28},
        {x,request->trip?81:has_art?157:119,198-x,27},
        1.25f,1.f
    };
    return layout;
}
static float text_left(const char *text,float scale) {
    float advance=0,left=0;
    while(*text) {
        uint32_t cp;
        if(!cards_utf8_next(&text,&cp))break;
        const route_glyph_t *g=glyph(cp);
        if(g){if(advance+g->left*scale<left)left=advance+g->left*scale;advance+=g->advance*scale/64.f;}
    }
    return floorf(left);
}
static int text_width(const char *text,float scale) {
    float advance=0,right=0,left=text_left(text,scale);
    while(*text) {
        uint32_t cp;
        if(!cards_utf8_next(&text,&cp))break;
        const route_glyph_t *g=glyph(cp);
        if(g) {
            float edge=advance+(g->left+g->w)*scale;
            if(edge>right)right=edge;
            advance+=g->advance*scale/64.f;
        }
    }
    return (int)ceilf(fmaxf(right,advance)-left);
}
int cards_scroll_offset(int overflow,uint64_t elapsed) {
    if(overflow<=0)return 0;
    uint64_t travel=((uint64_t)overflow*1000+CARDS_SCROLL_PIXELS_PER_SECOND-1)/CARDS_SCROLL_PIXELS_PER_SECOND;
    uint64_t phase=elapsed%(2*CARDS_SCROLL_PAUSE_MS+travel);
    if(phase<=CARDS_SCROLL_PAUSE_MS)return 0;
    if(phase>=CARDS_SCROLL_PAUSE_MS+travel)return overflow;
    return (int)((phase-CARDS_SCROLL_PAUSE_MS)*CARDS_SCROLL_PIXELS_PER_SECOND/1000);
}
cards_scroll cards_scroll_at(const cards_request *request,const cards_art *art,uint64_t elapsed) {
    cards_scroll out={0};
    if(!request->media)return out;
    media_layout layout=media_areas(request,art);
    if(supported(request->text[0]))
        out.title=cards_scroll_offset(text_width(request->text[0],layout.title_scale)-layout.title.w,elapsed);
    if(supported(request->text[1]))
        out.artist=cards_scroll_offset(text_width(request->text[1],layout.artist_scale)-layout.artist.w,elapsed);
    return out;
}
static void text(const cards_painter *painter,unsigned char *rgba,const char *s,
                 text_area area,float scale,unsigned color,int offset,int shorten) {
    int truncated=shorten && text_width(s,scale)>area.w;
    float left=text_left(s,scale),advance=-left;
    int available=area.w-(truncated?text_width("...",scale):0);
    while(*s) {
        uint32_t cp;
        if(!cards_utf8_next(&s,&cp))break;
        const route_glyph_t *g=glyph(cp);
        if(!g)break;
        float next=advance+g->advance*scale/64.f;
        if(shorten && next>available)break;
        for(int dy=0;dy<(int)(g->h*scale+.5f);dy++)
            for(int dx=0;dx<(int)(g->w*scale+.5f);dx++) {
                float sx=dx/scale,sy=dy/scale,alpha=0;
                unsigned ix=(unsigned)sx,iy=(unsigned)sy;
                float fx=sx-ix,fy=sy-iy;
                for(unsigned yy=0;yy<2;yy++)for(unsigned xx=0;xx<2;xx++)
                    if(ix+xx<g->w && iy+yy<g->h)
                        alpha+=painter->font[(g->y+iy+yy)*ROUTE_FONT_WIDTH+g->x+ix+xx]*
                            (xx?fx:1-fx)*(yy?fy:1-fy);
                int x=area.x+(int)(advance+g->left*scale)+dx-offset;
                int y=area.y+(int)(g->top*scale)+dy;
                if(x>=area.x && x<area.x+area.w && y>=area.y && y<area.y+area.h)
                    pixel(rgba,x,y,color,(unsigned)alpha);
            }
        advance=next;
    }
    if(truncated) {
        int used=(int)ceilf(advance);
        text_area tail={area.x+used,area.y,area.w-used,area.h};
        text(painter,rgba,"...",tail,scale,color,0,0);
    }
}
unsigned cards_paint(const cards_painter *painter,const cards_request *request,
                     const cards_art *art,cards_scroll scroll,unsigned char rgba[CARDS_IMAGE_BYTES]) {
    memset(rgba,0,CARDS_IMAGE_BYTES);
    unsigned omitted=0;
    const char *labels[CARDS_TEXT_FIELDS];
    for(unsigned i=0;i<CARDS_TEXT_FIELDS;i++) {
        int visible=i<3?request->media:request->trip;
        labels[i]=visible && supported(request->text[i])?request->text[i]:"";
        if(visible && request->text[i][0] && !labels[i][0])omitted|=1u<<i;
    }
    if(!request->media && !request->trip)return 0;
    rect(rgba,0,0,CARDS_WIDTH,CARDS_IMAGE_HEIGHT,0x121519,CARDS_BODY_ALPHA);
    const char *header=request->media?
        (!strcmp(labels[2],"Paused")?"Paused":!strcmp(labels[2],"Stopped")?"Stopped":
         !strcmp(labels[2],"Playing")?"Now playing":"Media"):"Trip";
    text_area heading={198-text_width(header,1.f),10,text_width(header,1.f),25};
    text(painter,rgba,header,heading,1.f,0xffffff,0,0);
    if(request->media) {
        media_layout layout=media_areas(request,art);
        if(art && art->pixels && art->width && art->height) {
            unsigned box=request->trip?44:64,w=box,h=box;
            int x=request->trip?12:(CARDS_WIDTH-(int)box)/2,y=54;
            if(art->width>art->height)h=box*art->height/art->width;
            else w=box*art->width/art->height;
            if(!w)w=1;
            if(!h)h=1;
            for(unsigned yy=0;yy<h;yy++)for(unsigned xx=0;xx<w;xx++) {
                const unsigned char *p=art->pixels+((size_t)(yy*art->height/h)*art->width+xx*art->width/w)*4;
                pixel(rgba,x+((int)box-(int)w)/2+(int)xx,y+((int)box-(int)h)/2+(int)yy,
                      ((unsigned)p[0]<<16)|((unsigned)p[1]<<8)|p[2],p[3]);
            }
        }
        text(painter,rgba,labels[0],layout.title,layout.title_scale,0xffffff,scroll.title,0);
        text(painter,rgba,labels[1],layout.artist,layout.artist_scale,0xe0e3e6,scroll.artist,0);
    }
    if(request->trip) {
        if(request->media) {
            rect(rgba,12,107,186,1,0x687078,180);
            text(painter,rgba,"Trip",(text_area){12,111,50,18},.85f,0xffffff,0,1);
        }
        char eta[CARDS_TEXT_BYTES+5];
        if(labels[3][0]) {
            snprintf(eta,sizeof(eta),"ETA %s",labels[3]);
            float scale=request->media?1.f:1.3f;
            if(text_width(eta,scale)>186)scale=1.f;
            text(painter,rgba,eta,(text_area){12,request->media?130:65,186,29},scale,0xffffff,0,1);
        }
        if(request->media) {
            int time_width=text_width(labels[4],.95f),distance_width=text_width(labels[5],.95f);
            if(time_width+distance_width+12<=186) {
                text(painter,rgba,labels[4],(text_area){12,153,time_width,24},.95f,0xe0e3e6,0,0);
                text(painter,rgba,labels[5],(text_area){198-distance_width,153,distance_width,24},.95f,0xe0e3e6,0,0);
            } else {
                text(painter,rgba,labels[4],(text_area){12,149,186,16},.8f,0xe0e3e6,0,1);
                text(painter,rgba,labels[5],(text_area){12,165,186,16},.8f,0xe0e3e6,0,1);
            }
        } else {
            text(painter,rgba,labels[4],(text_area){12,105,186,27},1.15f,0xe0e3e6,0,1);
            text(painter,rgba,labels[5],(text_area){12,137,186,27},1.15f,0xe0e3e6,0,1);
        }
        if(request->progress>=0) {
            text(painter,rgba,"Est. progress",(text_area){request->media?101:12,request->media?112:166,97,18},
                 .75f,0xe0e3e6,0,1);
            rect(rgba,12,182,186,3,0x687078,255);
            rect(rgba,12,182,186*request->progress/1000,3,0xd9e3ea,255);
        }
    }
    return omitted;
}
