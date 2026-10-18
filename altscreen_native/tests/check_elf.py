"""Validate the two independent QNX artifacts; never ship link-only BSP stubs."""
import pathlib
import re
import subprocess
import sys

out, export_map = map(pathlib.Path, sys.argv[1:])


def readelf(*args):
    return subprocess.check_output(["/usr/bin/readelf", "-W", *map(str, args)], text=True)


for name in ("libcarplay_altscreen.so", "carplay-alt111-mirror-display"):
    path = out / name
    header, dynamic, symbols = (readelf(flag, path) for flag in ("-h", "-d", "-s"))
    assert "Machine:                           ARM" in header, name
    assert "Version5 EABI" in header and "hard-float ABI" not in header, header
    assert "emutls" not in symbols, name
    needed = re.findall(r"Shared library: \[(.*?)\]", dynamic)
    allowed = {"libc.so.3", "libm.so.2"} if name.endswith(".so") else {
        "libc.so.3", "libm.so.2", "libsocket.so.3", "libEGL.so.1", "libGLESv2.so.1"}
    assert set(needed) <= allowed and "libc.so.3" in needed, (name, needed)
    undefined = [line.split()[-1] for line in symbols.splitlines()
                 if " UND " in line and len(line.split()) > 7]
    # The freestanding LLVM hook must not import any compiler helpers. QNX GCC
    # uses these two division helpers exported by the QNX6.5 libc for the mirror.
    if not name.endswith(".so"):
        dynsym = readelf("--dyn-syms", path)
        assert re.search(r"\bGLOBAL\s+DEFAULT\s+UND\s+eglSwapBuffers\b", dynsym), dynsym
        assert "eglSwapBuffers" in readelf("-r", path), "Missing interposable EGL swap relocation"
        undefined = [symbol for symbol in undefined
                     if symbol not in {"__aeabi_uidivmod", "__aeabi_ldivmod"}]
    assert not any(re.match(r"(__atomic|__sync|__aeabi|__gnu|__tls|__cxa|_Unwind|_Z)",
                            symbol) for symbol in undefined), undefined
    assert not re.search(r"(?:RPATH|RUNPATH)", dynamic), dynamic
    if name.endswith(".so"):
        assert set(needed) == allowed, needed
        expected = set(re.findall(r"^\s+([\w]+);", export_map.read_text(), re.M))
        dynsym = readelf("--dyn-syms", path)
        actual = {line.split()[-1] for line in dynsym.splitlines()
                  if re.search(r"\bGLOBAL\s+DEFAULT\b", line) and " UND " not in line}
        assert actual == expected, (actual - expected, expected - actual)
        forbidden = [token for token in
                     (b"changeMapZoomLevel", b"maneuverLayout=", b"mmi-mirror-wheel-zoom.events",
                      b"mmi-mirror-hmi.state") if token in path.read_bytes()]
        assert not forbidden, ("Competing native UI writer survived optimization", forbidden)
    print(f"{name}: ARM EABI5/softfp, exports/runtime dependencies checked: {','.join(needed)}")
print("ALTSCREEN_ELF_CHECKS=PASS")
