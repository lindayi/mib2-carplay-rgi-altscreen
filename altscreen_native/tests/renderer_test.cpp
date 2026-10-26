#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <EGL/egl.h>
#include "../mirror/src/gl_renderer.h"
#include "../mirror/src/cluster_video_display.h"
#include "../mirror/src/placement_veil.h"
#include "../hook/private111_direct_shm.h"
#include <stddef.h>
#define ALTSCREEN_HMI_STATE "renderer-hmi.fixture"
#define main unused_mirror_main
#include "../mirror/src/main.cpp"
#undef main

static unsigned swap_attempts;
static bool fail_swap;
Private111DirectSource::Private111DirectSource(bool) : generation_(7), writer_pid_(42) {}
Private111DirectSource::~Private111DirectSource() {}
bool Private111DirectSource::current_session_active() { return true; }

static void hmi(const char *view, unsigned epoch) {
    struct timeval tv; assert(gettimeofday(&tv,NULL)==0);
    const unsigned long long expiry=(unsigned long long)tv.tv_sec*1000+tv.tv_usec/1000+1200;
    FILE *f=fopen(ALTSCREEN_HMI_STATE,"w"); assert(f);
    fprintf(f,"view=%s\nlayout_name=LayoutMIB2HighB9Sport\nsmall_stage_dx=-476\nsmall_stage_dy=0\n"
        "receiver_pid=42\nreceiver_epoch=%u\nexpiry_ms=%llu\n",view,epoch,expiry);
    assert(fclose(f)==0);
}

/* Replace QNX window management only; keep the real presenter, GLES and EGL. */
Mhi2qBackend::Mhi2qBackend() : ready_(false) {}
Mhi2qBackend::~Mhi2qBackend() {}
bool Mhi2qBackend::init(const Mhi2qBackendConfig &cfg) {
    cfg_ = cfg;
    ready_ = true;
    return true;
}
void Mhi2qBackend::shutdown() { ready_ = false; }
bool Mhi2qBackend::swap() {
    ++swap_attempts;
    return !fail_swap &&
        eglSwapBuffers(eglGetCurrentDisplay(), eglGetCurrentSurface(EGL_DRAW));
}

int main() {
    assert(offsetof(p111_h264_shm_t, write_pos) == 0x18);
    assert(offsetof(p111_h264_shm_t, write_seq) == 0x1c);
    assert(offsetof(p111_h264_shm_t, total_bytes) == 0x20);
    assert(offsetof(p111_h264_shm_t, packet_count) == 0x24);
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    assert(eglInitialize(display, NULL, NULL));
    const EGLint attrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config; EGLint count;
    assert(eglChooseConfig(display, attrs, &config, 1, &count) && count == 1);
    const EGLint surface_attrs[] = {EGL_WIDTH, 1440, EGL_HEIGHT, 455, EGL_NONE};
    const EGLint context_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, surface_attrs);
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attrs);
    assert(eglMakeCurrent(display, surface, surface, context));
    {
        GlRenderer renderer;
        assert(renderer.init(1440, 455));
        assert(renderer.set_destination_rect(0, 0, 1440, 542));
        unsigned char *pixels = (unsigned char *)calloc(1440 * 542, 4);
        assert(pixels);
        for (int y = 0; y < 542; ++y) for (int x = 0; x < 1440; ++x) {
            unsigned char *p = pixels + 4 * (y * 1440 + x);
            p[0] = y < 455 ? 255 : 0; p[1] = y < 455 ? 0 : 255; p[3] = 255;
        }
        assert(renderer.upload_rgba(pixels, 1440, 542));
        renderer.draw();
        unsigned char sample[4];
        glReadPixels(720, 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, sample);
        assert(sample[0] > 245 && sample[1] < 10 && sample[3] == 255);
        glReadPixels(2, 450, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, sample);
        assert(sample[0] > 245 && sample[1] < 10); /* no source rescale or X offset */
        free(pixels);
        unsigned char nv12[12];
        memset(nv12, 16, 8); memset(nv12 + 8, 128, 4);
        VideoFrame frame;
        frame.data = nv12; frame.width = 4; frame.height = 2;
        frame.stride = 4; frame.format = PIXEL_FORMAT_NV12;
        renderer.set_fullscreen_destination();
        assert(renderer.upload_frame(frame));
        renderer.draw();
        glReadPixels(720, 200, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, sample);
        assert(sample[0] < 3 && sample[1] < 3 && sample[2] < 3 && sample[3] == 255);
        memset(nv12, 235, 8);
        assert(renderer.upload_frame(frame));
        renderer.draw();
        glReadPixels(720, 200, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, sample);
        assert(sample[0] > 250 && sample[1] > 250 && sample[2] > 250);
        renderer.draw_faded(0.5f);
        glReadPixels(720,200,1,1,GL_RGBA,GL_UNSIGNED_BYTE,sample);
        assert(sample[0]>=125 && sample[0]<=130 && sample[3]==255);
        renderer.draw_faded(0.f);
        glReadPixels(720,200,1,1,GL_RGBA,GL_UNSIGNED_BYTE,sample);
        assert(sample[0]==0 && sample[1]==0 && sample[2]==0 && sample[3]==255);
        assert(glGetError() == GL_NO_ERROR);
    }
    {
        ClusterVideoDisplay presenter;
        Mhi2qBackendConfig cfg = {1440, 455, 3, false};
        assert(presenter.init(cfg));
        assert(!presenter.present_overlay_frame());
        unsigned char nv12[12];
        memset(nv12, 16, 8); memset(nv12 + 8, 128, 4);
        VideoFrame frame;
        frame.data = nv12; frame.width = 4; frame.height = 2;
        frame.stride = 4; frame.format = PIXEL_FORMAT_NV12;
        fail_swap = true;
        assert(!presenter.present_frame(frame));
        assert(swap_attempts == 1 && presenter.frame_count() == 0);
        assert(!presenter.first_frame_presented());
        fail_swap = false;
        assert(presenter.present_frame(frame));
        assert(swap_attempts == 2 && presenter.frame_count() == 1);
        assert(presenter.first_frame_presented());
        assert(presenter.present_frame(frame));
        assert(swap_attempts == 3 && presenter.frame_count() == 2);
        fail_swap = true;
        assert(!presenter.present_frame(frame));
        assert(swap_attempts == 4 && presenter.frame_count() == 2);
        assert(!presenter.present_overlay_frame());
        assert(presenter.frame_count() == 2 && presenter.overlay_frame_count() == 0);
        fail_swap = false;
        for(int i=0;i<4;i++)assert(presenter.present_overlay_frame());
        assert(swap_attempts == 9 && presenter.frame_count() == 2 && presenter.overlay_frame_count() == 4);
        unsigned char sample[4];
        glReadPixels(720,200,1,1,GL_RGBA,GL_UNSIGNED_BYTE,sample);
        assert(sample[0]<3 && sample[1]<3 && sample[2]<3 && sample[3]==255);
        PlacementVeil veil;
        const unsigned long source_before=presenter.frame_count();
        veil.begin(1000000);
        assert(veil.present(presenter,NULL,1000000) && veil.active && !veil.fading);
        assert(veil.present(presenter,&frame,1100000) && !veil.fading);
        assert(veil.present(presenter,&frame,1150000) && veil.fading);
        assert(veil.present(presenter,NULL,1225000) && veil.active);
        fail_swap=true;
        assert(!veil.present(presenter,NULL,1300000) && veil.active);
        fail_swap=false;
        assert(veil.present(presenter,NULL,1300000) && !veil.active);
        assert(presenter.frame_count()==source_before+2);
        veil.begin(2000000);
        assert(veil.present(presenter,NULL,2450000) && veil.fading);
        assert(veil.present(presenter,NULL,2600000) && !veil.active);
        assert(presenter.frame_count()==source_before+2); /* cached fade cannot hide a decoder stall */
        memset(nv12,235,8);
        assert(presenter.present_frame(frame));
        const unsigned long frames_before_gap=presenter.frame_count();
        Private111DirectSource source(false);
        hmi("FULL",3); reconcile_oem_map_placement(presenter,source,"fixture",false);
        hmi("SMALL",3); reconcile_oem_map_placement(presenter,source,"fixture",true);
        assert(g_veil.active);
        unsigned long long started=g_veil.start_us;
        assert(g_veil.present(presenter,NULL,started));
        assert(remove(ALTSCREEN_HMI_STATE)==0);
        reconcile_oem_map_placement(presenter,source,"gap",true);
        hmi("SMALL",3); reconcile_oem_map_placement(presenter,source,"return",true);
        assert(g_veil.present(presenter,NULL,started+450000));
        assert(g_veil.present(presenter,NULL,started+600000));
        glReadPixels(720,200,1,1,GL_RGBA,GL_UNSIGNED_BYTE,sample);
        assert(sample[0]>250 && !g_veil.needs_present());
        assert(presenter.frame_count()==frames_before_gap);
        hmi("FULL",3); reconcile_oem_map_placement(presenter,source,"next-view",true);
        assert(g_veil.present(presenter,NULL,g_veil.start_us));
        hmi("FULL",4); reconcile_oem_map_placement(presenter,source,"new-epoch",true);
        assert(g_veil.needs_present() && g_veil.restore_pending);
        fail_swap=true;
        assert(!g_veil.present(presenter,NULL,monotonic_us()) && g_veil.restore_pending);
        fail_swap=false;
        assert(g_veil.present(presenter,NULL,monotonic_us()) && !g_veil.needs_present());
        glReadPixels(720,200,1,1,GL_RGBA,GL_UNSIGNED_BYTE,sample);
        assert(sample[0]>250 && presenter.frame_count()==frames_before_gap);
        assert(remove(ALTSCREEN_HMI_STATE)==0);
    }
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context); eglDestroySurface(display, surface);
    eglTerminate(display);
    puts("NATIVE_1440X455_GLES=PASS");
    puts("NATIVE_PRESENTATION_AND_HEALTH_ABI=PASS");
}
