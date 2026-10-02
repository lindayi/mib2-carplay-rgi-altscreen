#include "cards.h"
#include "../maneuver_render/route_font_data.h"
#include <stdio.h>
#include <string.h>
#include <math.h>

enum { SHOULDER=70,HEADER_HEIGHT=44,FOOTER_TOP=200,
       CONTENT_LEFT=12,CONTENT_RIGHT=CARDS_WIDTH-12,CONTENT_WIDTH=CARDS_WIDTH-24 };
static void frame_paint(unsigned char *rgba);

int cards_painter_init(cards_painter *painter) {
    if(painter->font && painter->frame)return 1;
    size_t capacity=ROUTE_FONT_WIDTH*ROUTE_FONT_HEIGHT,pos=0;
    painter->font=malloc(capacity);
    if(!painter->font)return 0;
    for(size_t i=0;i<sizeof(route_font_rle);i+=2) {
        unsigned count=route_font_rle[i];
        if(count>capacity-pos)break;
        memset(painter->font+pos,route_font_rle[i+1],count);pos+=count;
    }
    if(pos==capacity) {
        painter->frame=malloc(CARDS_IMAGE_BYTES);
        if(painter->frame){frame_paint(painter->frame);return 1;}
    }
    cards_painter_destroy(painter);return 0;
}
void cards_painter_destroy(cards_painter *painter) {
    free(painter->font);free(painter->frame);painter->font=painter->frame=NULL;
}
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
    return x>=0 && x<CARDS_WIDTH && y>=0 && y<CARDS_HEIGHT &&
        (x>=SHOULDER || (2*y+1)*SHOULDER>=(2*SHOULDER-2*x-1)*HEADER_HEIGHT);
}
static unsigned coverage(int x,int y) {
    if(x<0 || x>=CARDS_WIDTH || y<0 || y>=CARDS_HEIGHT)return 0;
    unsigned count=0;
    for(int yy=1;yy<=3;yy+=2)for(int xx=1;xx<=3;xx+=2)
        if((4*y+yy)*SHOULDER>=(4*SHOULDER-4*x-xx)*HEADER_HEIGHT)count++;
    return (count*255+2)/4;
}
static void blend(unsigned char *p,unsigned rgb,unsigned alpha) {
    for(unsigned i=0;i<3;i++)
        p[i]=(unsigned char)((((rgb>>(16-8*i))&255)*alpha+p[i]*(255-alpha)+127)/255);
    p[3]=(unsigned char)(alpha+(p[3]*(255-alpha)+127)/255);
}
typedef struct { unsigned char *pixels; int width,height,pad,card; } canvas;
static void pixel(canvas *image,int x,int y,unsigned rgb,unsigned alpha) {
    if(image->card?!cards_inside(x,y):x<0 || y<0 || x>=image->width || y>=image->height)return;
    blend(image->pixels+((size_t)(y+image->pad)*image->width+x+image->pad)*4,rgb,alpha);
}
static void frame_paint(unsigned char *rgba) {
    static const unsigned kernel[]={1,6,15,20,15,6,1};
    memset(rgba,0,CARDS_IMAGE_BYTES);
    for(int iy=0;iy<CARDS_IMAGE_HEIGHT;iy++)for(int ix=0;ix<CARDS_IMAGE_WIDTH;ix++) {
        int x=ix-CARDS_SHADOW_PAD,y=iy-CARDS_SHADOW_PAD;
        unsigned char *p=rgba+((size_t)iy*CARDS_IMAGE_WIDTH+ix)*4;
        unsigned edge=coverage(x,y);
        if(edge<255) {
            unsigned shadow=0;
            for(int yy=-3;yy<=3;yy++)for(int xx=-3;xx<=3;xx++)
                shadow+=kernel[xx+3]*kernel[yy+3]*coverage(x-1-xx,y-2-yy);
            p[3]=(unsigned char)((shadow*72+522240)/(4096*255));
        }
        if(!edge)continue;
        unsigned rgb=0x15181a,alpha=CARDS_BODY_ALPHA;
        if(y<HEADER_HEIGHT) {
            unsigned shade=49-(unsigned)y*17/(HEADER_HEIGHT-1);
            rgb=(shade<<16)|((shade-1)<<8)|(shade-6);alpha=255;
        } else if(y>=FOOTER_TOP){rgb=0;alpha=255;}
        float upper=x<SHOULDER?
            ((y+.5f)*SHOULDER+(x+.5f)*HEADER_HEIGHT-SHOULDER*HEADER_HEIGHT)/
                sqrtf((float)(SHOULDER*SHOULDER+HEADER_HEIGHT*HEADER_HEIGHT)):y+.5f;
        float light=fminf(x+.5f,upper),dark=fminf(CARDS_WIDTH-x-.5f,CARDS_HEIGHT-y-.5f);
        if(light<4) {
            rgb=light<1?0x53554e:light<2?0xa4a595:light<3?0x41483f:0x080b0a;
            alpha=255;
        }
        if(dark<4) {
            rgb=dark<1?0x31332e:dark<2?0x484b43:dark<3?0x2b2e29:0x070908;
            alpha=255;
        }
        if(x>=4 && x<CARDS_WIDTH-4) {
            int rule=y>=FOOTER_TOP?y-FOOTER_TOP:y-HEADER_HEIGHT;
            if(rule>=0 && rule<4) {
                rgb=rule<2?0x060807:rule==2?0x686c61:0x20231f;alpha=255;
            }
        }
        blend(p,rgb,(alpha*edge+127)/255);
    }
}
static void rect(canvas *rgba,int x,int y,int w,int h,unsigned rgb,unsigned alpha) {
    for(int yy=y;yy<y+h;yy++)for(int xx=x;xx<x+w;xx++)pixel(rgba,xx,yy,rgb,alpha);
}
typedef struct { int x,y,w,h; } text_area;
typedef struct { text_area title,artist; float title_scale,artist_scale; } media_layout;
static media_layout media_areas(const cards_request *request,const cards_art *art) {
    int has_art=art && art->pixels && art->width && art->height;
    int x=request->trip && has_art?66:12;
    media_layout layout={
        {x,request->trip?53:has_art?128:80,CONTENT_RIGHT-x,28},
        {x,request->trip?81:has_art?157:119,CONTENT_RIGHT-x,27},
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
static void text(const cards_painter *painter,canvas *rgba,const char *s,
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
static void header(const cards_painter *painter,canvas *rgba,const char *label) {
    int left=CARDS_WIDTH,right=0,top=HEADER_HEIGHT,bottom=0;
    float advance=-text_left(label,1.f);
    const char *s=label;
    while(*s) {
        uint32_t cp;
        if(!cards_utf8_next(&s,&cp))break;
        const route_glyph_t *g=glyph(cp);
        if(!g)break;
        if(g->w && g->h) {
            int x=(int)(advance+g->left);
            if(x<left)left=x;
            if(x+g->w>right)right=x+g->w;
            if(g->top<top)top=g->top;
            if(g->top+g->h>bottom)bottom=g->top+g->h;
        }
        advance+=g->advance/64.f;
    }
    text_area area={SHOULDER+(CARDS_WIDTH-SHOULDER-(right-left))/2-left,
        (HEADER_HEIGHT-(bottom-top))/2-top,text_width(label,1.f),HEADER_HEIGHT};
    text(painter,rgba,label,area,1.f,0xffffff,0,0);
}
static unsigned paint_cards(const cards_painter *painter,const cards_request *request,
                            const cards_art *art,cards_scroll scroll,canvas *rgba) {
    memset(rgba->pixels,0,CARDS_IMAGE_BYTES);
    unsigned omitted=0;
    const char *labels[CARDS_TEXT_FIELDS];
    for(unsigned i=0;i<CARDS_TEXT_FIELDS;i++) {
        int visible=i<3?request->media:request->trip;
        labels[i]=visible && supported(request->text[i])?request->text[i]:"";
        if(visible && request->text[i][0] && !labels[i][0])omitted|=1u<<i;
    }
    if(!request->media && !request->trip)return 0;
    memcpy(rgba->pixels,painter->frame,CARDS_IMAGE_BYTES);
    const char *heading=request->media?
        (!strcmp(labels[2],"Paused")?"Paused":!strcmp(labels[2],"Stopped")?"Stopped":
         !strcmp(labels[2],"Playing")?"Now playing":"Media"):"Trip";
    header(painter,rgba,heading);
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
            rect(rgba,CONTENT_LEFT,107,CONTENT_WIDTH,1,0x687078,180);
            text(painter,rgba,"Trip",(text_area){12,111,50,18},.85f,0xffffff,0,1);
        }
        int time_width=text_width(labels[4],.95f),distance_width=text_width(labels[5],.95f);
        int stacked=request->media && time_width+distance_width+12>CONTENT_WIDTH;
        char eta[CARDS_TEXT_BYTES+5];
        if(labels[3][0]) {
            snprintf(eta,sizeof(eta),"ETA %s",labels[3]);
            float scale=request->media?1.f:1.3f;
            if(text_width(eta,scale)>CONTENT_WIDTH)scale=1.f;
            text(painter,rgba,eta,(text_area){CONTENT_LEFT,request->media?(stacked?126:130):65,
                 CONTENT_WIDTH,stacked?20:29},scale,0xffffff,0,1);
        }
        if(request->media) {
            if(!stacked) {
                text(painter,rgba,labels[4],(text_area){12,153,time_width,24},.95f,0xe0e3e6,0,0);
                text(painter,rgba,labels[5],(text_area){CONTENT_RIGHT-distance_width,153,distance_width,24},.95f,0xe0e3e6,0,0);
            } else {
                text(painter,rgba,labels[4],(text_area){CONTENT_LEFT,146,CONTENT_WIDTH,15},.8f,0xe0e3e6,0,1);
                text(painter,rgba,labels[5],(text_area){CONTENT_LEFT,161,CONTENT_WIDTH,15},.8f,0xe0e3e6,0,1);
            }
        } else {
            text(painter,rgba,labels[4],(text_area){CONTENT_LEFT,105,CONTENT_WIDTH,27},1.15f,0xe0e3e6,0,1);
            text(painter,rgba,labels[5],(text_area){CONTENT_LEFT,137,CONTENT_WIDTH,27},1.15f,0xe0e3e6,0,1);
        }
        if(request->progress>=0) {
            text(painter,rgba,"Estimated progress",(text_area){CONTENT_LEFT,176,CONTENT_WIDTH,16},
                 .75f,0xaab0aa,0,1);
            rect(rgba,CONTENT_LEFT,194,CONTENT_WIDTH,3,0x687078,255);
            rect(rgba,CONTENT_LEFT,194,CONTENT_WIDTH*request->progress/1000,3,0xd9e3ea,255);
        }
    }
    return omitted;
}
unsigned cards_paint(const cards_painter *painter,const cards_request *request,
                     const cards_art *art,cards_scroll scroll,unsigned char rgba[CARDS_IMAGE_BYTES]) {
    canvas image={rgba,CARDS_IMAGE_WIDTH,CARDS_IMAGE_HEIGHT,CARDS_SHADOW_PAD,1};
    return paint_cards(painter,request,art,scroll,&image);
}
static void rounded(canvas *image,int x,int y,int w,int h,int radius,unsigned color) {
    for(int yy=0;yy<h;yy++)for(int xx=0;xx<w;xx++) {
        unsigned covered=0;
        for(int sy=1;sy<=3;sy+=2)for(int sx=1;sx<=3;sx+=2) {
            float px=xx+sx*.25f,py=yy+sy*.25f;
            float dx=fmaxf(fmaxf(radius-px,px-(w-radius)),0);
            float dy=fmaxf(fmaxf(radius-py,py-(h-radius)),0);
            if(dx*dx+dy*dy<=radius*radius)covered++;
        }
        pixel(image,x+xx,y+yy,color,(covered*255+2)/4);
    }
}
static void centered(const cards_painter *painter,canvas *image,const char *label,
                     text_area box,float scale,unsigned color) {
    int width=text_width(label,scale);
    if(width>box.w){scale*=box.w/(float)width;width=text_width(label,scale);}
    float top=1000,bottom=0;
    const char *p=label;
    while(*p) {
        uint32_t cp;
        if(!cards_utf8_next(&p,&cp))return;
        const route_glyph_t *g=glyph(cp);
        if(g && g->h){top=fminf(top,g->top*scale);bottom=fmaxf(bottom,(g->top+g->h)*scale);}
    }
    if(top>bottom)return;
    text_area area={box.x+(box.w-width)/2,box.y+(int)((box.h-bottom+top)/2-top),width,box.h+(int)top};
    text(painter,image,label,area,scale,color,0,0);
}
void cards_speed_paint(const cards_painter *painter,const cards_speed *speed,
                       unsigned char rgba[SPEED_IMAGE_BYTES]) {
    memset(rgba,0,SPEED_IMAGE_BYTES);
    if(!speed->enabled)return;
    canvas image={rgba,SPEED_WIDTH,SPEED_HEIGHT,0,0};
    rounded(&image,0,0,138,72,9,0x65696c);
    rounded(&image,1,1,136,70,8,0x24292d);
    rounded(&image,79,4,55,64,5,0xf2f1e9);
    rounded(&image,81,6,51,60,3,0x25282b);
    rounded(&image,82,7,49,58,2,0xf2f1e9);
    char number[12],limit[12];
    if(speed->speed>=0)snprintf(number,sizeof(number),"%d",speed->speed);else strcpy(number,"--");
    if(speed->limit>0)snprintf(limit,sizeof(limit),"%d",speed->limit);else strcpy(limit,"--");
    unsigned color=speed->speed>=0 && speed->limit>0 && speed->speed>speed->limit?0xff6262:0xffffff;
    centered(painter,&image,number,(text_area){5,8,68,40},1.9f,color);
    centered(painter,&image,speed->unit==0?"km/h":speed->unit==1?"mph":"",
             (text_area){5,51,68,16},.75f,0xc7cdd1);
    centered(painter,&image,"LIMIT",(text_area){85,10,43,12},.6f,0x25282b);
    centered(painter,&image,limit,(text_area){85,24,43,29},1.6f,0x181b1d);
    centered(painter,&image,speed->source==2?"CAM":speed->source==1?"MAP":"",
             (text_area){85,55,43,9},.45f,0x25282b);
}
