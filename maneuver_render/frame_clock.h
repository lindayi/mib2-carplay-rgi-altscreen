#ifndef CARPLAY_FRAME_CLOCK_H
#define CARPLAY_FRAME_CLOCK_H
#include <time.h>

static inline long long frame_delay_ns(const struct timespec *start,
                                      const struct timespec *end, long period) {
    long long elapsed=((long long)end->tv_sec-(long long)start->tv_sec)*1000000000LL
        +end->tv_nsec-start->tv_nsec;
    return (long long)period-elapsed;
}
#endif
