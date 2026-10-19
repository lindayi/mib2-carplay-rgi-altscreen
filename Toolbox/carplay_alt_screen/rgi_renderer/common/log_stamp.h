/* Wall-clock "HH:MM:SS.mmm" prefix, the same format as the hook log and the
 * Java log, so renderer milestones line up with them in one startup timeline. */
#ifndef LOG_STAMP_H
#define LOG_STAMP_H
#include <time.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/stat.h>
#include <fcntl.h>

/* Only the launcher's append-mode private file is owned here; never pipes or TTYs. */
static inline int bound_private_output(void) {
    struct stat st;
    int flags;
    if(fstat(STDERR_FILENO,&st)<0)return -1;
    if(!S_ISREG(st.st_mode))return 0;
    flags=fcntl(STDERR_FILENO,F_GETFL);
    if(flags<0)return -1;
    if(!(flags&O_APPEND) || st.st_size<1048576)return 0;
    if(fflush(stderr)!=0 || ftruncate(STDERR_FILENO,0)!=0)return -1;
    return fputs("PRIVATE_LOG_RESET limit=1048576 mode=same_inode\n",stderr)<0?-1:0;
}

static inline const char *log_stamp(void) {
    static char buf[16];
    struct timespec ts;
    struct tm tm;
    clock_gettime(CLOCK_REALTIME, &ts);
    localtime_r(&ts.tv_sec, &tm);
    snprintf(buf, sizeof(buf), "%02d:%02d:%02d.%03d", tm.tm_hour, tm.tm_min,
             tm.tm_sec, (int)(ts.tv_nsec / 1000000));
    return buf;
}

/* The Toolbox/carplay_alt_screen/rgi_native/src/Java carplay_verbose marker.  Renderers are long-lived, so it is read
 * each time a periodic report is due (every few hundred frames), not once:
 * touch or remove it and the next report follows.  Startup lines and errors
 * are always logged. */
static inline int log_verbose_marker(void) {
    return access("/mnt/app/carplay_verbose", F_OK) == 0
        || access("/tmp/carplay_verbose", F_OK) == 0;
}
#endif
