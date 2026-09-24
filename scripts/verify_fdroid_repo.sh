#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    printf 'Usage: %s <repo-directory-or-https-url>\n' "$0" >&2
    exit 2
fi

repo_root="${1%/}"

fetch() {
    local relative_path="$1"
    if [[ "$repo_root" == https://* ]]; then
        curl -fsSL --max-time 90 "$repo_root/$relative_path"
    else
        if [[ ! -f "$repo_root/$relative_path" ]]; then
            printf 'Missing: %s\n' "$relative_path" >&2
            return 1
        fi
        command cat "$repo_root/$relative_path"
    fi
}

verify_hash() {
    local index_path="$1" expected_hash="$2" relative_path actual_hash
    relative_path="${index_path#/}"
    if [[ "$relative_path" == "$index_path" || "$relative_path" == *'..'* ||
          ! "$relative_path" =~ ^[A-Za-z0-9_./-]+$ ||
          ! "$expected_hash" =~ ^[a-f0-9]{64}$ ]]; then
        printf 'Unsafe or invalid index entry: %s\n' "$index_path" >&2
        return 1
    fi
    actual_hash="$(fetch "$relative_path" | sha256sum | cut -d' ' -f1)"
    if [[ "$actual_hash" != "$expected_hash" ]]; then
        printf 'SHA-256 mismatch: %s\n' "$relative_path" >&2
        return 1
    fi
    printf 'OK %s\n' "$relative_path"
}

entry_json="$(fetch entry.json)"
index_json="$(fetch index-v2.json)"
fetch entry.jar > /dev/null
fetch index-v1.jar > /dev/null
printf '%s' "$entry_json" | jq -e '.index.name and .index.sha256 and .diffs' > /dev/null
printf '%s' "$index_json" | jq -e '.repo.address and .packages' > /dev/null

while IFS=$'\t' read -r index_path expected_hash; do
    verify_hash "$index_path" "$expected_hash"
done < <(printf '%s' "$entry_json" | jq -r '[.index, (.diffs[]?)][] | [.name, .sha256] | @tsv')

while IFS=$'\t' read -r index_path expected_hash; do
    verify_hash "$index_path" "$expected_hash"
done < <(printf '%s' "$index_json" | jq -r '.packages[]?.versions[]?.file | [.name, .sha256] | @tsv')

printf 'F-Droid repository references are complete.\n'
