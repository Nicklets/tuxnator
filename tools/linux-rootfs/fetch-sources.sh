#!/bin/bash
# Downloads the Debian source packages for everything in the rootfs (GPL compliance when the
# rootfs is distributed). Input: the <name>.packages.tsv written by make-rootfs.sh.
set -euo pipefail

TSV="$1"
DEST="${2:-/work/out/sources}"
mkdir -p "$DEST"
cd "$DEST"

export DEBIAN_FRONTEND=noninteractive
apt-get install -y --no-install-recommends dpkg-dev

cut -f3,4 "$TSV" | sort -u | while IFS=$'\t' read -r src ver; do
    apt-get source --download-only "$src=$ver" >/dev/null 2>&1 || echo "WARN: no source for $src=$ver" >&2
done
ls | wc -l
