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
    build-essential cmake git ca-certificates python3 pkg-config file \
    meson ninja-build glslang-tools python3-mako python3-yaml python3-packaging python3-ply libudev-dev
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
# Linking libdrm (see config.env) also turns on the KMS display WSI, but Turnip on KGSL fails
# instance creation when VK_KHR_display is enabled, and apps like vkcube enable it whenever it's
# listed. There is no KMS on Android, so leave the display WSI out.
for f in src/vulkan/meson.build src/vulkan/wsi/meson.build; do
    grep -q '^if system_has_kms_drm and not with_platform_android$' "$SRC/mesa/$f" \
        || { echo "display WSI condition not found in $f" >&2; exit 1; }
    sed -i 's/^if system_has_kms_drm and not with_platform_android$/if false/' "$SRC/mesa/$f"
done
# Without explicit sync or a dma-buf semaphore, the X11 WSI presents FIFO frames before the GPU has
# finished them, counting on the kernel's implicit sync. KGSL has none (and Android kernels lack
# DMA_BUF_IOCTL_IMPORT_SYNC_FILE), and the X server reads the dma-buf on another GPU queue, so wait
# for the frame's fence on the WSI's present thread instead.
grep -qxF '            x11_needs_wait_for_fences(chain->base.wsi, wsi_conn, present_mode) &&' \
    "$SRC/mesa/src/vulkan/wsi/wsi_common_x11.c" \
    || { echo "fence wait condition not found in wsi_common_x11.c" >&2; exit 1; }
sed -i 's/^            x11_needs_wait_for_fences(chain->base.wsi, wsi_conn, present_mode) &&$/            (x11_needs_wait_for_fences(chain->base.wsi, wsi_conn, present_mode) || !chain->base.dma_buf_semaphore) \&\&/' \
    "$SRC/mesa/src/vulkan/wsi/wsi_common_x11.c"
# shellcheck disable=SC2086
meson setup "$SRC/mesa/build" "$SRC/mesa" \
    --prefix=/usr --libdir=lib/aarch64-linux-gnu $MESA_MESON_FLAGS
ninja -C "$SRC/mesa/build" -j "$JOBS"
DESTDIR="$STAGING" ninja -C "$SRC/mesa/build" install
# Headers and pkg-config files are not needed at runtime
rm -rf "$STAGING/usr/include" "$STAGING/usr/lib/aarch64-linux-gnu/pkgconfig"
# Move Mesa's debug info to $DEBUG_STAGING (shipped as a separate package for gdb), leaving a
# .gnu_debuglink in the rootfs; gdb looks the files up under /usr/lib/debug/<path>.debug
DEBUG_STAGING="${DEBUG_STAGING:-$WORK/debug-staging}"
find "$STAGING/usr/lib/aarch64-linux-gnu" -type f -name '*.so*' | while read -r lib; do
    file -b "$lib" | grep -q 'ELF.*with debug_info' || continue
    rel="${lib#"$STAGING"}"
    mkdir -p "$DEBUG_STAGING/usr/lib/debug$(dirname "$rel")"
    objcopy --only-keep-debug "$lib" "$DEBUG_STAGING/usr/lib/debug$rel.debug"
    objcopy --strip-debug --add-gnu-debuglink="$DEBUG_STAGING/usr/lib/debug$rel.debug" "$lib"
done
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
# Silent udev monitors, since Android doesn't allow the netlink socket (see the source)
gcc -O2 -shared -fPIC -fvisibility=hidden -Wall -Wextra \
    -o "$STAGING/usr/lib/winlator/libfakeudev.so" \
    "$HERE/shims/fakeudev.c" -ludev
nm -D --defined-only "$STAGING/usr/lib/winlator/libfakeudev.so" | grep -q " T udev_monitor_new_from_netlink\$" \
    || { echo "libfakeudev.so does not export udev_monitor_new_from_netlink" >&2; exit 1; }
# Runs x86 programs through Box64 when something executes them (no binfmt_misc under PRoot)
gcc -O2 -shared -fPIC -fvisibility=hidden -Wall -Wextra \
    -o "$STAGING/usr/lib/winlator/libbox64exec.so" "$HERE/shims/box64exec.c" -ldl
for sym in execve execvp posix_spawn posix_spawnp; do
    nm -D --defined-only "$STAGING/usr/lib/winlator/libbox64exec.so" | grep -q " T $sym\$" \
        || { echo "libbox64exec.so does not export $sym" >&2; exit 1; }
done
echo "::endgroup::"

echo "::group::Desktop session and defaults"
# tuxnator-desktop and system-wide defaults for PCManFM/libfm, copied over the rootfs
cp -a "$HERE/overlay/." "$STAGING/"
chmod 755 "$STAGING/usr/local/bin/tuxnator-desktop"
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
