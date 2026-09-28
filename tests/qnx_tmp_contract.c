#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>

static int denied(const char *path) {
    const char *root=getenv("QNX_TMP_ROOT");
    if(!root)root="/tmp";
    size_t n=strlen(root);
    return !strncmp(path,root,n) && (path[n]=='/' || path[n]==0);
}
int rename(const char *from,const char *to) {
    typedef int (*fn)(const char *,const char *);
    fn real=(fn)dlsym(RTLD_NEXT,"rename");
    if(denied(from) || denied(to)){errno=ENOSYS;return -1;}
    return real(from,to);
}
