#!/usr/bin/env bash
# Export a reviewed working tree, never its history. See docs/public-export.md.
set -euo pipefail
if [[ $# != 1 ]]; then
    echo 'Usage: tools/make_public_export.sh EMPTY_DIRECTORY_OUTSIDE_REPOSITORY' >&2
    exit 2
fi
source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
# Locate the existing scanner before isolating Git from inherited repository state.
scanner=${NETKIT_SECRET_SCAN:-}
if [[ -z $scanner ]]; then
    hooks=$(git -C "$source_root" rev-parse --path-format=absolute --git-path hooks)
    scanner=$hooks/secret-scan.sh
fi
if [[ ! -f $scanner || ! -r $scanner ]]; then
    echo 'Missing secret scanner; set NETKIT_SECRET_SCAN to the existing secret-scan.sh.' >&2
    exit 2
fi
scanner=$(realpath -- "$scanner")
python3 -I "$source_root/tools/public_export.py" prepare "$source_root" "$1"
target=$(realpath -- "$1")
python3 -I "$source_root/tools/public_export.py" scan "$target"
# Never inherit an external index, worktree, template, identity, filters or remote.
# This affects only the new export; the source repository and its hook stay intact.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_COMMON_DIR GIT_OBJECT_DIRECTORY
unset GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_CONFIG_PARAMETERS GIT_CONFIG_COUNT
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export GIT_AUTHOR_NAME='NetKit contributors' GIT_COMMITTER_NAME='NetKit contributors'
export GIT_AUTHOR_EMAIL='netkit@example.org' GIT_COMMITTER_EMAIL='netkit@example.org'
git -C "$target" -c init.templateDir= init -q -b main
mkdir -p "$target/.git/hooks"
cp -- "$scanner" "$target/.git/hooks/secret-scan.sh"
cat > "$target/.git/hooks/pre-commit" <<'HOOK'
#!/usr/bin/env bash
set -euo pipefail
hook_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
exec bash "$hook_dir/secret-scan.sh" --staged
HOOK
chmod +x "$target/.git/hooks/pre-commit"
git -C "$target" add --all
(cd -- "$target" && bash .git/hooks/secret-scan.sh --staged)
git -C "$target" -c commit.gpgSign=false commit -q -m 'NetKit: počáteční veřejné vydání'
printf 'Public export created: %s\n' "$target"
