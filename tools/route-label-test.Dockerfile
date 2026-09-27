FROM qnx65-armv7-toolchain:8.5
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends build-essential libegl1-mesa-dev \
        libgles2-mesa-dev libgl1-mesa-dri python3-pil zlib1g-dev && \
    rm -rf /var/lib/apt/lists/*
ENV EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1
