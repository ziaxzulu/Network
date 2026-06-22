#!/usr/bin/env bash
set -euo pipefail

output_root=""
artifact_root="benchmark/build/benchmark-results/lab-impairment"
interface=""
profiles="perfect,near-loss,regional-loss,poor,severe"
case_prefix="lab-impairment"
target_host_role="receiver-a"
sudo_netem=false
baseline_args=()

usage() {
  cat <<'USAGE'
Usage:
  benchmark/scripts/plan-lab-impairment.sh --interface eth0 [options] -- [plan-lab-baseline options]

Generates a host/NIC-level impairment campaign for remote RakNet lab baselines.
For each selected profile it creates:
  - a complete plan-lab-baseline.sh output directory
  - netem apply/status/clear scripts for the impaired host or namespace
  - a campaign-level freshness check over every generated profile plan
  - a campaign manifest and README tying qdisc state to benchmark artifacts

Options before --:
  --out DIR                    Plan output root. Default: benchmark/build/benchmark-results/lab-impairment-plan-<timestamp>.
  --artifact-root DIR          Artifact root used by generated profile plans. Default: benchmark/build/benchmark-results/lab-impairment.
  --interface IFACE            Host/NIC interface shaped with tc netem. Required.
  --profiles CSV               Profiles to generate. Default: perfect,near-loss,regional-loss,poor,severe.
  --case-prefix NAME           Case prefix for generated profile plans. Default: lab-impairment.
  --target-host-role ROLE      Human label for the host/namespace that runs netem scripts. Default: receiver-a.
  --sudo-netem                 Prefix generated netem commands with sudo.
  --help                       Show this help.

Known profiles:
  perfect        clear qdisc, no impairment
  near-loss      10ms latency, 2ms jitter, 2% loss
  regional-loss  50ms latency, 5ms jitter, 2% loss
  poor           100ms latency, 10ms jitter, 5% loss
  severe         200ms latency, 20ms jitter, 10% loss

Example:
  benchmark/scripts/plan-lab-impairment.sh \
    --out benchmark/build/benchmark-results/lab-impairment-plan \
    --artifact-root benchmark/build/benchmark-results/lab-impairment \
    --interface eth0 \
    --sudo-netem \
    -- \
    --server-host <server-ip> \
    --curve-receiver receiver-a=1 \
    --contention-receiver receiver-a=100 \
    --raised-packet-limit 100000 \
    --raised-global-packet-limit 1000000
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --)
      shift
      baseline_args=("$@")
      break
      ;;
    --out)
      output_root="$2"
      shift 2
      ;;
    --artifact-root)
      artifact_root="$2"
      shift 2
      ;;
    --interface)
      interface="$2"
      shift 2
      ;;
    --profiles)
      profiles="$2"
      shift 2
      ;;
    --case-prefix|--case)
      case_prefix="$2"
      shift 2
      ;;
    --target-host-role)
      target_host_role="$2"
      shift 2
      ;;
    --sudo-netem)
      sudo_netem=true
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

if [[ -z "$interface" ]]; then
  echo "--interface is required" >&2
  usage >&2
  exit 2
fi
if [[ -z "$profiles" || "$profiles" == *, || "$profiles" == ,* ]]; then
  echo "--profiles must be a non-empty CSV value" >&2
  exit 2
fi
if [[ "${#baseline_args[@]}" -eq 0 ]]; then
  echo "plan-lab-baseline options are required after --" >&2
  usage >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
baseline_planner="$script_dir/plan-lab-baseline.sh"
netem_script="$script_dir/raknet-netem.sh"

if [[ -z "$output_root" ]]; then
  output_root="$repo_root/benchmark/build/benchmark-results/lab-impairment-plan-$timestamp"
elif [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi
if [[ "$artifact_root" != /* ]]; then
  artifact_root_default="\$REPO_ROOT/$artifact_root"
else
  artifact_root_default="$artifact_root"
fi

mkdir -p "$output_root/netem"
manifest="$output_root/manifest.jsonl"
readme="$output_root/README.md"
freshness_script="$output_root/check-plan-freshness.sh"
validate_all_script="$output_root/validate-all.sh"
summary_script="$output_root/summarize-campaign.sh"
: >"$manifest"

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  printf '%s' "$value"
}

profile_spec() {
  case "$1" in
    perfect)
      printf '%s|%s|%s\n' "0ms" "0ms" "0%"
      ;;
    near-loss)
      printf '%s|%s|%s\n' "10ms" "2ms" "2%"
      ;;
    regional-loss)
      printf '%s|%s|%s\n' "50ms" "5ms" "2%"
      ;;
    poor)
      printf '%s|%s|%s\n' "100ms" "10ms" "5%"
      ;;
    severe)
      printf '%s|%s|%s\n' "200ms" "20ms" "10%"
      ;;
    *)
      echo "Unknown impairment profile: $1" >&2
      exit 2
      ;;
  esac
}

write_netem_script() {
  local path="$1"
  local profile="$2"
  local action="$3"
  local latency="$4"
  local jitter="$5"
  local loss="$6"
  local profile_artifact_root="$7"
  local profile_artifact_root_default
  if [[ "$profile_artifact_root" == /* ]]; then
    profile_artifact_root_default="$profile_artifact_root"
  else
    profile_artifact_root_default="\$REPO_ROOT/$profile_artifact_root"
  fi
  local netem_action="$action"
  local allow_failure=false
  if [[ "$action" == "apply" && "$latency" == "0ms" && "$jitter" == "0ms" && "$loss" == "0%" ]]; then
    netem_action="clear"
    allow_failure=true
  elif [[ "$action" == "clear" ]]; then
    allow_failure=true
  fi
  cat >"$path" <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="\${REPO_ROOT:-\$(pwd)}"
PROFILE_ARTIFACT_ROOT="\${PROFILE_ARTIFACT_ROOT:-$profile_artifact_root_default}"
NETEM_EVIDENCE_DIR="\${NETEM_EVIDENCE_DIR:-\$PROFILE_ARTIFACT_ROOT/netem}"
cd "\$REPO_ROOT"
mkdir -p "\$NETEM_EVIDENCE_DIR"

PROFILE="$profile"
REQUESTED_ACTION="$action"
NETEM_ACTION="$netem_action"
INTERFACE="$interface"
LATENCY="$latency"
JITTER="$jitter"
LOSS="$loss"
ALLOW_FAILURE="$allow_failure"
USE_SUDO="$sudo_netem"
EVIDENCE_FILE="\$NETEM_EVIDENCE_DIR/\$PROFILE-\$REQUESTED_ACTION-\$(date -u +%Y%m%dT%H%M%SZ).txt"

cmd=(benchmark/scripts/raknet-netem.sh --interface "\$INTERFACE" --action "\$NETEM_ACTION")
if [[ "\$NETEM_ACTION" == "apply" ]]; then
  cmd+=(--latency "\$LATENCY" --jitter "\$JITTER" --loss "\$LOSS")
fi
if [[ "\$USE_SUDO" == "true" ]]; then
  cmd=(sudo "\${cmd[@]}")
fi

status=0
{
  echo "# RakNet Benchmark Netem Evidence"
  echo "profile=\$PROFILE"
  echo "requested_action=\$REQUESTED_ACTION"
  echo "netem_action=\$NETEM_ACTION"
  echo "interface=\$INTERFACE"
  echo "latency=\$LATENCY"
  echo "jitter=\$JITTER"
  echo "loss=\$LOSS"
  echo "utc=\$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'command='
  printf '%q ' "\${cmd[@]}"
  echo
  echo
  "\${cmd[@]}"
} >"\$EVIDENCE_FILE" 2>&1 || status="\$?"

cat "\$EVIDENCE_FILE"
echo "Netem evidence: \$EVIDENCE_FILE"
if [[ "\$status" -ne 0 && "\$ALLOW_FAILURE" == "true" ]]; then
  exit 0
fi
exit "\$status"
EOF
  chmod +x "$path"
}

IFS=',' read -r -a selected_profiles <<<"$profiles"
profile_names=()

for raw_profile in "${selected_profiles[@]}"; do
  profile="${raw_profile//[[:space:]]/}"
  [[ -z "$profile" ]] && continue
  spec="$(profile_spec "$profile")"
  IFS='|' read -r latency jitter loss <<<"$spec"
  profile_names+=("$profile")

  profile_plan="$output_root/$profile-plan"
  profile_artifact_root="$artifact_root/$profile"
  apply_script="$output_root/netem/$profile-apply.sh"
  status_script="$output_root/netem/$profile-status.sh"
  clear_script="$output_root/netem/$profile-clear.sh"

  "$baseline_planner" \
    --out "$profile_plan" \
    --artifact-root "$profile_artifact_root" \
    --case "$case_prefix-$profile" \
    "${baseline_args[@]}"

  write_netem_script "$apply_script" "$profile" apply "$latency" "$jitter" "$loss" "$profile_artifact_root"
  write_netem_script "$status_script" "$profile" status "$latency" "$jitter" "$loss" "$profile_artifact_root"
  write_netem_script "$clear_script" "$profile" clear "$latency" "$jitter" "$loss" "$profile_artifact_root"

  printf '{"profile":"%s","latency":"%s","jitter":"%s","loss":"%s","targetHostRole":"%s","interface":"%s","plan":"%s","artifactRoot":"%s","netemEvidenceDir":"%s","applyScript":"%s","statusScript":"%s","clearScript":"%s"}\n' \
    "$(json_escape "$profile")" \
    "$(json_escape "$latency")" \
    "$(json_escape "$jitter")" \
    "$(json_escape "$loss")" \
    "$(json_escape "$target_host_role")" \
    "$(json_escape "$interface")" \
    "$(json_escape "$profile_plan")" \
    "$(json_escape "$profile_artifact_root")" \
    "$(json_escape "$profile_artifact_root/netem")" \
    "$(json_escape "$apply_script")" \
    "$(json_escape "$status_script")" \
    "$(json_escape "$clear_script")" >>"$manifest"
done

if [[ "${#profile_names[@]}" -eq 0 ]]; then
  echo "No impairment profiles selected" >&2
  exit 2
fi

cat >"$freshness_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="\${REPO_ROOT:-\$(pwd)}"
MIN_LEAD_SECONDS="\${MIN_LEAD_SECONDS:-60}"
FRESHNESS_JSON="\${FRESHNESS_JSON:-$output_root/plan-freshness.json}"
MANIFEST="$manifest"
cd "\$REPO_ROOT"

if ! [[ "\$MIN_LEAD_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "MIN_LEAD_SECONDS must be a non-negative integer" >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to check impairment plan freshness" >&2
  exit 2
fi

rows_tmp="\$(mktemp)"
trap 'rm -f "\$rows_tmp"' EXIT
checked_at="\$(date -u +%Y-%m-%dT%H:%M:%SZ)"
status=0
while IFS=\$'\t' read -r profile plan; do
  if [[ -z "\$profile" || -z "\$plan" ]]; then
    continue
  fi
  if [[ "\$plan" != /* ]]; then
    plan="\$REPO_ROOT/\$plan"
  fi
  check="\$plan/check-plan-freshness.sh"
  echo "==> profile \$profile"
  if [[ ! -x "\$check" ]]; then
    echo "missing freshness check: \$check" >&2
    jq -c -n \
      --arg profile "\$profile" \
      --arg plan "\$plan" \
      --arg check "\$check" \
      '{profile:\$profile, plan:\$plan, check:\$check, passed:false, result:"missing-freshness-check"}' >>"\$rows_tmp"
    status=1
    continue
  fi
  profile_json="\$plan/plan-freshness.json"
  if ! MIN_LEAD_SECONDS="\$MIN_LEAD_SECONDS" "\$check"; then
    status=1
  fi
  if [[ -s "\$profile_json" ]]; then
    jq -c \
      --arg profile "\$profile" \
      --arg plan "\$plan" \
      --arg check "\$check" \
      '. + {profile:\$profile, plan:\$plan, check:\$check}' "\$profile_json" >>"\$rows_tmp"
  else
    jq -c -n \
      --arg profile "\$profile" \
      --arg plan "\$plan" \
      --arg check "\$check" \
      '{profile:\$profile, plan:\$plan, check:\$check, passed:false, result:"missing-profile-freshness-json"}' >>"\$rows_tmp"
    status=1
  fi
done < <(jq -r '[.profile, .plan] | @tsv' "\$MANIFEST")

passed_json=false
if [[ "\$status" -eq 0 ]]; then
  passed_json=true
fi
mkdir -p "\$(dirname "\$FRESHNESS_JSON")"
jq -s \
  --arg kind "raknet-lab-impairment-plan-freshness" \
  --arg checkedAt "\$checked_at" \
  --arg manifest "\$MANIFEST" \
  --argjson minimumLeadSeconds "\$MIN_LEAD_SECONDS" \
  --argjson passed "\$passed_json" \
  '{kind:\$kind, checkedAt:\$checkedAt, passed:\$passed, minimumLeadSeconds:\$minimumLeadSeconds, manifest:\$manifest, profiles:.}' \
  "\$rows_tmp" >"\$FRESHNESS_JSON"
echo "freshness_json=\$FRESHNESS_JSON"

exit "\$status"
EOF
chmod +x "$freshness_script"

cat >"$validate_all_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="\${REPO_ROOT:-\$(pwd)}"
REQUIRE_NETEM_EVIDENCE="\${REQUIRE_NETEM_EVIDENCE:-true}"
MANIFEST="$manifest"
cd "\$REPO_ROOT"
EOF
for profile in "${profile_names[@]}"; do
  printf '%q\n' "$output_root/$profile-plan/merge-all.sh" >>"$validate_all_script"
done
cat >>"$validate_all_script" <<'EOF'

if [[ "$REQUIRE_NETEM_EVIDENCE" == "true" ]]; then
  if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required to validate netem evidence" >&2
    exit 2
  fi
  missing=0
  while IFS=$'\t' read -r profile evidence_dir; do
    if [[ -z "$profile" || -z "$evidence_dir" ]]; then
      continue
    fi
    if [[ "$evidence_dir" != /* ]]; then
      evidence_dir="$REPO_ROOT/$evidence_dir"
    fi
    if ! compgen -G "$evidence_dir/$profile-status-*.txt" >/dev/null; then
      echo "Missing netem status evidence for profile '$profile': $evidence_dir/$profile-status-*.txt" >&2
      missing=1
    fi
  done < <(jq -r '[.profile, .netemEvidenceDir] | @tsv' "$MANIFEST")
  if [[ "$missing" -ne 0 ]]; then
    echo "Copy generated netem evidence directories back with the profile artifacts, or set REQUIRE_NETEM_EVIDENCE=false for non-baseline smoke validation." >&2
    exit 1
  fi
fi
EOF
chmod +x "$validate_all_script"

cat >"$summary_script" <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="\${REPO_ROOT:-\$(pwd)}"
SUMMARY_OUT="\${SUMMARY_OUT:-$artifact_root_default/campaign-summary}"
cd "\$REPO_ROOT"
benchmark/scripts/summarize-lab-impairment.sh --manifest "$manifest" --out "\$SUMMARY_OUT" "\$@"
EOF
chmod +x "$summary_script"

cat >>"$validate_all_script" <<EOF

summary_args=()
if [[ "\$REQUIRE_NETEM_EVIDENCE" != "true" ]]; then
  summary_args+=(--allow-missing-netem-evidence)
fi
"$summary_script" "\${summary_args[@]}"
EOF

{
  echo "# Lab Impairment Plan"
  echo
  echo "- Generated: \`$timestamp\`"
  echo "- Artifact root: \`$artifact_root\`"
  echo "- Interface: \`$interface\`"
  echo "- Target host role: \`$target_host_role\`"
  echo "- Profiles: \`$(IFS=','; echo "${profile_names[*]}")\`"
  echo "- Sudo netem: \`$sudo_netem\`"
  echo "- Manifest: \`$manifest\`"
  echo "- Freshness check: \`$freshness_script\`"
  echo "- Validate all: \`$validate_all_script\`"
  echo "- Summarize campaign: \`$summary_script\`"
  echo
  echo "## Run Order"
  echo
  echo "For each profile, run the netem apply script on the \`$target_host_role\` host or network namespace before starting that profile's generated remote-worker plan. Run the status script after applying, then run the clear script after the profile completes. Each script prints its command output and also writes timestamped evidence under that profile's artifact root."
  echo
  echo "| Profile | Latency | Jitter | Loss | Plan | Evidence dir | Netem apply | Netem status | Netem clear |"
  echo "| --- | ---: | ---: | ---: | --- | --- | --- | --- | --- |"
  jq -r '. | "| `\(.profile)` | `\(.latency)` | `\(.jitter)` | `\(.loss)` | `\(.plan)` | `\(.netemEvidenceDir)` | `\(.applyScript)` | `\(.statusScript)` | `\(.clearScript)` |"' "$manifest"
  echo
  echo "## Notes"
  echo
  echo "- Run \`check-plan-freshness.sh\` shortly before starting the campaign. It runs every generated profile plan's freshness check and fails when any scheduled start timestamp is stale or too close."
  echo "- Use the \`perfect\` profile to clear qdisc state and capture the no-impairment baseline."
  echo "- Each generated netem script writes a timestamped evidence file under \`<profile artifact root>/netem/\`; copy that directory back with the benchmark artifacts."
  echo "- Keep impaired or disappearing clients isolated to the shaped receiver host when exact healthy/affected attribution matters."
  echo "- Copy receiver artifacts back under each profile artifact root, then run that profile's \`merge-all.sh\`."
  echo "- After every profile is merged, run \`validate-all.sh\` as a convenience check over all generated profile plans. It requires \`<profile>-status-*.txt\` evidence by default; set \`REQUIRE_NETEM_EVIDENCE=false\` only for non-baseline smoke validation."
  echo "- The generated \`summarize-campaign.sh\` writes campaign-level \`impairment-summary.json\`, \`impairment-summary.jsonl\`, and \`impairment-summary.md\` after profile artifacts have been merged."
  echo "- \`ARTIFACT_ROOT\` can override the default artifact root when running generated merge scripts. Default: \`$artifact_root_default\`."
} >"$readme"

echo "Lab impairment plan: $output_root"
echo "Manifest: $manifest"
echo "README: $readme"
echo "Freshness check: $freshness_script"
echo "Validate all: $validate_all_script"
echo "Summarize campaign: $summary_script"
