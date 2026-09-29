#ifndef CARPLAY_VC_PANEL_H
#define CARPLAY_VC_PANEL_H

#include <stdlib.h>
#include <stdint.h>

#define VC_PANEL_ROWS 6
#define VC_PANEL_DEPTH 4
#define VC_PANEL_WIDTH 420
#define VC_PANEL_HEIGHT 348

enum vc_row_kind { VC_ROW_VALUE, VC_ROW_TOGGLE, VC_ROW_LINK, VC_ROW_CHOICE };
typedef struct {
    char label[40];
    char value[32];
    enum vc_row_kind kind;
    int checked;
} vc_panel_row;
typedef struct {
    char title[40];
    char hint[64];
    unsigned count;
    vc_panel_row rows[VC_PANEL_ROWS];
} vc_panel_page;
typedef struct {
    vc_panel_page pages[VC_PANEL_DEPTH];
    unsigned focus[VC_PANEL_DEPTH];
    unsigned depth;
} vc_panel;
typedef struct { unsigned char *font; } vc_panel_renderer;

/* UI-only model: no wheel subscription, input-ownership claim or settings writes. */
int vc_panel_open(vc_panel *panel, const vc_panel_page *page);
int vc_panel_enter(vc_panel *panel, const vc_panel_page *page);
int vc_panel_move(vc_panel *panel, int steps);
int vc_panel_selected(const vc_panel *panel);
int vc_panel_activate(vc_panel *panel);
void vc_panel_back(vc_panel *panel);
void vc_panel_close(vc_panel *panel);
int vc_panel_renderer_init(vc_panel_renderer *renderer);
void vc_panel_renderer_destroy(vc_panel_renderer *renderer);
int vc_panel_paint(const vc_panel_renderer *renderer, const vc_panel *panel,
    unsigned char *rgba, size_t bytes, unsigned width, unsigned height, unsigned stride);

#endif
