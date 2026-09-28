#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "framework/logging.h"

int __wrap_rename(const char *from,const char *to) {
    (void)from;(void)to;errno=ENOSYS;return -1;
}
int main(int argc,char **argv) {
    (void)argv;
    char path[]="/tmp/carplay-rotation-XXXXXX",archive[256];
    int fd=mkstemp(path);assert(fd>=0);
    char line[1024];memset(line,'x',sizeof(line));
    for(int i=0;i<100;i++)assert(write(fd,line,sizeof(line))==sizeof(line));
    close(fd);
    snprintf(archive,sizeof(archive),"%s.1",path);
    assert(rename(path,archive)<0 && errno==ENOSYS);
    if(argc>1)assert(mkdir(archive,0700)==0);
    log_config_t config=LOG_CONFIG_DEFAULT;
    config.log_path=path;config.max_size=4096;config.max_files=1;
    config.flush_immediate=false;
    assert(log_init(&config)==HOOK_OK);
    for(int i=0;i<256;i++) {
        log_write(LOG_LEVEL_WARN,"ROTATION","sequence=%d bounded archive",i);
        usleep(2000);
    }
    log_shutdown();
    struct stat st;
    if(argc>1) {
        FILE *file=fopen(path,"r");assert(file);
        assert(fgets(line,sizeof(line),file) && strstr(line,"LOG_ROTATION=ARCHIVE_FAILED"));
        fclose(file);assert(rmdir(archive)==0);
    } else {
        assert(stat(archive,&st)==0 && st.st_size<=4096 && st.st_size>0);
        unlink(archive);
    }
    assert(stat(path,&st)==0 && st.st_size<4096+64*1024);
    unlink(path);
    puts("Log rotation: unsupported QNX rename, repeated bounded archive and current file PASS");
    return 0;
}
