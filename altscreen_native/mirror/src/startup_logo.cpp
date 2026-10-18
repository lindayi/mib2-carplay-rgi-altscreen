#include "startup_logo.h"

/* No external artwork is part of this source distribution. Keep the startup
 * surface blank until the first decoded frame; do not add animation swaps. */
StartupLogo::StartupLogo()
    : cursor_(0), end_(0), pixels_(0), frame_count_(0), frame_index_(0), fps_(0) {}
StartupLogo::~StartupLogo() {}
bool StartupLogo::open() { return false; }
bool StartupLogo::next_frame() { return false; }
void StartupLogo::close() {}
