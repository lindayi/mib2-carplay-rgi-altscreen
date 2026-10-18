#include "ui_session.h"
#include "p1404_lock_wait.h"
#include <stddef.h>
#include <string.h>

#define UI_SESSION_SLOTS 8
static struct {
    void *receiver;
    uint32_t epoch;
    int main_ready, started, channel_ready, closing;
} sessions[UI_SESSION_SLOTS];
static volatile unsigned guard;
static uint32_t next_epoch, newest_epoch;
static uint32_t published_epoch;

/* The bus timer never waits for a writer, reads OEM memory or performs I/O. */
uint32_t altscreen_ui_session_generation(void) {
    return __atomic_load_n(&published_epoch, __ATOMIC_ACQUIRE);
}
static void lock(void) {
    while (__sync_lock_test_and_set(&guard, 1u)) p1404_lock_wait_yield();
}
static void unlock(void) { __sync_lock_release(&guard); }
static int find(void *receiver) {
    int i;
    for (i = 0; i < UI_SESSION_SLOTS; ++i)
        if (sessions[i].receiver == receiver) return i;
    return -1;
}

uint32_t alt_ui_session_begin(void *receiver) {
    int i;
    uint32_t epoch = 0;
    if (!receiver) return 0;
    lock();
    i = find(receiver);
    if (i < 0) {
        for (i = 0; i < UI_SESSION_SLOTS; ++i)
            if (!sessions[i].receiver) break;
        if (i < UI_SESSION_SLOTS && next_epoch != UINT32_MAX) {
            sessions[i].receiver = receiver;
            sessions[i].epoch = ++next_epoch;
            newest_epoch = next_epoch;
            __atomic_store_n(&published_epoch, 0u, __ATOMIC_RELEASE);
        } else {
            i = -1; /* Never wrap an epoch or evict a potentially live owner. */
            newest_epoch = 0;
            __atomic_store_n(&published_epoch, 0u, __ATOMIC_RELEASE);
        }
    }
    if (i >= 0) epoch = sessions[i].epoch;
    unlock();
    return epoch;
}

void alt_ui_session_note(void *receiver, uint32_t epoch,
                         int main_ready, int started, int channel_ready) {
    int i;
    if (!receiver || !epoch) return;
    lock();
    i = find(receiver);
    if (i >= 0 && sessions[i].epoch == epoch && !sessions[i].closing) {
        if (main_ready) sessions[i].main_ready = 1;
        if (started) sessions[i].started = 1;
        sessions[i].channel_ready = channel_ready;
        if (epoch == newest_epoch) {
            const uint32_t ready = sessions[i].main_ready && sessions[i].started &&
                sessions[i].channel_ready ? epoch : 0u;
            __atomic_store_n(&published_epoch, ready, __ATOMIC_RELEASE);
        }
    }
    unlock();
}

void alt_ui_session_closing(void *receiver) {
    int i;
    if (!receiver) return;
    lock();
    i = find(receiver);
    /* Reserve a tombstone even if teardown overtakes START. An unknown old
     * teardown must not supersede another receiver's current publication. */
    if (i < 0) {
        for (i = 0; i < UI_SESSION_SLOTS; ++i)
            if (!sessions[i].receiver) break;
        if (i < UI_SESSION_SLOTS && next_epoch != UINT32_MAX) {
            sessions[i].receiver = receiver;
            sessions[i].epoch = ++next_epoch;
        } else i = -1;
    }
    if (i >= 0) {
        sessions[i].closing = 1;
        if (sessions[i].epoch ==
            __atomic_load_n(&published_epoch, __ATOMIC_RELAXED))
            __atomic_store_n(&published_epoch, 0u, __ATOMIC_RELEASE);
    }
    unlock();
}

void alt_ui_session_forget(void *receiver) {
    int i;
    if (!receiver) return;
    lock();
    i = find(receiver);
    if (i >= 0) {
        if (sessions[i].epoch ==
            __atomic_load_n(&published_epoch, __ATOMIC_RELAXED))
            __atomic_store_n(&published_epoch, 0u, __ATOMIC_RELEASE);
        memset(&sessions[i], 0, sizeof(sessions[i]));
    }
    unlock();
}
