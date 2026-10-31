#!/bin/bash
# Downloads the Debian source packages for everything in the rootfs (GPL compliance when the
# rootfs is distributed). Input: the <name>.packages.tsv written by make-rootfs.sh.
set -euo pipefail

TSV="$1"
DEST="${2:-/work/out/sources}"
mkdir -p "$DEST"
cd "$DEST"

export DEBIAN_FRONTEND=noninteractive
# The Debian container image lists binary packages only
sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/debian.sources
apt-get update
apt-get install -y --no-install-recommends dpkg-dev

missing=0
while IFS=$'\t' read -r src ver; do
    apt-get source --download-only "$src=$ver" >/dev/null 2>&1 || { echo "WARN: no source for $src=$ver" >&2; missing=$((missing+1)); }
done < <(cut -f3,4 "$TSV" | sort -u)
echo "$(ls *.dsc | wc -l) source packages, $missing missing"
# A few can lag behind in testing; most missing means the source list is broken
[ "$(ls *.dsc | wc -l)" -gt $((missing * 10)) ] || { echo "Too many sources missing" >&2; exit 1; }
