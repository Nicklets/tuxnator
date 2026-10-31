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
# BOX64_REF can be a commit (what releases pin), which git clone --branch doesn't take
git init -q "$SRC/box64"
git -C "$SRC/box64" fetch -q --depth 1 "$BOX64_REPO" "$BOX64_REF"
git -C "$SRC/box64" checkout -q FETCH_HEAD
# glibc functions Box64 doesn't forward yet: x86 programs built against a recent glibc (Nix
# bundles) jump to the unresolved symbol and crash when they call one. Each takes the signature of
# the function it mirrors (memset, __memset_chk, explicit_bzero, strlcpy, strsignal...); sigqueue
# takes its union sigval in a register, like a pointer.
LIBC_WRAPS="$SRC/box64/src/wrapped/wrappedlibc_private.h"
grep -qx 'GO(arc4random, uFv)' "$LIBC_WRAPS" || { echo "arc4random not found in $LIBC_WRAPS" >&2; exit 1; }
for wrap in "arc4random_buf, vFpL" "arc4random_uniform, uFu" "memset_explicit, pFpiL" \
            "__memset_explicit_chk, pFpiLL" "wcslcpy, LFppL" "wcslcat, LFppL" "closefrom, vFi" \
            "sigabbrev_np, pFi" "sigdescr_np, pFi" "sigqueue, iFiip"; do
    grep -q "^GO[A-Z]*(${wrap%%,*}," "$LIBC_WRAPS" && continue
    # rebuild_wrappers.py counts commented-out entries too (sigqueue has one): drop it first
    sed -i "/^\/\/ *GO[A-Z]*(${wrap%%,*},/d" "$LIBC_WRAPS"
    sed -i "/^GO(arc4random, uFv)\$/a GO($wrap)" "$LIBC_WRAPS"
done
# The dynarec computes FS:/GS:-disp(%reg) (negative disp under 4 KiB) with scratch pointing at the
# guest's base register, then loads the segment base into scratch: the base register (RAX in
# SacredFire's TLS accesses, after `mov $0,%rax`) ends up holding the FS base and the next access
# lands at twice it. Give the segment base a real scratch register again (sent upstream as
# ptitSeb/box64#4645; drop this once Box64 has it).
GETED="$SRC/box64/src/dynarec/arm64/dynarec_arm64_helper.c"
grep -qx '    if(rex.seg && !seg_done) {' "$GETED" || { echo "segment block not found in $GETED" >&2; exit 1; }
sed -i 's/^    if(rex.seg \&\& !seg_done) {$/&\n        scratch = (hint>0 \&\& !IS_GPR(hint)) ? hint : x2;/' "$GETED"
grep -qx '        scratch = (hint>0 && !IS_GPR(hint)) ? hint : x2;' "$GETED" || { echo "geted patch failed" >&2; exit 1; }
# The page fault error code an x86 SIGSEGV handler gets says "write" only when the x86 instruction
# at RIP writes. When the fault is in a wrapped native function (memcpy into a page the program
# write-protected to track changes), that instruction is the call: the handler saw a read, left the
# page protected, and memcpy faulted again forever (SacredFire stuck loading). On arm64 the
# kernel's ESR tells whether the native access was a write.
SIGNALS="$SRC/box64/src/libtools/signals.c"
WRITE_OPCODE='if(write_opcode(sigcontext->uc_mcontext.gregs\[X64_RIP\], (uintptr_t)pc, (R_CS==0x23)))'
grep -q "^ *$WRITE_OPCODE\$" "$SIGNALS" || { echo "write_opcode call not found in $SIGNALS" >&2; exit 1; }
cat > "$SRC/esr-write.c" <<'EOF'
#if defined(DYNAREC) && defined(__aarch64__)
            if(p) {
                struct _aarch64_ctx* ff = (struct _aarch64_ctx*)p->uc_mcontext.__reserved;
                while(ff->magic && ff->size && ff->magic!=ESR_MAGIC)
                    ff = (struct _aarch64_ctx*)((uintptr_t)ff + ff->size);
                uint64_t esr = (ff->magic==ESR_MAGIC) ? ((struct esr_context*)ff)->esr : 0;
                // Data abort from EL0 with WnR set, not a cache maintenance operation
                if((esr>>26)==0x24 && (esr&(1<<6)) && !(esr&(1<<8)))
                    sigcontext->uc_mcontext.gregs[X64_ERR] |= 2;
            }
#endif
EOF
sed -i "/^ *$WRITE_OPCODE\$/{n;r $SRC/esr-write.c
}" "$SIGNALS"
grep -q 'ff->magic!=ESR_MAGIC' "$SIGNALS" || { echo "ESR patch failed" >&2; exit 1; }
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
# Terminal attributes through the ioctls Android allows on a pty (see the source)
gcc -O2 -shared -fPIC -fvisibility=hidden -Wall -Wextra \
    -o "$STAGING/usr/lib/winlator/libtermios-compat.so" "$HERE/shims/termios.c" -ldl
for sym in tcgetattr tcsetattr isatty ttyname ttyname_r; do
    nm -D --defined-only "$STAGING/usr/lib/winlator/libtermios-compat.so" | grep -q " T $sym\$" \
        || { echo "libtermios-compat.so does not export $sym" >&2; exit 1; }
done
echo "::endgroup::"

echo "::group::Desktop session and defaults"
# tuxnator-desktop, the AppImage runner and system-wide defaults for PCManFM/libfm, copied over
# the rootfs
cp -a "$HERE/overlay/." "$STAGING/"
chmod 755 "$STAGING/usr/local/bin/tuxnator-desktop" "$STAGING/usr/local/bin/tuxnator-run-appimage"
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
