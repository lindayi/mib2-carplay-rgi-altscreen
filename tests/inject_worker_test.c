#define ENABLE_LOGGING 0
#define ENABLE_STATE_TRACE 0
#include <assert.h>
#include "../hook/framework/hook_framework.c"

const hook_module_def_t *const hook_module_table[] = {NULL};
const size_t hook_module_table_count = 0;
void bus_shutdown(void) {}
void bus_set_periodic_tick(bus_tick_cb_t callback) { (void)callback; }

static pthread_mutex_t test_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t test_cond = PTHREAD_COND_INITIALIZER;
static int sends, completions, blocked = 1, stock_result;
static int objects[2] = {1, 0};
static hook_result_t results[4];
static int first_ticks, second_ticks;
static void first_tick(void) { first_ticks++; }
static void second_tick(void) { second_ticks++; }
static const hook_module_def_t first_module = { .name = "first", .on_tick = first_tick };
static const hook_module_def_t second_module = { .name = "second", .on_tick = second_tick };

static int addref(void *object) {
    return __sync_add_and_fetch((int *)object, 1);
}
static int release(void *object) {
    return __sync_sub_and_fetch((int *)object, 1);
}
static int create_iap(void *args) {
    assert(*(void **)args);
    return 0;
}
static int send_iap2(void *object, int session, const void *bytes, int length) {
    int result;
    assert(object && session == 1 && bytes && length == 4);
    assert(pthread_mutex_trylock(&g_fw.lock) == 0);
    pthread_mutex_unlock(&g_fw.lock);
    pthread_mutex_lock(&test_lock);
    ++sends;
    pthread_cond_broadcast(&test_cond);
    while (blocked) pthread_cond_wait(&test_cond, &test_lock);
    result = stock_result;
    pthread_mutex_unlock(&test_lock);
    return result;
}
static void completed(uint64_t token, hook_result_t result) {
    assert(pthread_mutex_trylock(&g_fw.lock) == 0);
    pthread_mutex_unlock(&g_fw.lock);
    assert(pthread_mutex_trylock(&g_inject_lock) == 0);
    pthread_mutex_unlock(&g_inject_lock);
    pthread_mutex_lock(&test_lock);
    assert(token == (uint64_t)completions + 1);
    results[completions++] = result;
    pthread_cond_broadcast(&test_cond);
    pthread_mutex_unlock(&test_lock);
}
static void await_count(int *counter, int expected) {
    struct timespec deadline;
    inject_deadline_after_ms(&deadline, 2000);
    pthread_mutex_lock(&test_lock);
    while (*counter < expected)
        assert(pthread_cond_timedwait(&test_cond, &test_lock, &deadline) == 0);
    pthread_mutex_unlock(&test_lock);
}
int main(void) {
    uint8_t frame[4] = {1, 2, 3, 4};
    void *next = &objects[1];
    ensure_fw_lock_init();
    g_fw.initialized = true;
    assert(hook_framework_register_module(&first_module) == HOOK_OK);
    assert(hook_framework_register_module(&second_module) == HOOK_OK);
    tick_modules();
    assert(first_ticks == 1 && second_ticks == 1);
    g_fw.cinemo_iap = &objects[0];
    g_fw.cinemo_iap_owner_pid = (int)getpid();
    g_fw.real_iap_addref = addref;
    g_fw.real_iap_release = release;
    g_fw.real_iap_send_iap2 = send_iap2;
    g_fw.real_cinemo_create_iap = create_iap;
    g_fw.ctx.inject.valid = g_fw.ctx.inject.linked = true;
    g_fw.ctx.inject.generation = 10;
    g_fw.ctx.inject.link_session = 1;
    assert(inject_worker_enqueue(frame, 4, 1, 10, completed, 1) == HOOK_OK);
    await_count(&sends, 1);
    assert(completions == 0);
    assert(inject_worker_enqueue(frame, 4, 1, 10, completed, 2) == HOOK_OK);
    assert(CinemoCreateIAP(&next) == 0);
    assert(g_fw.ctx.inject.generation == 11);
    assert(CinemoCreateIAP(&next) == 0);
    assert(g_fw.ctx.inject.generation == 11);
    pthread_mutex_lock(&test_lock);
    blocked = 0;
    pthread_cond_broadcast(&test_cond);
    pthread_mutex_unlock(&test_lock);
    await_count(&completions, 2);
    assert(sends == 1 && results[0] == HOOK_ERR_INIT && results[1] == HOOK_ERR_INIT);
    assert(objects[0] == 0 && objects[1] == 1);
    stock_result = -1;
    assert(inject_worker_enqueue(frame, 4, 1, 11, completed, 3) == HOOK_OK);
    await_count(&completions, 3);
    assert(results[2] == HOOK_ERR_IO);
    stock_result = 0;
    assert(inject_worker_enqueue(frame, 4, 1, 11, completed, 4) == HOOK_OK);
    await_count(&completions, 4);
    assert(results[3] == HOOK_OK && sends == 3);
    hook_framework_shutdown();
    tick_modules();
    assert(first_ticks == 1 && second_ticks == 1);
    assert(objects[1] == 0);
    puts("inject_worker_test: actual stock-send completion, object replacement and stale queue PASS");
    return 0;
}
