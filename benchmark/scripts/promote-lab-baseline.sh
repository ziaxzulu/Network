#!/usr/bin/env bash
set -euo pipefail

input_path=""
output_root="benchmark/build/benchmark-baselines"
baseline_name=""
manifest_paths=()
handoff_manifest_path=""
extra_validation_args=()
allow_existing=false
update_latest=true
allow_validation_bypasses=false

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/promote-lab-baseline.sh --input DIR|suite-aggregate.jsonl [options] [-- validation args]

Validates a completed lab run, then creates a compact baseline-of-record
package that can be used as the stable comparison target for future candidates.

Options before --:
  --input PATH             Lab artifact root, combined dir, or suite-aggregate.jsonl. Required.
  --out DIR                Baseline package root. Default: benchmark/build/benchmark-baselines.
  --name NAME              Baseline package name. Default: <timestamp>-<git-sha>.
  --manifest PATH          Planned manifest.jsonl. May be repeated and is passed to validation.
  --handoff-manifest PATH  Handoff manifest used to generate the lab plan; copied into the baseline package.
  --allow-existing         Allow writing into an existing baseline package directory.
  --allow-validation-bypasses Allow promotion when validation used bypass flags. Smoke only.
  --no-latest              Do not update the latest symlink.
  --help                   Show this help.

Arguments after -- are passed to validate-lab-baseline.sh, for example:
  -- --min-iterations 3 --min-contention-clients 500 --min-contention-target-client-mbps 5

Outputs under <out>/<name>/:
  baseline-manifest.json   Machine-readable promotion metadata.
  BASELINE.md              Human-readable baseline summary.
  suite-aggregate.jsonl    Comparable aggregate rows.
  validation.json          Validation result used for promotion.
  validation.md            Human-readable validation report.
  bandwidth-capacity.*     Capacity selector artifacts when present.
  topology.md              Copied topology metadata when present.
  host-reports/            Copied host reports when present.
  prereq-reports/          Copied host prerequisite reports when present.
  manifests/               Copied planned manifests when supplied.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --)
      shift
      extra_validation_args=("$@")
      break
      ;;
    --input)
      input_path="$2"
      shift 2
      ;;
    --out)
      output_root="$2"
      shift 2
      ;;
    --name)
      baseline_name="$2"
      shift 2
      ;;
    --manifest|--curve-manifest|--contention-manifest)
      manifest_paths+=("$2")
      shift 2
      ;;
    --handoff-manifest)
      handoff_manifest_path="$2"
      shift 2
      ;;
    --allow-existing)
      allow_existing=true
      shift
      ;;
    --allow-validation-bypasses)
      allow_validation_bypasses=true
      shift
      ;;
    --no-latest)
      update_latest=false
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument before --: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$input_path" ]]; then
  usage >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to promote lab baselines" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
checked_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
git_revision="$(git -C "$repo_root" rev-parse --short=12 HEAD 2>/dev/null || printf unknown)"

if [[ "$input_path" != /* ]]; then
  input_path="$repo_root/$input_path"
fi
if [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi
if [[ -n "$handoff_manifest_path" && "$handoff_manifest_path" != /* ]]; then
  handoff_manifest_path="$repo_root/$handoff_manifest_path"
fi

for i in "${!manifest_paths[@]}"; do
  if [[ "${manifest_paths[$i]}" != /* ]]; then
    manifest_paths[$i]="$repo_root/${manifest_paths[$i]}"
  fi
  if [[ ! -s "${manifest_paths[$i]}" ]]; then
    echo "manifest not found or empty: ${manifest_paths[$i]}" >&2
    exit 2
  fi
done
production_evidence_json="null"
if [[ -n "$handoff_manifest_path" ]]; then
  if [[ ! -s "$handoff_manifest_path" ]]; then
    echo "handoff manifest not found or empty: $handoff_manifest_path" >&2
    exit 2
  fi
  if ! jq -e '.kind == "raknet-lab-handoff"' "$handoff_manifest_path" >/dev/null; then
    echo "handoff manifest has an unexpected kind: $handoff_manifest_path" >&2
    exit 2
  fi
  if ! jq -e '(.productionEvidence.document // "") != "" and (.productionEvidence.exists == true) and ((.productionEvidence.sha256 // "") | test("^[0-9a-f]{64}$"))' "$handoff_manifest_path" >/dev/null; then
    echo "handoff manifest does not include a concrete production evidence fingerprint: $handoff_manifest_path" >&2
    exit 2
  fi
  production_evidence_json="$(jq -c '.productionEvidence' "$handoff_manifest_path")"
fi

safe_name() {
  local value="${1,,}"
  value="${value//[^a-z0-9._-]/-}"
  value="${value//--/-}"
  value="${value#-}"
  value="${value%-}"
  if [[ -z "$value" ]]; then
    value="baseline"
  fi
  printf '%s' "$value"
}

if [[ -z "$baseline_name" ]]; then
  baseline_name="$timestamp-$git_revision"
else
  baseline_name="$(safe_name "$baseline_name")"
fi

destination="$output_root/$baseline_name"
if [[ -e "$destination" && "$allow_existing" != "true" ]]; then
  echo "baseline package already exists: $destination" >&2
  echo "Pass --allow-existing to replace generated promotion files in that directory." >&2
  exit 2
fi

validation_tmp="$(mktemp -d)"
trap 'rm -rf "$validation_tmp"' EXIT

validation_cmd=(
  "$script_dir/validate-lab-baseline.sh"
  --input "$input_path"
  --out "$validation_tmp"
)
for manifest in "${manifest_paths[@]}"; do
  validation_cmd+=(--manifest "$manifest")
done
validation_cmd+=("${extra_validation_args[@]}")

"${validation_cmd[@]}"

validation_json="$validation_tmp/validation.json"
validation_md="$validation_tmp/validation.md"
if ! jq -e '.passed == true' "$validation_json" >/dev/null; then
  echo "validation did not pass; refusing to promote baseline" >&2
  exit 1
fi

validation_bypass_flags="$(jq -r '
  [
    ["allowUnstable", (.allowUnstable // false)],
    ["allowDisconnects", (.allowDisconnects // false)],
    ["allowMissingCapacity", (.allowMissingCapacity // false)],
    ["allowUnselectedCapacity", (.allowUnselectedCapacity // false)],
    ["allowMissingHostContext", (.allowMissingHostContext // false)],
    ["allowMissingPrereqContext", (.allowMissingPrereqContext // false)],
    ["allowLoosePrereqGates", (.allowLoosePrereqGates // false)]
  ]
  | map(select(.[1] == true) | .[0])
  | @tsv
' "$validation_json")"
if [[ -n "$validation_bypass_flags" && "$allow_validation_bypasses" != "true" ]]; then
  echo "validation used baseline bypass flags; refusing to promote baseline: $validation_bypass_flags" >&2
  echo "Use --allow-validation-bypasses only for non-baseline smoke packages." >&2
  exit 1
fi

suite_aggregate="$(jq -r '.suiteAggregate' "$validation_json")"
artifact_root="$(jq -r '.artifactRoot' "$validation_json")"
capacity_file="$(jq -r '.capacityFile // ""' "$validation_json")"
topology_file="$(jq -r '.topologyFile // ""' "$validation_json")"

mkdir -p "$destination"
rm -f \
  "$destination/suite-aggregate.jsonl" \
  "$destination/validation.json" \
  "$destination/validation.md" \
  "$destination/bandwidth-capacity.jsonl" \
  "$destination/bandwidth-capacity.csv" \
  "$destination/bandwidth-capacity.md" \
  "$destination/topology.md" \
  "$destination/handoff-manifest.json" \
  "$destination/baseline-manifest.json" \
  "$destination/BASELINE.md"
rm -rf "$destination/host-reports" "$destination/prereq-reports" "$destination/manifests"

cp "$suite_aggregate" "$destination/suite-aggregate.jsonl"
cp "$validation_json" "$destination/validation.json"
cp "$validation_md" "$destination/validation.md"

if [[ -n "$capacity_file" && -s "$capacity_file" ]]; then
  capacity_dir="$(cd "$(dirname "$capacity_file")" && pwd)"
  for file in bandwidth-capacity.jsonl bandwidth-capacity.csv bandwidth-capacity.md; do
    if [[ -s "$capacity_dir/$file" ]]; then
      cp "$capacity_dir/$file" "$destination/$file"
    fi
  done
fi

if [[ -n "$topology_file" && -s "$topology_file" ]]; then
  cp "$topology_file" "$destination/topology.md"
fi
if [[ -n "$handoff_manifest_path" ]]; then
  cp "$handoff_manifest_path" "$destination/handoff-manifest.json"
fi

host_report_count=0
if [[ -d "$artifact_root" ]]; then
  mkdir -p "$destination/host-reports"
  while IFS= read -r report; do
    [[ -z "$report" ]] && continue
    parent="$(basename "$(dirname "$report")")"
    target="$destination/host-reports/$(safe_name "$parent")-host-report.md"
    cp "$report" "$target"
    host_report_count=$((host_report_count + 1))
  done < <(find "$artifact_root" -maxdepth 2 -type f -name host-report.md 2>/dev/null | sort)
  if [[ "$host_report_count" -eq 0 ]]; then
    rmdir "$destination/host-reports" 2>/dev/null || true
  fi
fi

prereq_report_count=0
if [[ -d "$artifact_root" ]]; then
  mkdir -p "$destination/prereq-reports"
  while IFS= read -r report; do
    [[ -z "$report" ]] && continue
    parent="$(basename "$(dirname "$report")")"
    target_prefix="$destination/prereq-reports/$(safe_name "$parent")"
    cp "$report" "$target_prefix-prereq.json"
    prereq_md="$(dirname "$report")/prereq.md"
    if [[ -s "$prereq_md" ]]; then
      cp "$prereq_md" "$target_prefix-prereq.md"
    fi
    prereq_report_count=$((prereq_report_count + 1))
  done < <(find "$artifact_root" -maxdepth 2 -type f -name prereq.json 2>/dev/null | sort)
  if [[ "$prereq_report_count" -eq 0 ]]; then
    rmdir "$destination/prereq-reports" 2>/dev/null || true
  fi
fi

manifest_count=0
if [[ "${#manifest_paths[@]}" -gt 0 ]]; then
  mkdir -p "$destination/manifests"
  for manifest in "${manifest_paths[@]}"; do
    manifest_count=$((manifest_count + 1))
    parent="$(basename "$(dirname "$manifest")")"
    cp "$manifest" "$destination/manifests/$(printf '%02d' "$manifest_count")-$(safe_name "$parent")-manifest.jsonl"
  done
fi

source_paths_json="$(mktemp)"
promoted_files_json="$(mktemp)"
trap 'rm -rf "$validation_tmp" "$source_paths_json" "$promoted_files_json"' EXIT

jq -n \
  --arg input "$input_path" \
  --arg artifactRoot "$artifact_root" \
  --arg suiteAggregate "$suite_aggregate" \
  --arg capacityFile "$capacity_file" \
  --arg topologyFile "$topology_file" \
  --arg handoffManifest "$handoff_manifest_path" \
  --argjson manifests "$(printf '%s\n' "${manifest_paths[@]}" | jq -R -s 'split("\n") | map(select(length > 0))')" \
  '{
    input: $input,
    artifactRoot: $artifactRoot,
    suiteAggregate: $suiteAggregate,
    capacityFile: (if $capacityFile == "" then null else $capacityFile end),
    topologyFile: (if $topologyFile == "" then null else $topologyFile end),
    handoffManifest: (if $handoffManifest == "" then null else $handoffManifest end),
    manifests: $manifests
  }' >"$source_paths_json"

{
  echo "# RakNet Lab Baseline"
  echo
  echo "- Name: \`$baseline_name\`"
  echo "- Promoted: \`$checked_at\`"
  echo "- Git revision: \`$git_revision\`"
  echo "- Source artifact root: \`$artifact_root\`"
  echo "- Validation: \`passed\`"
  echo "- Rows: \`$(jq -r '.rowCount' "$validation_json")\`"
  echo "- Capacity rows: \`$(jq -r '.capacityRowCount' "$validation_json")\`"
  echo "- Host reports copied: \`$host_report_count\`"
  echo "- Prereq reports copied: \`$prereq_report_count\`"
  echo "- Planned manifests copied: \`$manifest_count\`"
  if [[ "$production_evidence_json" != "null" ]]; then
    echo "- Production evidence: \`$(jq -r '.document' <<<"$production_evidence_json")\`"
    echo "- Production evidence SHA-256: \`$(jq -r '.sha256' <<<"$production_evidence_json")\`"
  fi
  echo
  echo "## Scenario Counts"
  echo
  echo "| Scenario | Rows |"
  echo "| --- | ---: |"
  jq -r '.scenarioCounts | to_entries | sort_by(.key)[] | "| \(.key) | \(.value) |"' "$validation_json"
  if [[ -s "$destination/bandwidth-capacity.jsonl" ]]; then
    echo
    echo "## Selected Capacity"
    echo
    echo "| Case | Payload | Reliability | Selected | Gbps | p99 ms | Benchmark |"
    echo "| --- | ---: | --- | --- | ---: | ---: | --- |"
    jq -r '
      def fmt($value):
        if $value == null then "n/a"
        elif ($value | type) == "number" then (($value * 1000 | round) / 1000 | tostring)
        else ($value | tostring)
        end;
      . as $row |
      ($row.selectedCandidate // {}) as $selected |
      [
        ($row.case // "-"),
        (($row.payloadSize // 0) | tostring),
        ($row.reliability // "-"),
        (($row.selected // false) | tostring),
        fmt($selected.deliveredGbps),
        fmt($selected.probeRttP99Millis),
        ($selected.benchmarkName // "-")
      ] | @tsv
    ' "$destination/bandwidth-capacity.jsonl" | while IFS=$'\t' read -r case_name payload reliability selected gbps p99 benchmark; do
      echo "| $case_name | $payload | $reliability | $selected | $gbps | $p99 | $benchmark |"
    done
  fi
  echo
  echo "## Files"
  echo
  echo "- Comparable aggregate: \`suite-aggregate.jsonl\`"
  echo "- Promotion metadata: \`baseline-manifest.json\`"
  echo "- Validation report: \`validation.md\`"
  if [[ -s "$destination/bandwidth-capacity.md" ]]; then
    echo "- Capacity report: \`bandwidth-capacity.md\`"
  fi
  if [[ -s "$destination/topology.md" ]]; then
    echo "- Topology: \`topology.md\`"
  fi
  if [[ -s "$destination/handoff-manifest.json" ]]; then
    echo "- Handoff manifest: \`handoff-manifest.json\`"
  fi
} >"$destination/BASELINE.md"

find "$destination" -maxdepth 2 -type f -printf '%P\n' \
  | sort \
  | jq -R -s 'split("\n") | map(select(length > 0)) + ["baseline-manifest.json"] | unique' >"$promoted_files_json"

jq -n \
  --slurpfile validation "$validation_json" \
  --slurpfile sourcePaths "$source_paths_json" \
  --slurpfile promotedFiles "$promoted_files_json" \
  --arg name "$baseline_name" \
  --arg generatedAt "$checked_at" \
  --arg gitRevision "$git_revision" \
  --arg destination "$destination" \
  --argjson allowValidationBypasses "$allow_validation_bypasses" \
  --argjson productionEvidence "$production_evidence_json" \
  '{
    baselineKind: "raknet-lab-baseline",
    name: $name,
    generatedAt: $generatedAt,
    gitRevision: $gitRevision,
    destination: $destination,
    allowValidationBypasses: $allowValidationBypasses,
    productionEvidence: $productionEvidence,
    validation: $validation[0],
    sourcePaths: $sourcePaths[0],
    promotedFiles: $promotedFiles[0]
  }' >"$destination/baseline-manifest.json"

if "$update_latest"; then
  mkdir -p "$output_root"
  ln -sfn "$baseline_name" "$output_root/latest"
fi

echo "Promoted baseline: $destination"
echo "Baseline manifest: $destination/baseline-manifest.json"
echo "Baseline report: $destination/BASELINE.md"
if "$update_latest"; then
  echo "Latest baseline: $output_root/latest"
fi
