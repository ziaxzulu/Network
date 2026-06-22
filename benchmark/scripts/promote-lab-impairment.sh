#!/usr/bin/env bash
set -euo pipefail

input_path=""
output_root="benchmark/build/benchmark-baselines"
baseline_name=""
allow_existing=false
allow_failed_summary=false
allow_missing_netem_evidence=false
allow_validation_bypasses=false
update_latest=true

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/promote-lab-impairment.sh --input DIR|impairment-summary.json [options]

Promotes a validated multi-profile lab impairment campaign summary into a
compact baseline-of-record package that can be compared with
compare-lab-impairment.sh.

Options:
  --input PATH                    Campaign summary dir, campaign artifact root, or impairment-summary.json. Required.
  --out DIR                       Baseline package root. Default: benchmark/build/benchmark-baselines.
  --name NAME                     Baseline package name. Default: impairment-<timestamp>-<git-sha>.
  --allow-existing                Allow replacing generated files in an existing package directory.
  --allow-failed-summary          Allow promotion when impairment-summary.json is failed.
  --allow-missing-netem-evidence  Allow promotion when the summary did not require netem evidence.
  --allow-validation-bypasses     Allow promotion when profile validation used bypass flags. Smoke only.
  --no-latest                     Do not update the latest-impairment symlink.
  --help                          Show this help.

Outputs under <out>/<name>/:
  impairment-baseline-manifest.json Machine-readable promotion metadata.
  IMPAIRMENT_BASELINE.md            Human-readable campaign baseline report.
  impairment-summary.*              Comparable campaign summary files.
  campaign-manifest.jsonl           Copied plan-lab-impairment manifest when present.
  profiles/                         Per-profile validation, aggregate, capacity, and netem evidence.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
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
    --allow-existing)
      allow_existing=true
      shift
      ;;
    --allow-failed-summary)
      allow_failed_summary=true
      shift
      ;;
    --allow-missing-netem-evidence)
      allow_missing_netem_evidence=true
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
      echo "Unknown argument: $1" >&2
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
  echo "jq is required to promote lab impairment baselines" >&2
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

resolve_summary() {
  local path="$1"
  if [[ -d "$path" ]]; then
    if [[ -s "$path/impairment-summary.json" ]]; then
      path="$path/impairment-summary.json"
    elif [[ -s "$path/campaign-summary/impairment-summary.json" ]]; then
      path="$path/campaign-summary/impairment-summary.json"
    else
      echo "impairment-summary.json not found in: $path" >&2
      exit 2
    fi
  fi
  if [[ ! -s "$path" ]]; then
    echo "impairment summary not found or empty: $path" >&2
    exit 2
  fi
  if ! jq -e '.summaryKind == "raknet-lab-impairment-campaign"' "$path" >/dev/null; then
    echo "not a lab impairment campaign summary: $path" >&2
    exit 2
  fi
  printf '%s\n' "$path"
}

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

copy_if_present() {
  local source="$1"
  local target="$2"
  if [[ -s "$source" ]]; then
    mkdir -p "$(dirname "$target")"
    cp "$source" "$target"
    return 0
  fi
  return 1
}

summary_json="$(resolve_summary "$input_path")"
summary_dir="$(cd "$(dirname "$summary_json")" && pwd)"
summary_jsonl="$summary_dir/impairment-summary.jsonl"
summary_md="$summary_dir/impairment-summary.md"

if ! jq -e '.passed == true' "$summary_json" >/dev/null && [[ "$allow_failed_summary" != "true" ]]; then
  echo "impairment campaign summary did not pass; refusing to promote baseline" >&2
  exit 1
fi
if ! jq -e '.requireNetemEvidence == true' "$summary_json" >/dev/null && [[ "$allow_missing_netem_evidence" != "true" ]]; then
  echo "impairment campaign summary was generated without required netem evidence; refusing to promote baseline" >&2
  exit 1
fi
if jq -e '.allowValidationBypasses == true' "$summary_json" >/dev/null && [[ "$allow_validation_bypasses" != "true" ]]; then
  echo "impairment campaign summary allowed profile validation bypasses; refusing to promote baseline" >&2
  echo "Use --allow-validation-bypasses only for non-baseline smoke packages." >&2
  exit 1
fi

if [[ -z "$baseline_name" ]]; then
  baseline_name="impairment-$timestamp-$git_revision"
else
  baseline_name="$(safe_name "$baseline_name")"
fi

destination="$output_root/$baseline_name"
if [[ -e "$destination" && "$allow_existing" != "true" ]]; then
  echo "baseline package already exists: $destination" >&2
  echo "Pass --allow-existing to replace generated promotion files in that directory." >&2
  exit 2
fi

mkdir -p "$destination"
rm -f \
  "$destination/impairment-summary.json" \
  "$destination/impairment-summary.jsonl" \
  "$destination/impairment-summary.md" \
  "$destination/campaign-manifest.jsonl" \
  "$destination/impairment-baseline-manifest.json" \
  "$destination/IMPAIRMENT_BASELINE.md"
rm -rf "$destination/profiles"

cp "$summary_json" "$destination/impairment-summary.json"
copy_if_present "$summary_jsonl" "$destination/impairment-summary.jsonl" >/dev/null || true
copy_if_present "$summary_md" "$destination/impairment-summary.md" >/dev/null || true

campaign_manifest="$(jq -r '.manifest // ""' "$summary_json")"
if [[ -n "$campaign_manifest" && "$campaign_manifest" != "null" ]]; then
  if [[ "$campaign_manifest" != /* ]]; then
    campaign_manifest="$repo_root/$campaign_manifest"
  fi
  copy_if_present "$campaign_manifest" "$destination/campaign-manifest.jsonl" >/dev/null || true
fi

profile_count=0
copied_profile_files=0
while IFS=$'\t' read -r profile artifact_root validation_path aggregate_path capacity_path status_files_json; do
  [[ -z "$profile" ]] && continue
  profile_count=$((profile_count + 1))
  profile_dir="$destination/profiles/$(safe_name "$profile")"
  mkdir -p "$profile_dir"
  if copy_if_present "$validation_path" "$profile_dir/validation.json"; then
    copied_profile_files=$((copied_profile_files + 1))
    validation_md="$(dirname "$validation_path")/validation.md"
    if copy_if_present "$validation_md" "$profile_dir/validation.md"; then
      copied_profile_files=$((copied_profile_files + 1))
    fi
  fi
  if copy_if_present "$aggregate_path" "$profile_dir/suite-aggregate.jsonl"; then
    copied_profile_files=$((copied_profile_files + 1))
  fi
  if copy_if_present "$capacity_path" "$profile_dir/bandwidth-capacity.jsonl"; then
    copied_profile_files=$((copied_profile_files + 1))
  fi
  if [[ -n "$artifact_root" && -d "$artifact_root/combined" ]]; then
    for file in bandwidth-capacity.csv bandwidth-capacity.md; do
      if copy_if_present "$artifact_root/combined/$file" "$profile_dir/$file"; then
        copied_profile_files=$((copied_profile_files + 1))
      fi
    done
  fi
  while IFS= read -r status_file; do
    [[ -z "$status_file" ]] && continue
    if copy_if_present "$status_file" "$profile_dir/netem/$(basename "$status_file")"; then
      copied_profile_files=$((copied_profile_files + 1))
    fi
  done < <(jq -r '.[]?' <<<"$status_files_json")
done < <(jq -r '
  .profiles[]
  | [
      .profile,
      (.artifactRoot // ""),
      (.validation.path // ""),
      (.aggregate.path // ""),
      (.capacity.path // ""),
      ((.netem.statusEvidenceFiles // []) | @json)
    ] | @tsv
' "$summary_json")

{
  echo "# RakNet Lab Impairment Baseline"
  echo
  echo "- Name: \`$baseline_name\`"
  echo "- Promoted: \`$checked_at\`"
  echo "- Git revision: \`$git_revision\`"
  echo "- Source summary: \`$summary_json\`"
  echo "- Result: \`$(jq -r 'if .passed then "passed" else "failed" end' "$summary_json")\`"
  echo "- Profiles: \`$(jq -r '.profileCount' "$summary_json")\`"
  echo "- Validation passed: \`$(jq -r '.validationPassedCount' "$summary_json")\`"
  echo "- Aggregate rows: \`$(jq -r '.aggregateRowCount' "$summary_json")\`"
  echo "- Capacity rows: \`$(jq -r '.capacityRowCount' "$summary_json")\`"
  echo "- Netem status evidence files: \`$(jq -r '.netemStatusEvidenceCount' "$summary_json")\`"
  echo "- Profile files copied: \`$copied_profile_files\`"
  echo
  echo "## Profiles"
  echo
  echo "| Profile | Network | Validation | Rows | Capacity selected | Netem status |"
  echo "| --- | --- | --- | ---: | ---: | ---: |"
  jq -r '
    .profiles[]
    | [
        .profile,
        (.latency + "/" + .jitter + "/" + .loss),
        (if .validation.exists then (if .validation.passed then "passed" else "failed" end) else "missing" end),
        (.aggregate.rowCount | tostring),
        (.capacity.selectedCount | tostring),
        (.netem.statusEvidenceCount | tostring)
      ] | @tsv
  ' "$summary_json" | while IFS=$'\t' read -r profile network validation rows selected netem; do
    echo "| \`$profile\` | \`$network\` | \`$validation\` | $rows | $selected | $netem |"
  done
  echo
  echo "## Files"
  echo
  echo "- Comparable campaign summary: \`impairment-summary.json\`"
  echo "- Promotion metadata: \`impairment-baseline-manifest.json\`"
  if [[ -s "$destination/impairment-summary.md" ]]; then
    echo "- Campaign report: \`impairment-summary.md\`"
  fi
  if [[ -s "$destination/campaign-manifest.jsonl" ]]; then
    echo "- Campaign manifest: \`campaign-manifest.jsonl\`"
  fi
  echo "- Profile evidence: \`profiles/\`"
  echo
  echo "Compare a candidate campaign with:"
  echo
  echo '```bash'
  echo "benchmark/scripts/compare-lab-impairment.sh \\"
  echo "  --baseline $destination \\"
  echo "  --candidate <candidate-campaign-summary-dir> \\"
  echo "  --out <comparison.md>"
  echo '```'
} >"$destination/IMPAIRMENT_BASELINE.md"

promoted_files_json="$(mktemp)"
trap 'rm -f "$promoted_files_json"' EXIT
find "$destination" -maxdepth 4 -type f -printf '%P\n' \
  | sort \
  | jq -R -s 'split("\n") | map(select(length > 0)) + ["impairment-baseline-manifest.json"] | unique' >"$promoted_files_json"

jq -n \
  --slurpfile summary "$summary_json" \
  --slurpfile promotedFiles "$promoted_files_json" \
  --arg name "$baseline_name" \
  --arg generatedAt "$checked_at" \
  --arg gitRevision "$git_revision" \
  --arg destination "$destination" \
  --arg input "$input_path" \
  --arg summaryJson "$summary_json" \
  --arg summaryJsonl "$summary_jsonl" \
  --arg summaryMarkdown "$summary_md" \
  --arg campaignManifest "$campaign_manifest" \
  --argjson profileCount "$profile_count" \
  --argjson copiedProfileFiles "$copied_profile_files" \
  --argjson allowValidationBypasses "$allow_validation_bypasses" \
  '{
    baselineKind: "raknet-lab-impairment-campaign",
    name: $name,
    generatedAt: $generatedAt,
    gitRevision: $gitRevision,
    destination: $destination,
    sourcePaths: {
      input: $input,
      summaryJson: $summaryJson,
      summaryJsonl: (if $summaryJsonl == "" then null else $summaryJsonl end),
      summaryMarkdown: (if $summaryMarkdown == "" then null else $summaryMarkdown end),
      campaignManifest: (if $campaignManifest == "" then null else $campaignManifest end)
    },
    profileCount: $profileCount,
    copiedProfileFiles: $copiedProfileFiles,
    allowValidationBypasses: $allowValidationBypasses,
    summary: $summary[0],
    promotedFiles: $promotedFiles[0]
  }' >"$destination/impairment-baseline-manifest.json"

if "$update_latest"; then
  mkdir -p "$output_root"
  ln -sfn "$baseline_name" "$output_root/latest-impairment"
fi

echo "Promoted impairment baseline: $destination"
echo "Impairment baseline manifest: $destination/impairment-baseline-manifest.json"
echo "Impairment baseline report: $destination/IMPAIRMENT_BASELINE.md"
if "$update_latest"; then
  echo "Latest impairment baseline: $output_root/latest-impairment"
fi
