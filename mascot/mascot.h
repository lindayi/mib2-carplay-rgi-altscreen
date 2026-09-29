#ifndef CARPLAY_MASCOT_H
#define CARPLAY_MASCOT_H
#include <stdint.h>
#include <stddef.h>
#include <GLES2/gl2.h>

#define MASCOT_COUNT 2
#define MASCOT_MAX_FRAMES 32
typedef struct {
    unsigned width, height, count, duration;
    unsigned delay[MASCOT_MAX_FRAMES];
    unsigned char *pixels;
} mascot_animation;
typedef struct {
    GLuint program, buffer, textures[MASCOT_COUNT][MASCOT_MAX_FRAMES];
    GLint rectangle, sampler;
    int initialized;
} mascot_graphics;
int mascot_load(const char *path, mascot_animation animations[MASCOT_COUNT]);
void mascot_free(mascot_animation animations[MASCOT_COUNT]);
int mascot_config(const char *text, uint64_t now_ms, unsigned pid);
unsigned mascot_frame(const mascot_animation *animation, uint64_t elapsed_ms);
int mascot_draw(mascot_graphics *graphics, const mascot_animation animations[MASCOT_COUNT],
                unsigned selected, uint64_t elapsed_ms);
int mascot_draw_image(mascot_graphics *graphics,const unsigned char *rgba,
                     unsigned width,unsigned height,int refresh);
void mascot_graphics_destroy(mascot_graphics *graphics);
#endif
