FROM qnx65-armv7-toolchain:8.5
# V3.8's freestanding QNX ABI hook needs LLVM; the mirror still uses QNX GCC.
RUN apt-get update -qq && apt-get install -y --no-install-recommends clang lld python3-pyelftools \
    && rm -rf /var/lib/apt/lists/*
