/*
 * MU1316 integration policy. This boundary is intentionally not configurable:
 * Java owns layout URLs, zoom, viewArea selection and DisplayManager placement.
 * The separate native hook owns only Type111 transport and decoding.
 */
#ifndef ALTSCREEN_LOCAL_INTEGRATION_H
#define ALTSCREEN_LOCAL_INTEGRATION_H
#define ALTSCREEN_JAVA_OWNS_UI 1
#define ALTSCREEN_JAVA_OWNS_PLACEMENT 1
#define ALTSCREEN_OUTPUT_WIDTH 1440
#define ALTSCREEN_OUTPUT_HEIGHT 455
#define ALTSCREEN_KEYFRAME_REQUEST "/ramdisk/altscreen-private111.keyframe-request"
#define ALTSCREEN_DISPLAY_STATE "/ramdisk/mmi-altscreen-displayable3.state"
#endif
