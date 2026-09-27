#include <EGL/egl.h>
#include <dlfcn.h>
#include <assert.h>
static int count,fail;
int mascot_swap_count(void) {return count;}
void mascot_swap_fail(int value) {fail=value;}
EGLBoolean eglSwapBuffers(EGLDisplay display,EGLSurface surface) {
    typedef EGLBoolean (*swap)(EGLDisplay,EGLSurface);
    swap next=(swap)dlsym(RTLD_NEXT,"eglSwapBuffers");assert(next);
    count++;
    return fail?EGL_FALSE:next(display,surface);
}
