#!/usr/bin/env python3
"""Independently reconcile completed raw rows and retain failure-stage evidence."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import statistics


def audit(root):
    plan = json.loads((root / "plan.json").read_text())
    ledger = [json.loads(line) for line in (root / "completed.jsonl").read_text().splitlines()]
    assert (root / "complete.json").exists(), "Completion marker missing"
    assert len(plan) == len(ledger)
    key = lambda row: (row["case"], row["transport"], row["iteration"])
    assert [key(row) for row in ledger] == [key(row) for row in plan]
    assert len(set(map(key, ledger))) == len(ledger)
    aggregates = {(row["case"], row["transport"]): row
                  for row in json.loads((root / "aggregate.json").read_text())}
    measured = defaultdict(list)
    statuses, stages, ordering, errors = Counter(), Counter(), Counter(), Counter()
    failed_rows = []
    snapshot_skew = []
    for entry in ledger:
        name = Path(entry["out"]).name
        path = root / name / "result.json"
        transport = entry["transport"]
        if not path.exists():
            assert entry["exitCode"] != 0 and not entry.get("resultSha256")
            statuses[transport + ":no-final-result"] += 1
            failed_rows.append({"row": name, "exitCode": entry["exitCode"], "stage": "no-final-result"})
            continue
        assert hashlib.sha256(path.read_bytes()).hexdigest() == entry["resultSha256"]
        raw = json.loads(path.read_text())
        statuses[transport + ":" + raw["status"]] += 1
        if "deliveredMbps" not in raw:
            log = (root / (name + ".log")).read_text(errors="replace")
            stage = "other-failure"
            if "ComparisonMain.run(ComparisonMain.java:102)" in log:
                stage = "post-warmup-submission-barrier"
            elif "Harness send queue exceeded" in raw.get("failure", ""):
                stage = "generator-queue-guard"
            stages[transport + ":" + stage] += 1
            failed_rows.append({"row": name, "exitCode": entry["exitCode"], "stage": stage,
                                "failure": raw.get("failure")})
            continue
        seconds = raw["options"]["durationMillis"] / 1000
        peers = raw["peers"]
        expected = sum(p["deliveredBytesInWindow"] for p in peers) * 8 / seconds / 1e6
        assert abs(expected - raw["deliveredMbps"]) < 1e-6
        offered = sum(p["offeredBytes"] for p in peers)
        assert abs(offered * 8 / seconds / 1e6 - raw["offeredMbps"]) < 1e-6
        drained = sum(p["deliveredBytesIncludingDrain"] for p in peers)
        byte_skew = 0
        if offered:
            byte_skew = round(offered * raw["deliveryRatioIncludingDrain"] - drained)
            assert byte_skew >= 0
        else:
            assert raw["deliveryRatioIncludingDrain"] is None
        assert sum(p["probesSent"] for p in peers) == raw["probesSent"]
        assert sum(p["probeCount"] for p in peers) == raw["probeSamples"]
        probe_skew = raw["probesReceivedIncludingDrain"] - sum(p["probesReceivedIncludingDrain"] for p in peers)
        assert probe_skew >= 0
        if byte_skew or probe_skew:
            snapshot_skew.append({"row": name, "laterReadAdditionalBytes": byte_skew,
                                  "laterReadAdditionalProbes": probe_skew,
                                  "byteDeltaPctOfOffered": byte_skew / offered * 100 if offered else None})
        assert abs(raw["processCpuSeconds"] / raw["cpuMeasurementSeconds"] - raw["processCpuCores"]) < 1e-9
        ordering[transport] += sum(p["orderingErrors"] for p in peers)
        errors[transport] += len(raw["errors"])
        measured[key(entry)[:2]].append(raw)
    for group, rows in measured.items():
        aggregate = aggregates[group]
        assert aggregate["measuredRepetitions"] == len(rows)
        for field in ["offeredMbps", "deliveredMbps", "processCpuCores"]:
            assert abs(statistics.median(r[field] for r in rows) - aggregate[field]) < 1e-9
        ratios = [sum(p["deliveredBytesIncludingDrain"] for p in r["peers"]) /
                  sum(p["offeredBytes"] for p in r["peers"]) for r in rows if r["offeredMbps"] > 0]
        if ratios:
            assert abs(statistics.median(ratios) - aggregate["deliveryRatioIncludingDrain"]) < 1e-9
    return {"campaign": root.name, "executedRows": len(ledger),
            "ledgerSha256": hashlib.sha256((root / "completed.jsonl").read_bytes()).hexdigest(),
            "statuses": dict(statuses), "failureStages": dict(stages), "failedRows": failed_rows,
            "orderingErrorsInRetainedMeasurements": dict(ordering),
            "recordedErrorMessagesInRetainedMeasurements": dict(errors),
            "postDrainSnapshotSkew": snapshot_skew,
            "postDrainSnapshotSkewRows": len(snapshot_skew),
            "maxLaterReadAdditionalBytes": max((r["laterReadAdditionalBytes"] for r in snapshot_skew), default=0),
            "maxLaterReadAdditionalProbes": max((r["laterReadAdditionalProbes"] for r in snapshot_skew), default=0),
            "checks": ["ordered complete plan", "unique repetitions", "raw result hashes",
                       "per-peer offered and delivered bytes", "drain delivery ratio",
                       "active-window per-peer probe counts", "post-drain snapshot skew quantified",
                       "CPU time denominator", "raw-to-aggregate medians"],
            "limitations": "Counters absent from failed rows remain unknown; percentile histograms cannot be reconstructed from individual probe records because those records were not retained."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    args = parser.parse_args()
    result = audit(args.input)
    (args.input / "validation.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k not in ["failedRows", "checks", "limitations", "postDrainSnapshotSkew"]}, indent=2))
