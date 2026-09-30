#include "../mascot/mascot.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
int main(int argc,char **argv) {
    assert(argc==3);
    unsigned count=(unsigned)atoi(argv[2]);
    mascot_animation a[MASCOT_COUNT]={{0}};
    assert(mascot_load(argv[1],a));
    for(unsigned i=0;i<MASCOT_COUNT;i++) {
        if(i<count)assert(a[i].pixels && a[i].count==4 && a[i].faces_left==i%2 && a[i].duration==480);
        else assert(!a[i].count && !a[i].pixels);
    }
    mascot_free(a);
    puts("Generic atlas count, facing, timing, empty entries and cleanup PASS");
    return 0;
}
