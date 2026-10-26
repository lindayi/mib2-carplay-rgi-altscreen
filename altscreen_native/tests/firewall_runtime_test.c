#include "../hook/p1404_firewall.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>
#include <errno.h>
#include <stdarg.h>

static char last_log[1024];
void altscreen_log(const char *fmt, ...) {
    va_list args;
    va_start(args,fmt);
    vsnprintf(last_log,sizeof(last_log),fmt,args);
    va_end(args);
}

static char *read_file(const char *path) {
    FILE *f=fopen(path,"rb"); assert(f);
    assert(fseek(f,0,SEEK_END)==0);
    long length=ftell(f); assert(length>=0 && length<131072);
    rewind(f);
    char *data=malloc((size_t)length+1); assert(data);
    assert(fread(data,1,(size_t)length,f)==(size_t)length);
    data[length]=0; assert(fclose(f)==0);
    return data;
}
static void write_file(const char *path,const char *data) {
    FILE *f=fopen(path,"wb"); assert(f);
    assert(fwrite(data,1,strlen(data),f)==strlen(data));
    assert(fclose(f)==0);
}
static const char *original="scrub in all\npass out all queue (normal, ack)\nblock in quick on carplay0 all\n";
static const char *oem_reload="scrub in all\npass out quick all queue (normal, ack)\nblock in quick on carplay0 all\n";

int main(int argc,char **argv) {
    if(argc>1) {
        const char *rules=getenv("PF_TEST_RULES"), *domains=getenv("PF_TEST_DOMAINS");
        assert(rules && domains);
        if(argc==2 && !strcmp(argv[1],"-sr")) {
            if(getenv("PF_TEST_RACE")) {
                const char *count=getenv("PF_TEST_COUNT");
                char *value=read_file(count);
                if(!strcmp(value,"1")) {
                    const char *failure=getenv("PF_TEST_CAPTURE_FAILURE");
                    if(failure) {
                        free(value);
                        if(!strcmp(failure,"empty")) return 0;
                        fprintf(stderr,"injected capture helper failure\n");
                        return 7;
                    }
                    write_file(rules,oem_reload);
                }
                write_file(count,"1"); free(value);
            }
            char *data=read_file(rules);
            fputs(data,stdout); free(data); return 0;
        }
        if(argc==3 && !strcmp(argv[1],"-f")) {
            /* Model unrestricted loading: omitted domains are reset. */
            write_file(domains,"RESET");
            char *data=read_file(argv[2]); write_file(rules,data); free(data); return 0;
        }
        if(argc==4 && !strcmp(argv[1],"-R") && !strcmp(argv[2],"-f")) {
            char *data=read_file(argv[3]);
            assert(strstr(data,"scrub in all\n"));
            assert(strstr(data,"queue (normal, ack)\n"));
            write_file(rules,data); free(data); return 0;
        }
        return 2;
    }
    char directory[PATH_MAX],rules[PATH_MAX+32],domains[PATH_MAX+32],count[PATH_MAX+32];
    assert(getcwd(directory,sizeof(directory)));
    /* Child lookup must resolve this fixture, never a host/system pfctl. */
    assert(setenv("PATH",directory,1)==0);
    snprintf(rules,sizeof(rules),"%s/pf-rules.fixture",directory);
    snprintf(domains,sizeof(domains),"%s/pf-domains.fixture",directory);
    snprintf(count,sizeof(count),"%s/pf-count.fixture",directory);
    assert(setenv("PF_TEST_RULES",rules,1)==0 && setenv("PF_TEST_DOMAINS",domains,1)==0);
    const char *unchanged="NAT ALTQ OPTIONS TABLES ANCHORS";
    write_file(rules,original); write_file(domains,unchanged);
    struct p1404_pf_lease lease={0};
    assert(p1404_alt111_firewall_open_owned(61234,&lease));
    char *data=read_file(domains); assert(!strcmp(data,unchanged)); free(data);
    data=read_file(rules); assert(strstr(data,"port = 61234")); free(data);
    assert(p1404_alt111_firewall_close_owned(&lease));
    data=read_file(rules); assert(!strcmp(data,original)); free(data);
    data=read_file(domains); assert(!strcmp(data,unchanged)); free(data);
    write_file(count,"0");
    assert(setenv("PF_TEST_COUNT",count,1)==0 && setenv("PF_TEST_RACE","1",1)==0);
    lease.generation=0;
    assert(!p1404_alt111_firewall_open_owned(61235,&lease) && !lease.generation);
    data=read_file(rules); assert(!strcmp(data,oem_reload)); free(data);
    data=read_file(domains); assert(!strcmp(data,unchanged)); free(data);
    for(unsigned i=0;i<2;i++) {
        write_file(rules,original);
        write_file(count,"0");
        assert(setenv("PF_TEST_CAPTURE_FAILURE",i?"empty":"exit",1)==0);
        lease.generation=0;
        assert(!p1404_alt111_firewall_open_owned(61236+i,&lease) && !lease.generation);
        assert(errno==EIO);
        assert(strstr(last_log,i?"stage=read_capture":"stage=helper_exit"));
        assert(!strstr(last_log,"rules_changed_before_reload"));
        data=read_file(rules); assert(!strcmp(data,original)); free(data);
        data=read_file(domains); assert(!strcmp(data,unchanged)); free(data);
    }
    assert(unlink(rules)==0 && unlink(domains)==0 && unlink(count)==0);
    puts("PF_FILTER_DOMAIN_AND_RELOAD_FENCE=PASS");
    return 0;
}
