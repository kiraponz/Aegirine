#!/usr/bin/env bash
#
# Prepares the ARMSX2 submodule for building:
#   1. checks out the submodule
#   2. fetches shaderc's third-party deps (SPIRV-Tools, glslang, ...), which ARMSX2 does not vendor
#   3. applies our local patches from patches/armsx2/ (skipped if already applied)
#
# Safe to re-run.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARMSX2="$ROOT/external/armsx2"

git -C "$ROOT" submodule update --init --depth 1 external/armsx2

python3 "$ARMSX2/platforms/android/app/src/main/cpp/3rdparty/shaderc/utils/git-sync-deps"

for patch in "$ROOT"/patches/armsx2/*.patch; do
	[ -e "$patch" ] || continue
	if git -C "$ARMSX2" apply --reverse --check "$patch" 2>/dev/null; then
		echo "already applied: $(basename "$patch")"
	else
		git -C "$ARMSX2" apply "$patch"
		echo "applied: $(basename "$patch")"
	fi
done
