#!/usr/bin/env bash
#
# Packs the complete source of the current commit into build/ametrine-<version>-source.tar.gz,
# to attach to each GitHub release next to the APK (GPL-3.0 section 6). It holds:
#   - this repository at HEAD
#   - external/armsx2 as it is built: the pinned commit, our patches applied, and the shaderc
#     dependencies setup-armsx2.sh fetches; its commit is in external/armsx2.commit
#
# The archive builds without git or network: setup-armsx2.sh sees the .commit file and skips.
# Run setup-armsx2.sh first, from a clean checkout of the release tag.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARMSX2="$ROOT/external/armsx2"
cd "$ROOT"

if [ -n "$(git status --porcelain --ignore-submodules=all)" ]; then
	echo "Uncommitted changes; commit or stash them first." >&2
	exit 1
fi
if [ -z "$(git -C "$ARMSX2" status --porcelain --untracked-files=no)" ]; then
	echo "external/armsx2 has no patches applied; run scripts/setup-armsx2.sh first." >&2
	exit 1
fi
if [ ! -d "$ARMSX2/platforms/android/app/src/main/cpp/3rdparty/shaderc/third_party/glslang" ]; then
	echo "shaderc's dependencies are missing; run scripts/setup-armsx2.sh first." >&2
	exit 1
fi

VERSION="$(git describe --tags --always)"
NAME="ametrine-$VERSION-source"
OUT="$ROOT/build/$NAME.tar.gz"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/$NAME" "$ROOT/build"
git archive HEAD | tar -x -C "$STAGE/$NAME"
rsync -a --exclude='.git' "$ARMSX2/" "$STAGE/$NAME/external/armsx2/"
git -C "$ARMSX2" rev-parse HEAD > "$STAGE/$NAME/external/armsx2.commit"

tar -czf "$OUT" -C "$STAGE" "$NAME"
echo "$OUT"
shasum -a 256 "$OUT"
