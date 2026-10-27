#!/bin/bash
# Assembles the Linux runtime rootfs (Debian arm64 + amd64 base libs + components from
# build-components.sh) and packs it as linux-rootfs-<version>.tar.zst with a manifest.
# Must run as root on a Debian arm64 host after build-components.sh.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/config.env"

WORK="${WORK:-/work}"
STAGING="${STAGING:-$WORK/staging}"
OUT="${OUT:-$WORK/out}"
ROOTFS="$WORK/rootfs"
NAME="linux-rootfs-$ROOTFS_VERSION"
mkdir -p "$OUT"

[ -f "$STAGING/usr/local/bin/box64" ] || { echo "run build-components.sh first" >&2; exit 1; }

read_list() { grep -v -e '^\s*#' -e '^\s*$' "$1" | paste -sd, -; }

echo "::group::Bootstrap Debian $DEBIAN_SUITE"
export DEBIAN_FRONTEND=noninteractive
apt-get install -y --no-install-recommends mmdebstrap zstd
rm -rf "$ROOTFS"
# Docs, manuals and translations are useless on the device and take a lot of space
mmdebstrap --mode=root --variant=minbase --architectures=arm64,amd64 \
    --include="$(read_list "$HERE/packages-arm64.txt"),$(read_list "$HERE/packages-amd64.txt")" \
    --dpkgopt='path-exclude=/usr/share/doc/*' \
    --dpkgopt='path-include=/usr/share/doc/*/copyright' \
    --dpkgopt='path-exclude=/usr/share/man/*' \
    --dpkgopt='path-exclude=/usr/share/info/*' \
    --dpkgopt='path-exclude=/usr/share/locale/*' \
    --dpkgopt='path-exclude=/usr/share/lintian/*' \
    "$DEBIAN_SUITE" "$ROOTFS" "$DEBIAN_MIRROR"
echo "::endgroup::"

echo "::group::Overlay components"
cp -a "$STAGING/." "$ROOTFS/"
mv "$ROOTFS/.components.json" "$WORK/components.json"
# Keep apt from replacing our Mesa with Debian's (which lacks the KGSL backend)
chroot "$ROOTFS" sh -c 'for p in libgbm1 libglx-mesa0 libegl-mesa0 libgl1-mesa-dri mesa-vulkan-drivers; do
    dpkg-query -W "$p" >/dev/null 2>&1 && apt-mark hold "$p"; done; true'
chroot "$ROOTFS" ldconfig
echo "::endgroup::"

echo "::group::Configure"
# Unprivileged user matching HOME=/home/user used by the app's launcher
chroot "$ROOTFS" useradd -m -u 1000 -s /bin/bash user
mkdir -p "$ROOTFS/tmp/.X11-unix" "$ROOTFS/tmp/.sound" "$ROOTFS/tmp/.sysvshm" "$ROOTFS/tmp/runtime" "$ROOTFS/mnt/sdcard"
chmod 1777 "$ROOTFS/tmp" "$ROOTFS/tmp/.X11-unix"
chmod 700 "$ROOTFS/tmp/runtime"
# Default DNS; the launcher can bind-mount one with Android's active DNS server over it
echo "nameserver 8.8.8.8" > "$ROOTFS/etc/resolv.conf"
echo "$ROOTFS_VERSION" > "$ROOTFS/etc/winlator-rootfs-version"
echo "::endgroup::"

echo "::group::Package lists and cleanup"
chroot "$ROOTFS" dpkg-query -W -f='${Package}:${Architecture}\t${Version}\t${source:Package}\t${source:Version}\n' \
    | sort > "$OUT/$NAME.packages.tsv"
chroot "$ROOTFS" apt-get clean
rm -rf "$ROOTFS/var/lib/apt/lists/"* "$ROOTFS/var/cache/"* "$ROOTFS/var/log/"*
# /dev, /proc and /sys are bind-mounted by PRoot on the device
rm -rf "$ROOTFS/dev/"* "$ROOTFS/proc/"* "$ROOTFS/sys/"*
echo "::endgroup::"

echo "::group::Pack"
# --hard-dereference: the app's tar extractor handles symlinks but not hard links
tar --numeric-owner --hard-dereference -C "$ROOTFS" -cf - . | zstd -19 -T0 -q -o "$OUT/$NAME.tar.zst"
SHA256="$(sha256sum "$OUT/$NAME.tar.zst" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$OUT/$NAME.tar.zst")"
UNPACKED="$(du -sb "$ROOTFS" | cut -f1)"
echo "$SHA256  $NAME.tar.zst" > "$OUT/$NAME.tar.zst.sha256"

cat > "$OUT/$NAME.manifest.json" <<EOF
{
  "name": "$NAME",
  "version": $ROOTFS_VERSION,
  "archive": "$NAME.tar.zst",
  "sha256": "$SHA256",
  "size": $SIZE,
  "unpackedSize": $UNPACKED,
  "debian": {"suite": "$DEBIAN_SUITE", "architectures": ["arm64", "amd64"]},
  "components": $(cat "$WORK/components.json"),
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
echo "::endgroup::"

ls -la "$OUT"
cat "$OUT/$NAME.manifest.json"
