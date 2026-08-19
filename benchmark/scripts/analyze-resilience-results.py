#!/usr/bin/env python3
"""Fail-closed analysis for event-aligned RakNet resilience campaigns."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


SCHEMA_VERSION = 1
TIMELINE_SCHEMA_VERSION = 2
LEGACY_TIMELINE_SCHEMA_VERSION = 1
EVENT_LABELS = ("initial-netem", "external-blackhole", "external-recovery")
PRESSURE_FIELDS = (
    "nackRetransmittedDatagramsDelta",
    "nackRetransmittedBytesDelta",
    "timeoutRetransmittedDatagramsDelta",
    "timeoutRetransmittedBytesDelta",
    "nackRetransmittedDatagramsPerSecond",
    "timeoutRetransmittedDatagramsPerSecond",
    "peakCurrentQueuedBytesAbovePreEvent",
    "currentQueuedBytesAtTPlus10AbovePreEvent",
    "peakCurrentBytesInFlightAbovePreEvent",
    "currentBytesInFlightAtTPlus10AbovePreEvent",
)
REDUCTION_FIELDS = (
    "retransmittedDatagramsDelta",
    "retransmittedBytesDelta",
    "retransmittedDatagramsPerSecond",
)
QUEUE_FIELDS = (
    "maximumCurrentBytes",
    "currentBytesAtTPlus10",
)
FULL_CAMPAIGN_PROFILES = ("perfect", "near-loss", "regional-loss", "poor", "severe", "blackhole")
RECOVERY_MODES = ("legacy", "bounded", "model_based")
DEFAULT_MAX_PEER_QUEUE_BYTES = 8 * 1024 * 1024
DEFAULT_MAX_HEALTHY_QUEUE_BYTES_PER_CLIENT = 1024 * 1024
DEFAULT_MAX_AFFECTED_QUEUE_BYTES_PER_CLIENT = 8 * 1024 * 1024
MAX_TIMELINE_SAMPLE_GAP_MILLIS = 500
QDISC_SAMPLE_TOLERANCE_MILLIS = 1500
COUNTERS = (
    "disconnectEvents",
    "nackRetransmittedDatagrams",
    "nackRetransmittedBytes",
    "timeoutRetransmittedDatagrams",
    "timeoutRetransmittedBytes",
    "acknowledgementProgressEvents",
    "acknowledgementProgressBytes",
)
GAUGES = (
    "currentQueuedBytes",
    "sampledQueuedBytesHighWater",
    "maxPeerQueuedBytes",
    "currentBytesInFlight",
    "sampledBytesInFlightHighWater",
    "maxPeerBytesInFlight",
)


class AnalysisError(Exception):
    pass


def finite_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def maximum_available(values: Iterable[Any]) -> float | int | None:
    usable = [value for value in values if finite_number(value)]
    return max(usable) if usable else None


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AnalysisError(f"invalid JSON {path}: {error}") from error
    if not isinstance(value, dict):
        raise AnalysisError(f"expected a JSON object: {path}")
    return value


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise AnalysisError(f"unable to read {path}: {error}") from error
    if not lines:
        raise AnalysisError(f"JSONL is missing or empty: {path}")
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            raise AnalysisError(f"blank JSONL record at {path}:{line_number}")
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise AnalysisError(f"invalid JSONL at {path}:{line_number}: {error}") from error
        if not isinstance(row, dict):
            raise AnalysisError(f"JSONL record is not an object at {path}:{line_number}")
        rows.append(row)
    return rows


def generated_at() -> str:
    source_epoch = os.environ.get("SOURCE_DATE_EPOCH")
    if source_epoch is not None:
        instant = dt.datetime.fromtimestamp(int(source_epoch), tz=dt.timezone.utc)
    else:
        instant = dt.datetime.now(tz=dt.timezone.utc)
    return instant.isoformat(timespec="seconds").replace("+00:00", "Z")


def discover_evidence_roots(inputs: Iterable[Path]) -> tuple[list[Path], list[Path], list[str]]:
    discovered_cases: set[Path] = set()
    discovered_campaigns: set[Path] = set()
    issues: list[str] = []
    for supplied in inputs:
        path = supplied.expanduser().resolve()
        if not path.exists():
            issues.append(f"input does not exist: {path}")
            continue
        if path.is_file() and path.name == "timeline.jsonl":
            ancestor = path.parent
            case_root = None
            for candidate in (ancestor, *ancestor.parents):
                if (candidate / "manifest.json").is_file():
                    case_root = candidate
                    break
            if case_root is None:
                issues.append(f"cannot infer case root for raw timeline: {path}")
            else:
                discovered_cases.add(case_root.resolve())
            continue
        if path.is_file() and path.name == "manifest.json":
            discovered_cases.add(path.parent.resolve())
            continue
        if path.is_file() and path.name == "campaign-plan.json":
            discovered_campaigns.add(path.parent.resolve())
            continue
        if path.is_file():
            issues.append(
                f"unsupported input file (expected manifest.json, timeline.jsonl, "
                f"or campaign-plan.json): {path}"
            )
            continue
        manifests = sorted(path.rglob("manifest.json"))
        campaign_plans = sorted(path.rglob("campaign-plan.json"))
        if not manifests and not campaign_plans:
            issues.append(f"no netns case manifests or campaign plans found below: {path}")
            continue
        for manifest in manifests:
            discovered_cases.add(manifest.parent.resolve())
        for campaign_plan in campaign_plans:
            discovered_campaigns.add(campaign_plan.parent.resolve())
    return (
        sorted(discovered_cases, key=str),
        sorted(discovered_campaigns, key=str),
        issues,
    )


def profile_name(manifest: dict[str, Any]) -> str:
    if manifest.get("case") == "blackhole":
        return "blackhole"
    latency = str(manifest.get("latency", ""))
    jitter = str(manifest.get("jitter", ""))
    loss = str(manifest.get("loss", ""))
    if (latency, jitter, loss) == ("0ms", "0ms", "0%"):
        return "perfect"
    if (latency, jitter, loss) == ("10ms", "2ms", "2%"):
        return "near-loss"
    if (latency, jitter, loss) == ("50ms", "5ms", "2%"):
        return "regional-loss"
    if (latency, jitter, loss) == ("100ms", "10ms", "5%"):
        return "poor"
    if (latency, jitter, loss) == ("200ms", "20ms", "10%"):
        return "severe"
    return f"custom:{latency}/{jitter}/{loss}"


def find_campaign_root(case_root: Path) -> Path | None:
    for candidate in (case_root.parent, *case_root.parents):
        if (candidate / "campaign-plan.json").is_file():
            return candidate
    return None


def find_goal_root(campaign_root: Path) -> Path | None:
    for candidate in (campaign_root, *campaign_root.parents):
        if (candidate / "goal-manifest.json").is_file() \
                and (candidate / "distribution.sha256").is_file():
            return candidate
    return None


def validate_goal_provenance(campaign_root: Path, recovery_mode: str) -> dict[str, Any]:
    goal_root = find_goal_root(campaign_root)
    if goal_root is None:
        raise AnalysisError("campaign lacks ancestor goal-manifest.json and distribution.sha256")
    manifest_path = goal_root / "goal-manifest.json"
    distribution_path = goal_root / "distribution.sha256"
    manifest = load_json(manifest_path)
    if manifest.get("kind") != "raknet-netns-autonomous-goal":
        raise AnalysisError("goal manifest has the wrong kind")
    generated = manifest.get("generatedAt")
    if not isinstance(generated, str) or not generated.strip():
        raise AnalysisError("goal manifest lacks generatedAt provenance")
    if require_recovery_mode(manifest, "goal manifest") != recovery_mode:
        raise AnalysisError("goal recovery mode disagrees with campaign")
    candidate_revision = manifest.get("candidateRevision")
    if not isinstance(candidate_revision, str):
        raise AnalysisError("goal manifest lacks candidateRevision provenance")
    revision_match = re.fullmatch(r"([0-9a-f]{12,40})\+dist-([0-9a-f]{12})", candidate_revision)
    if revision_match is None:
        raise AnalysisError("goal candidateRevision is not a source revision plus distribution fingerprint")
    try:
        distribution_bytes = distribution_path.read_bytes()
        distribution_text = distribution_bytes.decode("utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise AnalysisError(f"invalid distribution manifest {distribution_path}: {error}") from error
    if not distribution_text.endswith("\n"):
        raise AnalysisError("distribution manifest must be nonempty and newline terminated")
    entries: list[tuple[str, str]] = []
    for line in distribution_text.splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([^/\\\x00]+\.jar)", line)
        if match is None:
            raise AnalysisError("distribution manifest contains a malformed or non-flat jar entry")
        entries.append((match.group(2), match.group(1)))
    if not entries or not any(name.startswith("benchmark-") for name, _hash in entries):
        raise AnalysisError("distribution manifest lacks a benchmark jar")
    names = [name for name, _hash in entries]
    if names != sorted(names) or len(names) != len(set(names)):
        raise AnalysisError("distribution manifest jar names are unsorted or duplicated")
    distribution_hash = hashlib.sha256(distribution_bytes).hexdigest()
    if revision_match.group(2) != distribution_hash[:12]:
        raise AnalysisError("goal candidateRevision distribution fingerprint disagrees with distribution.sha256")
    return {
        "goalRoot": str(goal_root),
        "goalGeneratedAt": generated,
        # The launcher emits one immutable manifest per goal. Its generatedAt value is the only
        # execution identifier shared by sibling campaigns within that goal; paths are not identities
        # because copying one goal tree must not manufacture another independent repetition.
        "goalExecutionIdentity": generated,
        "candidateRevision": candidate_revision,
        "sourceRevision": revision_match.group(1),
        "distributionSha256": distribution_hash,
        "distributionEntries": len(entries),
        "manifest": str(manifest_path),
        "distributionManifest": str(distribution_path),
    }


def validate_campaign(campaign_root: Path, cases: list[dict[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {
        "campaignRoot": str(campaign_root),
        "status": "fail",
        "fullCampaign": False,
        "issues": [],
    }
    try:
        plan = load_json(campaign_root / "campaign-plan.json")
        summary = load_json(campaign_root / "campaign-summary.json")
        if plan.get("kind") != "raknet-netns-pilot-campaign" or plan.get("execute") is not True:
            raise AnalysisError("campaign plan is not an executed netns pilot campaign")
        generated = plan.get("generatedAt")
        output_root = plan.get("outputRoot")
        parameters = plan.get("parameters")
        if not isinstance(generated, str) or not generated.strip():
            raise AnalysisError("campaign plan lacks a generatedAt execution identity")
        if not isinstance(output_root, str) or not output_root.strip() or not Path(output_root).is_absolute():
            raise AnalysisError("campaign plan lacks an outputRoot execution identity")
        if not isinstance(parameters, dict):
            raise AnalysisError("campaign plan parameters must be an object")
        required_parameters = (
            "clients", "affectedClients", "payloadSize", "perClientMbps", "warmup", "duration",
            "iterations", "probeInterval", "startDelay", "startOffset", "netemBeforeStart",
            "netemLimitPackets", "blackholeAfter", "blackholeDuration", "direction", "reliability",
            "recoveryMode", "packetLimit", "globalPacketLimit", "maxQueuedBytes", "workers",
            "resourceSafetyMaxAggregateQueuedBytes", "resourceSafetyMaxDirectMemoryUsedBytes",
        )
        missing_parameters = [field for field in required_parameters if field not in parameters]
        if missing_parameters:
            raise AnalysisError(f"campaign plan parameters are incomplete: {missing_parameters}")
        planned_recovery_mode = require_recovery_mode(parameters, "campaign plan parameters")
        planned_resource_safety = require_resource_safety(parameters, "campaign plan parameters")
        goal_provenance = validate_goal_provenance(campaign_root, planned_recovery_mode)
        for field in ("packetLimit", "globalPacketLimit", "maxQueuedBytes", "workers"):
            require_optional_positive_int(parameters, field, "campaign plan parameters")
        if summary.get("kind") != "raknet-netns-pilot-summary" or summary.get("executed") is not True \
                or summary.get("executionPassed") is not True:
            raise AnalysisError("campaign summary does not prove successful execution")
        if not isinstance(summary.get("generatedAt"), str) or not summary["generatedAt"].strip():
            raise AnalysisError("campaign summary lacks generatedAt provenance")
        declared_output_root = Path(output_root)
        if not isinstance(summary.get("campaignPlan"), str) \
                or Path(summary["campaignPlan"]) != declared_output_root / "campaign-plan.json":
            raise AnalysisError("campaign summary does not identify its campaign plan")
        if not isinstance(summary.get("campaignStatus"), str) \
                or Path(summary["campaignStatus"]) != declared_output_root / "campaign-status.jsonl":
            raise AnalysisError("campaign summary does not identify its campaign status log")
        profile_rows = plan.get("profiles")
        if not isinstance(profile_rows, list) or not all(isinstance(row, dict) for row in profile_rows):
            raise AnalysisError("campaign plan profiles must be an array of objects")
        for row in profile_rows:
            for field in ("profile", "caseType", "latency", "jitter", "loss"):
                if not isinstance(row.get(field), str):
                    raise AnalysisError(f"campaign profile lacks {field}")
        plan_profiles = [row["profile"] for row in profile_rows]
        if not plan_profiles or len(plan_profiles) != len(set(plan_profiles)):
            raise AnalysisError("campaign plan profiles are empty or duplicated")
        statuses = summary.get("statuses")
        if not isinstance(statuses, list):
            raise AnalysisError("campaign summary statuses must be an array")
        completed_profiles = []
        for row in statuses:
            if not isinstance(row, dict) or row.get("status") != "completed":
                continue
            for field in ("profile", "caseType", "startedAt", "completedAt", "artifact"):
                if not isinstance(row.get(field), str) or not row[field].strip():
                    raise AnalysisError(f"campaign completed status lacks {field}")
            completed_profiles.append(row["profile"])
        if sorted(completed_profiles) != sorted(plan_profiles) or len(completed_profiles) != len(statuses):
            raise AnalysisError("campaign statuses do not prove exactly one completed row per planned profile")
        summary_results = summary.get("results")
        if not isinstance(summary_results, list) or not all(isinstance(row, dict) for row in summary_results):
            raise AnalysisError("campaign summary results must be an array of objects")
        result_profiles = [row.get("profile") for row in summary_results]
        if sorted(result_profiles) != sorted(plan_profiles):
            raise AnalysisError("campaign results do not cover every planned profile")
        attached_cases = [case for case in cases if case.get("campaignRoot") == str(campaign_root)]
        attached_profiles = sorted(case.get("profile") for case in attached_cases)
        if attached_profiles != sorted(plan_profiles):
            raise AnalysisError("discovered case artifacts do not match the campaign plan profiles")

        by_profile = {case["profile"]: case for case in attached_cases}
        profile_by_name = {row["profile"]: row for row in profile_rows}
        status_by_profile = {row["profile"]: row for row in statuses}
        result_by_profile = {row["profile"]: row for row in summary_results}
        for profile in plan_profiles:
            case = by_profile[profile]
            manifest = case.get("manifest")
            if not isinstance(manifest, dict):
                raise AnalysisError(f"campaign case {profile} lacks a validated manifest")
            if require_recovery_mode(manifest, f"campaign case {profile} manifest") != planned_recovery_mode:
                raise AnalysisError(f"campaign recovery mode disagrees with manifest for {profile}")
            if case.get("recoveryMode") != planned_recovery_mode:
                raise AnalysisError(f"campaign recovery mode disagrees with timeline for {profile}")
            if case.get("artifactRevisions") != [goal_provenance["candidateRevision"]]:
                raise AnalysisError(
                    f"campaign merged server/receiver revisions disagree with goal candidate for {profile}"
                )
            planned = profile_by_name[profile]
            if manifest.get("case") != planned["caseType"]:
                raise AnalysisError(f"campaign case type disagrees with manifest for {profile}")
            if any(manifest.get(field) != planned[field] for field in ("latency", "jitter", "loss")):
                raise AnalysisError(f"campaign impairment shape disagrees with manifest for {profile}")
            reconciled = {
                "clients": manifest.get("clients"),
                "payloadSize": manifest.get("payloadSize"),
                "perClientMbps": manifest.get("perClientMbps"),
                "netemLimitPackets": manifest.get("netemLimitPackets"),
                "direction": manifest.get("direction"),
                "packetLimit": manifest.get("packetLimit"),
                "globalPacketLimit": manifest.get("globalPacketLimit"),
                "maxQueuedBytes": manifest.get("maxQueuedBytes"),
                "workers": manifest.get("workers"),
                "resourceSafetyMaxAggregateQueuedBytes": manifest.get(
                    "resourceSafetyMaxAggregateQueuedBytes"
                ),
                "resourceSafetyMaxDirectMemoryUsedBytes": manifest.get(
                    "resourceSafetyMaxDirectMemoryUsedBytes"
                ),
            }
            for field, actual in reconciled.items():
                expected = parameters.get(field)
                if str(actual) != str(expected):
                    raise AnalysisError(f"campaign parameter {field} disagrees with manifest for {profile}")
            if profile != "perfect" and str(manifest.get("affectedClients")) != str(parameters["affectedClients"]):
                raise AnalysisError(f"campaign affectedClients disagrees with manifest for {profile}")
            expected_case_name = Path(case["caseRoot"]).name
            status_artifact = status_by_profile[profile]["artifact"]
            result_artifact = result_by_profile[profile].get("caseArtifact")
            if not isinstance(result_artifact, str) or not result_artifact.strip():
                raise AnalysisError(f"campaign result lacks caseArtifact for {profile}")
            expected_artifact = declared_output_root / "cases" / expected_case_name
            if Path(status_artifact) != expected_artifact or Path(result_artifact) != expected_artifact:
                raise AnalysisError(f"campaign artifact provenance disagrees with discovered case for {profile}")

        comparison_parameters = {
            field: value for field, value in parameters.items() if field != "recoveryMode"
        }
        configuration = {"profiles": profile_rows, "parameters": comparison_parameters}
        # One autonomous goal launches at most one full six-profile campaign. Count the launcher's
        # path-independent goal identity so relocating or copying evidence cannot create a repetition.
        identity = goal_provenance["goalExecutionIdentity"]
        result.update({
            "status": "pass",
            "profiles": plan_profiles,
            "fullCampaign": plan_profiles == list(FULL_CAMPAIGN_PROFILES),
            "executionIdentity": identity,
            "recoveryMode": planned_recovery_mode,
            "resourceSafety": planned_resource_safety,
            "goalProvenance": goal_provenance,
            "experimentConfiguration": configuration,
            "experimentConfigurationKey": json.dumps(configuration, sort_keys=True, separators=(",", ":")),
            "plan": str(campaign_root / "campaign-plan.json"),
            "summary": str(campaign_root / "campaign-summary.json"),
        })
    except (AnalysisError, KeyError, TypeError, ValueError, ArithmeticError) as error:
        reason = str(error) or error.__class__.__name__
        result["issues"].append(reason)
    return result


def find_one(case_root: Path, pattern: str, description: str) -> Path:
    matches = sorted(case_root.glob(pattern))
    if len(matches) != 1:
        raise AnalysisError(
            f"expected exactly one {description} below {case_root}, found {len(matches)}"
        )
    return matches[0]


def require_number(container: dict[str, Any], field: str, context: str, *, nonnegative: bool = True) -> float:
    value = container.get(field)
    if not finite_number(value):
        raise AnalysisError(f"{context}.{field} must be a finite number")
    if nonnegative and value < 0:
        raise AnalysisError(f"{context}.{field} must be non-negative")
    return float(value)


def require_recovery_mode(container: dict[str, Any], context: str) -> str:
    value = container.get("recoveryMode")
    if value not in RECOVERY_MODES:
        raise AnalysisError(
            f"{context}.recoveryMode must be one of: {', '.join(RECOVERY_MODES)}"
        )
    return value


def require_optional_positive_int(container: dict[str, Any], field: str, context: str) -> int | None:
    if field not in container:
        raise AnalysisError(f"{context}.{field} must be present as null or a positive integer")
    value = container[field]
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise AnalysisError(f"{context}.{field} must be null or a positive integer")
    return value


def require_resource_safety(container: dict[str, Any], context: str) -> dict[str, int]:
    result: dict[str, int] = {}
    for field in (
        "resourceSafetyMaxAggregateQueuedBytes",
        "resourceSafetyMaxDirectMemoryUsedBytes",
    ):
        value = container.get(field)
        if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
            raise AnalysisError(f"{context}.{field} must be a positive integer")
        result[field] = value
    return result


def validate_resource_safety_policy(row: dict[str, Any], expected: dict[str, int],
                                    enforcement_status: str, context: str) -> None:
    policy = row.get("resourceSafetyPolicy")
    if not isinstance(policy, dict):
        raise AnalysisError(f"{context}.resourceSafetyPolicy must be an object")
    if policy.get("maxAggregateQueuedBytes") != expected["resourceSafetyMaxAggregateQueuedBytes"]:
        raise AnalysisError(f"{context} aggregate queue safety threshold disagrees with manifest")
    if policy.get("maxDirectMemoryUsedBytes") != expected["resourceSafetyMaxDirectMemoryUsedBytes"]:
        raise AnalysisError(f"{context} direct-memory safety threshold disagrees with manifest")
    if policy.get("enforcementStatus") != enforcement_status:
        raise AnalysisError(f"{context}.resourceSafetyPolicy.enforcementStatus must be {enforcement_status}")


def validate_congestion_model_sample(sample: dict[str, Any], expected_recovery_mode: str,
                                     *, receiver: bool, context: str,
                                     previous_counters: dict[str, float] | None = None) -> None:
    availability = sample.get("metricAvailability")
    if not isinstance(availability, dict):
        raise AnalysisError(f"{context}.metricAvailability must be an object")
    expected_status = (
        "unavailable-on-receiver-worker" if receiver
        else "available" if expected_recovery_mode == "model_based"
        else "not-configured-recovery-mode"
    )
    for field in ("congestionModelState", "nackValidationEvents"):
        if availability.get(field) != expected_status:
            raise AnalysisError(f"{context}.metricAvailability.{field} must be {expected_status}")
    for cohort_name in ("all", "healthy", "affected"):
        cohort = sample.get(cohort_name)
        if not isinstance(cohort, dict):
            raise AnalysisError(f"{context}.{cohort_name} must be an object")
        model = cohort.get("congestionModel")
        if receiver or expected_recovery_mode != "model_based":
            if model is not None:
                raise AnalysisError(f"{context}.{cohort_name}.congestionModel must be null")
            continue
        if not isinstance(model, dict):
            raise AnalysisError(f"{context}.{cohort_name}.congestionModel must be an object")
        for field in ("observedPeers", "estimatedDeliveryRateObservedPeers",
                      "pacingRateObservedPeers", "minimumRttObservedPeers",
                      "recentLossObservedPeers", "packetRoundObservedPeers",
                      "startupPeers", "persistentCongestionPeers",
                      "nackRecoveryHints", "nackReorderingResolved", "nackLossValidated"):
            value = model.get(field)
            if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                raise AnalysisError(f"{context}.{cohort_name}.congestionModel.{field} must be a non-negative integer")
        coverage_fields = (
            "estimatedDeliveryRateObservedPeers", "pacingRateObservedPeers",
            "minimumRttObservedPeers", "recentLossObservedPeers", "packetRoundObservedPeers",
        )
        if model["observedPeers"] > cohort.get("observedPeers", -1) \
                or any(model[field] > model["observedPeers"] for field in coverage_fields) \
                or model["startupPeers"] > model["observedPeers"] \
                or model["persistentCongestionPeers"] > model["observedPeers"]:
            raise AnalysisError(f"{context}.{cohort_name}.congestionModel peer counts are inconsistent")
        for field in ("oldestObservedAtEpochMillis", "latestObservedAtEpochMillis",
                      "totalEstimatedDeliveryRateBytesPerSecond",
                      "maxEstimatedDeliveryRateBytesPerSecond", "totalPacingRateBytesPerSecond",
                      "maxPacingRateBytesPerSecond", "minimumRttMillis", "maximumMinimumRttMillis",
                      "maximumRecentLossRate", "minimumPacketRound", "maximumPacketRound",
                      "maxNackRecoveryHintDelayMillis", "maxNackReorderingResolvedDelayMillis",
                      "maxNackLossValidatedDelayMillis"):
            value = model.get(field)
            if value is not None and (not finite_number(value) or value < 0):
                raise AnalysisError(
                    f"{context}.{cohort_name}.congestionModel.{field} must be null or non-negative"
                )
        loss = model.get("maximumRecentLossRate")
        if finite_number(loss) and loss > 1:
            raise AnalysisError(f"{context}.{cohort_name}.congestionModel.maximumRecentLossRate must be <= 1")
        gauge_fields = (
            "oldestObservedAtEpochMillis", "latestObservedAtEpochMillis",
            "totalEstimatedDeliveryRateBytesPerSecond",
            "maxEstimatedDeliveryRateBytesPerSecond", "totalPacingRateBytesPerSecond",
            "maxPacingRateBytesPerSecond", "minimumRttMillis", "maximumMinimumRttMillis",
            "maximumRecentLossRate", "minimumPacketRound", "maximumPacketRound",
        )
        if model["observedPeers"] == 0 and any(model.get(field) is not None for field in gauge_fields):
            raise AnalysisError(f"{context}.{cohort_name}.congestionModel has gauges without observed peers")
        oldest_observed = model.get("oldestObservedAtEpochMillis")
        latest_observed = model.get("latestObservedAtEpochMillis")
        if model["observedPeers"] > 0:
            if not finite_number(oldest_observed) or not finite_number(latest_observed):
                raise AnalysisError(
                    f"{context}.{cohort_name}.congestionModel observation epochs are unavailable"
                )
            if oldest_observed > latest_observed or latest_observed > sample.get("epochMillis", -1):
                raise AnalysisError(
                    f"{context}.{cohort_name}.congestionModel observation epochs are inconsistent"
                )
        coverage_gauges = (
            ("estimatedDeliveryRateObservedPeers",
             ("totalEstimatedDeliveryRateBytesPerSecond", "maxEstimatedDeliveryRateBytesPerSecond")),
            ("pacingRateObservedPeers",
             ("totalPacingRateBytesPerSecond", "maxPacingRateBytesPerSecond")),
            ("minimumRttObservedPeers", ("minimumRttMillis", "maximumMinimumRttMillis")),
            ("recentLossObservedPeers", ("maximumRecentLossRate",)),
            ("packetRoundObservedPeers", ("minimumPacketRound", "maximumPacketRound")),
        )
        for coverage_field, fields in coverage_gauges:
            has_coverage = model[coverage_field] > 0
            if any((model.get(field) is not None) != has_coverage for field in fields):
                raise AnalysisError(
                    f"{context}.{cohort_name}.congestionModel {coverage_field} disagrees with gauges"
                )
        event_delay_fields = (
            ("nackRecoveryHints", "maxNackRecoveryHintDelayMillis"),
            ("nackReorderingResolved", "maxNackReorderingResolvedDelayMillis"),
            ("nackLossValidated", "maxNackLossValidatedDelayMillis"),
        )
        for count_field, delay_field in event_delay_fields:
            if (model[count_field] == 0) != (model.get(delay_field) is None):
                raise AnalysisError(
                    f"{context}.{cohort_name}.congestionModel.{delay_field} availability disagrees with {count_field}"
                )
        if previous_counters is not None:
            for field in ("nackRecoveryHints", "nackReorderingResolved", "nackLossValidated"):
                key = f"{cohort_name}.{field}"
                previous = previous_counters.get(key)
                if previous is not None and model[field] < previous:
                    raise AnalysisError(f"counter congestionModel.{field} regresses in {context}")
                previous_counters[key] = model[field]


def validate_role_timeline(path: Path, manifest: dict[str, Any], expected_run_id: str) -> dict[str, Any]:
    rows = read_jsonl(path)
    expected_recovery_mode = require_recovery_mode(manifest, "manifest")
    expected_resource_safety = require_resource_safety(manifest, "manifest")
    sequences: list[int] = []
    for index, row in enumerate(rows):
        context = f"receiver timeline record {index + 1}"
        schema_version = row.get("schemaVersion")
        if schema_version not in (LEGACY_TIMELINE_SCHEMA_VERSION, TIMELINE_SCHEMA_VERSION):
            raise AnalysisError(f"unsupported receiver timeline schema at {path} record {index + 1}")
        if expected_recovery_mode == "model_based" and schema_version != TIMELINE_SCHEMA_VERSION:
            raise AnalysisError(f"model_based receiver timeline requires schema {TIMELINE_SCHEMA_VERSION}: {path}")
        sequence = row.get("sequence")
        if not isinstance(sequence, int) or isinstance(sequence, bool) or sequence < 0:
            raise AnalysisError(f"invalid receiver timeline sequence at {path} record {index + 1}")
        sequences.append(sequence)
        if row.get("recordType") not in ("event", "sample"):
            raise AnalysisError(f"unknown receiver recordType at {path} record {index + 1}")
        if row.get("role") != "client":
            raise AnalysisError(f"{context}.role must be client in {path}")
        if row.get("runId") != expected_run_id:
            raise AnalysisError(f"receiver timeline runId does not match worker topology: {path}")
        actual_recovery_mode = require_recovery_mode(row, context)
        if actual_recovery_mode != expected_recovery_mode:
            raise AnalysisError(f"receiver timeline recoveryMode does not match manifest: {path}")
        validate_resource_safety_policy(
            row, expected_resource_safety, "not-applicable-receiver-worker", context
        )
        if schema_version == TIMELINE_SCHEMA_VERSION and row.get("recordType") == "sample":
            validate_congestion_model_sample(
                row, expected_recovery_mode, receiver=True, context=context
            )
    if sequences != sorted(sequences) or len(sequences) != len(set(sequences)):
        raise AnalysisError(f"receiver timeline sequences are not strictly increasing: {path}")
    return {
        "status": "available",
        "recordCount": len(rows),
        "runId": expected_run_id,
        "recoveryMode": expected_recovery_mode,
        "resourceSafety": expected_resource_safety,
    }


def parse_timeline(path: Path, manifest: dict[str, Any]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    rows = read_jsonl(path)
    expected_recovery_mode = require_recovery_mode(manifest, "manifest")
    expected_resource_safety = require_resource_safety(manifest, "manifest")
    sequences: list[int] = []
    samples: list[dict[str, Any]] = []
    run_ids: set[str] = set()
    safety_aborts: list[dict[str, Any]] = []
    for index, row in enumerate(rows):
        schema_version = row.get("schemaVersion")
        if schema_version not in (LEGACY_TIMELINE_SCHEMA_VERSION, TIMELINE_SCHEMA_VERSION):
            raise AnalysisError(f"unsupported timeline schema at {path} record {index + 1}")
        if expected_recovery_mode == "model_based" and schema_version != TIMELINE_SCHEMA_VERSION:
            raise AnalysisError(f"model_based timeline requires schema {TIMELINE_SCHEMA_VERSION}: {path}")
        sequence = row.get("sequence")
        if not isinstance(sequence, int) or isinstance(sequence, bool) or sequence < 0:
            raise AnalysisError(f"invalid timeline sequence at {path} record {index + 1}")
        sequences.append(sequence)
        actual_recovery_mode = require_recovery_mode(row, f"timeline record {index + 1}")
        if actual_recovery_mode != expected_recovery_mode:
            raise AnalysisError(f"timeline recoveryMode does not match manifest: {path}")
        validate_resource_safety_policy(
            row, expected_resource_safety, "enforced", f"timeline record {index + 1}"
        )
        if isinstance(row.get("runId"), str):
            run_ids.add(row["runId"])
        record_type = row.get("recordType")
        if record_type == "event":
            require_number(row, "epochMillis", f"timeline event {index + 1}")
            if row.get("eventName") == "resource-safety-abort":
                abort = row.get("resourceSafetyAbort")
                if not isinstance(abort, dict):
                    raise AnalysisError(f"resource-safety-abort event lacks structured details: {path}")
                safety_aborts.append(abort)
        elif record_type == "sample":
            samples.append(row)
        else:
            raise AnalysisError(f"unknown recordType at {path} record {index + 1}")
    if sequences != sorted(sequences) or len(sequences) != len(set(sequences)):
        raise AnalysisError(f"timeline sequences are not strictly increasing: {path}")
    if len(run_ids) != 1:
        raise AnalysisError(f"timeline mixes run identifiers: {path}")
    if len(samples) < 2:
        raise AnalysisError(f"timeline requires at least two samples: {path}")

    previous_epoch = -1.0
    previous_monotonic = -1.0
    previous_counters: dict[str, float] = {}
    largest_gap = 0.0
    direct_available = False
    cpu_load_available = False
    rss_available = False
    cpu_time_available = False
    event_loop_available = False
    model_available = False
    previous_model_counters: dict[str, float] = {}
    for index, sample in enumerate(samples):
        context = f"timeline sample {index + 1}"
        if sample.get("role") != "server":
            raise AnalysisError(f"{context}.role must be server in {path}")
        epoch = require_number(sample, "epochMillis", context)
        monotonic = require_number(sample, "monotonicElapsedMillis", context)
        if epoch < previous_epoch or monotonic < previous_monotonic:
            raise AnalysisError(f"timeline sample clocks regress in {path}")
        if previous_epoch >= 0:
            largest_gap = max(largest_gap, epoch - previous_epoch)
        previous_epoch, previous_monotonic = epoch, monotonic
        availability = sample.get("metricAvailability")
        if not isinstance(availability, dict):
            raise AnalysisError(f"{context}.metricAvailability must be an object")
        for field in ("usefulSendCounters", "transportRecoveryCounters", "nackCounters", "queueCounters"):
            if availability.get(field) != "available":
                raise AnalysisError(f"{context}.metricAvailability.{field} is not available")
        affected = sample.get("affected")
        if not isinstance(affected, dict):
            raise AnalysisError(f"{context}.affected must be an object")
        for field in ("configuredPeers", "observedPeers", "openPeers", "activePeers", "disconnectedPeers"):
            require_number(affected, field, f"{context}.affected")
        for field in (*COUNTERS, *GAUGES):
            value = require_number(affected, field, f"{context}.affected")
            if field in COUNTERS:
                previous = previous_counters.get(field)
                if previous is not None and value < previous:
                    raise AnalysisError(f"counter {field} regresses in {path}")
                previous_counters[field] = value
        if sample.get("schemaVersion") == TIMELINE_SCHEMA_VERSION:
            validate_congestion_model_sample(
                sample, expected_recovery_mode, receiver=False, context=context,
                previous_counters=previous_model_counters
            )
            if expected_recovery_mode == "model_based":
                model = sample["all"]["congestionModel"]
                model_available = model_available or model["observedPeers"] > 0
        runtime = sample.get("runtime")
        if not isinstance(runtime, dict):
            raise AnalysisError(f"{context}.runtime must be an object")
        require_number(runtime, "heapUsedBytes", f"{context}.runtime")
        for field in ("processCpuTimeNanos", "processCpuLoad", "residentSetSizeBytes",
                      "directBufferPoolMemoryUsedBytes", "nettyPooledDirectMemoryUsedBytes"):
            value = runtime.get(field)
            if value is not None and (not finite_number(value) or value < 0):
                raise AnalysisError(f"{context}.runtime.{field} must be null or a non-negative finite number")
        direct_available = direct_available or any(
            finite_number(runtime.get(field))
            for field in ("directBufferPoolMemoryUsedBytes", "nettyPooledDirectMemoryUsedBytes")
        )
        cpu_load_available = cpu_load_available or finite_number(runtime.get("processCpuLoad"))
        rss_available = rss_available or finite_number(runtime.get("residentSetSizeBytes"))
        cpu_time_available = cpu_time_available or finite_number(runtime.get("processCpuTimeNanos"))
        event_loops = runtime.get("sharedEventLoops")
        if event_loops is not None:
            if not isinstance(event_loops, dict):
                raise AnalysisError(f"{context}.runtime.sharedEventLoops must be null or an object")
            status = event_loops.get("status")
            if status == "available":
                for field in ("eventLoopCount", "totalPendingTasks", "maxPendingTasks",
                              "completedSchedulingProbes", "outstandingSchedulingProbes"):
                    require_number(event_loops, field, f"{context}.runtime.sharedEventLoops")
                for field in ("latestMaxSchedulingLagMillis", "maxSchedulingLagMillis"):
                    value = event_loops.get(field)
                    if value is not None and (not finite_number(value) or value < 0):
                        raise AnalysisError(
                            f"{context}.runtime.sharedEventLoops.{field} must be null or non-negative"
                        )
                event_loop_available = True
            elif not isinstance(status, str) or not status.startswith("unavailable-"):
                raise AnalysisError(f"{context}.runtime.sharedEventLoops.status is invalid")

    expected_run_id = manifest.get("runId")
    if isinstance(expected_run_id, str) and run_ids != {expected_run_id}:
        raise AnalysisError(f"timeline runId does not match manifest in {path}")
    if not direct_available:
        raise AnalysisError(f"timeline has no direct-memory metric: {path}")
    if not cpu_load_available:
        raise AnalysisError(f"timeline has no process CPU-load metric: {path}")
    if not rss_available:
        raise AnalysisError(f"timeline has no resident-set metric: {path}")
    if not cpu_time_available:
        raise AnalysisError(f"timeline has no process CPU-time metric: {path}")
    if largest_gap > MAX_TIMELINE_SAMPLE_GAP_MILLIS:
        raise AnalysisError(
            f"timeline sample gap {largest_gap:g}ms exceeds {MAX_TIMELINE_SAMPLE_GAP_MILLIS}ms: {path}"
        )
    if expected_recovery_mode == "model_based" and not model_available:
        raise AnalysisError(f"model_based timeline has no observed congestion-model state: {path}")

    configured_epochs = {
        "externalImpairmentAtEpochMillis": manifest.get("netemAtEpochMillis"),
        "externalBlackholeAtEpochMillis": manifest.get("blackholeAtEpochMillis"),
        "externalRecoveryAtEpochMillis": manifest.get("recoveryAtEpochMillis"),
    }
    for field, expected in configured_epochs.items():
        for sample in samples:
            actual = sample.get(field)
            if expected is None:
                if actual is not None:
                    raise AnalysisError(f"timeline unexpectedly configures {field}: {path}")
            elif actual != expected:
                raise AnalysisError(f"timeline {field} does not match manifest: {path}")
    return samples, {
        "recordCount": len(rows),
        "sampleCount": len(samples),
        "firstSampleEpochMillis": samples[0]["epochMillis"],
        "lastSampleEpochMillis": samples[-1]["epochMillis"],
        "largestSampleGapMillis": largest_gap,
        "serverUsefulDelivery": "unavailable-on-server-worker",
        "directMemory": "available",
        "processCpuLoad": "available",
        "processCpuTime": "available",
        "residentSetSize": "available",
        "sharedEventLoops": "available" if event_loop_available else "unavailable",
        "congestionModelState": (
            "available" if expected_recovery_mode == "model_based"
            else "not-configured-recovery-mode"
        ),
        "resourceSafety": expected_resource_safety,
        "resourceSafetyAbort": safety_aborts[0] if len(safety_aborts) == 1 else None,
        "resourceSafetyAbortCount": len(safety_aborts),
    }


def parse_key_values(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise AnalysisError(f"unable to read qdisc apply evidence {path}: {error}") from error
    for line in lines:
        if "=" not in line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def qdisc_status_lines(path: Path, completed: int) -> list[str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise AnalysisError(f"unable to read qdisc apply evidence {path}: {error}") from error
    marker = f"applyCompletedAtEpochMillis={completed}"
    try:
        marker_index = lines.index(marker)
    except ValueError as error:
        raise AnalysisError(f"{path} does not contain its exact apply completion marker") from error
    return [line.strip() for line in lines[marker_index + 1:] if line.strip()]


def validate_text_qdisc_state(
    path: Path, label: str, manifest: dict[str, Any], completed: int,
    interface: str, target_values: dict[str, str],
) -> str:
    try:
        full_text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise AnalysisError(f"unable to read qdisc apply evidence {path}: {error}") from error
    clear_devices = re.findall(r"(?m)^\+ tc qdisc del dev (\S+) root\s*$", full_text)
    replace_devices = re.findall(r"(?m)^\+ tc qdisc replace dev (\S+) root netem(?: .*)?$", full_text)
    command_devices = clear_devices + replace_devices
    if len(command_devices) != 1:
        raise AnalysisError(f"{path} must prove exactly one executed qdisc clear or replace command")
    if command_devices[0] != interface:
        raise AnalysisError(f"{path} qdisc command device does not match interface={interface}")
    proves_clear = len(clear_devices) == 1
    proves_replace = len(replace_devices) == 1
    if label in ("initial-netem", "external-blackhole") and not proves_replace:
        raise AnalysisError(f"{path} must replace netem for {label}")
    if label == "external-recovery":
        zero_recovery = all(
            target_values[field] in zeroes
            for field, zeroes in (
                ("latency", {"0", "0ms"}),
                ("jitter", {"", "0", "0ms"}),
                ("loss", {"0", "0%"}),
            )
        )
        if zero_recovery and not proves_clear:
            raise AnalysisError(f"{path} must clear qdisc when recovering to zero impairment")
        if not zero_recovery and not proves_replace:
            raise AnalysisError(f"{path} must restore netem when recovering to a nonzero impairment")
    lines = qdisc_status_lines(path, completed)
    show_devices = [
        match.group(1) for line in lines
        if (match := re.fullmatch(r"\+ tc qdisc show dev (\S+)", line)) is not None
    ]
    if show_devices != [interface]:
        raise AnalysisError(f"{path} must prove exactly one post-apply status for interface={interface}")
    netem_lines = [line for line in lines if line.startswith("qdisc netem ")]
    expects_netem = proves_replace
    if not expects_netem:
        if netem_lines:
            raise AnalysisError(f"{path} status still contains netem after recovery")
        if len([line for line in lines if line.startswith("qdisc noqueue ")]) != 1:
            raise AnalysisError(f"{path} does not prove the expected noqueue state after clear")
        return "clear"
    if len(netem_lines) != 1:
        raise AnalysisError(f"{path} does not prove exactly one active netem qdisc")
    status = netem_lines[0]
    if not re.search(rf"(?:^| )limit {int(manifest['netemLimitPackets'])}(?: |$)", status):
        raise AnalysisError(f"{path} qdisc status does not prove the configured packet limit")
    expected_loss = target_values["loss"]
    if expected_loss not in ("0", "0%") \
            and not re.search(rf"(?:^| )loss {re.escape(expected_loss)}(?: |$)", status):
        raise AnalysisError(f"{path} qdisc status does not prove loss {expected_loss}")
    expected_latency = target_values["latency"]
    expected_jitter = target_values["jitter"]
    if expected_latency not in ("0", "0ms") \
            and not re.search(rf"(?:^| )delay {re.escape(expected_latency)}(?: |$)", status):
        raise AnalysisError(f"{path} qdisc status does not prove delay {expected_latency}")
    if expected_jitter not in ("", "0", "0ms") and expected_jitter not in status:
        raise AnalysisError(f"{path} qdisc status does not prove jitter {expected_jitter}")
    return "replace"


def parse_apply_event(case_root: Path, manifest: dict[str, Any], label: str) -> dict[str, Any] | None:
    scheduled_field = {
        "initial-netem": "netemAtEpochMillis",
        "external-blackhole": "blackholeAtEpochMillis",
        "external-recovery": "recoveryAtEpochMillis",
    }[label]
    scheduled = manifest.get(scheduled_field)
    files = sorted((case_root / "netem").glob(f"{label}-*.txt"))
    if scheduled is None and not files:
        return None
    if not finite_number(scheduled):
        raise AnalysisError(f"{scheduled_field} must be present when {label} evidence exists")
    expected_targets = 2 if manifest.get("direction") == "both" else 1
    if len(files) != expected_targets:
        raise AnalysisError(
            f"{label} requires {expected_targets} apply evidence files below {case_root}, found {len(files)}"
        )
    targets: list[dict[str, Any]] = []
    starts: list[int] = []
    completions: list[int] = []
    for path in files:
        values = parse_key_values(path)
        for field in ("namespace", "interface", "action", "latency", "jitter", "loss", "limit",
                      "applyStartedAtEpochMillis", "applyCompletedAtEpochMillis"):
            if not values.get(field):
                raise AnalysisError(f"{path} is missing {field}")
        try:
            started = int(values["applyStartedAtEpochMillis"])
            completed = int(values["applyCompletedAtEpochMillis"])
            limit = int(values["limit"])
        except ValueError as error:
            raise AnalysisError(f"{path} has a non-integer apply bound or limit") from error
        if started <= 0 or completed < started or limit <= 0:
            raise AnalysisError(f"{path} has invalid apply bounds or limit")
        if limit != manifest.get("netemLimitPackets"):
            raise AnalysisError(f"{path} netem limit does not match the manifest")
        if label == "external-blackhole" and (
            values["action"] != "blackhole" or values["loss"] != "100%"
        ):
            raise AnalysisError(f"{path} does not prove a 100% loss blackhole")
        if label == "external-recovery" and values["action"] != "restore":
            raise AnalysisError(f"{path} does not prove path restoration")
        if label == "initial-netem" and values["action"] != "apply":
            raise AnalysisError(f"{path} does not prove initial netem application")
        if label in ("initial-netem", "external-recovery") and (
            values["latency"] != manifest.get("latency")
            or values["jitter"] != manifest.get("jitter")
            or values["loss"] != manifest.get("loss")
        ):
            raise AnalysisError(f"{path} restored/applied shape does not match the manifest")
        qdisc_action = validate_text_qdisc_state(
            path, label, manifest, completed, values["interface"], values
        )
        starts.append(started)
        completions.append(completed)
        targets.append({
            "namespace": values["namespace"],
            "interface": values["interface"],
            "startedAtEpochMillis": started,
            "completedAtEpochMillis": completed,
            "latency": values["latency"],
            "jitter": values["jitter"],
            "loss": values["loss"],
            "limitPackets": limit,
            "qdiscAction": qdisc_action,
            "path": str(path),
        })
    target_keys = [(target["namespace"], target["interface"]) for target in targets]
    if len(set(target_keys)) != len(target_keys):
        raise AnalysisError(f"{label} apply evidence repeats a namespace/interface target")
    earliest = min(starts)
    latest = max(completions)
    return {
        "label": label,
        "scheduledAtEpochMillis": int(scheduled),
        "applyStartedAtEpochMillis": earliest,
        "applyCompletedAtEpochMillis": latest,
        "startOffsetMillis": earliest - int(scheduled),
        "completionOffsetMillis": latest - int(scheduled),
        "applySpanMillis": latest - earliest,
        "targets": targets,
    }


def duration_millis(value: str) -> float:
    match = re.fullmatch(r"([0-9]+(?:\.[0-9]+)?)(us|ms|s)", value)
    if match is None:
        raise AnalysisError(f"unsupported netem duration: {value}")
    multiplier = {"us": 0.001, "ms": 1.0, "s": 1000.0}[match.group(2)]
    return float(match.group(1)) * multiplier


def option_duration_matches(value: Any, expected: str) -> bool:
    expected_ms = duration_millis(expected)
    if isinstance(value, str):
        try:
            return abs(duration_millis(value) - expected_ms) <= 0.001
        except AnalysisError:
            return False
    if not finite_number(value):
        return False
    # The validated zulubox iproute2 JSON contract is numeric seconds.
    expected_seconds = expected_ms / 1000.0
    return abs(float(value) - expected_seconds) <= 1e-9


def option_loss_percent(options: dict[str, Any]) -> float | None:
    direct = options.get("loss")
    if isinstance(direct, str) and direct.endswith("%"):
        try:
            return float(direct[:-1])
        except ValueError:
            return None
    if finite_number(direct):
        return float(direct)
    for key in ("loss-random", "loss_random", "lossRandom"):
        nested = options.get(key)
        if not isinstance(nested, dict):
            continue
        probability = nested.get("loss", nested.get("probability"))
        if not finite_number(probability):
            continue
        numeric = float(probability)
        if numeric <= 1.0:
            return numeric * 100.0
        if numeric <= 100.0:
            return numeric
        return numeric * 100.0 / 4_294_967_295.0
    return None


def qdisc_state_matches(row: dict[str, Any], event: dict[str, Any], target: dict[str, Any]) -> tuple[bool, str]:
    qdiscs = row["qdisc"]
    netem = [qdisc for qdisc in qdiscs if isinstance(qdisc, dict) and qdisc.get("kind") == "netem"]
    expects_netem = target["qdiscAction"] == "replace"
    if not expects_netem:
        noqueue = [qdisc for qdisc in qdiscs if isinstance(qdisc, dict) and qdisc.get("kind") == "noqueue"]
        return (len(qdiscs) == 1 and len(noqueue) == 1, "expected one noqueue qdisc after clear")
    if len(netem) != 1:
        return (False, "expected exactly one netem qdisc")
    options = netem[0].get("options")
    if not isinstance(options, dict):
        return (False, "netem qdisc lacks structured options")
    if options.get("limit") != target["limitPackets"]:
        return (False, "netem packet limit differs from apply evidence")
    expected_loss = 100.0 if event["label"] == "external-blackhole" else float(target["loss"].rstrip("%"))
    observed_loss = option_loss_percent(options)
    if expected_loss > 0.0 and (observed_loss is None or abs(observed_loss - expected_loss) > 0.01):
        return (False, f"netem loss is not {expected_loss:g}%")
    if expected_loss == 0.0 and observed_loss not in (None, 0.0):
        return (False, "restored netem qdisc still has loss")
    delay_options = options.get("delay")
    delay = delay_options.get("delay") if isinstance(delay_options, dict) else delay_options
    jitter = delay_options.get("jitter") if isinstance(delay_options, dict) else options.get("jitter")
    if target["latency"] not in ("0", "0ms") \
            and not option_duration_matches(delay, target["latency"]):
        return (False, f"netem delay is not {target['latency']}")
    if target["jitter"] not in ("", "0", "0ms") \
            and not option_duration_matches(jitter, target["jitter"]):
        return (False, f"netem jitter is not {target['jitter']}")
    return (True, "matched")


def validate_qdisc_timeseries(case_root: Path, events: list[dict[str, Any]]) -> dict[str, Any]:
    if not events:
        return {"status": "not-required", "path": None, "sampleCount": 0, "targets": []}
    path = case_root / "netem" / "qdisc-timeseries.jsonl"
    rows = read_jsonl(path)
    observed: set[tuple[str, str]] = set()
    target_rows: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    epochs: list[int] = []
    for index, row in enumerate(rows, 1):
        if "error" in row:
            raise AnalysisError(f"qdisc sampler recorded an error at {path}:{index}")
        epoch = row.get("epochMillis")
        namespace = row.get("namespace")
        interface = row.get("interface")
        if not finite_number(epoch) or not isinstance(namespace, str) or not isinstance(interface, str):
            raise AnalysisError(f"malformed qdisc sampler row at {path}:{index}")
        if not isinstance(row.get("qdisc"), list):
            raise AnalysisError(f"qdisc sampler row lacks qdisc array at {path}:{index}")
        epochs.append(int(epoch))
        observed.add((namespace, interface))
        target_rows[(namespace, interface)].append(row)
    expected = {
        (target["namespace"], target["interface"])
        for event in events
        for target in event["targets"]
    }
    missing = sorted(expected - observed)
    if missing:
        raise AnalysisError(f"qdisc sampler is missing expected targets at {path}: {missing}")
    first, last = min(epochs), max(epochs)
    state_evidence: list[dict[str, Any]] = []
    for event_index, event in enumerate(events):
        next_apply_start = (
            min(target["startedAtEpochMillis"] for target in events[event_index + 1]["targets"])
            if event_index + 1 < len(events) else None
        )
        for target in event["targets"]:
            key = (target["namespace"], target["interface"])
            samples = sorted(target_rows.get(key, []), key=lambda row: row["epochMillis"])
            sample_epochs = [int(row["epochMillis"]) for row in samples]
            if not samples or min(sample_epochs) > target["startedAtEpochMillis"] \
                    or max(sample_epochs) < target["completedAtEpochMillis"]:
                raise AnalysisError(
                    f"qdisc sampler does not span {event['label']} for {key[0]}/{key[1]}: {path}"
                )
            post = next((row for row in samples if row["epochMillis"] >= target["completedAtEpochMillis"]), None)
            if post is None or post["epochMillis"] > target["completedAtEpochMillis"] + QDISC_SAMPLE_TOLERANCE_MILLIS:
                raise AnalysisError(
                    f"qdisc sampler lacks a timely post-apply sample for {event['label']} "
                    f"on {key[0]}/{key[1]}: {path}"
                )
            if next_apply_start is not None and post["epochMillis"] >= next_apply_start:
                raise AnalysisError(
                    f"qdisc sampler has no {event['label']} state sample before the next apply "
                    f"on {key[0]}/{key[1]}: {path}"
                )
            state_rows = [
                row for row in samples
                if row["epochMillis"] >= post["epochMillis"]
                and (next_apply_start is None or row["epochMillis"] < next_apply_start)
            ]
            for state_row in state_rows:
                matches, reason = qdisc_state_matches(state_row, event, target)
                if not matches:
                    raise AnalysisError(
                        f"qdisc sampler state does not prove {event['label']} on {key[0]}/{key[1]} "
                        f"at {state_row['epochMillis']}: {reason}"
                    )
            state_evidence.append({
                "event": event["label"],
                "namespace": key[0],
                "interface": key[1],
                "sampleEpochMillis": post["epochMillis"],
                "state": "matched",
            })
    return {
        "status": "available",
        "path": str(path),
        "sampleCount": len(rows),
        "firstEpochMillis": first,
        "lastEpochMillis": last,
        "targets": [f"{namespace}:{interface}" for namespace, interface in sorted(observed)],
        "targetCoverage": {
            f"{namespace}:{interface}": {
                "firstEpochMillis": min(row["epochMillis"] for row in target_rows[(namespace, interface)]),
                "lastEpochMillis": max(row["epochMillis"] for row in target_rows[(namespace, interface)]),
                "sampleCount": len(target_rows[(namespace, interface)]),
            }
            for namespace, interface in sorted(observed)
        },
        "eventStateEvidence": state_evidence,
    }


def last_at_or_before(samples: list[dict[str, Any]], epoch: int) -> dict[str, Any] | None:
    candidates = [sample for sample in samples if sample["epochMillis"] <= epoch]
    return candidates[-1] if candidates else None


def first_at_or_after(
    samples: list[dict[str, Any]], epoch: int, tolerance: int = MAX_TIMELINE_SAMPLE_GAP_MILLIS
) -> dict[str, Any] | None:
    for sample in samples:
        if sample["epochMillis"] >= epoch:
            return sample if sample["epochMillis"] <= epoch + tolerance else None
    return None


def affected(sample: dict[str, Any]) -> dict[str, Any]:
    return sample["affected"]


def runtime_direct(runtime: dict[str, Any]) -> float | None:
    values = [
        runtime.get("directBufferPoolMemoryUsedBytes"),
        runtime.get("nettyPooledDirectMemoryUsedBytes"),
    ]
    usable = [float(value) for value in values if finite_number(value)]
    return max(usable) if usable else None


def sustained_reclamation(
    samples: list[dict[str, Any]], anchor: int, pre: dict[str, Any], quiet_millis: int = 1000,
    *, require_closed_peers: bool = False,
) -> dict[str, Any]:
    pre_affected = affected(pre)
    queue_limit = pre_affected["currentQueuedBytes"] * 1.05
    flight_limit = pre_affected["currentBytesInFlight"] * 1.05
    pre_direct = runtime_direct(pre["runtime"])
    direct_limit = None if pre_direct is None else pre_direct * 1.05

    def first_sustained(predicate) -> int | None:
        after = [sample for sample in samples if sample["epochMillis"] >= anchor]
        for index, sample in enumerate(after):
            if not predicate(sample):
                continue
            quiet_end = sample["epochMillis"] + quiet_millis
            proof_end = first_at_or_after(after[index:], quiet_end)
            if proof_end is None:
                continue
            tail = [
                candidate for candidate in after[index:]
                if candidate["epochMillis"] <= proof_end["epochMillis"]
            ]
            if all(predicate(candidate) for candidate in tail):
                return int(sample["epochMillis"] - anchor)
        return None

    queue_ms = first_sustained(lambda sample: affected(sample)["currentQueuedBytes"] <= queue_limit)
    flight_ms = first_sustained(lambda sample: affected(sample)["currentBytesInFlight"] <= flight_limit)
    combined_ms = first_sustained(
        lambda sample: affected(sample)["currentQueuedBytes"] <= queue_limit
        and affected(sample)["currentBytesInFlight"] <= flight_limit
    )
    direct_ms = None
    if direct_limit is not None:
        direct_ms = first_sustained(
            lambda sample: runtime_direct(sample["runtime"]) is not None
            and runtime_direct(sample["runtime"]) <= direct_limit
        )
    peer_queue_flight_ms = None
    if require_closed_peers:
        if pre_affected["openPeers"] <= 0 or pre_affected["activePeers"] <= 0:
            raise AnalysisError("irrecoverable-peer reclamation requires pre-event open and active peers")
        peer_queue_flight_ms = first_sustained(
            lambda sample: affected(sample)["openPeers"] == 0
            and affected(sample)["activePeers"] == 0
            and affected(sample)["currentQueuedBytes"] <= queue_limit
            and affected(sample)["currentBytesInFlight"] <= flight_limit
        )
    return {
        "steadyStateWindowMillis": quiet_millis,
        "preEventCurrentQueuedBytes": pre_affected["currentQueuedBytes"],
        "preEventCurrentBytesInFlight": pre_affected["currentBytesInFlight"],
        "preEventDirectMemoryBytes": pre_direct,
        "queueReclaimedMillis": queue_ms,
        "bytesInFlightReclaimedMillis": flight_ms,
        "queueAndInFlightReclaimedMillis": combined_ms,
        "directMemoryWithinFivePercentMillis": direct_ms,
        "closedPeersQueueAndInFlightReclaimedMillis": peer_queue_flight_ms,
    }


def event_metrics(
    samples: list[dict[str, Any]], event: dict[str, Any], next_event: dict[str, Any] | None,
    previous_event: dict[str, Any] | None, *, permanent_disappearance: bool = False,
    expected_recovery_mode: str = "legacy", affected_clients: int = 0,
) -> dict[str, Any]:
    start = event["applyStartedAtEpochMillis"]
    completed = event["applyCompletedAtEpochMillis"]
    pre = last_at_or_before(samples, start)
    if pre is None:
        raise AnalysisError(f"timeline has no pre-event sample for {event['label']}")
    if start - pre["epochMillis"] > MAX_TIMELINE_SAMPLE_GAP_MILLIS:
        raise AnalysisError(f"timeline pre-event sample is stale for {event['label']}")
    nominal_end = completed + 10_000
    truncate_for_next_event = (
        event["label"] == "initial-netem"
        and next_event is not None
        and next_event["applyStartedAtEpochMillis"] <= nominal_end
    )
    segment_end = next_event["applyStartedAtEpochMillis"] if truncate_for_next_event else nominal_end
    end = last_at_or_before(samples, segment_end) if truncate_for_next_event \
        else first_at_or_after(samples, segment_end)
    if end is None or end["epochMillis"] <= pre["epochMillis"]:
        raise AnalysisError(f"timeline has no post-event window for {event['label']}")
    window_samples = [
        sample for sample in samples
        if pre["epochMillis"] <= sample["epochMillis"] <= end["epochMillis"]
    ]
    t_plus_10_truncated = truncate_for_next_event
    t_plus_10 = None if t_plus_10_truncated else first_at_or_after(samples, nominal_end)
    pre_affected = affected(pre)
    end_affected = affected(end)
    duration_seconds = (end["epochMillis"] - pre["epochMillis"]) / 1000.0
    deltas = {
        f"{field}Delta": end_affected[field] - pre_affected[field]
        for field in (
            "nackRetransmittedDatagrams",
            "nackRetransmittedBytes",
            "timeoutRetransmittedDatagrams",
            "timeoutRetransmittedBytes",
        )
    }
    pressure = {
        **deltas,
        "nackRetransmittedDatagramsPerSecond": deltas["nackRetransmittedDatagramsDelta"] / duration_seconds,
        "timeoutRetransmittedDatagramsPerSecond": deltas["timeoutRetransmittedDatagramsDelta"] / duration_seconds,
        "peakCurrentQueuedBytesAbovePreEvent": max(
            0, max(affected(sample)["currentQueuedBytes"] for sample in window_samples)
            - pre_affected["currentQueuedBytes"]
        ),
        "currentQueuedBytesAtTPlus10AbovePreEvent": None if t_plus_10 is None else max(
            0, affected(t_plus_10)["currentQueuedBytes"] - pre_affected["currentQueuedBytes"]
        ),
        "peakCurrentBytesInFlightAbovePreEvent": max(
            0, max(affected(sample)["currentBytesInFlight"] for sample in window_samples)
            - pre_affected["currentBytesInFlight"]
        ),
        "currentBytesInFlightAtTPlus10AbovePreEvent": None if t_plus_10 is None else max(
            0, affected(t_plus_10)["currentBytesInFlight"] - pre_affected["currentBytesInFlight"]
        ),
    }
    pressure["retransmittedDatagramsDelta"] = (
        pressure["nackRetransmittedDatagramsDelta"]
        + pressure["timeoutRetransmittedDatagramsDelta"]
    )
    pressure["retransmittedBytesDelta"] = (
        pressure["nackRetransmittedBytesDelta"]
        + pressure["timeoutRetransmittedBytesDelta"]
    )
    pressure["retransmittedDatagramsPerSecond"] = (
        pressure["nackRetransmittedDatagramsPerSecond"]
        + pressure["timeoutRetransmittedDatagramsPerSecond"]
    )
    pressure["unacknowledgedTransportBytesAtTPlus10AbovePreEvent"] = pressure[
        "currentBytesInFlightAtTPlus10AbovePreEvent"
    ]
    first_disconnect = next((
        sample for sample in samples
        if sample["epochMillis"] >= completed
        and affected(sample)["disconnectEvents"] > pre_affected["disconnectEvents"]
    ), None)
    first_ack = next((
        sample for sample in samples
        if sample["epochMillis"] >= completed
        and affected(sample)["acknowledgementProgressEvents"]
        > pre_affected["acknowledgementProgressEvents"]
    ), None)
    runtime_samples = [sample["runtime"] for sample in window_samples]
    direct_pool = [row.get("directBufferPoolMemoryUsedBytes") for row in runtime_samples]
    netty_direct = [row.get("nettyPooledDirectMemoryUsedBytes") for row in runtime_samples]
    cpu_load = [row.get("processCpuLoad") for row in runtime_samples]
    runtime_maxima = {
        "heapUsedBytes": max(row["heapUsedBytes"] for row in runtime_samples),
        "directBufferPoolMemoryUsedBytes": max((value for value in direct_pool if finite_number(value)), default=None),
        "nettyPooledDirectMemoryUsedBytes": max((value for value in netty_direct if finite_number(value)), default=None),
        "residentSetSizeBytes": maximum_available(
            row.get("residentSetSizeBytes") for row in runtime_samples
        ),
        "processCpuLoad": maximum_available(cpu_load),
        "processCpuTimeNanos": maximum_available(
            row.get("processCpuTimeNanos") for row in runtime_samples
        ),
    }
    missing_runtime = [
        field for field in ("residentSetSizeBytes", "processCpuLoad", "processCpuTimeNanos")
        if runtime_maxima[field] is None
    ]
    if runtime_maxima["directBufferPoolMemoryUsedBytes"] is None \
            and runtime_maxima["nettyPooledDirectMemoryUsedBytes"] is None:
        missing_runtime.append("directMemory")
    if missing_runtime:
        raise AnalysisError(f"event window {event['label']} lacks runtime metrics: {missing_runtime}")
    model_pre = pre_affected.get("congestionModel")
    model_window_rows = [
        (sample, affected(sample).get("congestionModel")) for sample in window_samples
        if isinstance(affected(sample).get("congestionModel"), dict)
    ]
    complete_model_window_rows = [
        (sample, model) for sample, model in model_window_rows
        if affected(sample).get("observedPeers") == affected_clients
        and model.get("observedPeers") == affected_clients
        and model["observedPeers"] > 0
        and model.get("estimatedDeliveryRateObservedPeers") == model["observedPeers"]
        and model.get("pacingRateObservedPeers") == model["observedPeers"]
        and model.get("minimumRttObservedPeers") == model["observedPeers"]
    ]
    usable_model_window_rows = [
        (sample, model) for sample, model in complete_model_window_rows
        if model.get("oldestObservedAtEpochMillis", -1) >= completed
        and model.get("latestObservedAtEpochMillis", -1) <= sample["epochMillis"]
    ]
    usable_model_window = [model for _, model in usable_model_window_rows]
    model_t_plus_10 = None if t_plus_10 is None else affected(t_plus_10).get("congestionModel")
    congestion_model = None
    model_available = bool(usable_model_window)
    if expected_recovery_mode == "model_based" and isinstance(model_pre, dict) and model_window_rows:
        model_window = [model for _, model in model_window_rows]
        window_metrics = None
        if usable_model_window:
            window_metrics = {
                "minimumObservedPeers": min(model["observedPeers"] for model in usable_model_window),
                "minimumEstimatedDeliveryRateObservedPeers": min(
                    model["estimatedDeliveryRateObservedPeers"] for model in usable_model_window
                ),
                "minimumPacingRateObservedPeers": min(
                    model["pacingRateObservedPeers"] for model in usable_model_window
                ),
                "minimumRttObservedPeers": min(
                    model["minimumRttObservedPeers"] for model in usable_model_window
                ),
                "minimumEstimatedDeliveryRateBytesPerSecond": min(
                    (model["totalEstimatedDeliveryRateBytesPerSecond"] for model in usable_model_window
                     if finite_number(model.get("totalEstimatedDeliveryRateBytesPerSecond"))),
                    default=None,
                ),
                "minimumPacingRateBytesPerSecond": min(
                    (model["totalPacingRateBytesPerSecond"] for model in usable_model_window
                     if finite_number(model.get("totalPacingRateBytesPerSecond"))),
                    default=None,
                ),
                "maximumRecentLossRate": maximum_available(
                    model.get("maximumRecentLossRate") for model in usable_model_window
                ),
                "maximumPersistentCongestionPeers": maximum_available(
                    model.get("persistentCongestionPeers") for model in usable_model_window
                ),
                "maximumMinimumRttMillis": maximum_available(
                    model.get("maximumMinimumRttMillis") for model in usable_model_window
                ),
            }
        congestion_model = {
            "preEvent": model_pre,
            "atTPlus10": model_t_plus_10 if isinstance(model_t_plus_10, dict) else None,
            "coverage": {
                "sampleCount": len(model_window),
                "samplesWithObservedPeers": sum(model["observedPeers"] > 0 for model in model_window),
                "samplesWithCompleteKeyCoverage": len(complete_model_window_rows),
                "samplesWithFreshCompleteKeyCoverage": len(usable_model_window_rows),
                "minimumObservedPeers": min(model["observedPeers"] for model in model_window),
                "maximumObservedPeers": max(model["observedPeers"] for model in model_window),
                "minimumEstimatedDeliveryRateObservedPeers": min(
                    model["estimatedDeliveryRateObservedPeers"] for model in model_window
                ),
                "maximumEstimatedDeliveryRateObservedPeers": max(
                    model["estimatedDeliveryRateObservedPeers"] for model in model_window
                ),
                "minimumPacingRateObservedPeers": min(
                    model["pacingRateObservedPeers"] for model in model_window
                ),
                "maximumPacingRateObservedPeers": max(
                    model["pacingRateObservedPeers"] for model in model_window
                ),
                "minimumRttObservedPeers": min(
                    model["minimumRttObservedPeers"] for model in model_window
                ),
                "maximumRttObservedPeers": max(
                    model["minimumRttObservedPeers"] for model in model_window
                ),
            },
            "window": window_metrics,
            "eventDeltas": {
                field: model_window[-1][field] - model_pre[field]
                for field in ("nackRecoveryHints", "nackReorderingResolved", "nackLossValidated")
            },
        }
    result = {
        **event,
        "analysisWindow": {
            "preEventSampleEpochMillis": pre["epochMillis"],
            "endSampleEpochMillis": end["epochMillis"],
            "durationMillis": end["epochMillis"] - pre["epochMillis"],
            "truncatedByNextEvent": truncate_for_next_event,
            "tPlus10SampleEpochMillis": None if t_plus_10 is None else t_plus_10["epochMillis"],
        },
        "pressure": pressure,
        "affectedQueue": {
            "preEventCurrentBytes": pre_affected["currentQueuedBytes"],
            "maximumCurrentBytes": max(affected(sample)["currentQueuedBytes"] for sample in window_samples),
            "maximumPeerBytes": max(affected(sample)["maxPeerQueuedBytes"] for sample in window_samples),
            "currentBytesAtTPlus10": None if t_plus_10 is None else affected(t_plus_10)["currentQueuedBytes"],
        },
        "timing": {
            "firstDisconnectMillis": None if first_disconnect is None else first_disconnect["epochMillis"] - completed,
            "firstAcknowledgementProgressMillis": None if first_ack is None else first_ack["epochMillis"] - completed,
            "irrecoverablePeerReclamationMillis": None,
            "reclamation": None,
        },
        "runtimeMaxima": runtime_maxima,
        "affectedCongestionModel": congestion_model,
        "dataAvailability": {
            "postEventTPlus10": (
                "available" if t_plus_10 is not None
                else "unavailable-truncated-by-next-event" if t_plus_10_truncated
                else "unavailable-timeline-ended"
            ),
            "affectedRetryCounters": "available",
            "affectedQueueCounters": "available",
            "runtime": "available",
            "affectedCongestionModel": (
                "available" if model_available
                else "unavailable-no-affected-model-samples"
                if expected_recovery_mode == "model_based" and affected_clients > 0
                else "not-applicable-no-affected-clients"
                if expected_recovery_mode == "model_based"
                else "not-configured-recovery-mode"
            ),
            "usefulDeliveryInEventWindow": "unavailable-on-server-worker",
        },
    }
    if event["label"] == "external-recovery":
        steady_pre = pre
        if previous_event is not None and previous_event["label"] == "external-blackhole":
            steady_pre = last_at_or_before(samples, previous_event["applyStartedAtEpochMillis"]) or pre
        result["timing"]["reclamation"] = sustained_reclamation(samples, completed, steady_pre)
    elif event["label"] == "external-blackhole" and next_event is None and permanent_disappearance:
        result["timing"]["reclamation"] = sustained_reclamation(
            samples, completed, pre, require_closed_peers=True
        )
        result["timing"]["irrecoverablePeerReclamationMillis"] = result["timing"]["reclamation"][
            "closedPeersQueueAndInFlightReclaimedMillis"
        ]
    return result


def load_summary(path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    summary = load_json(path)
    aggregate = summary.get("aggregate")
    if not isinstance(aggregate, dict):
        raise AnalysisError(f"merged summary lacks aggregate object: {path}")
    required = (
        "clients", "affectedClients", "measuredIterations", "payloadSize", "targetClientMbps",
        "elapsedMillis",
        "healthyClientMbpsP50", "healthyFairnessIndex", "healthySentToDeliveredBytesRatio",
        "affectedClientMbpsP50", "affectedSentToDeliveredBytesRatio",
        "affectedUndeliveredServerBytesOut", "affectedUndeliveredServerGbps", "maxQueuedBytes",
    )
    for field in required:
        require_number(aggregate, field, "aggregate")
    if aggregate["measuredIterations"] <= 0:
        raise AnalysisError(f"merged summary has no measured iterations: {path}")
    return aggregate, summary


def shape_key(case: dict[str, Any], *, perfect_reference: bool = False) -> str:
    manifest = case["manifest"]
    aggregate = case.get("aggregate") or {}
    shape = {
        "clients": manifest.get("clients"),
        "payloadSize": manifest.get("payloadSize"),
        "perClientMbps": manifest.get("perClientMbps"),
        "rateMbps": manifest.get("rateMbps"),
        "reliability": aggregate.get("reliability"),
        "configuredMaxQueuedBytes": aggregate.get("configuredMaxQueuedBytes"),
        "packetLimit": manifest.get("packetLimit"),
        "globalPacketLimit": manifest.get("globalPacketLimit"),
        "maxQueuedBytes": manifest.get("maxQueuedBytes"),
        "workers": manifest.get("workers"),
        "resourceSafetyMaxAggregateQueuedBytes": manifest.get(
            "resourceSafetyMaxAggregateQueuedBytes"
        ),
        "resourceSafetyMaxDirectMemoryUsedBytes": manifest.get(
            "resourceSafetyMaxDirectMemoryUsedBytes"
        ),
        "measuredIterations": aggregate.get("measuredIterations"),
        "elapsedMillis": aggregate.get("elapsedMillis"),
        "targetClientMbps": aggregate.get("targetClientMbps"),
    }
    if not perfect_reference:
        shape.update({
            "case": manifest.get("case"),
            "affectedClients": manifest.get("affectedClients"),
            "latency": manifest.get("latency"),
            "jitter": manifest.get("jitter"),
            "loss": manifest.get("loss"),
            "direction": manifest.get("direction"),
            "netemLimitPackets": manifest.get("netemLimitPackets"),
            "hasRecovery": manifest.get("recoveryAtEpochMillis") is not None,
            "netemRelativeToStartMillis": (
                None if manifest.get("netemAtEpochMillis") is None
                else manifest.get("netemAtEpochMillis") - manifest.get("startAtEpochMillis")
            ),
            "blackholeRelativeToStartMillis": (
                None if manifest.get("blackholeAtEpochMillis") is None
                else manifest.get("blackholeAtEpochMillis") - manifest.get("startAtEpochMillis")
            ),
            "recoveryAfterBlackholeMillis": (
                None if manifest.get("recoveryAtEpochMillis") is None
                else manifest.get("recoveryAtEpochMillis") - manifest.get("blackholeAtEpochMillis")
            ),
        })
    return json.dumps(shape, sort_keys=True, separators=(",", ":"))


def analyze_case(case_root: Path, early_millis: int, late_millis: int) -> dict[str, Any]:
    campaign_root = find_campaign_root(case_root)
    result: dict[str, Any] = {
        "caseRoot": str(case_root),
        "caseId": str(case_root),
        # Preserve structural ancestry even when manifest JSON is malformed. Comparison mode must
        # not lose the ability to scope a failed candidate case merely because parsing failed early.
        "campaignRoot": None if campaign_root is None else str(campaign_root),
        "status": "fail",
        "issues": [],
        "dataAvailability": {},
        "externalEvents": [],
    }
    try:
        manifest_path = case_root / "manifest.json"
        manifest = load_json(manifest_path)
        if manifest.get("kind") != "raknet-netns-worker-smoke" or manifest.get("execute") is not True:
            raise AnalysisError(f"manifest is not an executed netns worker case: {manifest_path}")
        result["manifest"] = manifest
        result["recoveryMode"] = require_recovery_mode(manifest, "manifest")
        result["resourceSafety"] = require_resource_safety(manifest, "manifest")
        for field in ("packetLimit", "globalPacketLimit", "maxQueuedBytes", "workers"):
            require_optional_positive_int(manifest, field, "manifest")
        run_id = manifest.get("runId")
        if not isinstance(run_id, str) or not run_id.strip():
            raise AnalysisError("manifest.runId must be a non-empty string")
        healthy_clients = require_number(manifest, "healthyClients", "manifest")
        affected_clients = require_number(manifest, "affectedClients", "manifest")
        if healthy_clients != int(healthy_clients) or affected_clients != int(affected_clients):
            raise AnalysisError("manifest healthy/affected client counts must be integers")
        result["profile"] = profile_name(manifest)
        timeline_path = find_one(case_root, "server/*/timeline.jsonl", "server timeline.jsonl")
        samples, timeline_availability = parse_timeline(timeline_path, manifest)
        result["artifacts"] = {"manifest": str(manifest_path), "timeline": str(timeline_path)}
        result["dataAvailability"]["timeline"] = {"status": "available", **timeline_availability}
        result["resourceSafetyAbort"] = timeline_availability["resourceSafetyAbort"]
        if timeline_availability["resourceSafetyAbortCount"] > 1:
            raise AnalysisError("server timeline contains multiple resource-safety-abort events")
        if result["resourceSafetyAbort"] is not None:
            raise AnalysisError("benchmark was terminated by the resource safety watchdog")
        receiver_timeline_availability: dict[str, Any] = {}
        receiver_roles = (
            ("healthy", "receiver-healthy", int(healthy_clients),
             f"{run_id}-healthy" if affected_clients > 0 else f"{run_id}-receiver"),
            ("affected", "receiver-affected", int(affected_clients), f"{run_id}-affected"),
        )
        for role_name, directory, client_count, expected_run_id in receiver_roles:
            matches = sorted(case_root.glob(f"{directory}/*/timeline.jsonl"))
            if client_count > 0:
                if len(matches) != 1:
                    raise AnalysisError(
                        f"expected exactly one {role_name} receiver timeline.jsonl below {case_root}, "
                        f"found {len(matches)}"
                    )
                receiver_timeline_availability[role_name] = validate_role_timeline(
                    matches[0], manifest, expected_run_id
                )
                result["artifacts"][f"{role_name}ReceiverTimeline"] = str(matches[0])
            elif matches:
                raise AnalysisError(
                    f"unexpected {role_name} receiver timeline.jsonl below {case_root}"
                )
            else:
                receiver_timeline_availability[role_name] = {
                    "status": "not-configured-zero-clients"
                }
        result["dataAvailability"]["receiverTimelines"] = receiver_timeline_availability
        aggregate, merged_summary = load_summary(case_root / "merged" / "lab-summary.json")
        result["aggregate"] = aggregate
        if aggregate.get("recoveryModeProvenanceValid") is not True \
                or aggregate.get("recoveryMode") != result["recoveryMode"]:
            raise AnalysisError("merged summary recovery-mode provenance is invalid or disagrees with manifest")
        server_provenance = merged_summary.get("server")
        if not isinstance(server_provenance, dict) \
                or server_provenance.get("runId") != run_id \
                or server_provenance.get("role") != "server" \
                or server_provenance.get("recoveryMode") != result["recoveryMode"] \
                or not isinstance(server_provenance.get("gitRevision"), str) \
                or not server_provenance["gitRevision"].strip():
            raise AnalysisError("merged summary lacks exact server run/revision provenance")
        artifact_revisions: list[str] = [server_provenance["gitRevision"]]
        receiver_provenance = merged_summary.get("receivers")
        expected_receivers = {
            expected_run_id: client_count
            for _role_name, _directory, client_count, expected_run_id in receiver_roles
            if client_count > 0
        }
        if not isinstance(receiver_provenance, list) \
                or len(receiver_provenance) != len(expected_receivers):
            raise AnalysisError("merged summary receiver provenance count is incomplete or duplicated")
        seen_receiver_runs: set[str] = set()
        for receiver in receiver_provenance:
            if not isinstance(receiver, dict):
                raise AnalysisError("merged summary receiver provenance row is not an object")
            receiver_run_id = receiver.get("runId")
            receiver_revision = receiver.get("gitRevision")
            if receiver.get("role") != "client" \
                    or receiver.get("recoveryMode") != result["recoveryMode"] \
                    or receiver_run_id not in expected_receivers \
                    or receiver_run_id in seen_receiver_runs \
                    or receiver.get("clients") != expected_receivers.get(receiver_run_id) \
                    or not isinstance(receiver_revision, str) \
                    or not receiver_revision.strip():
                raise AnalysisError("merged summary receiver run/revision provenance is incomplete or unexpected")
            seen_receiver_runs.add(receiver_run_id)
            artifact_revisions.append(receiver_revision)
        if seen_receiver_runs != set(expected_receivers):
            raise AnalysisError("merged summary receiver provenance does not cover the expected roles")
        result["artifactRevisions"] = sorted(set(artifact_revisions))
        result["artifacts"]["mergedSummary"] = str(case_root / "merged" / "lab-summary.json")
        if int(aggregate["clients"]) != int(manifest["clients"]):
            raise AnalysisError("merged aggregate client count does not match manifest")
        if int(aggregate["affectedClients"]) != int(manifest["affectedClients"]):
            raise AnalysisError("merged aggregate affected-client count does not match manifest")
        aggregate_optional_fields = {
            "packetLimit": "packetLimit",
            "globalPacketLimit": "globalPacketLimit",
            "maxQueuedBytes": "configuredMaxQueuedBytes",
        }
        for manifest_field, aggregate_field in aggregate_optional_fields.items():
            if aggregate.get(aggregate_field) != manifest.get(manifest_field):
                raise AnalysisError(
                    f"merged aggregate {aggregate_field} does not match manifest {manifest_field}"
                )
        for field in ("resourceSafetyMaxAggregateQueuedBytes", "resourceSafetyMaxDirectMemoryUsedBytes"):
            if aggregate.get(field) != manifest.get(field):
                raise AnalysisError(f"merged aggregate {field} does not match manifest")
        if aggregate.get("resourceSafetyStatus") != "completed-no-abort":
            raise AnalysisError("merged aggregate does not prove completion without resource safety abort")
        events = [event for label in EVENT_LABELS if (event := parse_apply_event(case_root, manifest, label))]
        event_labels = {event["label"] for event in events}
        configured_impairment = any(
            str(manifest.get(field)) not in zeroes
            for field, zeroes in (
                ("latency", {"0", "0ms"}),
                ("jitter", {"", "0", "0ms"}),
                ("loss", {"0", "0%"}),
            )
        )
        if configured_impairment and "initial-netem" not in event_labels:
            raise AnalysisError("configured impaired profile lacks required initial-netem event evidence")
        if manifest.get("case") == "blackhole" and "external-blackhole" not in event_labels:
            raise AnalysisError("blackhole case lacks required external-blackhole event evidence")
        qdisc = validate_qdisc_timeseries(case_root, events)
        result["dataAvailability"]["qdiscTimeseries"] = qdisc
        for event in events:
            bounds_pass = (
                event["startOffsetMillis"] >= -early_millis
                and event["completionOffsetMillis"] <= late_millis
            )
            event["applyBounds"] = {
                "status": "pass" if bounds_pass else "fail",
                "maximumEarlyStartMillis": early_millis,
                "maximumLateCompletionMillis": late_millis,
            }
        permanent_disappearance = (
            manifest.get("case") == "blackhole"
            and manifest.get("recoveryAtEpochMillis") is None
            and manifest.get("direction") == "both"
        )
        for index, event in enumerate(events):
            next_event = events[index + 1] if index + 1 < len(events) else None
            previous_event = events[index - 1] if index > 0 else None
            result["externalEvents"].append(event_metrics(
                samples, event, next_event, previous_event,
                permanent_disappearance=permanent_disappearance,
                expected_recovery_mode=result["recoveryMode"],
                affected_clients=manifest["affectedClients"],
            ))
        result["runtimeMaxima"] = {
            "heapUsedBytes": max(sample["runtime"]["heapUsedBytes"] for sample in samples),
            "directBufferPoolMemoryUsedBytes": max(
                (sample["runtime"].get("directBufferPoolMemoryUsedBytes") for sample in samples
                 if finite_number(sample["runtime"].get("directBufferPoolMemoryUsedBytes"))), default=None
            ),
            "nettyPooledDirectMemoryUsedBytes": max(
                (sample["runtime"].get("nettyPooledDirectMemoryUsedBytes") for sample in samples
                 if finite_number(sample["runtime"].get("nettyPooledDirectMemoryUsedBytes"))), default=None
            ),
            "residentSetSizeBytes": maximum_available(
                sample["runtime"].get("residentSetSizeBytes") for sample in samples
            ),
            "processCpuLoad": maximum_available(
                sample["runtime"].get("processCpuLoad") for sample in samples
            ),
            "processCpuTimeNanos": maximum_available(
                sample["runtime"].get("processCpuTimeNanos") for sample in samples
            ),
            "sharedEventLoopTotalPendingTasks": maximum_available(
                (sample["runtime"].get("sharedEventLoops") or {}).get("totalPendingTasks")
                for sample in samples
            ),
            "sharedEventLoopMaxPendingTasks": maximum_available(
                (sample["runtime"].get("sharedEventLoops") or {}).get("maxPendingTasks")
                for sample in samples
            ),
            "sharedEventLoopSchedulingLagMillis": maximum_available(
                (sample["runtime"].get("sharedEventLoops") or {}).get("maxSchedulingLagMillis")
                for sample in samples
            ),
        }
        result["finalAffected"] = samples[-1]["affected"]
        result["affectedQueueMaxima"] = {
            "currentQueuedBytes": max(sample["affected"]["currentQueuedBytes"] for sample in samples),
            "sampledQueuedBytesHighWater": max(
                sample["affected"]["sampledQueuedBytesHighWater"] for sample in samples
            ),
            "maxPeerQueuedBytes": max(sample["affected"]["maxPeerQueuedBytes"] for sample in samples),
        }
        result["healthyQueueMaxima"] = {
            "currentQueuedBytes": max(sample["healthy"]["currentQueuedBytes"] for sample in samples),
            "sampledQueuedBytesHighWater": max(
                sample["healthy"]["sampledQueuedBytesHighWater"] for sample in samples
            ),
            "maxPeerQueuedBytes": max(sample["healthy"]["maxPeerQueuedBytes"] for sample in samples),
        }
        result["allQueueMaxima"] = {
            "currentQueuedBytes": max(sample["all"]["currentQueuedBytes"] for sample in samples),
            "sampledQueuedBytesHighWater": max(
                sample["all"]["sampledQueuedBytesHighWater"] for sample in samples
            ),
            "maxPeerQueuedBytes": max(sample["all"]["maxPeerQueuedBytes"] for sample in samples),
        }
        result["configurationKey"] = shape_key(result)
        result["perfectReferenceKey"] = shape_key(result, perfect_reference=True)
        if any(event["applyBounds"]["status"] != "pass" for event in result["externalEvents"]):
            result["issues"].append("external qdisc apply bounds exceeded")
        if any(
            event["dataAvailability"]["postEventTPlus10"] == "unavailable-timeline-ended"
            for event in result["externalEvents"]
        ):
            result["issues"].append("timeline does not reach T+10 for every external event")
        if result["recoveryMode"] == "model_based" and manifest["affectedClients"] > 0 \
                and any(event["dataAvailability"]["affectedCongestionModel"] != "available"
                        for event in result["externalEvents"]):
            result["issues"].append(
                "model_based event window lacks usable affected-cohort model samples"
            )
        result["status"] = "pass" if not result["issues"] else "fail"
    except (AnalysisError, KeyError, TypeError, ValueError, ArithmeticError) as error:
        reason = str(error) or error.__class__.__name__
        result["issues"].append(reason)
        result["dataAvailability"].setdefault("analysis", {"status": "unavailable", "reason": reason})
    return result


def gate(gate_id: str, scope: str, actual: Any, operator: str, threshold: Any,
         passed: bool, reason: str | None = None) -> dict[str, Any]:
    return {
        "id": gate_id,
        "scope": scope,
        "status": "pass" if passed else "fail",
        "actual": actual,
        "operator": operator,
        "threshold": threshold,
        "reason": reason,
    }


def absolute_gates(cases: list[dict[str, Any]], args: argparse.Namespace) -> list[dict[str, Any]]:
    gates: list[dict[str, Any]] = []
    for case in cases:
        gates.append(gate(
            "required-data-availability", case["caseId"], case["status"], "==", "pass",
            case["status"] == "pass", "; ".join(case.get("issues", [])) or None
        ))
        for event in case.get("externalEvents", []):
            gates.append(gate(
                "external-apply-bounds", f"{case['caseId']}:{event['label']}",
                {"startOffsetMillis": event["startOffsetMillis"],
                 "completionOffsetMillis": event["completionOffsetMillis"]},
                "within", {"earlyMillis": args.apply_early, "lateMillis": args.apply_late},
                event["applyBounds"]["status"] == "pass"
            ))
    valid = [case for case in cases if case["status"] == "pass"]
    perfect_by_shape = {
        case["perfectReferenceKey"]: case for case in valid if case.get("profile") == "perfect"
    }
    for case in valid:
        scope = case["caseId"]
        aggregate = case["aggregate"]
        perfect = perfect_by_shape.get(case["perfectReferenceKey"])
        healthy_p50 = aggregate["healthyClientMbpsP50"]
        gates.append(gate("healthy-p50-mbps", scope, healthy_p50, ">=", args.healthy_p50_min,
                          healthy_p50 >= args.healthy_p50_min))
        if perfect is not None and perfect["aggregate"]["healthyClientMbpsP50"] > 0:
            ratio = aggregate["healthyClientMbpsP50"] / perfect["aggregate"]["healthyClientMbpsP50"]
            gates.append({"id": "healthy-p50-vs-perfect", "scope": scope, "status": "informational",
                          "actual": ratio, "operator": None, "threshold": None,
                          "reason": "active goal uses an absolute healthy p50 gate"})
        fairness = aggregate["healthyFairnessIndex"]
        gates.append(gate("healthy-fairness", scope, fairness, ">=", args.healthy_fairness,
                          fairness >= args.healthy_fairness))
        send_deliver = aggregate["healthySentToDeliveredBytesRatio"]
        gates.append(gate("healthy-send-deliver", scope, send_deliver, "<=", args.healthy_send_deliver,
                          send_deliver <= args.healthy_send_deliver))
        peer_queue = case["allQueueMaxima"]["maxPeerQueuedBytes"]
        gates.append(gate("observed-max-peer-queue-bytes", scope, peer_queue, "<=", args.max_peer_queue,
                          peer_queue <= args.max_peer_queue))
        affected_queue = max(
            case["affectedQueueMaxima"]["currentQueuedBytes"],
            case["affectedQueueMaxima"]["sampledQueuedBytesHighWater"],
        )
        affected_limit = int(case["manifest"]["affectedClients"]) * args.max_affected_queue_per_client
        gates.append(gate("affected-cohort-queue-bytes", scope, affected_queue, "<=", affected_limit,
                          affected_queue <= affected_limit))
        healthy_queue = max(
            case["healthyQueueMaxima"]["currentQueuedBytes"],
            case["healthyQueueMaxima"]["sampledQueuedBytesHighWater"],
        )
        healthy_limit = int(case["manifest"]["healthyClients"]) * args.max_healthy_queue_per_client
        gates.append(gate("healthy-collateral-queue-bytes", scope, healthy_queue, "<=", healthy_limit,
                          healthy_queue <= healthy_limit))
        all_queue = max(
            case["allQueueMaxima"]["currentQueuedBytes"],
            case["allQueueMaxima"]["sampledQueuedBytesHighWater"],
        )
        configured_queue_safety = case["resourceSafety"]["resourceSafetyMaxAggregateQueuedBytes"]
        gates.append(gate("resource-safety-aggregate-queue-bytes", scope, all_queue, "<=",
                          configured_queue_safety, all_queue <= configured_queue_safety))
        direct_memory = maximum_available((
            case["runtimeMaxima"]["directBufferPoolMemoryUsedBytes"],
            case["runtimeMaxima"]["nettyPooledDirectMemoryUsedBytes"],
        ))
        configured_direct_safety = case["resourceSafety"]["resourceSafetyMaxDirectMemoryUsedBytes"]
        gates.append(gate("resource-safety-direct-memory-bytes", scope, direct_memory, "<=",
                          configured_direct_safety,
                          direct_memory is not None and direct_memory <= configured_direct_safety))
        for event in case.get("externalEvents", []):
            gates.append({
                "id": "affected-bytes-in-flight-at-t-plus-10",
                "scope": f"{scope}:{event['label']}",
                "status": "informational",
                "actual": event["pressure"].get("unacknowledgedTransportBytesAtTPlus10AbovePreEvent"),
                "operator": None,
                "threshold": None,
                "reason": "ACK-evidence semantics make lower bytes-in-flight an invalid universal recovery target",
            })
    poor = [case for case in valid if case.get("profile") == "poor"]
    for case in poor:
        scope = case["caseId"]
        aggregate = case["aggregate"]
        actual = aggregate["affectedClientMbpsP50"]
        gates.append(gate("poor-affected-p50-mbps", scope, actual, ">=", args.poor_affected_p50,
                          actual >= args.poor_affected_p50))
        disconnects = case["finalAffected"]["disconnectEvents"]
        gates.append(gate("poor-affected-disconnects", scope, disconnects, "<=", args.poor_disconnects,
                          disconnects <= args.poor_disconnects))
        actual = aggregate["affectedSentToDeliveredBytesRatio"]
        gates.append(gate("poor-affected-send-deliver", scope, actual, "<=", args.poor_send_deliver,
                          actual <= args.poor_send_deliver))
    event_cases = [case for case in valid if case.get("profile") == "blackhole"]
    for case in event_cases:
        scope = case["caseId"]
        blackholes = [event for event in case["externalEvents"] if event["label"] == "external-blackhole"]
        if not blackholes:
            gates.append(gate("blackhole-event-evidence", scope, None, "available", True, False,
                              "blackhole case has no validated apply event"))
            continue
        for event in blackholes:
            gates.append(gate("event-t-plus-10-availability", scope,
                              event["dataAvailability"]["postEventTPlus10"], "==", "available",
                              event["dataAvailability"]["postEventTPlus10"] == "available"))
        recoveries = [event for event in case["externalEvents"] if event["label"] == "external-recovery"]
        if recoveries:
            for event in recoveries:
                ack = event["timing"]["firstAcknowledgementProgressMillis"]
                gates.append(gate("recovery-ack-progress-millis", scope, ack, "<=", args.recovery_ack,
                                  ack is not None and ack <= args.recovery_ack))
                disconnects = case["finalAffected"]["disconnectEvents"]
                gates.append(gate("handover-disconnects", scope, disconnects, "==", 0, disconnects == 0))
                reclaim = event["timing"]["reclamation"]
                reclaim_ms = None if reclaim is None else reclaim["queueAndInFlightReclaimedMillis"]
                gates.append(gate("recovery-queue-inflight-reclamation-millis", scope, reclaim_ms, "<=", 10_000,
                                  reclaim_ms is not None and reclaim_ms <= 10_000))
        else:
            for event in blackholes:
                if case["manifest"].get("direction") == "both":
                    peer_reclaim = event["timing"]["irrecoverablePeerReclamationMillis"]
                    gates.append(gate("irrecoverable-peer-reclamation-millis", scope, peer_reclaim,
                                      "<=", args.peer_reclamation,
                                      peer_reclaim is not None and peer_reclaim <= args.peer_reclamation))
                else:
                    gates.append({
                        "id": "irrecoverable-peer-reclamation-millis",
                        "scope": scope,
                        "status": "not-applicable",
                        "actual": None,
                        "operator": None,
                        "threshold": None,
                        "reason": "permanent disappearance requires a bidirectional blackhole",
                    })
    if not poor:
        gates.append({"id": "poor-link-usefulness", "scope": "dataset", "status": "not-applicable",
                      "reason": "no poor profile"})
    if not event_cases:
        gates.append({"id": "event-resilience", "scope": "dataset", "status": "not-applicable",
                      "reason": "no blackhole profile"})
    return gates


def assign_occurrences(cases: list[dict[str, Any]]) -> None:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for case in cases:
        if case.get("configurationKey"):
            grouped[case["configurationKey"]].append(case)
    for key, rows in grouped.items():
        for occurrence, case in enumerate(sorted(rows, key=lambda row: row["caseRoot"]), 1):
            case["occurrence"] = occurrence
            case["matchKey"] = f"{key}#{occurrence}"


def reduction(baseline: float, candidate: float) -> tuple[str, float | None]:
    if baseline == 0:
        if candidate == 0:
            return "not-applicable-zero-baseline", None
        return "fail-baseline-zero-candidate-positive", None
    return "comparable", (baseline - candidate) * 100.0 / baseline


def compare_cases(baseline: list[dict[str, Any]], candidate: list[dict[str, Any]],
                  baseline_campaigns: list[dict[str, Any]], candidate_campaigns: list[dict[str, Any]],
                  minimum: float, max_affected_queue_per_client: int,
                  minimum_campaigns: int, peer_reclamation_millis: int,
                  baseline_recovery_mode: str, candidate_recovery_mode: str) -> dict[str, Any]:
    complete_baseline_rows = [
        campaign for campaign in baseline_campaigns
        if campaign["status"] == "pass" and campaign["fullCampaign"]
    ]
    complete_candidate_rows = [
        campaign for campaign in candidate_campaigns
        if campaign["status"] == "pass" and campaign["fullCampaign"]
    ]
    complete_baseline_roots = {campaign["campaignRoot"] for campaign in complete_baseline_rows}
    complete_candidate_roots = {campaign["campaignRoot"] for campaign in complete_candidate_rows}
    campaign_by_root = {
        campaign["campaignRoot"]: campaign for campaign in (*baseline_campaigns, *candidate_campaigns)
    }
    permanent_candidate_cases = [
        case for case in candidate
        if case.get("profile") == "blackhole"
        and case.get("manifest", {}).get("direction") == "both"
        and case.get("manifest", {}).get("recoveryAtEpochMillis") is None
    ]
    valid_permanent_candidate_cases = []
    for case in permanent_candidate_cases:
        blackholes = [
            event for event in case.get("externalEvents", [])
            if event.get("label") == "external-blackhole"
        ]
        reclamation = None if len(blackholes) != 1 else \
            blackholes[0].get("timing", {}).get("irrecoverablePeerReclamationMillis")
        if case["status"] == "pass" and case.get("campaignValidationStatus") == "pass" \
                and finite_number(reclamation) and reclamation <= peer_reclamation_millis:
            valid_permanent_candidate_cases.append(case)
    permanent_goal_identities = {
        campaign_by_root[case["campaignRoot"]]["goalProvenance"]["goalExecutionIdentity"]
        for case in valid_permanent_candidate_cases
        if case.get("campaignRoot") in campaign_by_root
        and isinstance(campaign_by_root[case["campaignRoot"]].get("goalProvenance"), dict)
    }
    invalid_baseline = [
        case["caseRoot"] for case in baseline
        if case.get("campaignRoot") in complete_baseline_roots
        and (case["status"] != "pass" or case.get("campaignValidationStatus") != "pass")
    ]
    invalid_candidate = [
        case["caseRoot"] for case in candidate
        if case["status"] != "pass" or case.get("campaignValidationStatus") != "pass"
    ]
    invalid_candidate_campaigns = [
        campaign["campaignRoot"] for campaign in candidate_campaigns
        if campaign["status"] != "pass"
    ]
    base_map = {case.get("matchKey"): case for case in baseline
                if case.get("matchKey") and case["status"] == "pass"
                and case.get("campaignValidationStatus") == "pass"
                and case.get("campaignRoot") in complete_baseline_roots}
    cand_map = {case.get("matchKey"): case for case in candidate
                if case.get("matchKey") and case["status"] == "pass"
                and case.get("campaignValidationStatus") == "pass"
                and case.get("campaignRoot") in complete_candidate_roots}
    missing_candidate = sorted(key for key in base_map if key not in cand_map)
    extra_candidate = sorted(key for key in cand_map if key not in base_map)
    event_rows: list[dict[str, Any]] = []
    component_values: dict[str, dict[str, list[float]]] = defaultdict(lambda: {"baseline": [], "candidate": []})
    for key in sorted(base_map.keys() & cand_map.keys()):
        base_events = {event["label"]: event for event in base_map[key].get("externalEvents", [])}
        cand_events = {event["label"]: event for event in cand_map[key].get("externalEvents", [])}
        for label in ("initial-netem", "external-blackhole"):
            if label == "initial-netem" and base_map[key].get("profile") != "severe":
                continue
            if label not in base_events and label not in cand_events:
                continue
            row = {"matchKey": key, "event": label, "components": [], "status": "pass"}
            if label not in base_events or label not in cand_events:
                row["status"] = "fail"
                row["reason"] = "event missing from one side"
                event_rows.append(row)
                continue
            row["sendTypeDiagnostics"] = {
                "baseline": {
                    field: base_events[label]["pressure"].get(field)
                    for field in (
                        "nackRetransmittedDatagramsDelta", "nackRetransmittedBytesDelta",
                        "nackRetransmittedDatagramsPerSecond", "timeoutRetransmittedDatagramsDelta",
                        "timeoutRetransmittedBytesDelta", "timeoutRetransmittedDatagramsPerSecond",
                    )
                },
                "candidate": {
                    field: cand_events[label]["pressure"].get(field)
                    for field in (
                        "nackRetransmittedDatagramsDelta", "nackRetransmittedBytesDelta",
                        "nackRetransmittedDatagramsPerSecond", "timeoutRetransmittedDatagramsDelta",
                        "timeoutRetransmittedBytesDelta", "timeoutRetransmittedDatagramsPerSecond",
                    )
                },
                "status": "informational-send-type-classification",
            }
            for field in REDUCTION_FIELDS:
                base_value = base_events[label]["pressure"].get(field)
                cand_value = cand_events[label]["pressure"].get(field)
                component_name = f"{label}.{field}"
                component = {
                    "name": component_name,
                    "baseline": base_value,
                    "candidate": cand_value,
                    "minimumReductionPercent": minimum,
                }
                if not finite_number(base_value) or not finite_number(cand_value):
                    component.update({"status": "fail", "reductionPercent": None,
                                      "reason": "component unavailable or invalid"})
                    row["status"] = "fail"
                else:
                    status, reduction_percent = reduction(float(base_value), float(cand_value))
                    if status == "not-applicable-zero-baseline":
                        component.update({"status": status, "reductionPercent": None})
                    elif status != "comparable":
                        component.update({"status": "fail", "reductionPercent": reduction_percent,
                                          "reason": status})
                        row["status"] = "fail"
                    else:
                        passed = reduction_percent is not None and reduction_percent + 1e-9 >= minimum
                        component.update({"status": "pass" if passed else "fail",
                                          "reductionPercent": reduction_percent})
                        if not passed:
                            row["status"] = "fail"
                    component_values[component_name]["baseline"].append(float(base_value))
                    component_values[component_name]["candidate"].append(float(cand_value))
                row["components"].append(component)
            event_rows.append(row)
    queue_rows: list[dict[str, Any]] = []
    for key in sorted(base_map.keys() & cand_map.keys()):
        base_events = {event["label"]: event for event in base_map[key].get("externalEvents", [])}
        cand_events = {event["label"]: event for event in cand_map[key].get("externalEvents", [])}
        for label in ("initial-netem", "external-blackhole"):
            if label == "initial-netem" and base_map[key].get("profile") != "severe":
                continue
            if label not in base_events or label not in cand_events:
                continue
            for field in QUEUE_FIELDS:
                baseline_value = base_events[label]["affectedQueue"].get(field)
                candidate_value = cand_events[label]["affectedQueue"].get(field)
                affected_clients = int(cand_map[key]["manifest"]["affectedClients"])
                max_queue = affected_clients * max_affected_queue_per_client
                status = "pass" if finite_number(candidate_value) and candidate_value <= max_queue else "fail"
                _reduction_status, reduction_percent = (
                    reduction(float(baseline_value), float(candidate_value))
                    if finite_number(baseline_value) and finite_number(candidate_value)
                    else ("unavailable", None)
                )
                queue_rows.append({
                    "matchKey": key,
                    "event": label,
                    "name": field,
                    "baseline": baseline_value,
                    "candidate": candidate_value,
                    "reductionPercentInformational": reduction_percent,
                    "maximumCandidateBytes": max_queue,
                    "status": status,
                })
    components: list[dict[str, Any]] = []
    for name in sorted(component_values):
        base_total = sum(component_values[name]["baseline"])
        cand_total = sum(component_values[name]["candidate"])
        status, percent = reduction(base_total, cand_total)
        if status == "not-applicable-zero-baseline":
            gate_status = status
        else:
            gate_status = "pass" if status == "comparable" and percent is not None and percent + 1e-9 >= minimum else "fail"
        components.append({
            "name": name,
            "definition": "sum across matched event windows; lower is better",
            "baseline": base_total,
            "candidate": cand_total,
            "reductionPercent": percent,
            "minimumReductionPercent": minimum,
            "status": gate_status,
        })
    issues: list[str] = []
    complete_baseline_campaigns = [campaign["campaignRoot"] for campaign in complete_baseline_rows]
    complete_candidate_campaigns = [campaign["campaignRoot"] for campaign in complete_candidate_rows]
    baseline_identities = {campaign["executionIdentity"] for campaign in complete_baseline_rows}
    candidate_identities = {campaign["executionIdentity"] for campaign in complete_candidate_rows}
    configuration_keys = {
        campaign["experimentConfigurationKey"]
        for campaign in (*complete_baseline_rows, *complete_candidate_rows)
    }
    valid_candidate_campaign_rows = [
        campaign for campaign in candidate_campaigns if campaign["status"] == "pass"
    ]
    provenance_rows = [
        *complete_baseline_rows, *valid_candidate_campaign_rows,
    ]
    distribution_hashes = {
        campaign["goalProvenance"]["distributionSha256"]
        for campaign in provenance_rows
    }
    candidate_revisions = {
        campaign["goalProvenance"]["candidateRevision"]
        for campaign in provenance_rows
    }
    source_revisions = {
        campaign["goalProvenance"]["sourceRevision"]
        for campaign in provenance_rows
    }
    baseline_scoped_cases = [
        case for case in baseline if case.get("campaignRoot") in complete_baseline_roots
    ]
    # Every explicitly supplied candidate case is decision evidence. Unlike baseline ancillary diagnostics,
    # a malformed, wrong-mode, or differently-built supplemental candidate must never be silently ignored.
    candidate_scoped_cases = candidate
    candidate_scoped_campaigns = candidate_campaigns
    baseline_mode_provenance = {
        "expected": baseline_recovery_mode,
        "caseModes": sorted({case.get("recoveryMode") for case in baseline_scoped_cases},
                            key=lambda value: str(value)),
        "campaignModes": sorted(
            {campaign.get("recoveryMode") for campaign in complete_baseline_rows},
            key=lambda value: str(value)
        ),
    }
    candidate_mode_provenance = {
        "expected": candidate_recovery_mode,
        "caseModes": sorted({case.get("recoveryMode") for case in candidate_scoped_cases},
                            key=lambda value: str(value)),
        "campaignModes": sorted(
            {campaign.get("recoveryMode") for campaign in candidate_scoped_campaigns},
            key=lambda value: str(value)
        ),
    }
    recovery_modes_pass = (
        bool(baseline_scoped_cases) and bool(candidate_scoped_cases)
        and bool(complete_baseline_rows) and bool(complete_candidate_rows)
        and all(case.get("recoveryMode") == baseline_recovery_mode for case in baseline_scoped_cases)
        and all(case.get("recoveryMode") == candidate_recovery_mode for case in candidate_scoped_cases)
        and all(campaign.get("recoveryMode") == baseline_recovery_mode for campaign in complete_baseline_rows)
        and all(campaign.get("recoveryMode") == candidate_recovery_mode for campaign in candidate_scoped_campaigns)
    )
    campaigns_pass = (
        len(baseline_identities) >= minimum_campaigns
        and len(candidate_identities) >= minimum_campaigns
        and len(configuration_keys) == 1
    )
    distribution_provenance_pass = (
        bool(complete_baseline_rows) and bool(complete_candidate_rows)
        and len(distribution_hashes) == 1
        and len(candidate_revisions) == 1
        and len(source_revisions) == 1
    )
    permanent_disappearance_pass = len(permanent_goal_identities) >= minimum_campaigns
    if len(baseline_identities) < minimum_campaigns:
        issues.append(f"baseline has fewer than {minimum_campaigns} distinct complete campaign executions")
    if len(candidate_identities) < minimum_campaigns:
        issues.append(f"candidate has fewer than {minimum_campaigns} distinct complete campaign executions")
    if len(configuration_keys) != 1:
        issues.append("complete campaigns do not share one exact experiment configuration")
    if not recovery_modes_pass:
        issues.append(
            f"comparison requires every baseline campaign/case to be {baseline_recovery_mode} "
            f"and every candidate campaign/case to be {candidate_recovery_mode}"
        )
    if not distribution_provenance_pass:
        issues.append("comparison requires one identical source revision and staged jar distribution across both recovery modes")
    if not permanent_disappearance_pass:
        issues.append(
            f"candidate has fewer than {minimum_campaigns} distinct valid permanent bidirectional "
            "disappearance goal executions"
        )
    integrity_pass = not invalid_baseline and not invalid_candidate \
        and not invalid_candidate_campaigns \
        and not missing_candidate and not extra_candidate
    if invalid_baseline:
        issues.append("baseline contains invalid cases excluded from comparison")
    if invalid_candidate:
        issues.append("candidate contains invalid cases excluded from comparison")
    if invalid_candidate_campaigns:
        issues.append("candidate contains invalid campaign evidence")
    if missing_candidate:
        issues.append("candidate is missing baseline cases")
    if extra_candidate:
        issues.append("candidate has unmatched cases")
    reduction_pass = bool(event_rows) \
        and all(row["status"] == "pass" for row in event_rows) \
        and not any(component["status"] == "fail" for component in components)
    queue_pass = bool(queue_rows) and all(row["status"] == "pass" for row in queue_rows)
    if not event_rows:
        issues.append("no comparable severe initial-netem or timed blackhole events")
    if any(row["status"] != "pass" for row in event_rows):
        issues.append("one or more event pressure components miss the reduction target")
    if any(component["status"] == "fail" for component in components):
        issues.append("one or more aggregate pressure components miss the reduction target")
    if not queue_pass:
        issues.append("one or more candidate event queues exceed the independent queue cap")
    return {
        "minimumPressureReductionPercent": minimum,
        "missingCandidateCases": missing_candidate,
        "extraCandidateCases": extra_candidate,
        "events": event_rows,
        "components": components,
        "queueComponents": queue_rows,
        "requiredCompleteCampaigns": {
            "minimumPerSide": minimum_campaigns,
            "requiredProfiles": list(FULL_CAMPAIGN_PROFILES),
            "baseline": complete_baseline_campaigns,
            "candidate": complete_candidate_campaigns,
            "baselineExecutionIdentities": sorted(baseline_identities),
            "candidateExecutionIdentities": sorted(candidate_identities),
            "experimentConfigurationKeys": sorted(configuration_keys),
        },
        "recoveryModeProvenance": {
            "baseline": baseline_mode_provenance,
            "candidate": candidate_mode_provenance,
        },
        "distributionProvenance": {
            "candidateRevisions": sorted(candidate_revisions),
            "sourceRevisions": sorted(source_revisions),
            "distributionSha256": sorted(distribution_hashes),
            "baselineGoalRoots": sorted({
                campaign["goalProvenance"]["goalRoot"] for campaign in complete_baseline_rows
            }),
            "candidateGoalRoots": sorted({
                campaign["goalProvenance"]["goalRoot"] for campaign in valid_candidate_campaign_rows
            }),
        },
        "permanentDisappearanceEvidence": {
            "minimumExecutions": minimum_campaigns,
            "maximumReclamationMillis": peer_reclamation_millis,
            "candidateGoalExecutionIdentities": sorted(permanent_goal_identities),
            "candidateCases": [case["caseRoot"] for case in valid_permanent_candidate_cases],
            "discoveredCandidateCases": [case["caseRoot"] for case in permanent_candidate_cases],
        },
        "invalidBaselineCases": invalid_baseline,
        "invalidCandidateCases": invalid_candidate,
        "invalidCandidateCampaigns": invalid_candidate_campaigns,
        "gateStatus": {
            "pressureReduction": "pass" if reduction_pass else "fail",
            "eventQueueCap": "pass" if queue_pass else "fail",
            "completeCampaigns": "pass" if campaigns_pass else "fail",
            "evidenceIntegrity": "pass" if integrity_pass else "fail",
            "recoveryModeProvenance": "pass" if recovery_modes_pass else "fail",
            "distributionProvenance": "pass" if distribution_provenance_pass else "fail",
            "permanentDisappearanceEvidence": "pass" if permanent_disappearance_pass else "fail",
        },
        "status": "pass" if not issues else "fail",
        "issues": issues,
    }


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# RakNet Resilience Analysis",
        "",
        f"- Status: `{report['status']}`",
        f"- Mode: `{report['mode']}`",
        f"- Cases: `{len(report['cases'])}`",
        "",
        "## Gates",
        "",
        "| Status | Gate | Scope | Actual | Target |",
        "| --- | --- | --- | ---: | --- |",
    ]
    for row in report["gates"]:
        actual = json.dumps(row.get("actual"), sort_keys=True)
        target = f"{row.get('operator', '')} {json.dumps(row.get('threshold'), sort_keys=True)}".strip()
        lines.append(f"| {row['status']} | {row['id']} | {row.get('scope', '')} | {actual} | {target} |")
    lines.extend(["", "## Resource safety and event-loop diagnostics", "",
                  "| Case | Safety thresholds queue/direct | Abort | Event-loop availability | Max pending total/per-loop | Max scheduling lag ms |",
                  "| --- | ---: | --- | --- | ---: | ---: |"])
    for case in report["cases"]:
        policy = case.get("resourceSafety") or {}
        abort = case.get("resourceSafetyAbort")
        availability = (case.get("dataAvailability", {}).get("timeline") or {}).get(
            "sharedEventLoops", "unavailable"
        )
        runtime = case.get("runtimeMaxima") or {}
        lines.append(
            f"| {case.get('caseRoot')} | "
            f"{policy.get('resourceSafetyMaxAggregateQueuedBytes')} / "
            f"{policy.get('resourceSafetyMaxDirectMemoryUsedBytes')} | "
            f"{json.dumps(abort, sort_keys=True) if abort is not None else 'none'} | "
            f"{availability} | {runtime.get('sharedEventLoopTotalPendingTasks')} / "
            f"{runtime.get('sharedEventLoopMaxPendingTasks')} | "
            f"{runtime.get('sharedEventLoopSchedulingLagMillis')} |"
        )
    lines.extend(["", "## Event windows", "",
                  "| Case | Event | NACK delta/rate | Timeout delta/rate | Queue max / T+10 | Recovery / disconnect / reclaim ms | CPU max | Direct-memory max |",
                  "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |"])
    for case in report["cases"]:
        for event in case.get("externalEvents", []):
            pressure = event["pressure"]
            queue = event["affectedQueue"]
            timing = event["timing"]
            reclaim = timing.get("reclamation") or {}
            reclaim_millis = timing.get("irrecoverablePeerReclamationMillis")
            if reclaim_millis is None:
                reclaim_millis = reclaim.get("queueAndInFlightReclaimedMillis")
            runtime = event["runtimeMaxima"]
            direct = max(
                (value for value in (runtime.get("directBufferPoolMemoryUsedBytes"),
                                     runtime.get("nettyPooledDirectMemoryUsedBytes")) if finite_number(value)),
                default=None,
            )
            lines.append(
                f"| {case.get('caseRoot')} | {event['label']} | "
                f"{pressure['nackRetransmittedDatagramsDelta']} / {pressure['nackRetransmittedDatagramsPerSecond']:.3f} | "
                f"{pressure['timeoutRetransmittedDatagramsDelta']} / {pressure['timeoutRetransmittedDatagramsPerSecond']:.3f} | "
                f"{queue['maximumCurrentBytes']} / {queue['currentBytesAtTPlus10']} | "
                f"{timing.get('firstAcknowledgementProgressMillis')} / {timing.get('firstDisconnectMillis')} / "
                f"{reclaim_millis} | {runtime.get('processCpuLoad')} | {direct} |"
            )
    comparison = report.get("comparison")
    if comparison is not None:
        lines.extend(["", "## Comparative pressure reduction", "",
                      "| Status | Component | Baseline | Candidate | Reduction | Required |",
                      "| --- | --- | ---: | ---: | ---: | ---: |"])
        for component in comparison["components"]:
            reduction_value = component.get("reductionPercent")
            rendered = "n/a" if reduction_value is None else f"{reduction_value:.3f}%"
            lines.append(
                f"| {component['status']} | {component['name']} | {component['baseline']} | "
                f"{component['candidate']} | {rendered} | {comparison['minimumPressureReductionPercent']}% |"
            )
        lines.extend(["", "## Independent event queue cap", "",
                      "| Status | Event | Queue metric | Baseline bytes | Candidate bytes | Candidate cap |",
                      "| --- | --- | --- | ---: | ---: | ---: |"])
        for component in comparison["queueComponents"]:
            lines.append(
                f"| {component['status']} | {component['event']} | {component['name']} | "
                f"{component['baseline']} | {component['candidate']} | {component['maximumCandidateBytes']} |"
            )
    lines.extend(["", "## Campaign evidence", ""])
    if not report.get("campaigns"):
        lines.append("- No ancestor campaign plan/summary was supplied; raw-case analysis only.")
    for campaign in report.get("campaigns", []):
        provenance = campaign.get("goalProvenance") or {}
        lines.append(
            f"- `{campaign['campaignRoot']}`: `{campaign['status']}`; "
            f"full six-profile campaign: `{campaign.get('fullCampaign', False)}`; "
            f"candidate: `{provenance.get('candidateRevision')}`; "
            f"distribution SHA-256: `{provenance.get('distributionSha256')}`"
        )
        for issue in campaign.get("issues", []):
            lines.append(f"  - {issue}")
    lines.extend(["", "## Data availability", ""])
    for case in report["cases"]:
        lines.append(f"- `{case.get('caseRoot')}`: `{case.get('status')}`")
        timeline = case.get("dataAvailability", {}).get("timeline", {})
        if timeline:
            lines.append(
                f"  - Timeline: `{timeline.get('status')}`; server event-window delivery: "
                f"`{timeline.get('serverUsefulDelivery')}`"
            )
        qdisc = case.get("dataAvailability", {}).get("qdiscTimeseries", {})
        if qdisc:
            lines.append(f"  - Qdisc timeseries: `{qdisc.get('status')}`")
        for issue in case.get("issues", []):
            lines.append(f"  - {issue}")
    for issue in report.get("issues", []):
        lines.append(f"- {issue}")
    return "\n".join(lines) + "\n"


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--root", action="append", type=Path,
                      help="goal, campaign, case, manifest, or raw server timeline; repeatable")
    mode.add_argument("--baseline-root", action="append", type=Path,
                      help="baseline goal/campaign/case root; repeatable; requires --candidate-root")
    parser.add_argument("--candidate-root", action="append", type=Path)
    parser.add_argument("--baseline-recovery-mode", choices=RECOVERY_MODES, default="legacy",
                        help="required baseline provenance in comparison mode (default: legacy)")
    parser.add_argument("--candidate-recovery-mode", choices=RECOVERY_MODES, default="bounded",
                        help="required candidate provenance in comparison mode (default: bounded)")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--healthy-p50-min", type=float, default=4.75)
    parser.add_argument("--healthy-fairness", type=float, default=0.99)
    parser.add_argument("--healthy-send-deliver", type=float, default=1.10)
    parser.add_argument("--poor-affected-p50", type=float, default=3.5)
    parser.add_argument("--poor-disconnects", type=int, default=1)
    parser.add_argument("--poor-send-deliver", type=float, default=2.0)
    parser.add_argument("--max-peer-queue", type=int, default=DEFAULT_MAX_PEER_QUEUE_BYTES)
    parser.add_argument("--max-affected-queue-per-client", type=int,
                        default=DEFAULT_MAX_AFFECTED_QUEUE_BYTES_PER_CLIENT)
    parser.add_argument("--max-healthy-queue-per-client", type=int,
                        default=DEFAULT_MAX_HEALTHY_QUEUE_BYTES_PER_CLIENT)
    parser.add_argument("--recovery-ack", type=int, default=2000)
    parser.add_argument("--peer-reclamation", type=int, default=30_000)
    parser.add_argument("--apply-early", type=int, default=250)
    parser.add_argument("--apply-late", type=int, default=1000)
    parser.add_argument("--minimum-pressure-reduction-percent", type=float, default=90.0)
    parser.add_argument("--minimum-complete-campaigns", type=int, default=2)
    args = parser.parse_args(argv)
    if args.baseline_root and not args.candidate_root:
        parser.error("--baseline-root requires --candidate-root")
    if args.candidate_root and not args.baseline_root:
        parser.error("--candidate-root requires --baseline-root")
    if args.baseline_root and args.baseline_recovery_mode == args.candidate_recovery_mode:
        parser.error("comparison recovery modes must differ")
    for name in ("healthy_p50_min", "healthy_fairness", "healthy_send_deliver",
                 "poor_affected_p50", "poor_send_deliver", "minimum_pressure_reduction_percent"):
        value = getattr(args, name)
        if not finite_number(value) or value < 0:
            parser.error(f"--{name.replace('_', '-')} must be a non-negative finite number")
    for name in ("poor_disconnects", "max_peer_queue", "max_affected_queue_per_client",
                 "max_healthy_queue_per_client", "recovery_ack", "peer_reclamation",
                 "apply_early", "apply_late", "minimum_complete_campaigns"):
        if getattr(args, name) < 0:
            parser.error(f"--{name.replace('_', '-')} must be non-negative")
    lower_bounds = {
        "healthy_p50_min": 4.75,
        "healthy_fairness": 0.99,
        "poor_affected_p50": 3.5,
        "minimum_pressure_reduction_percent": 90.0,
        "minimum_complete_campaigns": 2,
    }
    upper_bounds = {
        "healthy_send_deliver": 1.10,
        "poor_disconnects": 1,
        "poor_send_deliver": 2.0,
        "max_peer_queue": DEFAULT_MAX_PEER_QUEUE_BYTES,
        "max_affected_queue_per_client": DEFAULT_MAX_AFFECTED_QUEUE_BYTES_PER_CLIENT,
        "max_healthy_queue_per_client": DEFAULT_MAX_HEALTHY_QUEUE_BYTES_PER_CLIENT,
        "recovery_ack": 2000,
        "peer_reclamation": 30_000,
        "apply_early": 250,
        "apply_late": 1000,
    }
    for name, minimum in lower_bounds.items():
        if getattr(args, name) < minimum:
            parser.error(f"--{name.replace('_', '-')} cannot weaken the active minimum of {minimum}")
    for name, maximum in upper_bounds.items():
        if getattr(args, name) > maximum:
            parser.error(f"--{name.replace('_', '-')} cannot weaken the active maximum of {maximum}")
    return args


def analyze_inputs(paths: list[Path], args: argparse.Namespace) -> tuple[
        list[dict[str, Any]], list[str], list[dict[str, Any]]]:
    roots, discovered_campaign_roots, issues = discover_evidence_roots(paths)
    cases = [analyze_case(root, args.apply_early, args.apply_late) for root in roots]
    if not cases:
        issues.append("no analyzable cases discovered")
    assign_occurrences(cases)
    campaign_roots = sorted({
        *(str(path) for path in discovered_campaign_roots),
        *(case["campaignRoot"] for case in cases if case.get("campaignRoot")),
    })
    campaigns = [validate_campaign(Path(path), cases) for path in campaign_roots]
    campaign_status = {campaign["campaignRoot"]: campaign["status"] for campaign in campaigns}
    for case in cases:
        case["campaignValidationStatus"] = (
            "unavailable-no-campaign" if case.get("campaignRoot") is None
            else campaign_status.get(case["campaignRoot"], "fail")
        )
    return cases, issues, campaigns


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    comparison = None
    if args.root:
        cases, issues, campaigns = analyze_inputs(args.root, args)
        gates = absolute_gates(cases, args)
        if any(campaign["status"] != "pass" for campaign in campaigns):
            issues.append("one or more discovered campaigns have invalid plan/summary evidence")
        if any(case["status"] != "pass" for case in cases):
            issues.append("one or more cases have missing or invalid required evidence")
        mode = "single"
    else:
        baseline, baseline_issues, baseline_campaigns = analyze_inputs(args.baseline_root, args)
        candidate, candidate_issues, candidate_campaigns = analyze_inputs(args.candidate_root, args)
        cases = [dict(case, side="baseline") for case in baseline] + [
            dict(case, side="candidate") for case in candidate
        ]
        issues = [f"baseline: {issue}" for issue in baseline_issues] + [
            f"candidate: {issue}" for issue in candidate_issues
        ]
        comparison = compare_cases(
            baseline, candidate, baseline_campaigns, candidate_campaigns,
            args.minimum_pressure_reduction_percent, args.max_affected_queue_per_client,
            args.minimum_complete_campaigns, args.peer_reclamation,
            args.baseline_recovery_mode, args.candidate_recovery_mode
        )
        baseline_comparison_roots = set(comparison["requiredCompleteCampaigns"]["baseline"])
        candidate_comparison_roots = set(comparison["requiredCompleteCampaigns"]["candidate"])
        candidate_disappearance_cases = set(
            comparison["permanentDisappearanceEvidence"]["discoveredCandidateCases"]
        )
        for case in cases:
            if case["side"] == "baseline":
                case["comparisonRole"] = (
                    "matched-comparison"
                    if case.get("campaignRoot") in baseline_comparison_roots
                    else "diagnostic-only"
                )
            elif case.get("campaignRoot") in candidate_comparison_roots:
                case["comparisonRole"] = "matched-comparison"
            elif case.get("caseRoot") in candidate_disappearance_cases:
                case["comparisonRole"] = "candidate-absolute-evidence"
            else:
                case["comparisonRole"] = "candidate-supplemental-evidence"
        campaigns = [dict(campaign, side="baseline") for campaign in baseline_campaigns] + [
            dict(campaign, side="candidate") for campaign in candidate_campaigns
        ]
        gates = absolute_gates(candidate, args)
        gates.extend([{
            "id": "post-event-pressure-reduction",
            "scope": "comparison",
            "status": comparison["gateStatus"]["pressureReduction"],
            "actual": {component["name"]: component["reductionPercent"]
                       for component in comparison["components"]},
            "operator": ">=",
            "threshold": args.minimum_pressure_reduction_percent,
            "reason": None,
        }, {
            "id": "independent-event-queue-cap",
            "scope": "comparison",
            "status": comparison["gateStatus"]["eventQueueCap"],
            "actual": {f"{row['matchKey']}:{row['event']}:{row['name']}": row["candidate"]
                       for row in comparison["queueComponents"]},
            "operator": "<=",
            "threshold": {
                "bytesPerAffectedClient": args.max_affected_queue_per_client,
                "scaledPerCase": True,
            },
            "reason": None,
        }, {
            "id": "complete-six-profile-campaigns",
            "scope": "comparison",
            "status": comparison["gateStatus"]["completeCampaigns"],
            "actual": {
                "baseline": len(comparison["requiredCompleteCampaigns"]["baselineExecutionIdentities"]),
                "candidate": len(comparison["requiredCompleteCampaigns"]["candidateExecutionIdentities"]),
                "sharedExperimentConfigurations": len(
                    comparison["requiredCompleteCampaigns"]["experimentConfigurationKeys"]
                ),
            },
            "operator": ">= per side",
            "threshold": args.minimum_complete_campaigns,
            "reason": None,
        }, {
            "id": "comparison-recovery-mode-provenance",
            "scope": "comparison",
            "status": comparison["gateStatus"]["recoveryModeProvenance"],
            "actual": comparison["recoveryModeProvenance"],
            "operator": (
                f"baseline {args.baseline_recovery_mode} and "
                f"candidate {args.candidate_recovery_mode}"
            ),
            "threshold": True,
            "reason": None,
        }, {
            "id": "comparison-distribution-provenance",
            "scope": "comparison",
            "status": comparison["gateStatus"]["distributionProvenance"],
            "actual": comparison["distributionProvenance"],
            "operator": "one identical source revision and staged jar distribution",
            "threshold": True,
            "reason": None,
        }, {
            "id": "comparison-permanent-disappearance-evidence",
            "scope": "comparison",
            "status": comparison["gateStatus"]["permanentDisappearanceEvidence"],
            "actual": comparison["permanentDisappearanceEvidence"],
            "operator": ">= distinct bidirectional no-recovery executions",
            "threshold": args.minimum_complete_campaigns,
            "reason": None,
        }, {
            "id": "comparison-evidence-integrity",
            "scope": "comparison",
            "status": comparison["gateStatus"]["evidenceIntegrity"],
            "actual": {
                "invalidBaselineCases": comparison["invalidBaselineCases"],
                "invalidCandidateCases": comparison["invalidCandidateCases"],
                "invalidCandidateCampaigns": comparison["invalidCandidateCampaigns"],
                "missingCandidateCases": comparison["missingCandidateCases"],
                "extraCandidateCases": comparison["extraCandidateCases"],
            },
            "operator": "all empty",
            "threshold": True,
            "reason": None,
        }])
        mode = "comparison"
    if comparison is not None and comparison["status"] != "pass":
        issues.append("one or more comparative gates failed")
    if any(row["status"] == "fail" for row in gates):
        issues.append("one or more absolute or comparative gates failed")
    status = "pass" if not issues else "fail"
    report: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "raknet-resilience-analysis",
        "generatedAt": generated_at(),
        "mode": mode,
        "status": status,
        "thresholds": {
            "baselineRecoveryMode": args.baseline_recovery_mode if comparison is not None else None,
            "candidateRecoveryMode": args.candidate_recovery_mode if comparison is not None else None,
            "healthyP50Mbps": args.healthy_p50_min,
            "healthyFairness": args.healthy_fairness,
            "healthySendDeliver": args.healthy_send_deliver,
            "poorAffectedP50Mbps": args.poor_affected_p50,
            "poorAffectedSendDeliver": args.poor_send_deliver,
            "poorAffectedDisconnects": args.poor_disconnects,
            "maxPeerQueueBytes": args.max_peer_queue,
            "maxAffectedQueueBytesPerClient": args.max_affected_queue_per_client,
            "maxHealthyQueueBytesPerClient": args.max_healthy_queue_per_client,
            "recoveryAckProgressMillis": args.recovery_ack,
            "irrecoverablePeerReclamationMillis": args.peer_reclamation,
            "externalApplyEarlyMillis": args.apply_early,
            "externalApplyLateMillis": args.apply_late,
            "minimumPressureReductionPercent": args.minimum_pressure_reduction_percent,
            "minimumCompleteSixProfileCampaignsPerSide": args.minimum_complete_campaigns,
        },
        "pressureComponentDefinitions": {
            **{
                field: "lower is better; computed independently without weighting or an opaque score"
                for field in PRESSURE_FIELDS
            },
            "unacknowledgedTransportBytesAtTPlus10AbovePreEvent":
                "affected bytes still in transport flight at T+10 above the pre-event gauge; "
                "this is unacknowledged wire state, not inferred application delivery",
        },
        "cases": cases,
        "campaigns": campaigns,
        "gates": gates,
        "issues": list(dict.fromkeys(issues)),
    }
    if comparison is not None:
        report["comparison"] = comparison
    json_path = args.out / "resilience-analysis.json"
    markdown_path = args.out / "resilience-analysis.md"
    json_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    markdown_path.write_text(markdown(report), encoding="utf-8")
    print(f"Resilience analysis: {json_path}")
    print(f"Resilience report: {markdown_path}")
    print(f"Status: {status}")
    return 0 if status == "pass" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
