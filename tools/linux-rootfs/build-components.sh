#!/bin/bash
# Builds Box64, Mesa (Turnip KGSL + Zink) and the Winlator glibc shims for arm64,
# installing everything under $STAGING so it can be copied on top of the rootfs.
# Must run on Debian (same suite as the rootfs) on an arm64 host, as root.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
source "$HERE/config.env"

WORK="${WORK:-/work}"
STAGING="${STAGING:-$WORK/staging}"
SRC="$WORK/src"
JOBS="$(nproc)"
mkdir -p "$SRC" "$STAGING"

[ "$(uname -m)" = "aarch64" ] || { echo "must run on an arm64 host" >&2; exit 1; }

echo "::group::Build dependencies"
export DEBIAN_FRONTEND=noninteractive
# Enable deb-src so `apt-get build-dep mesa` pulls Mesa's full build dependency set
sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/debian.sources
apt-get update
apt-get install -y --no-install-recommends \
    build-essential cmake git ca-certificates python3 pkg-config \
    meson ninja-build glslang-tools python3-mako python3-yaml python3-packaging python3-ply
apt-get build-dep -y mesa
echo "::endgroup::"

echo "::group::Box64 $BOX64_REF"
git clone --depth 1 --branch "$BOX64_REF" "$BOX64_REPO" "$SRC/box64"
# shellcheck disable=SC2086
cmake -S "$SRC/box64" -B "$SRC/box64/build" $BOX64_CMAKE_FLAGS
cmake --build "$SRC/box64/build" -j "$JOBS"
# Install by hand: Box64's install step also registers binfmt through systemd, which doesn't exist here
install -Dm755 "$SRC/box64/build/box64" "$STAGING/usr/local/bin/box64"
mkdir -p "$STAGING/usr/lib/box64-x86_64-linux-gnu"
cp -a "$SRC/box64/x64lib/." "$STAGING/usr/lib/box64-x86_64-linux-gnu/"
install -Dm644 "$SRC/box64/system/box64.box64rc" "$STAGING/etc/box64.box64rc"
echo "::endgroup::"

echo "::group::Mesa $MESA_REF"
git clone --depth 1 --branch "$MESA_REF" "$MESA_REPO" "$SRC/mesa"
# shellcheck disable=SC2086
meson setup "$SRC/mesa/build" "$SRC/mesa" \
    --prefix=/usr --libdir=lib/aarch64-linux-gnu $MESA_MESON_FLAGS
ninja -C "$SRC/mesa/build" -j "$JOBS"
DESTDIR="$STAGING" ninja -C "$SRC/mesa/build" install
# Headers and pkg-config files are not needed at runtime
rm -rf "$STAGING/usr/include" "$STAGING/usr/lib/aarch64-linux-gnu/pkgconfig"
echo "::endgroup::"

echo "::group::Winlator glibc shims"
# SysV shared memory over the app's socket server (Android has no SysV IPC), needed by MIT-SHM
cmake -S "$REPO_ROOT/android_sysvshm" -B "$SRC/sysvshm"
cmake --build "$SRC/sysvshm" -j "$JOBS"
install -Dm755 "$SRC/sysvshm/libandroid-sysvshm.so" "$STAGING/usr/lib/winlator/libandroid-sysvshm.so"
# Fake evdev devices for the on-screen/physical gamepad
g++ -O2 -shared -fPIC -fvisibility=hidden -Wall -Wextra \
    -o "$STAGING/usr/lib/winlator/libfakeinput.so" \
    "$REPO_ROOT/app/src/main/cpp/winlator/fakeinput.cpp" -ldl -lpthread
# The shim only works if its hooks interpose libc's: fail the build if they aren't exported
for sym in open openat stat scandir ioctl inotify_add_watch; do
    nm -D --defined-only "$STAGING/usr/lib/winlator/libfakeinput.so" | grep -q " T $sym\$" \
        || { echo "libfakeinput.so does not export $sym" >&2; exit 1; }
done
echo "::endgroup::"

# Record what was built, for the manifest
cat > "$STAGING/.components.json" <<EOF
{
  "box64": {"ref": "$BOX64_REF", "commit": "$(git -C "$SRC/box64" rev-parse HEAD)"},
  "mesa": {"ref": "$MESA_REF", "commit": "$(git -C "$SRC/mesa" rev-parse HEAD)"},
  "winlator_shims": {"commit": "$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"}
}
EOF
echo "Components staged in $STAGING"
