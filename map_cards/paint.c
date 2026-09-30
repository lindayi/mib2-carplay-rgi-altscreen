#include "cards.h"
#include "../maneuver_render/route_font_data.h"
#include <stdio.h>
#include <string.h>

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
static void pixel(unsigned char *rgba,int x,int y,unsigned rgb,unsigned alpha) {
    if(x<0 || x>=CARDS_WIDTH || y<0 || y>=CARDS_IMAGE_HEIGHT)return;
    unsigned char *p=rgba+((size_t)y*CARDS_WIDTH+x)*4;
    for(unsigned i=0;i<3;i++)
        p[i]=(unsigned char)((((rgb>>(16-8*i))&255)*alpha+p[i]*(255-alpha)+127)/255);
    p[3]=(unsigned char)(alpha+(p[3]*(255-alpha)+127)/255);
}
static void rect(unsigned char *rgba,int x,int y,int w,int h,unsigned rgb,unsigned alpha) {
    for(int yy=y;yy<y+h;yy++)for(int xx=x;xx<x+w;xx++)pixel(rgba,xx,yy,rgb,alpha);
}
static float text_width(const char *text,float scale) {
    float width=0;
    while(*text) {
        uint32_t cp;
        if(!cards_utf8_next(&text,&cp))break;
        const route_glyph_t *g=glyph(cp);
        if(g)width+=g->advance*scale/64.f;
    }
    return width;
}
static void text(const cards_painter *painter,unsigned char *rgba,const char *s,
                 int x,int y,int limit,float scale,unsigned color) {
    int truncated=text_width(s,scale)>limit;
    float available=limit-(truncated?text_width("...",scale):0),advance=0;
    while(*s) {
        uint32_t cp;
        if(!cards_utf8_next(&s,&cp))break;
        const route_glyph_t *g=glyph(cp);
        if(!g)break;
        float next=advance+g->advance*scale/64.f;
        if(next>available)break;
        for(int dy=0;dy<(int)(g->h*scale+.5f);dy++)
            for(int dx=0;dx<(int)(g->w*scale+.5f);dx++) {
                float sx=dx/scale,sy=dy/scale,alpha=0;
                unsigned ix=(unsigned)sx,iy=(unsigned)sy;
                float fx=sx-ix,fy=sy-iy;
                for(unsigned yy=0;yy<2;yy++)for(unsigned xx=0;xx<2;xx++)
                    if(ix+xx<g->w && iy+yy<g->h)
                        alpha+=painter->font[(g->y+iy+yy)*ROUTE_FONT_WIDTH+g->x+ix+xx]*
                            (xx?fx:1-fx)*(yy?fy:1-fy);
                pixel(rgba,x+(int)(advance+g->left*scale)+dx,y+(int)(g->top*scale)+dy,color,(unsigned)alpha);
            }
        advance=next;
    }
    if(truncated)text(painter,rgba,"...",x+(int)(advance+.5f),y,limit-(int)(advance+.5f),scale,color);
}
unsigned cards_paint(const cards_painter *painter,const cards_request *request,
                     const cards_art *art,unsigned char rgba[CARDS_IMAGE_BYTES]) {
    memset(rgba,0,CARDS_IMAGE_BYTES);
    unsigned omitted=0;
    const char *labels[CARDS_TEXT_FIELDS];
    for(unsigned i=0;i<CARDS_TEXT_FIELDS;i++) {
        int visible=i<3?request->media:request->trip;
        labels[i]=visible && supported(request->text[i])?request->text[i]:"";
        if(visible && request->text[i][0] && !labels[i][0])omitted|=1u<<i;
    }
    if(request->media) {
        rect(rgba,0,0,CARDS_WIDTH,CARDS_HEIGHT,0x121519,CARDS_BODY_ALPHA);
        text(painter,rgba,"Now playing",12,7,216,1.15f,0xffffff);
        int x=12;
        if(art && art->pixels && art->width && art->height) {
            unsigned w=64,h=64;
            if(art->width>art->height)h=64*art->height/art->width;
            else w=64*art->width/art->height;
            if(!w)w=1;
            if(!h)h=1;
            for(unsigned yy=0;yy<h;yy++)for(unsigned xx=0;xx<w;xx++) {
                const unsigned char *p=art->pixels+((size_t)(yy*art->height/h)*art->width+xx*art->width/w)*4;
                pixel(rgba,12+(64-(int)w)/2+(int)xx,38+(64-(int)h)/2+(int)yy,
                      ((unsigned)p[0]<<16)|((unsigned)p[1]<<8)|p[2],p[3]);
            }
            x=86;
        }
        text(painter,rgba,labels[0],x,37,CARDS_WIDTH-12-x,1.f,0xffffff);
        text(painter,rgba,labels[1],x,60,CARDS_WIDTH-12-x,1.f,0xe0e3e6);
        text(painter,rgba,labels[2],x,88,CARDS_WIDTH-12-x,.9f,0xe0e3e6);
    }
    if(request->trip) {
        int y=CARDS_HEIGHT+CARDS_GAP;
        rect(rgba,0,y,CARDS_WIDTH,CARDS_HEIGHT,0x121519,CARDS_BODY_ALPHA);
        text(painter,rgba,"Trip",12,y+5,216,1.15f,0xffffff);
        char eta[CARDS_TEXT_BYTES+5];
        if(labels[3][0]) {
            snprintf(eta,sizeof(eta),"ETA %s",labels[3]);
            text(painter,rgba,eta,12,y+29,216,1.f,0xffffff);
        }
        text(painter,rgba,labels[4],12,y+50,216,1.f,0xe0e3e6);
        text(painter,rgba,labels[5],12,y+71,216,1.f,0xe0e3e6);
        if(request->progress>=0) {
            text(painter,rgba,"Estimated progress",12,y+92,216,.75f,0xe0e3e6);
            rect(rgba,12,y+110,216,3,0x687078,255);
            rect(rgba,12,y+110,216*request->progress/1000,3,0xd9e3ea,255);
        }
    }
    return omitted;
}
