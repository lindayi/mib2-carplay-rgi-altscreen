#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include "../maneuver_render/render.h"
#include "../maneuver_render/maneuver.h"
#include "../maneuver_render/route_labels.h"
#include "../maneuver_render/lane_panel.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static cr_scene_t *scene;
static int handles(void *ctx,const maneuver_state_t *m) {
    (void)ctx;(void)m;return cr_scene_route(scene) && !cr_scene_is_native(scene);
}
static void route(void *ctx,const maneuver_state_t *m,route_path_t *out) {
    (void)ctx;(void)m;*out=*cr_scene_route(scene);
}
static void paint(void *ctx,const maneuver_state_t *m,float x,float y,float c,float s) {
    (void)ctx;(void)m;cr_scene_paint(scene,x,y,c,s);
}
static float elevation(void *ctx,const maneuver_state_t *m) {
    (void)ctx;(void)m;return cr_scene_info(scene)->route_elevation;
}
static void save(const char *name) {
    unsigned char pixels[328*181*4];
    glReadPixels(0,0,328,181,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    char path[256];
    snprintf(path,sizeof(path),"/src/build/route-label-previews/%s.ppm",name);
    FILE *f=fopen(path,"wb");assert(f);
    fprintf(f,"P6\n328 181\n255\n");
    for(int y=180;y>=0;y--)for(int x=0;x<328;x++) {
        unsigned char *p=pixels+(y*328+x)*4;
        fwrite(p,1,3,f);
    }
    assert(!fclose(f));
}
int main(void) {
    EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
    assert(eglInitialize(display,NULL,NULL));
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_DEPTH_SIZE,16,EGL_NONE};
    EGLConfig config;EGLint count;
    assert(eglChooseConfig(display,attrs,&config,1,&count) && count);
    EGLint surface_attrs[]={EGL_WIDTH,328,EGL_HEIGHT,181,EGL_NONE};
    EGLSurface surface=eglCreatePbufferSurface(display,config,surface_attrs);
    EGLint context_attrs[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,context_attrs);
    assert(eglMakeCurrent(display,surface,surface,context));
    assert(!render_init(328,181));assert(!cr_route_labels_init());
    { extern float g_3d_offset_adjust; g_3d_offset_adjust=-.16f; }
    scene=cr_scene_create();assert(scene);
    maneuver_scene_provider_t provider={0};
    provider.handles=handles;provider.build_route=route;provider.paint_masks=paint;provider.elevation=elevation;
    cr_scene_configure_provider(&provider);maneuver_set_scene_provider(&provider);
    maneuver_state_t maneuver={0};
    maneuver.icon=ICON_TURN;maneuver.direction=1;maneuver.exit_angle=90;
    cr_lane_panel_t *panel=cr_lane_panel_create();assert(panel);
    cr_lane_guidance_t lanes={0};
    lanes.showing=1;lanes.complete=1;lanes.count=3;
    for(int i=0;i<3;i++) {
        lanes.lanes[i].position=i;lanes.lanes[i].status=i==2?2:0;
        lanes.lanes[i].primary=i==2?90:0;lanes.lanes[i].angle_count=1;
        lanes.lanes[i].angles[0]=lanes.lanes[i].primary;
    }
    const char *names[]={"popup","popup-lanes","in-tube-lanes","long-road","metric","distance-only",
        "uturn-lanes","roundabout-lanes","exit-lanes","left-turn-lanes"};
    const char *roads[]={"Main Street","Main Street","Main Street","Commonwealth Avenue Extension","Rue de l'Eglise","",
        "Main Street","Park Avenue","Exit 12","Broadway"};
    for(int test=0;test<10;test++) {
        maneuver.icon=test==6?ICON_UTURN:test==7?ICON_ROUNDABOUT:test==8?ICON_EXIT:ICON_TURN;
        maneuver.direction=test==6 || test==9?-1:1;
        maneuver.exit_angle=test==9?-90:90;
        maneuver.junction_angle_count=test==7?3:0;
        maneuver.junction_angles[0]=-90;maneuver.junction_angles[1]=0;maneuver.junction_angles[2]=90;
        cr_rect_t visible=test==2?(cr_rect_t){0,0,328,180}:(cr_rect_t){59,27,210,153};
        cr_cmd_t packet={CMD_ROUTE_LABELS,0,{0}};
        const char *distance=test==4?"1.2 km":"500 ft";
        packet.payload[0]=(unsigned char)strlen(distance);packet.payload[1]=(unsigned char)strlen(roads[test]);
        memcpy(packet.payload+2,distance,strlen(distance));memcpy(packet.payload+14,roads[test],strlen(roads[test]));
        cr_route_labels_t labels={{0},{0}};cr_route_labels_receive(&labels,&packet);
        float footer=cr_route_labels_height(&labels);
        lanes.showing=test==1 || test==2 || test>=6;
        cr_lane_panel_update(panel,&lanes,visible.w,0);
        cr_lane_panel_set_footer(panel,footer);
        render_set_visible_area((int)visible.x,(int)visible.y,(int)visible.w,(int)visible.h);
        render_set_perspective(1);render_set_global_alpha(1);maneuver_set_slide(1);
        cr_scene_input_t input={0};input.maneuver=maneuver;
        cr_scene_view_t view={0};view.compact=1;render_get_layout_matrix(view.projection);
        assert(cr_scene_prepare(scene,&input,&view));
        render_invalidate_masks();
        for(int frame=0;frame<120;frame++) {
            render_set_frame_step(1);
            float framing[3];cr_lane_panel_framing(panel,scene,NULL,framing);
            if(frame==119) {
                float matrix[16],bounds[4],head[4];
                render_build_framing_matrix(matrix,328.f/181,framing[2]);
                assert(cr_scene_project_bounds(scene,matrix,bounds,head));
                float bottom=180-footer-(lanes.showing?CR_LANE_PANEL_HEIGHT+CR_LANE_PANEL_FADE_Y:0)-3;
                printf("%s: framing %.2f,%.2f,%.2f; arrow head bottom %.2f, safe bottom %.2f\n",
                    names[test],framing[0],framing[1],framing[2],head[3]+framing[1],bottom);
                assert(head[3]+framing[1]<=bottom+1);
            }
            render_set_content_framing(framing[0],framing[1],framing[2]);
            maneuver_prepare_frame(&maneuver,NULL);
            render_begin_frame();maneuver_draw(&maneuver,NULL);
            cr_rect_t lane_area=visible;lane_area.h-=footer;
            cr_lane_panel_draw(panel,lane_area,10);
            cr_route_labels_draw(&labels,visible,1);
            render_end_frame();
        }
        assert(glGetError()==GL_NO_ERROR);
        save(names[test]);
    }
    cr_lane_panel_destroy(panel);cr_scene_destroy(scene);
    cr_route_labels_shutdown();render_shutdown();
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    eglDestroyContext(display,context);eglDestroySurface(display,surface);eglTerminate(display);
    puts("route_labels_preview: ten real GLES2 panel/lanes/label renders saved");
}
