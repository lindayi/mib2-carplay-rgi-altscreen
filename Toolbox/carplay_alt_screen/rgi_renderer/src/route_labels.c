#include "route_labels.h"
#include "render.h"
#include "gl_compat.h"
#include "route_font_data.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

static GLuint font_texture;
static unsigned options=15;
static int large_text,scroll_road;
static double clock_now,road_epoch;
static char scrolling_text[CR_LABEL_ROAD_BYTES+1];

int cr_route_labels_configure(const cr_cmd_t *cmd) {
    if(cmd->payload[3]!=1 || (cmd->payload[0]&~15) || cmd->payload[1]>1 || cmd->payload[2]>1) {
        fprintf(stderr,"route labels: invalid display options; keeping previous settings\n");
        return 0;
    }
    if(options==cmd->payload[0] && large_text==cmd->payload[1] && scroll_road==cmd->payload[2])return 0;
    options=cmd->payload[0];large_text=cmd->payload[1];scroll_road=cmd->payload[2];
    scrolling_text[0]=0;
    return 1;
}
unsigned cr_route_labels_options(void){return options;}
void cr_route_labels_clock(double now){clock_now=now;}

static unsigned codepoint(const unsigned char **cursor, const unsigned char *end) {
    if (*cursor >= end) return 0;
    unsigned first = *(*cursor)++, cp = first, extra = 0, minimum = 0;
    if (first >= 0xc2 && first <= 0xdf) { cp &= 31; extra = 1; minimum = 128; }
    else if (first >= 0xe0 && first <= 0xef) { cp &= 15; extra = 2; minimum = 2048; }
    else if (first >= 0xf0 && first <= 0xf4) { cp &= 7; extra = 3; minimum = 65536; }
    else if (first >= 128) return 0;
    while (extra--) {
        if (*cursor >= end || (**cursor & 0xc0) != 0x80) return 0;
        cp = (cp << 6) | (*(*cursor)++ & 63);
    }
    return cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) ? 0 : cp;
}

static const route_glyph_t *glyph(unsigned cp) {
    size_t lo = 0, hi = sizeof(route_glyphs) / sizeof(route_glyphs[0]);
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        if (route_glyphs[mid].cp < cp) lo = mid + 1; else hi = mid;
    }
    return lo < sizeof(route_glyphs) / sizeof(route_glyphs[0]) && route_glyphs[lo].cp == cp
        ? &route_glyphs[lo] : NULL;
}

static int validate_text(const char *text, size_t length) {
    const unsigned char *p = (const unsigned char *)text, *end = p + length;
    int covered = 1;
    while (p < end) {
        unsigned cp = codepoint(&p, end);
        if (cp < 32 || (cp >= 127 && cp < 160)) return -1;
        if (!glyph(cp)) covered = 0;
    }
    return covered;
}

void cr_route_labels_clear(cr_route_labels_t *labels) {
    memset(labels,0,sizeof(*labels));scrolling_text[0]=0;
}

int cr_route_labels_receive(cr_route_labels_t *labels, const cr_cmd_t *cmd) {
    cr_route_labels_t next = {{0}, {0}};
    unsigned d = cmd->payload[0], r = cmd->payload[1];
    if (d > CR_LABEL_DISTANCE_BYTES || r > CR_LABEL_ROAD_BYTES) goto invalid;
    memcpy(next.distance, cmd->payload + 2, d);
    memcpy(next.road, cmd->payload + 14, r);
    if (validate_text(next.distance, d) != 1 || validate_text(next.road, r) < 0) goto invalid;
    if (!validate_text(next.road, r)) {
        static char warned[CR_LABEL_ROAD_BYTES + 1];
        if (strcmp(warned, next.road)) {
            fprintf(stderr, "route labels: unsupported road glyphs; retaining native route text: %s\n", next.road);
            memcpy(warned, next.road, sizeof(warned));
        }
        next.road[0] = 0;
    }
    if (!memcmp(labels, &next, sizeof(next))) return 0;
    *labels = next;
    return 1;
invalid:
    fprintf(stderr, "route labels: malformed text packet; clearing labels\n");
    cr_route_labels_clear(labels);
    return 1;
}

int cr_route_labels_init(void) {
    size_t size = ROUTE_FONT_WIDTH * ROUTE_FONT_HEIGHT, pos = 0;
    unsigned char *pixels = malloc(size);
    if (!pixels) { fprintf(stderr, "route labels: font allocation failed\n"); return -1; }
    for (size_t i = 0; i < sizeof(route_font_rle); i += 2) {
        unsigned count = route_font_rle[i];
        if (pos + count > size) { free(pixels); return -1; }
        memset(pixels + pos, route_font_rle[i + 1], count);
        pos += count;
    }
    if (pos != size) { free(pixels); return -1; }
    glGenTextures(1, &font_texture);
    glBindTexture(GL_TEXTURE_2D, font_texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, ROUTE_FONT_WIDTH, ROUTE_FONT_HEIGHT,
                 0, GL_ALPHA, GL_UNSIGNED_BYTE, pixels);
    free(pixels);
    if (!font_texture || glGetError() != GL_NO_ERROR) {
        fprintf(stderr, "route labels: font texture upload failed\n");
        cr_route_labels_shutdown();
        return -1;
    }
    return 0;
}

void cr_route_labels_shutdown(void) {
    if (font_texture) glDeleteTextures(1, &font_texture);
    font_texture = 0;
}

float cr_route_labels_height(const cr_route_labels_t *labels) {
    int distance=(options&1) && labels->distance[0], road=(options&2) && labels->road[0];
    if (!distance && !road) return 0;
    return (distance ? (large_text?26.f:21.f) : 0) + (road ? (large_text?20.f:17.f) : 0) + 4.f;
}

int cr_route_labels_animating(const cr_route_labels_t *labels,float width) {
    if(!scroll_road || !(options&2) || !labels->road[0])return 0;
    const unsigned char *p=(const unsigned char *)labels->road,*end=p+strlen(labels->road);
    float text_width=0,scale=large_text?1.f:.875f;
    while(p<end) {
        const route_glyph_t *g=glyph(codepoint(&p,end));
        if(!g)return 0;
        text_width+=g->advance*scale/64;
    }
    return text_width>width-12;
}
static void line(const char *text, cr_rect_t visible, float top, float scale, float shade, float alpha,int scrolling) {
    const route_glyph_t *row[CR_LABEL_ROAD_BYTES + 1];
    const unsigned char *p = (const unsigned char *)text, *end = p + strlen(text);
    int count = 0;
    float width = 0, max_width = visible.w - 12;
    while (p < end && count < CR_LABEL_ROAD_BYTES) {
        const route_glyph_t *g = glyph(codepoint(&p, end));
        if (!g) return;
        row[count++] = g;
        width += g->advance * scale / 64;
    }
    float offset=0;
    if(scrolling && width>max_width) {
        double travel=(width-max_width)/18.f;
        double phase=fmod(fmax(0,clock_now-road_epoch),4+2*travel);
        offset=phase<2?0:phase<2+travel?(float)((phase-2)*18):
            phase<4+travel?width-max_width:(float)((4+2*travel-phase)*18);
    } else if (width > max_width) {
        const route_glyph_t *dots = glyph(0x2026);
        while (count && width + dots->advance * scale / 64 > max_width)
            width -= row[--count]->advance * scale / 64;
        row[count++] = dots;
        width += dots->advance * scale / 64;
    }
    float vertices[(CR_LABEL_ROAD_BYTES + 1) * 6 * 4];
    float x = scrolling && width>max_width?visible.x+6-offset:visible.x+(visible.w-width)*.5f;
    int n = 0;
    for (int i = 0; i < count; ++i) {
        const route_glyph_t *g = row[i];
        float left = x + g->left * scale, y = top + g->top * scale;
        float right = left + g->w * scale, bottom = y + g->h * scale;
        float u = (float)g->x / ROUTE_FONT_WIDTH, v = (float)g->y / ROUTE_FONT_HEIGHT;
        float u1 = (float)(g->x + g->w) / ROUTE_FONT_WIDTH, v1 = (float)(g->y + g->h) / ROUTE_FONT_HEIGHT;
        float quad[] = {left,y,u,v, right,y,u1,v, right,bottom,u1,v1,
                        left,y,u,v, right,bottom,u1,v1, left,bottom,u,v1};
        memcpy(vertices + n, quad, sizeof(quad)); n += 24;
        x += g->advance * scale / 64;
    }
    render_overlay_texture(font_texture, vertices, n / 4, shade, alpha);
}

void cr_route_labels_draw(const cr_route_labels_t *labels, cr_rect_t visible, float alpha) {
    float height = cr_route_labels_height(labels);
    if (!height || !font_texture || visible.h <= height || alpha <= 0) return;
    float top = visible.y + visible.h - height;
    render_begin_overlay(visible);
    cr_rect_t footer = {visible.x, top, visible.w, height};
    render_overlay_cutout(footer, 0, 3, alpha);
    if ((options&1) && labels->distance[0]) {
        line(labels->distance,visible,top+1,large_text?1.375f:1.125f,1,alpha,0);
        top+=large_text?26:21;
    }
    if ((options&2) && labels->road[0]) {
        if(strcmp(scrolling_text,labels->road)){strcpy(scrolling_text,labels->road);road_epoch=clock_now;}
        cr_rect_t text_clip={visible.x+6,top,visible.w-12,visible.y+visible.h-top};
        render_begin_overlay(text_clip);
        line(labels->road,visible,top+1,large_text?1.f:.875f,.9f,alpha,scroll_road);
    }
    render_end_overlay();
}
