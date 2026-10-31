#!/usr/bin/env bash
# Verifies the signed release assets before the web is rebuilt (publish.yml, docs/distribuce.md §2).
#
#   tools/verify_release_assets.sh <asset-dir> <X.Y.Z>
#
# Checks, all of which must pass:
#   1. <asset-dir>/netkit-X.Y.Z.apk exists and SHA256SUMS lists it exactly once with the right hash;
#   2. apksigner verifies the APK and it has exactly one signer whose certificate SHA-256 equals the
#      fingerprint recorded in docs/distribuce.md (or NETKIT_EXPECTED_CERT_SHA256, for tests);
#   3. aapt2 badging: package cz.netkit.app, versionName X.Y.Z, versionCode from the version scheme,
#      and no REQUEST_INSTALL_PACKAGES;
#   4. latest.json (D22, D23) is listed in SHA256SUMS with the right hash and describes exactly this APK,
#      certificate, web (netkitWebBase) and repo (netkitSourceUrl) — tools/release_latest_json.py check.
# Tools: APKSIGNER and AAPT2 (paths) or apksigner / aapt2 on PATH. Prints the APK and certificate
# digests on success. Never needs or reads a signing key.
set -euo pipefail

usage() {
    echo "usage: $0 <asset-dir> <X.Y.Z>" >&2
    exit 2
}
fail() {
    echo "error: $*" >&2
    exit 1
}

[[ $# -eq 2 ]] || usage
dir=$1
version=$2
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
docs=${NETKIT_DISTRIBUCE_MD:-"$script_dir/../docs/distribuce.md"}
properties=${NETKIT_GRADLE_PROPERTIES:-"$script_dir/../gradle.properties"}
apksigner=${APKSIGNER:-apksigner}
aapt2=${AAPT2:-aapt2}

[[ $version =~ ^(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] || fail "version '$version' is not X.Y.Z"
IFS=. read -r major minor patch <<<"$version"
version_code=$((major * 10000 + minor * 100 + patch))
apk_name="netkit-$version.apk"
apk="$dir/$apk_name"
sums="$dir/SHA256SUMS"
[[ -f $apk ]] || fail "$apk_name missing in $dir"
[[ -f $sums ]] || fail "SHA256SUMS missing in $dir"
latest="$dir/latest.json"
[[ -f $latest ]] || fail "latest.json missing in $dir (tools/sign_release.sh creates it)"

normalize() {
    # 64 lowercase hex digits from "AB:CD:..." or "abcd..."; empty when the input is not a SHA-256.
    local value
    value=$(printf '%s' "$1" | tr -d ':' | tr '[:upper:]' '[:lower:]')
    [[ $value =~ ^[0-9a-f]{64}$ ]] && printf '%s' "$value"
    return 0
}

# 1. SHA256SUMS: exactly one entry for the APK and for latest.json (sha256sum text or binary mode).
listed_sha() {
    local name=$1 file=$2 entries listed actual
    mapfile -t entries < <(awk -v name="$name" '$2 == name || $2 == "*" name { print $1 }' "$sums")
    [[ ${#entries[@]} -eq 1 ]] || fail "SHA256SUMS must list $name exactly once (found ${#entries[@]})"
    listed=$(normalize "${entries[0]}")
    [[ -n $listed ]] || fail "SHA256SUMS entry for $name is not a SHA-256"
    actual=$(sha256sum "$file" | awk '{ print $1 }')
    [[ $actual == "$listed" ]] || fail "$name SHA-256 $actual does not match SHA256SUMS $listed"
    printf '%s' "$actual"
}
actual=$(listed_sha "$apk_name" "$apk")
listed_sha latest.json "$latest" >/dev/null

# 2. Signature and certificate fingerprint.
if [[ -n ${NETKIT_EXPECTED_CERT_SHA256:-} ]]; then
    expected_raw=$NETKIT_EXPECTED_CERT_SHA256
else
    [[ -f $docs ]] || fail "$docs not found"
    # The backticks are the Markdown code span around the value, not command substitution.
    # shellcheck disable=SC2016
    expected_raw=$(sed -nE 's/^- \*\*Otisk release certifikátu \(SHA-256\):\*\* `([^`]*)`.*$/\1/p' "$docs" | head -n 1)
fi
expected=$(normalize "$expected_raw")
[[ -n $expected ]] || fail "release certificate fingerprint is not filled in ($docs, docs/distribuce.md §2)"

signer_output=$("$apksigner" verify --print-certs "$apk") || fail "apksigner rejected $apk_name"
mapfile -t certs < <(printf '%s\n' "$signer_output" | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F:]+)$/\1/p')
[[ ${#certs[@]} -eq 1 ]] || fail "expected exactly one signer, found ${#certs[@]}"
cert=$(normalize "${certs[0]}")
[[ $cert == "$expected" ]] || fail "signer certificate $cert does not match the recorded fingerprint $expected"

# 3. Identity of the APK.
badging=$("$aapt2" dump badging "$apk") || fail "aapt2 cannot read $apk_name"
package_line=$(printf '%s\n' "$badging" | grep -m1 '^package: ' || true)
[[ $package_line == *"name='cz.netkit.app'"* ]] || fail "package is not cz.netkit.app: $package_line"
[[ $package_line == *"versionCode='$version_code'"* ]] || fail "versionCode is not $version_code: $package_line"
[[ $package_line == *"versionName='$version'"* ]] || fail "versionName is not $version: $package_line"
if printf '%s\n' "$badging" | grep -q "REQUEST_INSTALL_PACKAGES"; then
    fail "REQUEST_INSTALL_PACKAGES must never be in the APK"
fi

# 4. latest.json describes this APK (including its minSdk) on this web and repo.
min_sdk=$(printf '%s\n' "$badging" | sed -nE "s/^(minSdkVersion|sdkVersion):'([0-9]+)'$/\2/p" | head -n 1)
[[ -n $min_sdk ]] || fail "aapt2 badging has no minSdkVersion"
python3 -I "$script_dir/release_latest_json.py" --gradle-properties "$properties" \
    check "$latest" --version "$version" --apk "$apk" --cert "$cert" --min-sdk "$min_sdk" >/dev/null \
    || fail "latest.json does not match $apk_name (see the message above)"

echo "ok: $apk_name sha256=$actual cert_sha256=$cert versionCode=$version_code latest.json=ok"
