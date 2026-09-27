#include "../maneuver_render/route_labels.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    cr_route_labels_t labels={{0},{0}};
    cr_cmd_t cmd={CMD_ROUTE_LABELS,0,{0}};
    assert(cr_route_labels_height(&labels)==0);
    cmd.payload[0]=6;cmd.payload[1]=11;
    memcpy(cmd.payload+2,"500 ft",6);memcpy(cmd.payload+14,"Main Street",11);
    assert(cr_route_labels_receive(&labels,&cmd)==1);
    assert(!strcmp(labels.distance,"500 ft") && !strcmp(labels.road,"Main Street"));
    assert(cr_route_labels_height(&labels)==42);
    assert(cr_route_labels_receive(&labels,&cmd)==0);
    cr_cmd_t options={CMD_DISPLAY_OPTIONS,0,{0,0,0,1}};
    assert(cr_route_labels_configure(&options)==1);
    assert(cr_route_labels_height(&labels)==0 && !strcmp(labels.distance,"500 ft"));
    options.payload[0]=1;
    cr_route_labels_configure(&options);
    assert(cr_route_labels_height(&labels)==25);
    options.payload[0]=15;options.payload[1]=1;
    cr_route_labels_configure(&options);
    assert(cr_route_labels_height(&labels)==50);
    options.payload[3]=2;
    assert(cr_route_labels_configure(&options)==0 && cr_route_labels_height(&labels)==50);
    options.payload[3]=1;options.payload[1]=0;options.payload[2]=1;
    cr_route_labels_configure(&options);
    strcpy(labels.road,"Commonwealth Avenue Extension");
    assert(cr_route_labels_animating(&labels,210));
    options.payload[2]=0;cr_route_labels_configure(&options);
    assert(!cr_route_labels_animating(&labels,210));
    cr_route_labels_receive(&labels,&cmd);
    cmd.payload[0]=0;
    assert(cr_route_labels_receive(&labels,&cmd)==1 && labels.distance[0]==0);
    assert(cr_route_labels_height(&labels)==21);
    cmd.payload[1]=33;
    cr_route_labels_receive(&labels,&cmd);
    assert(cr_route_labels_height(&labels)==0);
    cmd.payload[1]=2;cmd.payload[14]=0xc0;cmd.payload[15]=0xaf;
    cr_route_labels_receive(&labels,&cmd);
    assert(!labels.road[0]);
    cmd.payload[1]=3;cmd.payload[14]=0xe6;cmd.payload[15]=0x9d;cmd.payload[16]=0xb1;
    cr_route_labels_receive(&labels,&cmd);
    assert(!labels.road[0]);
    cmd.payload[0]=6;memcpy(cmd.payload+2,"500 ft",6);cmd.payload[1]=0;
    cr_route_labels_receive(&labels,&cmd);
    assert(!strcmp(labels.distance,"500 ft"));
    cr_route_labels_clear(&labels);
    assert(cr_route_labels_height(&labels)==0);
    puts("route_labels_test: atomic fields, bounds, invalid UTF-8, unsupported glyphs, clear PASS");
}
