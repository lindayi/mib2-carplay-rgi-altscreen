#ifndef VC_PANEL_RUNTIME_H
#define VC_PANEL_RUNTIME_H
#include "panel.h"
typedef struct {
    uint64_t epoch,expires;
    unsigned pid,revision,connection,focus;
    vc_panel_page page;
} vc_panel_request;
int vc_panel_decode(char *text,uint64_t now,unsigned pid,vc_panel_request *request);
void vc_overlay_poll(void);
typedef struct { uint64_t epoch; unsigned revision; int drawn; } vc_panel_frame;
void vc_overlay_draw(vc_panel_frame *frame);
void vc_overlay_presented(const vc_panel_frame *frame,int success);
void vc_overlay_context_lost(void *destroyed);
#endif
