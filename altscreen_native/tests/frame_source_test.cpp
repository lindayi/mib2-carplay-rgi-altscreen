#include <assert.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
#include "../hook/private111_direct_shm.h"

static int local_shm_open(const char *name, int flags, mode_t mode) {
    return open(!strcmp(name, P111_H264_SHM_NAME) ? "h264.fixture" : "frame.fixture",
                flags, mode);
}
#define shm_open local_shm_open
#include "../mirror/src/private111_direct_source.cpp"
#undef shm_open

static void *make_fixture(const char *path, size_t bytes) {
    int fd = open(path, O_RDWR | O_CREAT | O_TRUNC, 0600);
    assert(fd >= 0 && ftruncate(fd, bytes) == 0);
    void *data = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    assert(data != MAP_FAILED);
    close(fd);
    return data;
}

int main() {
    {
        int fd = open("frame.fixture", O_RDWR | O_CREAT | O_TRUNC, 0600);
        assert(fd >= 0 && ftruncate(fd, 1) == 0);
        close(fd);
        Private111DirectSource incomplete(false);
        VideoFrame frame;
        assert(incomplete.init() && !incomplete.read_frame(&frame));
    }
    p111_h264_shm_t *h = (p111_h264_shm_t *)make_fixture("h264.fixture", sizeof(*h));
    p111_frame_shm_t *f = (p111_frame_shm_t *)make_fixture("frame.fixture", sizeof(*f));
    h->magic = P111_H264_SHM_MAGIC; h->version = P111_H264_SHM_VERSION;
    f->magic = P111_FRAME_SHM_MAGIC; f->version = P111_FRAME_SHM_VERSION;
    h->writer_pid = f->writer_pid = 17;
    h->generation = f->generation = 3;
    h->stream_cookie = f->stream_cookie = 42;
    h->active = f->active = 1;
    f->width = f->stride = 4; f->height = 2;
    f->format = P111_FRAME_FORMAT_NV12; f->frame_bytes = 12;
    f->sequence = 1; memset(f->data, 111, 12);
    {
        Private111DirectSource source(false);
        VideoFrame frame;
        assert(source.init() && source.read_frame(&frame));
        assert(frame.width == 4 && frame.height == 2 && frame.data[0] == 111);
        assert(!source.read_frame(&frame));
        ++f->sequence; h->active = 0;
        assert(!source.read_frame(&frame)); /* teardown cannot replay stale decoded data */
        h->active = 1; ++h->generation;
        assert(!source.read_frame(&frame)); /* reject mismatched receiver sessions */
        f->generation = h->generation;
        f->stride = 8;
        assert(!source.read_frame(&frame)); /* packed-tight ABI, not vendor padding */
        f->stride = 4; f->frame_bytes = 11;
        assert(!source.read_frame(&frame));
        f->frame_bytes = 12;
        assert(source.read_frame(&frame));
        ++f->sequence; f->current_slot = P111_FRAME_SLOTS;
        assert(!source.read_frame(&frame));
        f->current_slot = 0; ++f->writer_pid;
        assert(!source.read_frame(&frame));
    }
    munmap(h, sizeof(*h)); munmap(f, sizeof(*f));
    unlink("h264.fixture"); unlink("frame.fixture");
    puts("NATIVE_FRAME_SESSION=PASS");
}
