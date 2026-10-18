/* One receiver UI queue, using upstream leases, retained receiver and tracked
 * completion. Metadata locks never span OEM I/O. Included by fullchain. */
#ifndef P1404_BOOTSTRAP_H
#define P1404_BOOTSTRAP_H
#include <time.h>
#ifndef ALT_BOOTSTRAP_ACK_POLLS
#define ALT_BOOTSTRAP_ACK_POLLS 40u
#endif
#ifndef ALT_BOOTSTRAP_POLL_US
#define ALT_BOOTSTRAP_POLL_US 100000u
#endif
#define ALT_BOOTSTRAP_ATTEMPTS 3u
static struct {
    void *receiver;
    int main_ready, worker, pending, failed, show_ack, stopped, stop_sent;
    uint32_t token, request, revision, attempted_revision, accepted_revision;
    char url[512], inflight_url[512];
} bootstrap[8];
static volatile unsigned bootstrap_guard;
static volatile unsigned oem_map_ready;
static uint32_t bootstrap_token;
struct bootstrap_job { void *receiver; unsigned slot; uint32_t token; };

int alt_oem_map_startup_ready(void) {
    return __sync_fetch_and_add(&oem_map_ready, 0u) != 0u;
}
static void bootstrap_lock(void) {
    while (__sync_lock_test_and_set(&bootstrap_guard, 1u)) p1404_lock_wait_yield();
}
static void bootstrap_unlock(void) { __sync_lock_release(&bootstrap_guard); }
static uint32_t bootstrap_identity_locked(void) {
    if (bootstrap_token == UINT32_MAX) return 0;
    return ++bootstrap_token;
}
static void bootstrap_forget(void *receiver) {
    unsigned i;
    bootstrap_lock();
    for (i = 0; i < 8; ++i)
        if (bootstrap[i].receiver == receiver) memset(&bootstrap[i], 0, sizeof(bootstrap[i]));
    bootstrap_unlock();
}

int altscreen_queue_ui(const char *url, uint32_t epoch) {
    unsigned i;
    size_t n, base_len;
    int profile = alt_local_phone_profile();
    const char *base = profile == 1 ? "maps:/car/instrumentcluster" :
                                     "maps:/car/instrumentcluster/map";
    int result = -1;
    if (!url || !epoch || profile < 0 ||
        epoch != altscreen_ui_session_generation()) return -1;
    for (n = 0; n < sizeof(bootstrap[0].url) && url[n]; ++n) {}
    base_len = strlen(base);
    if (n < base_len || n >= sizeof(bootstrap[0].url) ||
        strncmp(url, base, base_len) ||
        (url[base_len] && url[base_len] != '?')) return -1;
    for (i = 0; i < n; ++i)
        if ((unsigned char)url[i] <= 32u || (unsigned char)url[i] >= 127u) return -1;
    bootstrap_lock();
    for (i = 0; i < 8; ++i) {
        if (!bootstrap[i].receiver || bootstrap[i].stopped ||
            alt_ui_session_epoch(bootstrap[i].receiver) != epoch ||
            altscreen_ui_session_generation() != epoch) continue;
        /* Pending identical startup/route requests coalesce. An intentional
         * later same-URL request remains a real reapply, never a timer resend. */
        if (bootstrap[i].revision != bootstrap[i].attempted_revision &&
            !strcmp(bootstrap[i].url, url)) { result = 0; break; }
        if (bootstrap[i].pending && !strcmp(bootstrap[i].inflight_url, url) &&
            !strcmp(bootstrap[i].url, url)) {
            result = 0; break;
        }
        if (bootstrap[i].revision == UINT32_MAX) break;
        memcpy(bootstrap[i].url, url, n + 1);
        ++bootstrap[i].revision;
        result = 0;
        break;
    }
    bootstrap_unlock();
    return result;
}

uint32_t alt_ui_accepted_revision(void *receiver) {
    unsigned i;
    uint32_t revision = 0;
    bootstrap_lock();
    for (i = 0; i < 8; ++i)
        if (bootstrap[i].receiver == receiver && !bootstrap[i].stopped &&
            alt_ui_session_epoch(receiver) &&
            alt_ui_session_epoch(receiver) == altscreen_ui_session_generation())
            revision = bootstrap[i].accepted_revision;
    bootstrap_unlock();
    return revision;
}

void alt_ui_reapply(void *receiver) {
    unsigned i;
    bootstrap_lock();
    for (i = 0; i < 8; ++i)
        if (bootstrap[i].receiver == receiver && !bootstrap[i].stopped &&
            bootstrap[i].accepted_revision && !bootstrap[i].pending &&
            bootstrap[i].revision == bootstrap[i].attempted_revision &&
            bootstrap[i].revision < UINT32_MAX && alt_ui_session_epoch(receiver))
            ++bootstrap[i].revision;
    bootstrap_unlock();
}

/* Called within upstream's teardown lease, before stock stops its event client.
 * It never waits for a phone response. Completion is logged, not fabricated. */
int alt_ui_stop(void *receiver) {
    unsigned i, slot = 8;
    uint32_t token = 0;
    int rc;
    bootstrap_lock();
    for (i = 0; i < 8; ++i) {
        if (bootstrap[i].receiver != receiver) continue;
        bootstrap[i].stopped = 1;
        bootstrap[i].pending = 0;
        if (bootstrap[i].request && !bootstrap[i].stop_sent) {
            slot = i; token = bootstrap[i].token;
            bootstrap[i].stop_sent = 1;
        }

        break;
    }
    bootstrap_unlock();
    if (slot == 8) return 0;
    rc = alt_send_cluster_event_impl(receiver, NULL, 0,
        ALT111_EVENT_STOP_UI, 1, 0, -1, NULL);
    altscreen_log("PHASE=ALT111_STOP_SUBMIT receiver=%p token=%u rc=%d before_teardown=1 acknowledgement=CALLBACK_ONLY",
                  receiver, token, rc);
    return rc;
}

/* A replacement Type111 reuses its receiver's selected URL and UI worker. */
void alt_ui_resume(void *receiver) {
    unsigned i;
    bootstrap_lock();
    for (i = 0; i < 8; ++i)
        if (bootstrap[i].receiver == receiver && bootstrap[i].stopped &&
            alt_ui_session_epoch(receiver) &&
            alt_ui_session_epoch(receiver) == altscreen_ui_session_generation() &&
            bootstrap[i].revision < UINT32_MAX) {
            bootstrap[i].stopped = bootstrap[i].stop_sent = 0;
            bootstrap[i].accepted_revision = 0;
            if (bootstrap[i].url[0]) ++bootstrap[i].revision;
        }
    bootstrap_unlock();
}

static int bootstrap_command_current(void *receiver, uint32_t seq) {
    unsigned i; int current = 0;
    bootstrap_lock();
    for (i = 0; i < 8; ++i)
        if (bootstrap[i].receiver == receiver && bootstrap[i].request == seq &&
            bootstrap[i].pending && !bootstrap[i].stopped &&
            alt_ui_session_epoch(receiver) != 0 &&
            alt_ui_session_epoch(receiver) == altscreen_ui_session_generation()) {
            current = 1; break;
        }
    bootstrap_unlock();
    return current;
}
static int bootstrap_selected_url(void *receiver, uint32_t seq, char *url, size_t cap) {
    unsigned i; int result = 0;
    bootstrap_lock();
    for (i = 0; i < 8; ++i)
        if (bootstrap[i].receiver == receiver && bootstrap[i].request == seq &&
            bootstrap[i].pending && !bootstrap[i].stopped &&
            strlen(bootstrap[i].inflight_url) < cap) {
            memcpy(url, bootstrap[i].inflight_url, strlen(bootstrap[i].inflight_url) + 1); result = 1; break;
        }
    bootstrap_unlock();
    return result;
}
static void bootstrap_event_result(void *receiver, uint32_t seq, int event,
                                    int status, int response) {
    unsigned i; int applied = 0;
    bootstrap_lock();
    for (i = 0; i < 8; ++i) {
        if (bootstrap[i].receiver != receiver || bootstrap[i].request != seq ||
            !bootstrap[i].pending || bootstrap[i].stopped ||
            !alt_ui_session_epoch(receiver) ||
            alt_ui_session_epoch(receiver) != altscreen_ui_session_generation()) continue;
        applied = 1;
        if (status || !response) bootstrap[i].failed = 1;
        else if (event == ALT111_EVENT_SHOW_UI) bootstrap[i].show_ack = 1;
        break;
    }
    bootstrap_unlock();
    altscreen_log("PHASE=ALT111_BOOTSTRAP_RESULT receiver=%p request=%u event=%d status=%d response=%d applied=%d",
                  receiver, seq, event, status, response, applied);
}
static int bootstrap_job_current_locked(const struct bootstrap_job *job) {
    return bootstrap[job->slot].receiver == job->receiver &&
           bootstrap[job->slot].token == job->token;
}
static void *bootstrap_worker(void *opaque) {
    struct bootstrap_job *job = opaque;
    unsigned attempt = 0, poll;
    uint32_t revision = 0, seq = 0;
    int rc, current, accepted, failed;
    for (;;) {
        char view[16], layout[128];
        int dx, dy;
        usleep(ALT_BOOTSTRAP_POLL_US);
        (void)alt_local_hmi(view, sizeof(view), layout, sizeof(layout), &dx, &dy);
        bootstrap_lock();
        current = bootstrap_job_current_locked(job);
        if (!current) { bootstrap_unlock(); break; }
        if (bootstrap[job->slot].stopped || !alt_oem_map_startup_ready() ||
            !alt_ui_session_epoch(job->receiver) ||
            alt_ui_session_epoch(job->receiver) != altscreen_ui_session_generation() ||
            !bootstrap[job->slot].revision ||
            bootstrap[job->slot].revision == bootstrap[job->slot].attempted_revision) {
            bootstrap_unlock(); continue;
        }
        if (revision != bootstrap[job->slot].revision) {
            revision = bootstrap[job->slot].revision;
            attempt = 0;
        }
        seq = bootstrap_identity_locked();
        if (!seq) { bootstrap_unlock(); break; }
        bootstrap[job->slot].request = seq;
        bootstrap[job->slot].pending = 1;
        bootstrap[job->slot].failed = bootstrap[job->slot].show_ack = 0;
        memcpy(bootstrap[job->slot].inflight_url, bootstrap[job->slot].url,
               strlen(bootstrap[job->slot].url) + 1);
        bootstrap_unlock();
        ++attempt;
        rc = alt_request_cluster_after_main_tracked(job->receiver, seq);
        accepted = 0;
        for (poll = 0; poll < ALT_BOOTSTRAP_ACK_POLLS; ++poll) {
            bootstrap_lock();
            current = bootstrap_job_current_locked(job);
            failed = !current || bootstrap[job->slot].stopped ||
                !bootstrap[job->slot].pending || bootstrap[job->slot].request != seq ||
                bootstrap[job->slot].failed || rc != 0 ||
                !alt_ui_session_epoch(job->receiver) ||
                alt_ui_session_epoch(job->receiver) != altscreen_ui_session_generation();
            accepted = current && !failed && bootstrap[job->slot].show_ack;
            bootstrap_unlock();
            if (failed || accepted) break;
            usleep(ALT_BOOTSTRAP_POLL_US);
        }
        bootstrap_lock();
        current = bootstrap_job_current_locked(job);
        if (current && !bootstrap[job->slot].stopped) {
            bootstrap[job->slot].pending = 0;
            if (accepted) bootstrap[job->slot].accepted_revision = revision;
            if (accepted || attempt >= ALT_BOOTSTRAP_ATTEMPTS)
                bootstrap[job->slot].attempted_revision = revision;
        }
        bootstrap_unlock();
        altscreen_log("PHASE=ALT111_BOOTSTRAP_ATTEMPT receiver=%p request=%u revision=%u attempt=%u submit_rc=%d remote_accepted=%d current=%d picture_ready=UNPROVEN",
                      job->receiver, seq, revision, attempt, rc, accepted, current);
        if (!current) break;
        if (!accepted)
            for (poll = 0; poll < 5u * attempt; ++poll) usleep(ALT_BOOTSTRAP_POLL_US);
    }
    bootstrap_lock();
    if (bootstrap[job->slot].receiver == job->receiver &&
        bootstrap[job->slot].token == job->token)
        bootstrap[job->slot].worker = 0;
    bootstrap_unlock();
    alt_airplay_release_object(job->receiver);
    free(job);
    return NULL;
}

void altscreen_oem_map_ready(int ready) {
    if (ready) __sync_lock_test_and_set(&oem_map_ready, 1u);
}
static void bootstrap_note(void *receiver, int main_ready) {
    unsigned i, slot = 8;
    struct bootstrap_job *job = NULL;
    pthread_t thread;
    int rc = -1;
    int (*detach_fn)(pthread_t) = (int (*)(pthread_t))dlsym(RTLD_DEFAULT, "pthread_detach");
    if (!receiver) return;
    bootstrap_lock();
    for (i = 0; i < 8; ++i) {
        if (bootstrap[i].receiver == receiver) { slot = i; break; }
        if (!bootstrap[i].receiver && slot == 8) slot = i;
    }
    if (slot < 8) {
        if (!bootstrap[slot].receiver) {
            bootstrap[slot].token = bootstrap_identity_locked();
            if (bootstrap[slot].token) bootstrap[slot].receiver = receiver;
        }
        if (main_ready) bootstrap[slot].main_ready = 1;
        if (bootstrap[slot].receiver && bootstrap[slot].main_ready &&
            !bootstrap[slot].worker && !bootstrap[slot].stopped) {
            job = malloc(sizeof(*job));
            if (job) {
                job->receiver = receiver; job->slot = slot; job->token = bootstrap[slot].token;
                bootstrap[slot].worker = 1;
            }
        }
    }
    bootstrap_unlock();
    if (!job) return;
    if (detach_fn && alt_airplay_retain_object(receiver)) {
        rc = pthread_create(&thread, NULL, bootstrap_worker, job);
        if (!rc) { (void)detach_fn(thread); return; }
        alt_airplay_release_object(receiver);
    }
    bootstrap_lock();
    if (bootstrap_job_current_locked(job)) bootstrap[slot].worker = 0;
    bootstrap_unlock();
    free(job);
    altscreen_log("ERROR PHASE=ALT111_BOOTSTRAP_WORKER receiver=%p rc=%d", receiver, rc);
}
#endif
