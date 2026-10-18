#include "../Toolbox/carplay_alt_screen/rgi_renderer/src/frame_clock.h"
#include "../Toolbox/carplay_alt_screen/rgi_renderer/common/log_stamp.h"
#include <assert.h>
#include <stdlib.h>
#include <errno.h>

int main(void) {
    struct timespec start={100,900000000},end={101,10000000};
    assert(frame_delay_ns(&start,&end,33333333L)==-76666667LL);
    end.tv_sec=104;end.tv_nsec=900000000;
    assert(frame_delay_ns(&start,&end,66666666L)==-3933333334LL);
    end=start;end.tv_nsec+=10000000;
    assert(frame_delay_ns(&start,&end,33333333L)==23333333LL);

    char path[]="/tmp/renderer-runtime-XXXXXX";
    int file=mkstemp(path),saved=dup(STDERR_FILENO);
    struct stat before,after;
    assert(file>=0 && saved>=0);
    assert(ftruncate(file,1048576)==0 && fstat(file,&before)==0);
    assert(dup2(file,STDERR_FILENO)>=0);
    assert(bound_private_output()==0 && fstat(file,&after)==0 && after.st_size==1048576);
    assert(fcntl(file,F_SETFL,O_APPEND)==0);
    assert(bound_private_output()==0 && fstat(file,&after)==0);
    assert(before.st_ino==after.st_ino && after.st_size>0 && after.st_size<128);
    assert(ftruncate(file,1048576)==0);
    int readonly=open(path,O_RDONLY|O_APPEND);
    assert(readonly>=0 && dup2(readonly,STDERR_FILENO)>=0);
    errno=0;
    assert(bound_private_output()==-1 && errno!=0);
    assert(fstat(file,&after)==0 && after.st_size==1048576 && after.st_ino==before.st_ino);
    close(readonly);
    int pipes[2];assert(pipe(pipes)==0 && dup2(pipes[1],STDERR_FILENO)>=0);
    assert(bound_private_output()==0);
    assert(dup2(saved,STDERR_FILENO)>=0);
    close(pipes[0]);close(pipes[1]);close(saved);close(file);unlink(path);
    puts("Renderer runtime: multi-second 64-bit pacing, same-inode cap, explicit truncate failure and non-owned output preserved PASS");
    return 0;
}
