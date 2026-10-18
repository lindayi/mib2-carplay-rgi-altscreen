/*
 * MU1316 integration policy. This boundary is intentionally not configurable:
 * Java selects URLs and owns zoom and physical DisplayManager placement.
 * Native owns AirPlay UI submission/completion, viewArea and stream recovery.
 */
#ifndef ALTSCREEN_LOCAL_INTEGRATION_H
#define ALTSCREEN_LOCAL_INTEGRATION_H
#define ALTSCREEN_JAVA_OWNS_PLACEMENT 1
#define ALTSCREEN_JAVA_OWNS_ZOOM 1
#define ALT_LOCAL_HMI_LEASE_MS 1500u
#ifndef ALTSCREEN_HMI_STATE
#define ALTSCREEN_HMI_STATE "/ramdisk/mmi-mirror-hmi.state"
#endif
#define ALTSCREEN_OUTPUT_WIDTH 1440
#define ALTSCREEN_OUTPUT_HEIGHT 455
#define ALTSCREEN_KEYFRAME_REQUEST "/ramdisk/altscreen-private111.keyframe-request"
#define ALTSCREEN_DISPLAY_STATE "/ramdisk/mmi-altscreen-displayable3.state"
#endif
