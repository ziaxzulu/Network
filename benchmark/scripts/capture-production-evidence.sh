#!/usr/bin/env bash
set -euo pipefail

output_root=""
require_sources=""
include_paths=false
geyser_repo="${GEYSER_REPO:-}"
cloudburst_protocol_repo="${CLOUDBURST_PROTOCOL_REPO:-}"
cloudburst_nukkit_repo="${CLOUDBURST_NUKKIT_REPO:-}"
cubecraft_repo="${CUBECRAFT_REPO:-}"
teamziax_ebpf_repo="${TEAMZIAX_EBPF_REPO:-}"

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/capture-production-evidence.sh [options]

Captures a private-safe source availability and revision audit for the
production-usage evidence behind the established RakNet benchmark matrix.

Options:
  --out DIR                         Output directory. Default: benchmark/build/benchmark-results/production-evidence-<timestamp>.
  --geyser DIR                      Geyser checkout. Env: GEYSER_REPO.
  --cloudburst-protocol DIR         Cloudburst Protocol checkout. Env: CLOUDBURST_PROTOCOL_REPO.
  --cloudburst-nukkit DIR           Cloudburst Nukkit checkout. Env: CLOUDBURST_NUKKIT_REPO.
  --cubecraft DIR                   Private CubeCraft checkout. Env: CUBECRAFT_REPO.
  --teamziax-ebpf DIR               Private teamziax/bedrock-ebpf-filter checkout. Env: TEAMZIAX_EBPF_REPO.
  --require-sources CSV             Fail when any listed source id is unavailable.
                                    IDs: geyser,cloudburst-protocol,cloudburst-nukkit,cubecraft,teamziax-ebpf.
  --include-paths                   Include absolute local checkout paths in the output. Off by default.
  --help                            Show this help.

Outputs:
  source-audit.json                 Machine-readable source audit.
  source-audit.md                   Human-readable source audit.

Private source paths are omitted by default. Use --include-paths only for local
handoff artifacts that will not be committed to a public repository.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      output_root="$2"
      shift 2
      ;;
    --geyser)
      geyser_repo="$2"
      shift 2
      ;;
    --cloudburst-protocol)
      cloudburst_protocol_repo="$2"
      shift 2
      ;;
    --cloudburst-nukkit)
      cloudburst_nukkit_repo="$2"
      shift 2
      ;;
    --cubecraft)
      cubecraft_repo="$2"
      shift 2
      ;;
    --teamziax-ebpf|--teamziax-bedrock-ebpf-filter)
      teamziax_ebpf_repo="$2"
      shift 2
      ;;
    --require-sources)
      require_sources="$2"
      shift 2
      ;;
    --include-paths)
      include_paths=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to write production source audit artifacts" >&2
  exit 2
fi
if ! command -v git >/dev/null 2>&1; then
  echo "git is required to inspect production source checkouts" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

resolve_path() {
  local path="$1"
  if [[ "$path" == /* ]]; then
    printf '%s\n' "$path"
  else
    printf '%s\n' "$repo_root/$path"
  fi
}

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/benchmark/build/benchmark-results/production-evidence-$timestamp"
else
  output_root="$(resolve_path "$output_root")"
fi
mkdir -p "$output_root"

json_file="$output_root/source-audit.json"
md_file="$output_root/source-audit.md"
source_rows="$(mktemp)"
issues_rows="$(mktemp)"
trap 'rm -f "$source_rows" "$issues_rows"' EXIT
: >"$source_rows"
: >"$issues_rows"

is_git_repo() {
  local path="$1"
  [[ -n "$path" ]] && git -C "$path" rev-parse --is-inside-work-tree >/dev/null 2>&1
}

repo_candidates() {
  local id="$1"
  case "$id" in
    geyser)
      printf '%s\n' \
        "$repo_root/../geyser-readonly" \
        "$repo_root/../../geyser-readonly" \
        "${HOME:-}/.codex/worktrees/geyser-readonly" \
        "${HOME:-}/development/ziax/geyser" \
        "${HOME:-}/development/ziax/Geyser"
      ;;
    cloudburst-protocol)
      printf '%s\n' \
        "$repo_root/../cloudburst-protocol-readonly" \
        "$repo_root/../../cloudburst-protocol-readonly" \
        "${HOME:-}/.codex/worktrees/cloudburst-protocol-readonly" \
        "${HOME:-}/development/ziax/cloudburst-protocol" \
        "${HOME:-}/development/ziax/Protocol"
      ;;
    cloudburst-nukkit)
      printf '%s\n' \
        "$repo_root/../cloudburst-nukkit-readonly" \
        "$repo_root/../../cloudburst-nukkit-readonly" \
        "${HOME:-}/.codex/worktrees/cloudburst-nukkit-readonly" \
        "${HOME:-}/development/ziax/cloudburst-nukkit" \
        "${HOME:-}/development/ziax/Nukkit"
      ;;
    cubecraft)
      printf '%s\n' \
        "$repo_root/../cubecraft" \
        "$repo_root/../../cubecraft" \
        "${HOME:-}/.codex/worktrees/ba97/cubecraft" \
        "${HOME:-}/development/ziax/cubecraft"
      ;;
    teamziax-ebpf)
      printf '%s\n' \
        "$repo_root/../teamziax-bedrock-ebpf-filter" \
        "$repo_root/../../teamziax-bedrock-ebpf-filter" \
        "${HOME:-}/.codex/worktrees/teamziax-bedrock-ebpf-filter" \
        "${HOME:-}/development/ziax/bedrock-ebpf-filter"
      ;;
  esac
}

find_repo() {
  local explicit_path="$1"
  local id="$2"

  if [[ -n "$explicit_path" ]]; then
    resolve_path "$explicit_path"
    return
  fi

  local candidate
  while IFS= read -r candidate; do
    [[ -n "$candidate" ]] || continue
    if is_git_repo "$candidate"; then
      printf '%s\n' "$candidate"
      return
    fi
  done < <(repo_candidates "$id")
}

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$path" | awk '{print $1}'
  else
    return 1
  fi
}

append_issue() {
  local code="$1"
  local source_id="$2"
  local message="$3"
  jq -n \
    --arg code "$code" \
    --arg sourceId "$source_id" \
    --arg message "$message" \
    '{code:$code,sourceId:$sourceId,message:$message}' >>"$issues_rows"
}

write_source() {
  local id="$1"
  local label="$2"
  local visibility="$3"
  local role="$4"
  local configured_path="$5"
  local canonical_source="$6"
  local matrix_signal="$7"

  local path=""
  path="$(find_repo "$configured_path" "$id" || true)"

  local available=false
  local revision=""
  local short_revision=""
  local branch=""
  local dirty=false
  local path_json="null"

  if is_git_repo "$path"; then
    available=true
    revision="$(git -C "$path" rev-parse HEAD)"
    short_revision="$(git -C "$path" rev-parse --short=12 HEAD)"
    branch="$(git -C "$path" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
    if [[ -n "$(git -C "$path" status --porcelain --untracked-files=no)" ]]; then
      dirty=true
    fi
    if "$include_paths"; then
      path_json="$(jq -n --arg path "$path" '$path')"
    fi
  elif [[ -n "$configured_path" ]]; then
    append_issue "configured-source-unavailable" "$id" "configured source path is not a readable Git worktree"
  fi

  jq -n \
    --arg id "$id" \
    --arg label "$label" \
    --arg visibility "$visibility" \
    --arg role "$role" \
    --arg canonicalSource "$canonical_source" \
    --arg matrixSignal "$matrix_signal" \
    --arg revision "$revision" \
    --arg shortRevision "$short_revision" \
    --arg branch "$branch" \
    --argjson available "$available" \
    --argjson dirty "$dirty" \
    --argjson path "$path_json" \
    '{
      id: $id,
      label: $label,
      visibility: $visibility,
      role: $role,
      canonicalSource: $canonicalSource,
      matrixSignal: $matrixSignal,
      available: $available,
      revision: $revision,
      shortRevision: $shortRevision,
      branch: $branch,
      dirtyTrackedFiles: $dirty,
      path: $path
    }' >>"$source_rows"
}

write_source "geyser" "Geyser" "public" "Bedrock proxy/session workload" "$geyser_repo" \
  "https://github.com/GeyserMC/Geyser" \
  "50ms queued Bedrock flush, MTU 1400, immediate sends, 256KiB resource-pack chunks"
write_source "cloudburst-protocol" "Cloudburst Protocol" "public" "Bedrock batching/compression boundary" "$cloudburst_protocol_repo" \
  "https://github.com/CloudburstMC/Protocol" \
  "Bedrock packets are batched/compressed/framed before Network wraps ByteBufs into RakMessages"
write_source "cloudburst-nukkit" "Cloudburst Nukkit" "public" "Server-side Bedrock traffic shape" "$cloudburst_nukkit_repo" \
  "https://github.com/CloudburstMC/Nukkit" \
  "20ms network tick, one Rak ordering channel, 256B compression threshold, 8KiB resource-pack responses"
write_source "cubecraft" "CubeCraft" "private" "Production validation" "$cubecraft_repo" \
  "private:cubecraft" \
  "role-specific client caps, one ordering channel, 10ms Rak flush, 50ms downstream batch flushing, slow-client backlog protection"
write_source "teamziax-ebpf" "TeamZiax Bedrock eBPF Filter" "private" "Optional companion VM/eBPF harness" "$teamziax_ebpf_repo" \
  "private:teamziax/bedrock-ebpf-filter" \
  "QEMU/PCAP harness shape is reusable as companion capture-replay evidence, not active RakNet workload proof"

required_json="$(printf '%s' "$require_sources" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' \
  | jq -R -s 'split("\n") | map(select(length > 0))')"

while IFS= read -r required_id; do
  [[ -n "$required_id" ]] || continue
  if ! jq -e --arg id "$required_id" 'select(.id == $id and .available == true)' "$source_rows" >/dev/null; then
    append_issue "required-source-unavailable" "$required_id" "required production source checkout is unavailable"
  fi
done < <(jq -r '.[]' <<<"$required_json")

network_revision="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || echo unknown)"
network_short_revision="$(git -C "$repo_root" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
network_dirty=false
if [[ -n "$(git -C "$repo_root" status --porcelain --untracked-files=no)" ]]; then
  network_dirty=true
fi
evidence_doc_rel="benchmark/docs/production-usage-evidence.md"
evidence_doc="$repo_root/$evidence_doc_rel"
evidence_sha256=""
if [[ -s "$evidence_doc" ]]; then
  evidence_sha256="$(sha256_file "$evidence_doc" || true)"
fi

jq -n \
  --arg kind "raknet-production-source-audit" \
  --arg generatedAt "$timestamp" \
  --arg networkRevision "$network_revision" \
  --arg networkShortRevision "$network_short_revision" \
  --arg evidenceDocument "$evidence_doc_rel" \
  --arg evidenceSha256 "$evidence_sha256" \
  --argjson networkDirty "$network_dirty" \
  --argjson includePaths "$include_paths" \
  --argjson requiredSources "$required_json" \
  --slurpfile sources "$source_rows" \
  --slurpfile issues "$issues_rows" \
  '{
    kind: $kind,
    generatedAt: $generatedAt,
    networkRevision: $networkRevision,
    networkShortRevision: $networkShortRevision,
    networkDirtyTrackedFiles: $networkDirty,
    evidenceDocument: {
      document: $evidenceDocument,
      sha256: $evidenceSha256
    },
    includePaths: $includePaths,
    requiredSources: $requiredSources,
    ready: (($issues | length) == 0),
    issueCount: ($issues | length),
    sources: $sources,
    issues: $issues
  }' >"$json_file"

{
  echo "# Production Source Audit"
  echo
  echo "- Generated: \`$timestamp\`"
  echo "- Network revision: \`$network_short_revision\`"
  echo "- Network dirty tracked files: \`$network_dirty\`"
  echo "- Evidence document: \`$evidence_doc_rel\`"
  echo "- Evidence SHA-256: \`$evidence_sha256\`"
  echo "- Required sources: \`$require_sources\`"
  echo "- Local paths included: \`$include_paths\`"
  echo "- Ready: \`$(jq -r '.ready' "$json_file")\`"
  echo
  echo "| Source | Visibility | Available | Revision | Dirty tracked files | Matrix signal |"
  echo "| --- | --- | ---: | --- | ---: | --- |"
  jq -r '.sources[] | "| `" + .id + "` | " + .visibility + " | `" + (.available|tostring) + "` | `" + (if .shortRevision == "" then "-" else .shortRevision end) + "` | `" + (.dirtyTrackedFiles|tostring) + "` | " + .matrixSignal + " |"' "$json_file"
  echo
  echo "Private checkout paths are omitted by default. Re-run with \`--include-paths\` only for local handoff artifacts that will not be committed."
  if [[ "$(jq -r '.issueCount' "$json_file")" != "0" ]]; then
    echo
    echo "## Issues"
    echo
    jq -r '.issues[] | "- `" + .code + "` for `" + .sourceId + "`: " + .message' "$json_file"
  fi
} >"$md_file"

echo "Production source audit JSON: $json_file"
echo "Production source audit report: $md_file"

if [[ "$(jq -r '.ready' "$json_file")" != "true" ]]; then
  exit 1
fi
