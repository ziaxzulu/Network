#!/usr/bin/env python3
"""Verify and summarize every planned repetition, preserving failures and limits."""
import argparse
from collections import defaultdict
import csv
import hashlib
import json
from pathlib import Path
import statistics
from path_metrics import path_metrics


def planned_options(command):
    options = dict(transport="raknet", scenario="bulk", clients=1, affected=0,
                   payload=512, clientMbps=5., intervalMillis=20, warmupMillis=3000,
                   durationMillis=10000, drainMillis=3000, port=19132,
                   profile="clean", disappear="none")
    names = {"client-mbps": "clientMbps", "interval-ms": "intervalMillis",
             "warmup-ms": "warmupMillis", "duration-ms": "durationMillis", "drain-ms": "drainMillis"}
    if len(command) % 2 != 1:
        raise ValueError("Malformed planned command")
    for key, value in zip(command[1::2], command[2::2]):
        name = names.get(key[2:], key[2:])
        if name in ["out", "identity"]:
            continue
        if name not in options:
            raise ValueError("Unrecognized planned option: " + key)
        options[name] = type(options[name])(value)
    return options


def validate_plan(plan):
    pairs = defaultdict(dict)
    for row in plan:
        key = (row["case"], row["iteration"])
        transport = row["transport"]
        options = planned_options(row["command"])
        if options.pop("transport") != transport or transport in pairs[key]:
            raise ValueError("Duplicate or mismatched planned transport")
        pairs[key][transport] = options
    for key, pair in pairs.items():
        if set(pair) != {"raknet", "nethernet"} or pair["raknet"] != pair["nethernet"]:
            raise ValueError("Transport workloads are not matched: " + str(key))


def median(values):
    values = [x for x in values if x is not None]
    return statistics.median(values) if values else None


def spread(values):
    values = [x for x in values if x is not None]
    if not values:
        return None
    mid = statistics.median(values)
    return (max(values) - min(values)) / mid * 100 if mid else (0 if max(values) == min(values) else 100)


def target_mbps(options):
    if options["scenario"] == "resource":
        return options["clients"] * options["payload"] * 8 / (options["intervalMillis"] / 1000) / 1e6
    return options["clients"] * options["clientMbps"]


def expected_offered_mbps(options):
    target = target_mbps(options)
    if options.get("disappear") == "close":
        # The driver intentionally closes the affected cohort at 2 s and stops
        # allocating sends after server inactivity is observed.
        active_fraction = min(1, 2000 / options["durationMillis"])
        return target * (options["clients"] - options["affected"] * (1 - active_fraction)) / options["clients"]
    return target


def summarize(root, allow_incomplete=False):
    plan = json.loads((root / "plan.json").read_text())
    validate_plan(plan)
    planned_by_key = {(p["case"], p["transport"], p["iteration"]): p for p in plan}
    completed = [json.loads(line) for line in (root / "completed.jsonl").read_text().splitlines() if line]
    if not allow_incomplete and (not (root / "complete.json").exists() or len(completed) != len(plan)):
        raise ValueError(f"Campaign incomplete: {len(completed)}/{len(plan)} rows")
    if (root / "complete.json").exists() and json.loads((root / "complete.json").read_text())["completedRows"] != len(plan):
        raise ValueError("Completion marker does not match the plan")
    expected = {(p["case"], p["transport"], p["iteration"]) for p in plan}
    seen = set()
    groups = defaultdict(list)
    quality = []
    for entry in completed:
        key = (entry["case"], entry["transport"], entry["iteration"])
        if key not in expected or key in seen:
            raise ValueError("Unexpected or duplicate completed row " + str(key))
        seen.add(key)
        # Resolve by the retained row directory name so packages remain relocatable.
        path = root / Path(entry["out"]).name / "result.json"
        result = None
        if path.exists():
            if hashlib.sha256(path.read_bytes()).hexdigest() != entry["resultSha256"]:
                raise ValueError("Result changed after capture: " + str(path))
            result = json.loads(path.read_text())
            options = result["options"]
            if any(options.get(name) != value for name, value in planned_options(planned_by_key[key]["command"]).items()):
                raise ValueError("Recorded workload differs from the planned workload: " + str(path))
            if options["transport"] != entry["transport"]:
                raise ValueError("Transport mismatch: " + str(path))
            if result.get("nativeReleaseLoaded") is not True and options["transport"] == "nethernet" and result.get("status") != "failed":
                raise ValueError("Release native library was not verified: " + str(path))
            source = result.get("raknetCodeSource", "")
            if result.get("status") != "failed" and "netty-transport-raknet-1.1.0.CR1-20260820.174333-6.jar" not in source:
                raise ValueError("Baseline code source mismatch: " + str(path))
            if result.get("processCpuCores", 0) > 4.2:
                raise ValueError("CPU accounting exceeds the four-CPU allocation: " + str(path))
            if result.get("deliveredMbps") is not None and result.get("cpuAffinity", "").split()[-1:] != ["0-3"]:
                raise ValueError("Recorded CPU affinity differs from the campaign: " + str(path))
            returned = result.get("probesReceivedIncludingDrain", 0)
            if returned > result.get("probesSent", 0):
                raise ValueError("Probe returns exceed sends: " + str(path))
            peers = result.get("peers", [])
            if peers:
                if len(peers) != options["clients"] or len({p["id"] for p in peers}) != len(peers):
                    raise ValueError("Recorded peer set is incomplete or duplicated: " + str(path))
                independently_delivered = sum(p["deliveredBytesInWindow"] for p in peers) * 8 / (options["durationMillis"] / 1000) / 1e6
                if abs(independently_delivered - result["deliveredMbps"]) > 1e-6:
                    raise ValueError("Per-peer goodput does not reconcile: " + str(path))
                if any(p["deliveredBytesIncludingDrain"] > p["offeredBytes"] for p in peers):
                    raise ValueError("Delivered bytes exceed offered bytes: " + str(path))
                if any(not 0 <= p["deliveredBytesInWindow"] <= p["deliveredBytesIncludingDrain"] for p in peers):
                    raise ValueError("Measurement-window bytes exceed delivered bytes: " + str(path))
                # Receive callbacks may still progress while the post-drain
                # result is assembled. Use the retained per-peer counters as
                # the consistent denominator set, and expose later-read skew.
                offered = sum(p["offeredBytes"] for p in peers)
                drained = sum(p["deliveredBytesIncludingDrain"] for p in peers)
                settled_probes = sum(p["probesReceivedIncludingDrain"] for p in peers)
                result["drainSnapshotBytesDelta"] = round(result["deliveryRatioIncludingDrain"] * offered - drained) if offered else 0
                result["drainSnapshotProbeDelta"] = result["probesReceivedIncludingDrain"] - settled_probes
                if result["drainSnapshotBytesDelta"] < 0 or result["drainSnapshotProbeDelta"] < 0:
                    raise ValueError("Unexpected negative post-drain snapshot skew: " + str(path))
                result["deliveryRatioIncludingDrain"] = drained / offered if offered else None
                result["probesReceivedIncludingDrain"] = settled_probes
                for cohort, affected in [("healthy", False), ("affected", True)]:
                    cohort_peers = [p for p in peers if p["affected"] is affected]
                    sent = sum(p["probesSent"] for p in cohort_peers)
                    replies = sum(p["probeCount"] for p in cohort_peers)
                    result[cohort + "ProbeReturnPct"] = replies / sent * 100 if sent else None
                result["unexpectedDisconnectedClients"] = sum(
                    not p["open"] for p in peers
                    if not (p["affected"] and options.get("disappear") == "close"))
            result.update(path_metrics(path.parent, result))
        groups[key[:2]].append((entry, result))

    aggregates = []
    for (case, transport), entries in groups.items():
        measurements = [r for e, r in entries if r is not None and r.get("deliveredMbps") is not None]
        options = next((r["options"] for _, r in entries if r is not None),
                       planned_options(planned_by_key[(case, transport, entries[0][0]["iteration"])]["command"]))
        def values(field):
            return [r.get(field) for r in measurements]
        reasons = []
        if len(entries) < 3 or len(measurements) < 3:
            reasons.append("insufficient-repetitions")
        if any(e["exitCode"] or r is None or r.get("status") != "measured" or r.get("writeFailures", 0) or r.get("errors") for e, r in entries):
            reasons.append("failed-or-error-rows")
        throughput_spread, p99_spread = spread(values("deliveredMbps")), spread(values("probeP99Ms"))
        if throughput_spread is not None and throughput_spread > 10:
            reasons.append("throughput-spread")
        if p99_spread is not None and p99_spread > 10:
            reasons.append("p99-spread")
        if any(r.get("probeSamples", 0) < 10 or r.get("probeP99Ms") is None for r in measurements):
            reasons.append("insufficient-probe-samples")
        active_probe_returns = [r["probeSamples"] / r["probesSent"] if r["probesSent"] else 0 for r in measurements]
        if any(x < .5 for x in active_probe_returns):
            reasons.append("insufficient-probe-return")
        if any(r.get("affectedProbeReturnPct") is not None and r["affectedProbeReturnPct"] < 50 for r in measurements):
            reasons.append("insufficient-affected-probe-return")
        if any(r["generatorDeadlineMissedBytes"] * 8 / (r["options"]["durationMillis"] / 1000) / 1e6 > expected_offered_mbps(r["options"]) * .01 for r in measurements):
            reasons.append("generator-saturated")
        if any(r["offeredMbps"] < expected_offered_mbps(r["options"]) * .95 for r in measurements):
            reasons.append("offered-load-shortfall")
        if any(r.get("unexpectedDisconnectedClients", r.get("disconnectedClients", 0)) for r in measurements):
            reasons.append("disconnected-clients")
        settled_returns = [r["probesReceivedIncludingDrain"] / r["probesSent"] if r["probesSent"] else 0 for r in measurements]
        row = dict(case=case, transport=transport, repetitions=len(entries), measuredRepetitions=len(measurements),
                   targetMbps=target_mbps(options) if options else None,
                   expectedOfferedMbps=expected_offered_mbps(options) if options else None,
                   offeredMbps=median(values("offeredMbps")), deliveredMbps=median(values("deliveredMbps")),
                   deliveredMbpsMin=min(values("deliveredMbps"), default=None), deliveredMbpsMax=max(values("deliveredMbps"), default=None),
                   throughputSpreadPct=throughput_spread, probeP99Ms=median(values("probeP99Ms")), p99SpreadPct=p99_spread,
                   probeP99IncludingDrainMs=median(values("probeP99IncludingDrainMs")),
                   minimumActiveProbeReturnPct=min(active_probe_returns) * 100 if active_probe_returns else None,
                   minimumSettledProbeReturnPct=min(settled_returns) * 100 if settled_returns else None,
                   processCpuCores=median(values("processCpuCores")), maxRssMiB=median([v / 1024**2 for v in values("maxRssBytes") if v is not None]),
                   healthyMbps=median(values("healthyMbps")), affectedMbps=median(values("affectedMbps")),
                   healthyFairness=median(values("healthyFairness")),
                   healthyProbeReturnPctMin=min([x for x in values("healthyProbeReturnPct") if x is not None], default=None),
                   affectedProbeReturnPctMin=min([x for x in values("affectedProbeReturnPct") if x is not None], default=None),
                   deliveryRatioIncludingDrain=median(values("deliveryRatioIncludingDrain")),
                   drainSnapshotBytesDeltaMax=max(values("drainSnapshotBytesDelta"), default=None),
                   drainSnapshotProbeDeltaMax=max(values("drainSnapshotProbeDelta"), default=None),
                   writeFailures=sum(r["writeFailures"] for _, r in entries if r and "writeFailures" in r)
                       if any(r and "writeFailures" in r for _, r in entries) else None,
                   disconnectedClientsMax=max([r["disconnectedClients"] for _, r in entries if r and "disconnectedClients" in r], default=None),
                   unexpectedDisconnectedClientsMax=max([r["unexpectedDisconnectedClients"] for r in measurements if "unexpectedDisconnectedClients" in r], default=None),
                   qdiscMbps=median(values("qdiscMbps")),
                   qdiscPackets=median(values("qdiscPackets")),
                   qdiscDrops=median(values("qdiscDrops")),
                   qdiscBytesPerDeliveredByte=median(values("qdiscBytesPerDeliveredByte")),
                   blackoutHealthyMbps=median(values("blackoutHealthyMbps")),
                   recoveryObservedRepetitions=sum(r.get("recoveryObserved", False) for r in measurements),
                   recoveryFirstDeliveryUpperMs=median(values("recoveryFirstDeliveryUpperMs")),
                   recoverySustainedUpperMs=median(values("recoverySustainedUpperMs")),
                   generatorDeadlineMissedBytesMax=max([r["generatorDeadlineMissedBytes"] for r in measurements], default=None),
                   repeatedCleanly=not reasons, qualification=";".join(reasons))
        aggregates.append(row)
        quality.append(dict(case=case, transport=transport, reasons=reasons))
    return aggregates, quality, len(completed), len(plan)


def render(root, aggregates, completed, planned):
    def number(value, precision=2):
        return "n/a" if value is None else f"{value:.{precision}f}"
    lines = ["# RakNet / NXS NetherNet development comparison", "",
        f"Captured rows: **{completed}/{planned}**. All table values are medians across the available repetitions unless stated otherwise.", "",
        "This is a single-host, four-CPU, combined client/server JVM experiment using the unchanged production-pinned RakNet artifact and the pinned NXS candidate. It is not a separate-host capacity baseline. CPU includes clients. Consult the companion README and artifact provenance before interpreting it.", "",
        "Probe p99 is the median of each repetition's p99. Missing replies are excluded from the percentile and exposed by the minimum return column. The CSV also includes p99 and return rates after draining. Write failures cover the whole row, including warmup and cleanup; they are not a measurement-window error rate. Qualifying rows require three measurements, <=10% throughput and p99 spread, at least ten active probe responses and >=50% active probe return in every repetition, with no error/disconnect or substantial generator shortfall. These checks do not replace the original lab promotion gates.", "",
        "Write-failure totals count retained results only; an attempt without a final result has unknown counters. All missing counter observations remain unavailable.", "",
        "Post-drain delivery and probe-return totals are recomputed from retained peer records. Callbacks can still arrive while the final result is assembled, causing slight differences from its later-read global counters; the CSV exposes maximum byte and probe-count deltas. Active-window goodput and probe counts reconcile independently. Drain p99 remains a later snapshot and is advisory.", "",
        "| Workload | Transport | Measured / executed | Offered Mbps | Delivered Mbps (min–max) | p99 RTT ms | Min probe return | CPU cores | RSS MiB | Recorded write failures | Qualification |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |"]
    for row in aggregates:
        lines.append(f"| {row['case']} | {row['transport']} | {row['measuredRepetitions']}/{row['repetitions']} | {number(row['offeredMbps'])} | {number(row['deliveredMbps'])} ({number(row['deliveredMbpsMin'])}–{number(row['deliveredMbpsMax'])}) | {number(row['probeP99Ms'])} | {number(row['minimumActiveProbeReturnPct'], 1)}% | {number(row['processCpuCores'])} | {number(row['maxRssMiB'], 0)} | {number(row['writeFailures'], 0)} | {row['qualification'] or 'repeatable in this setup'} |")
    lines += ["", "## Healthy / affected clients", "",
        "Probe returns below are the minimum active-window return across repetitions for each cohort; aggregate latency can otherwise hide missing affected-client replies. Disconnect totals include intentionally closed peers; the CSV separately records unexpected disconnects. The close case's expected offered rate accounts for the affected clients closing at 2 seconds, rather than treating the intended reduction as a generator shortfall.", "",
        "| Workload | Transport | Healthy Mbps | Affected Mbps | Healthy Jain fairness | Healthy probe return % | Affected probe return % | Delivered/offered incl. drain | Max disconnected |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for row in aggregates:
        if "fairness" in row["case"] or "disappear" in row["case"]:
            lines.append(f"| {row['case']} | {row['transport']} | {number(row['healthyMbps'])} | {number(row['affectedMbps'])} | {number(row['healthyFairness'], 6)} | {number(row['healthyProbeReturnPctMin'], 1)} | {number(row['affectedProbeReturnPctMin'], 1)} | {number(row['deliveryRatioIncludingDrain'], 4)} | {number(row['disconnectedClientsMax'], 0)} |")
    lines += ["", "## Kernel path and blackout observations", "",
        "Qdisc counters include both UDP directions, protocol overhead, probes and retransmissions on loopback; they are not NIC wire-byte counters. Snapshot timing slightly brackets the application measurement window. Ratios use delivered application bytes, so losses and incomplete delivery increase them.", "",
        "| Workload | Transport | Qdisc Mbps | Qdisc bytes / delivered byte | Qdisc drops |",
        "| --- | --- | ---: | ---: | ---: |"]
    for row in aggregates:
        lines.append(f"| {row['case']} | {row['transport']} | {number(row['qdiscMbps'])} | {number(row['qdiscBytesPerDeliveredByte'], 3)} | {number(row['qdiscDrops'], 0)} |")
    lines += ["", "Blackout recovery is sampled application delivery, not ACK progress or proof that protocol queues cleared. Only intervals wholly after restoration are used. The observed-delivery column reports the median elapsed upper edge of the first such interval with affected delivery. Sustained recovery requires three consecutive intervals (each <=350 ms) at >=90% of that row's pre-blackout affected goodput. Missing recovery remains n/a; observation counts expose censoring.", "",
        "| Workload | Transport | Recovery observed / measured | Healthy Mbps during blackout | Post-restore delivery observation ms | Sustained-rate observation ms |",
        "| --- | --- | ---: | ---: | ---: | ---: |"]
    for row in aggregates:
        if "blackhole" in row["case"]:
            lines.append(f"| {row['case']} | {row['transport']} | {row['recoveryObservedRepetitions']}/{row['measuredRepetitions']} | {number(row['blackoutHealthyMbps'])} | {number(row['recoveryFirstDeliveryUpperMs'])} | {number(row['recoverySustainedUpperMs'])} |")
    lines += ["", "Raw per-run metrics, peer records, qdisc snapshots, and recovery timelines remain alongside this report. `completed.jsonl` includes every executed command, exit status, result hash, and observed host load; `plan.json` defines the complete matrix. Failed and unstable rows remain in the tables. No NIC line-rate or stock-client gameplay claim follows from these results.", ""]
    (root / "REPORT.md").write_text("\n".join(lines))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--allow-incomplete", action="store_true")
    args = parser.parse_args()
    aggregates, quality, completed, planned = summarize(args.input, args.allow_incomplete)
    (args.input / "aggregate.json").write_text(json.dumps(aggregates, indent=2) + "\n")
    (args.input / "quality.json").write_text(json.dumps(quality, indent=2) + "\n")
    if aggregates:
        with (args.input / "aggregate.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(aggregates[0]))
            writer.writeheader()
            writer.writerows(aggregates)
    render(args.input, aggregates, completed, planned)
    print(f"Verified {completed}/{planned} rows; wrote {len(aggregates)} aggregates")
