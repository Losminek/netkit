#!/usr/bin/env bash
# Checks a release tag against the app version (docs/distribuce.md §2).
#
#   tools/check_release_version.sh v1.0.0 [path/to/androidApp/build.gradle.kts]
#
# Passes only when the tag is exactly v<netkitVersion>, the version is X.Y.Z (no suffix such as
# -dev), minor and patch are 0..99, and prints the versionCode (major*10000 + minor*100 + patch).
set -euo pipefail

usage() {
    echo "usage: $0 vX.Y.Z [androidApp/build.gradle.kts]" >&2
    exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage
tag=$1
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
gradle_file=${2:-"$script_dir/../androidApp/build.gradle.kts"}

if [[ ! -f $gradle_file ]]; then
    echo "error: $gradle_file not found" >&2
    exit 1
fi

semver='^(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$'

if [[ ! $tag =~ ^v(.+)$ ]]; then
    echo "error: tag '$tag' must start with v" >&2
    exit 1
fi
tag_version=${BASH_REMATCH[1]}
if [[ ! $tag_version =~ $semver ]]; then
    echo "error: tag '$tag' is not vX.Y.Z (minor and patch 0..99, no suffix)" >&2
    exit 1
fi

# Exactly one top-level declaration: val netkitVersion = "X.Y.Z"
mapfile -t declarations < <(grep -E '^val netkitVersion = "[^"]*"$' "$gradle_file" || true)
if [[ ${#declarations[@]} -ne 1 ]]; then
    echo "error: expected one 'val netkitVersion = \"X.Y.Z\"' in $gradle_file, found ${#declarations[@]}" >&2
    exit 1
fi
app_version=${declarations[0]#*\"}
app_version=${app_version%\"}
if [[ ! $app_version =~ $semver ]]; then
    echo "error: netkitVersion '$app_version' is not X.Y.Z" >&2
    exit 1
fi

if [[ $tag_version != "$app_version" ]]; then
    echo "error: tag $tag does not match netkitVersion $app_version" >&2
    exit 1
fi

IFS=. read -r major minor patch <<<"$app_version"
echo "ok: $tag = netkitVersion $app_version, versionCode $((major * 10000 + minor * 100 + patch))"
