#!/usr/bin/env bash
#
# Packs the complete source of the current commit into build/aegirine-<version>-source.tar.gz,
# to attach to each GitHub release next to the APK (GPL-3.0 section 6). It holds:
#   - this repository at HEAD
#   - external/aegirine-core at the commit HEAD points to; the commit is in
#     external/aegirine-core.commit
#   - the source tarballs of the core's third-party libraries, in
#     external/core-deps/arm64-v8a/deps-build, where build-dependencies-android.sh finds them
#
# The archive builds without git, and its native libraries without network.
# Run it from a clean checkout of the release tag.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE="$ROOT/external/aegirine-core"
cd "$ROOT"

if [ -n "$(git status --porcelain)" ]; then
	echo "Uncommitted changes; commit or stash them first." >&2
	exit 1
fi
if [ "$(git -C "$CORE" rev-parse HEAD)" != "$(git rev-parse HEAD:external/aegirine-core)" ]; then
	echo "external/aegirine-core is not at the commit HEAD points to; run git submodule update." >&2
	exit 1
fi

VERSION="$(git describe --tags --always)"
NAME="aegirine-$VERSION-source"
OUT="$ROOT/build/$NAME.tar.gz"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/$NAME/external/aegirine-core" "$ROOT/build"
git archive HEAD | tar -x -C "$STAGE/$NAME"
git -C "$CORE" archive HEAD | tar -x -C "$STAGE/$NAME/external/aegirine-core"
git -C "$CORE" rev-parse HEAD > "$STAGE/$NAME/external/aegirine-core.commit"
"$CORE/scripts/deps/build-dependencies-android.sh" --fetch \
	"$STAGE/$NAME/external/core-deps/arm64-v8a/deps-build"

tar -czf "$OUT" -C "$STAGE" "$NAME"
echo "$OUT"
shasum -a 256 "$OUT"
