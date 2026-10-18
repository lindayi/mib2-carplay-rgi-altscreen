#include <assert.h>
#include <stdio.h>
#include "../hook/ui_session.h"

int main(void) {
    void *a = (void *)1, *b = (void *)2;
    uint32_t first = alt_ui_session_begin(a), second, reused;
    assert(first && !altscreen_ui_session_generation());
    alt_ui_session_note(a, first, 1, 0, 1);
    assert(!altscreen_ui_session_generation()); /* SETUP alone is insufficient */
    alt_ui_session_note(a, first, 0, 1, 0);
    assert(!altscreen_ui_session_generation()); /* START without event client */
    alt_ui_session_note(a, first, 0, 1, 1);
    assert(altscreen_ui_session_generation() == first);
    assert(alt_ui_session_begin(a) == first);
    alt_ui_session_note(a, first, 1, 0, 1);
    assert(altscreen_ui_session_generation() == first);
    alt_ui_session_closing(a);
    assert(!altscreen_ui_session_generation());
    alt_ui_session_note(a, first, 1, 1, 1);
    assert(!altscreen_ui_session_generation()); /* in-flight START cannot revive stop */
    second = alt_ui_session_begin(b);
    assert(second > first);
    alt_ui_session_note(b, second, 1, 1, 1);
    assert(altscreen_ui_session_generation() == second);
    alt_ui_session_closing((void *)3);
    assert(altscreen_ui_session_generation() == second);
    alt_ui_session_forget((void *)3);
    alt_ui_session_forget(a);
    alt_ui_session_note(a, first, 1, 1, 1);
    assert(altscreen_ui_session_generation() == second); /* late old completion/stop */
    reused = alt_ui_session_begin(a);
    assert(reused > second && !altscreen_ui_session_generation());
    alt_ui_session_note(a, first, 1, 1, 1);
    alt_ui_session_note(b, second, 1, 1, 1);
    assert(!altscreen_ui_session_generation()); /* no previous-owner resurrection */
    alt_ui_session_note(a, reused, 1, 1, 1);
    assert(altscreen_ui_session_generation() == reused);
    alt_ui_session_forget(a);
    alt_ui_session_forget(b);
    alt_ui_session_closing(a); /* teardown can overtake the first START */
    reused = alt_ui_session_begin(a);
    alt_ui_session_note(a, reused, 1, 1, 1);
    assert(!altscreen_ui_session_generation());
    alt_ui_session_forget(a);
    puts("NATIVE_UI_SESSION_READINESS=PASS");
}
