#include <EGL/egl.h>
#include <dlfcn.h>
#include <assert.h>
static int count,fail;
static void (*before_swap)(void);
int mascot_swap_count(void) {return count;}
void mascot_swap_fail(int value) {fail=value;}
void mascot_swap_before(void (*callback)(void)) {before_swap=callback;}
EGLBoolean eglSwapBuffers(EGLDisplay display,EGLSurface surface) {
    typedef EGLBoolean (*swap)(EGLDisplay,EGLSurface);
    swap next=(swap)dlsym(RTLD_NEXT,"eglSwapBuffers");assert(next);
    count++;
    void (*callback)(void)=before_swap;
    before_swap=0;
    if(callback)callback();
    return fail?EGL_FALSE:next(display,surface);
}
