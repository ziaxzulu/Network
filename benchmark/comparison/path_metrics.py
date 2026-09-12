"""Postprocess retained kernel counters and sampled application recovery.

No packet-path changes. Qdisc counts are loopback traffic in both directions,
not physical NIC bytes. Recovery intervals include timestamp/sample uncertainty.
"""
import json
from pathlib import Path


def snapshot(path):
    rows = json.loads(path.read_text())
    roots = [row for row in rows if row.get("root") is True]
    if len(roots) != 1:
        raise ValueError("Expected one root qdisc: " + str(path))
    return int(path.stem.rsplit("-", 1)[1]), roots[0]


def path_metrics(folder, result):
    folder = Path(folder)
    output = {}
    before = list(folder.glob("qdisc-measurement-before-*.json"))
    after = list(folder.glob("qdisc-measurement-after-*.json"))
    if len(before) == 1 and len(after) == 1:
        start, a = snapshot(before[0])
        end, b = snapshot(after[0])
        if end <= start:
            raise ValueError("Non-increasing qdisc measurement clock")
        for field in ["bytes", "packets", "drops"]:
            delta = b[field] - a[field]
            if delta < 0:
                raise ValueError("Qdisc counter reset during row: " + str(folder))
            output["qdisc" + field.capitalize()] = delta
        output["qdiscIntervalMillis"] = end - start
        output["qdiscMbps"] = output["qdiscBytes"] * 8 / (end - start) / 1000
        delivered = sum(p["deliveredBytesInWindow"] for p in result.get("peers", []))
        output["qdiscBytesPerDeliveredByte"] = output["qdiscBytes"] / delivered if delivered else None

    if result.get("options", {}).get("profile") != "blackhole":
        return output
    output.update(recoveryObserved=False, recoveryFirstDeliveryLowerMs=None,
                  recoveryFirstDeliveryUpperMs=None, recoverySustainedUpperMs=None,
                  recoverySampleGapMs=None, blackoutHealthyMbps=None,
                  preBlackoutAffectedMbps=None, recoveryReason="missing-evidence")
    timing_file, timeline_file = folder / "measurement-start.json", folder / "timeline.json"
    starts = sorted(folder.glob("qdisc-after-blackhole-*.json"))
    restores_before = sorted(folder.glob("qdisc-before-clean-*.json"))
    restores_after = sorted(folder.glob("qdisc-after-clean-*.json"))
    if not all([timing_file.exists(), timeline_file.exists(), starts, restores_before, restores_after]):
        return output
    epoch = json.loads(timing_file.read_text())["epochMillis"]
    blackout = snapshot(starts[0])[0] - epoch
    restore_lower = snapshot(restores_before[0])[0] - epoch
    restore_upper = snapshot(restores_after[0])[0] - epoch
    if not 0 <= blackout <= restore_lower <= restore_upper:
        raise ValueError("Invalid blackout/restore clock ordering")
    output["restoreCommandWindowMillis"] = restore_upper - restore_lower
    samples = [p for p in json.loads(timeline_file.read_text()) if p["phase"] == "measurement"]
    if any(b["elapsedMs"] <= a["elapsedMs"] for a, b in zip(samples, samples[1:])):
        raise ValueError("Non-increasing recovery sample time")
    if len(samples) < 2:
        return output
    for field in ["healthyDeliveredBytes", "affectedDeliveredBytes"]:
        if any(b[field] < a[field] for a, b in zip(samples, samples[1:])):
            raise ValueError("Application delivery counter decreased")

    def cohort_rate(points, field):
        if len(points) < 2:
            return None
        return (points[-1][field] - points[0][field]) * 8 / (points[-1]["elapsedMs"] - points[0]["elapsedMs"]) / 1000

    baseline = [p for p in samples if 200 <= p["elapsedMs"] < blackout]
    affected_rate = cohort_rate(baseline, "affectedDeliveredBytes")
    output["preBlackoutAffectedMbps"] = affected_rate
    output["blackoutHealthyMbps"] = cohort_rate(
        [p for p in samples if blackout <= p["elapsedMs"] <= restore_lower], "healthyDeliveredBytes")
    output["recoveryReason"] = "no-post-restore-delivery-observed"
    consecutive = 0
    for a, b in zip(samples, samples[1:]):
        # Require the entire interval to be after the command completed. This
        # avoids attributing pre-restore buffered application delivery to recovery.
        if a["elapsedMs"] < restore_upper:
            continue
        gap = b["elapsedMs"] - a["elapsedMs"]
        increase = b["affectedDeliveredBytes"] - a["affectedDeliveredBytes"]
        if increase > 0 and not output["recoveryObserved"]:
            output.update(recoveryObserved=True,
                          recoveryFirstDeliveryLowerMs=max(0, a["elapsedMs"] - restore_upper),
                          recoveryFirstDeliveryUpperMs=max(0, b["elapsedMs"] - restore_lower),
                          recoverySampleGapMs=gap,
                          recoveryReason="sampled-post-restore-delivery")
        rate = increase * 8 / gap / 1000
        consecutive = consecutive + 1 if gap <= 350 and affected_rate and rate >= .9 * affected_rate else 0
        if consecutive >= 3 and output["recoverySustainedUpperMs"] is None:
            output["recoverySustainedUpperMs"] = max(0, b["elapsedMs"] - restore_lower)
    return output
