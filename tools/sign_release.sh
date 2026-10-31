#!/usr/bin/env bash
# Signs a NetKit release draft locally and prepares every asset the app and the web need (F7).
#
#   tools/sign_release.sh X.Y.Z --keystore PATH --alias NAME [--notes-cs TEXT] [--notes-en TEXT]
#                         [--unsigned PATH] [--upload] [--drop-mapping]
#   tools/sign_release.sh --print-fingerprint --keystore PATH --alias NAME
#
# 1. tag vX.Y.Z must equal netkitVersion (tools/check_release_version.sh);
# 2. the unsigned APK comes from the DRAFT release (release.yml) or from --unsigned; its hash is
#    checked against the draft's SHA256SUMS;
# 3. zipalign (16 KB pages) if needed, apksigner sign: apksigner asks for the passwords itself,
#    they never appear on the command line, in the environment or in a file;
# 4. the signer certificate must equal the fingerprint in docs/distribuce.md §2;
# 5. latest.json (tools/release_latest_json.py), new SHA256SUMS (APK + latest.json),
#    tools/verify_release_assets.sh — the same check publish.yml runs;
# 6. --upload: adds netkit-X.Y.Z.apk, latest.json and SHA256SUMS to the draft and removes the
#    unsigned APK (and with --drop-mapping the mapping file). Assets must be complete before
#    publishing: immutable releases lock them. The script never publishes.
#
# Work files stay in a new directory under ${TMPDIR:-/tmp}; the keystore is only read by apksigner/keytool.
# Tests: NETKIT_GRADLE_PROPERTIES, NETKIT_EXPECTED_CERT_SHA256 (see verify_release_assets.sh),
# NETKIT_ALLOW_DRYRUN=1 to sign a dry-run draft with a test key.
# Residual risk (security review 2026-10-29, O2): the draft's SHA256SUMS comes from the same CI run as the APK, so it
# protects the download, not a compromised CI; a reproducible local build would be the stronger check.
set -euo pipefail

usage() {
    sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}
fail() {
    echo "error: $*" >&2
    exit 1
}

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root=$(cd -- "$script_dir/.." && pwd)
build_tools=${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/35.0.0
apksigner=${APKSIGNER:-$build_tools/apksigner}
zipalign=${ZIPALIGN:-$build_tools/zipalign}
properties=${NETKIT_GRADLE_PROPERTIES:-"$root/gradle.properties"}

version='' keystore='' alias_name='' notes_cs='' notes_en='' unsigned='' upload=0 drop_mapping=0 print_fp=0
while [[ $# -gt 0 ]]; do
    case $1 in
        --keystore) keystore=${2:?}; shift 2 ;;
        --alias) alias_name=${2:?}; shift 2 ;;
        --notes-cs) notes_cs=${2:?}; shift 2 ;;
        --notes-en) notes_en=${2:?}; shift 2 ;;
        --unsigned) unsigned=${2:?}; shift 2 ;;
        --upload) upload=1; shift ;;
        --drop-mapping) drop_mapping=1; shift ;;
        --print-fingerprint) print_fp=1; shift ;;
        -h | --help) usage ;;
        -*) fail "unknown option $1" ;;
        *) [[ -z $version ]] || usage; version=$1; shift ;;
    esac
done
[[ -n $keystore && -n $alias_name ]] || usage
[[ -f $keystore ]] || fail "keystore $keystore not found"
command -v keytool >/dev/null || fail "keytool not found (JDK on PATH)"

if [[ $print_fp -eq 1 ]]; then
    # keytool -exportcert writes the DER certificate; its SHA-256 is what apksigner prints.
    # keytool asks for the store password itself.
    fp=$(keytool -exportcert -keystore "$keystore" -alias "$alias_name" | sha256sum | awk '{ print $1 }')
    echo "SHA-256 otisk certifikátu: $fp"
    echo "Zapiš ho do docs/distribuce.md §2 (řádek Otisk release certifikátu) a web/netkit/README.md."
    exit 0
fi
[[ -n $version ]] || usage
[[ -x $apksigner && -x $zipalign ]] || fail "apksigner/zipalign not found in $build_tools (set ANDROID_HOME, APKSIGNER, ZIPALIGN)"

tag="v$version"
"$script_dir/check_release_version.sh" "$tag" >/dev/null

source_url=$(sed -nE 's/^netkitSourceUrl=(.*)$/\1/p' "$properties" | head -n 1)
[[ $source_url =~ ^https://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)$ ]] || fail "netkitSourceUrl in $properties is not a GitHub repo"
repo=${BASH_REMATCH[1]}

if [[ -n ${NETKIT_EXPECTED_CERT_SHA256:-} ]]; then
    expected=$NETKIT_EXPECTED_CERT_SHA256
else
    # The backticks are the Markdown code span around the value, not command substitution.
    # shellcheck disable=SC2016
    expected=$(sed -nE 's/^- \*\*Otisk release certifikátu \(SHA-256\):\*\* `([^`]*)`.*$/\1/p' "$root/docs/distribuce.md" | head -n 1)
fi
expected=$(printf '%s' "$expected" | tr -d ':' | tr '[:upper:]' '[:lower:]')
[[ $expected =~ ^[0-9a-f]{64}$ ]] || fail "fingerprint in docs/distribuce.md §2 is not filled in (run --print-fingerprint, commit it before tagging)"

work=$(mktemp -d "${TMPDIR:-/tmp}/netkit-release-$version.XXXXXX")
echo "work directory: $work"
unsigned_name="netkit-$version-unsigned.apk"
if [[ -z $unsigned ]]; then
    command -v gh >/dev/null || fail "gh CLI not found"
    [[ $(gh release view "$tag" -R "$repo" --json isDraft --jq .isDraft) == true ]] || fail "$tag is not a draft release in $repo"
    # A dry-run draft (release.yml workflow_dispatch) is built with the placeholder web; never sign it for real.
    draft_name=$(gh release view "$tag" -R "$repo" --json name --jq .name)
    if [[ $draft_name == *"zkušební běh"* && ${NETKIT_ALLOW_DRYRUN:-0} != 1 ]]; then
        fail "$tag is a dry-run draft ($draft_name); delete it and build the release from the tag"
    fi
    gh release download "$tag" -R "$repo" --dir "$work/draft" --pattern "$unsigned_name" --pattern SHA256SUMS
    (cd "$work/draft" && sha256sum --check --ignore-missing --strict SHA256SUMS) || fail "unsigned APK does not match the draft's SHA256SUMS"
    unsigned=$work/draft/$unsigned_name
fi
[[ -f $unsigned ]] || fail "$unsigned not found"

apk_name="netkit-$version.apk"
aligned=$unsigned
if ! "$zipalign" -c -P 16 4 "$unsigned" >/dev/null; then
    aligned=$work/aligned.apk
    "$zipalign" -P 16 -f 4 "$unsigned" "$aligned"
fi
echo "apksigner sign: zadej hesla ke keystore a klíči"
"$apksigner" sign --ks "$keystore" --ks-key-alias "$alias_name" --out "$work/$apk_name" "$aligned"
rm -f "$work/$apk_name.idsig"

cert=$("$apksigner" verify --print-certs "$work/$apk_name" | sed -nE 's/^Signer #1 certificate SHA-256 digest: ([0-9a-f]+)$/\1/p')
[[ $cert == "$expected" ]] || fail "signed with certificate $cert, docs/distribuce.md expects $expected"

min_sdk=$("${AAPT2:-$build_tools/aapt2}" dump badging "$work/$apk_name" | sed -nE "s/^(minSdkVersion|sdkVersion):'([0-9]+)'$/\2/p" | head -n 1)
[[ -n $min_sdk ]] || fail "aapt2 badging has no minSdkVersion"
notes_args=(--min-sdk "$min_sdk")
[[ -n $notes_cs ]] && notes_args+=(--notes-cs "$notes_cs")
[[ -n $notes_en ]] && notes_args+=(--notes-en "$notes_en")
python3 -I "$script_dir/release_latest_json.py" --gradle-properties "$properties" build \
    --version "$version" --apk "$work/$apk_name" --cert "$cert" --out "$work/latest.json" "${notes_args[@]}"
(cd "$work" && sha256sum -- "$apk_name" latest.json > SHA256SUMS)
NETKIT_GRADLE_PROPERTIES=$properties NETKIT_EXPECTED_CERT_SHA256=$expected AAPT2=${AAPT2:-$build_tools/aapt2} \
    APKSIGNER=$apksigner "$script_dir/verify_release_assets.sh" "$work" "$version"

# Release notes for the public: the CHANGELOG section of this version and the verification data.
fingerprint=$(printf '%s' "$cert" | sed -E 's/(..)/\1:/g; s/:$//' | tr '[:lower:]' '[:upper:]')
{
    awk -v v="$version" '$0 ~ "^## " v "([^0-9.]|$)" { on = 1; next } /^## / { on = 0 } on' "$root/CHANGELOG.md"
    echo
    echo "### Ověření / Verification"
    echo
    echo "- SHA-256 \`$apk_name\`: \`$(awk -v n="$apk_name" '$2 == n { print $1 }' "$work/SHA256SUMS")\`"
    echo "- SHA-256 otisk podpisového certifikátu / signing certificate: \`$fingerprint\`"
    echo "- \`apksigner verify --print-certs $apk_name\`"
} > "$work/notes.md"

if [[ $upload -eq 1 ]]; then
    gh release upload "$tag" -R "$repo" --clobber "$work/$apk_name" "$work/latest.json" "$work/SHA256SUMS"
    gh release edit "$tag" -R "$repo" --notes-file "$work/notes.md"
    gh release delete-asset "$tag" "$unsigned_name" -R "$repo" --yes
    if [[ $drop_mapping -eq 1 ]]; then
        gh release delete-asset "$tag" "netkit-$version-mapping.txt" -R "$repo" --yes
    fi
    echo "Draft $tag je připravený. Zkontroluj ho na GitHubu a publikuj ručně:"
    echo "  gh release edit $tag -R $repo --draft=false"
else
    echo "Bez --upload: soubory jsou v $work, draft se nezměnil."
fi
