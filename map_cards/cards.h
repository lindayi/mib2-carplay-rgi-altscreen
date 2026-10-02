#ifndef CARPLAY_MAP_CARDS_H
#define CARPLAY_MAP_CARDS_H
#include <stdlib.h>
#include <stdint.h>

#define CARDS_MAX_SNAPSHOT 2048
#define CARDS_PROTOCOL_VERSION 3
#define CARDS_TEXT_BYTES 128
#define CARDS_TEXT_FIELDS 6
#define CARDS_CONTROL_LEASE_MS 4000
#define CARDS_WORKER_LEASE_MS 1000
#define CARDS_X 119
#define CARDS_TOP 70
#define CARDS_WIDTH 230
#define CARDS_HEIGHT 256
#define CARDS_SHADOW_PAD 6
#define CARDS_IMAGE_WIDTH (CARDS_WIDTH+2*CARDS_SHADOW_PAD)
#define CARDS_IMAGE_HEIGHT (CARDS_HEIGHT+2*CARDS_SHADOW_PAD)
#define CARDS_IMAGE_BYTES (CARDS_IMAGE_WIDTH*CARDS_IMAGE_HEIGHT*4)
#define CARDS_BODY_ALPHA 220
#define CARDS_SCROLL_PAUSE_MS 1800
#define CARDS_SCROLL_PIXELS_PER_SECOND 20
#define CARDS_CONTROL_PATH "/ramdisk/carplay_cards.control"
#define CARDS_ART_PATH "/var/app/icab/tmp/37/coverart.png"
#define SPEED_MAX_VALUE 399
#define SPEED_MOVING_LEASE_MS 2000u
#define SPEED_STOPPED_LEASE_MS 15000u
#define SPEED_WIDTH 138
#define SPEED_HEIGHT 72
#define SPEED_IMAGE_BYTES (SPEED_WIDTH*SPEED_HEIGHT*4)
#define SPEED_X_WIDE 920
#define SPEED_X_INSET 805
#define SPEED_Y 115

typedef struct {
    unsigned enabled,source,wide;
    int speed,limit,unit;
    uint64_t expires;
} cards_speed;

typedef struct {
    uint64_t expires,connection,track;
    unsigned pid,media,trip;
    int progress;
    uint32_t art_crc;
    char text[CARDS_TEXT_FIELDS][CARDS_TEXT_BYTES+1];
    cards_speed speed;
} cards_request;
typedef struct {
    unsigned char *pixels;
    unsigned width,height;
} cards_art;
typedef struct { unsigned char *font,*frame; } cards_painter;
typedef struct { int title,artist; } cards_scroll;

int cards_decode(const char *data,size_t size,uint64_t now,unsigned pid,cards_request *out);
int cards_utf8_next(const char **text,uint32_t *cp);
uint32_t cards_crc32(const unsigned char *data,size_t size);
const char *cards_art_load(const char *path,uint32_t crc,cards_art *art);
void cards_art_free(cards_art *art);
int cards_painter_init(cards_painter *painter);
void cards_painter_destroy(cards_painter *painter);
int cards_inside(int x,int y);
int cards_scroll_offset(int overflow,uint64_t elapsed);
cards_scroll cards_scroll_at(const cards_request *request,const cards_art *art,uint64_t elapsed);
/* Premultiplied RGBA; unsupported fields are omitted and returned as a bit mask. */
unsigned cards_paint(const cards_painter *painter,const cards_request *request,
                     const cards_art *art,cards_scroll scroll,unsigned char rgba[CARDS_IMAGE_BYTES]);
void cards_speed_paint(const cards_painter *painter,const cards_speed *speed,
                       unsigned char rgba[SPEED_IMAGE_BYTES]);

/* Poll only on the existing native control worker; draw never reads files or paints. */
void cards_overlay_poll(void);
void cards_overlay_draw(int menu_visible);
void cards_overlay_context_lost(void *destroyed);
#endif
