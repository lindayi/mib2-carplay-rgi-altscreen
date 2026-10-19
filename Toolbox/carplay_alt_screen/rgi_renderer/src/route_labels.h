#ifndef CR_ROUTE_LABELS_H
#define CR_ROUTE_LABELS_H
#include "protocol.h"
#include "visible_area.h"

#define CR_LABEL_DISTANCE_BYTES 12
#define CR_LABEL_ROAD_BYTES 32
typedef struct {
    char distance[CR_LABEL_DISTANCE_BYTES + 1];
    char road[CR_LABEL_ROAD_BYTES + 1];
} cr_route_labels_t;

int cr_route_labels_init(void);
void cr_route_labels_shutdown(void);
void cr_route_labels_clear(cr_route_labels_t *labels);
int cr_route_labels_receive(cr_route_labels_t *labels, const cr_cmd_t *cmd);
float cr_route_labels_height(const cr_route_labels_t *labels);
void cr_route_labels_draw(const cr_route_labels_t *labels, cr_rect_t visible, float alpha);
int cr_route_labels_configure(const cr_cmd_t *cmd);
unsigned cr_route_labels_options(void);
void cr_route_labels_clock(double now);
int cr_route_labels_animating(const cr_route_labels_t *labels, float width);
#endif
