#!/usr/bin/env python3
"""Sequential, alternating-order repetitions; failed rows stay in the manifest."""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parent


def validate_completed(out, plan):
    ledger = out / "completed.jsonl"
    completed = [json.loads(line) for line in ledger.read_text().splitlines() if line] if ledger.exists() else []
    if len(completed) > len(plan):
        raise ValueError("Completion ledger is longer than the plan")
    for expected, entry in zip(plan, completed):
        if any(entry.get(key) != value for key, value in expected.items()):
            raise ValueError("Completed rows are not an unchanged prefix of the plan")
        result = out / Path(entry["out"]).name / "result.json"
        if entry.get("resultSha256"):
            if not result.exists() or hashlib.sha256(result.read_bytes()).hexdigest() != entry["resultSha256"]:
                raise ValueError("Completed result changed: " + str(result))
        elif result.exists():
            raise ValueError("Unrecorded result appeared for a completed row: " + str(result))
    return completed


def verify_runtime(provenance):
    for name, digest in provenance["runtimeJars"].items():
        actual = hashlib.sha256((ROOT / "build/install/transport-comparison/lib" / name).read_bytes()).hexdigest()
        if actual != digest:
            raise RuntimeError("Runtime changed during campaign: " + name)
    native = ROOT / ".inputs/.native-deps" / ("libdatachannel-java-" + provenance["nativeBindingRevision"]) / "build/benchmark-release/libdatachannel-java.so"
    if hashlib.sha256(native.read_bytes()).hexdigest() != provenance["nativeReleaseSha256"]:
        raise RuntimeError("Release native library changed during campaign")
    for name, digest in provenance["sourceSha256"].items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError("Harness source changed during campaign: " + name)


def preserve_unrecorded_attempt(out, row):
    folder = Path(row["out"])
    log = out / (folder.name + ".log")
    if folder.exists() or log.exists():
        archive = out / "interrupted-attempts" / (folder.name + "-" + str(time.time_ns()))
        archive.mkdir(parents=True)
        if folder.exists():
            shutil.move(str(folder), str(archive / folder.name))
        if log.exists():
            shutil.move(str(log), str(archive / log.name))
        (archive / "interruption.json").write_text(json.dumps({
            "row": row, "preservedAt": time.time(),
            "reason": "Runner ended before recording this attempt; exit status is unknown. Preserved separately and rerun.",
        }, indent=2) + "\n")


def cases(profile):
    result = []
    def add(name, **options):
        result.append((name, options))
    if profile == "network-control":
        # Preserve the 100-client stress matrix, and isolate impairment at a
        # lower aggregate load when that matrix saturates the shared host.
        add("fanout-10x5", clients=10)
        for impairment in ["near", "regional", "poor", "severe", "blackhole"]:
            add(f"fairness-10-2-{impairment}", clients=10, affected=2, profile=impairment)
        for mode in ["close", "stop-reading"]:
            add(f"disappear-10-2-{mode}", clients=10, affected=2, disappear=mode)
        return result
    if profile == "full":
        for payload in [64, 256, 512, 1200, 1340, 1400, 262144]:
            for rate in [25, 100, 250, 500]:
                add(f"curve-p{payload}-{rate}mbps", payload=payload, **{"client-mbps": rate})
    else:
        for rate in [25, 100, 250, 500]:
            add(f"curve-p1340-{rate}mbps", payload=1340, **{"client-mbps": rate})
    add("fanout-20x5", clients=20)
    add("fanout-100x5", clients=100)
    add("immediate-100x1-p256", clients=100, payload=256, **{"client-mbps": 1})
    for interval in [10, 20, 50]:
        add(f"batch-100-{interval}ms", clients=100, scenario="batch", **{"interval-ms": interval})
    for payload in [8192, 262144]:
        add(f"resource-100-{payload}", clients=100, scenario="resource", payload=payload, **{"interval-ms": 200})
    for impairment in ["near", "regional", "poor", "severe", "blackhole"]:
        add(f"fairness-100-10-{impairment}", clients=100, affected=10, profile=impairment)
    for mode in ["close", "stop-reading"]:
        add(f"disappear-100-10-{mode}", clients=100, affected=10, disappear=mode)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=["pilot", "full", "network-control"], default="full")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--duration-ms", type=int, default=10000)
    parser.add_argument("--warmup-ms", type=int, default=3000)
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--max-rows", type=int, help="Checkpoint after this many new rows; resume the same complete plan later")
    parser.add_argument("--max-seconds", type=int, help="Checkpoint between rows after this invocation duration")
    args = parser.parse_args()
    out = args.out.resolve()
    if args.max_rows is not None and args.max_rows < 1:
        raise ValueError("max-rows must be positive")
    if args.max_seconds is not None and args.max_seconds < 1:
        raise ValueError("max-seconds must be positive")
    if args.resume and not out.is_dir():
        raise ValueError("Resume directory is missing")
    out.mkdir(parents=True, exist_ok=args.resume)
    campaign_lock = (out / ".campaign.lock").open("a")
    fcntl.flock(campaign_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    provenance = json.loads((ROOT / "artifacts/artifact-provenance.json").read_text())
    if args.resume:
        if json.loads((out / "artifact-provenance.json").read_text()) != provenance:
            raise ValueError("Artifact provenance changed before resume")
    else:
        shutil.copy2(ROOT / "artifacts/artifact-provenance.json", out / "artifact-provenance.json")
    selected = [(name, options) for name, options in cases(args.profile) if not args.case or name in args.case]
    if not selected:
        raise ValueError("No matching cases")
    plan = []
    for name, options in selected:
        for iteration in range(1, args.iterations + 1):
            order = ["raknet", "nethernet"] if iteration % 2 else ["nethernet", "raknet"]
            for transport in order:
                folder = out / f"{name}-{transport}-{iteration}"
                argv = [str(ROOT / "run-row.sh"), "--transport", transport, "--out", str(folder),
                        "--warmup-ms", str(args.warmup_ms), "--duration-ms", str(args.duration_ms)]
                for key, value in options.items():
                    argv += ["--" + key, str(value)]
                plan.append(dict(case=name, iteration=iteration, transport=transport, command=argv, out=str(folder)))
    if args.resume:
        if json.loads((out / "plan.json").read_text()) != plan:
            raise ValueError("Resume arguments changed the frozen plan")
    else:
        (out / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    completed = validate_completed(out, plan)
    verify_runtime(provenance)
    host = {
        "uname": subprocess.check_output(["uname", "-a"], text=True),
        "lscpu": subprocess.check_output(["lscpu", "-J"], text=True),
        "memory": Path("/proc/meminfo").read_text(),
        "cpuAffinity": "0-3", "jvmActiveProcessorCount": 4,
        "sharedHost": True, "namespaceTopology": "isolated user/network namespace per row; loopback, affected IP 127.0.0.2",
    }
    if not args.resume:
        (out / "host.json").write_text(json.dumps(host, indent=2) + "\n")
    invocation = out / "invocations" / (str(time.time_ns()) + ".json")
    invocation.parent.mkdir(exist_ok=True)
    invocation.write_text(json.dumps({"startedAt": time.time(), "resume": args.resume,
        "completedBefore": len(completed), "maxRows": args.max_rows, "maxSeconds": args.max_seconds, "host": host,
        "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}, indent=2) + "\n")
    remaining = plan[len(completed):]
    if args.max_rows:
        remaining = remaining[:args.max_rows]
    invocation_started = time.monotonic()
    executed = 0
    for index, row in enumerate(remaining, len(completed) + 1):
        if args.max_seconds and executed and time.monotonic() - invocation_started >= args.max_seconds:
            break
        verify_runtime(provenance)
        preserve_unrecorded_attempt(out, row)
        print(f"[{index}/{len(plan)}] {row['case']} {row['transport']} repetition {row['iteration']}", flush=True)
        started = time.time()
        row["hostLoadBefore"] = Path("/proc/loadavg").read_text().strip()
        log = out / (Path(row["out"]).name + ".log")
        with log.open("w") as stream:
            process = subprocess.run(["timeout", "--kill-after=10s", "150s", *row["command"]], cwd=ROOT,
                                     stdout=stream, stderr=subprocess.STDOUT)
        row["exitCode"] = process.returncode
        row["elapsedSeconds"] = time.time() - started
        row["hostLoadAfter"] = Path("/proc/loadavg").read_text().strip()
        result_path = Path(row["out"]) / "result.json"
        if result_path.exists():
            result = json.loads(result_path.read_text())
            row["status"] = result.get("status")
            row["deliveredMbps"] = result.get("deliveredMbps")
            row["probeP99Ms"] = result.get("probeP99Ms")
            row["generatorDeadlineMissedBytes"] = result.get("generatorDeadlineMissedBytes")
            row["resultSha256"] = hashlib.sha256(result_path.read_bytes()).hexdigest()
        else:
            row["status"] = "timeout" if process.returncode in [124, 137] else "failed-without-result"
        with (out / "completed.jsonl").open("a") as stream:
            stream.write(json.dumps(row) + "\n")
        print(json.dumps({key: row[key] for key in ["status", "exitCode", "elapsedSeconds"]}), flush=True)
        executed += 1
    count = len(completed) + executed
    if count == len(plan):
        (out / "complete.json").write_text(json.dumps({"completedRows": len(plan), "completedAt": time.time()}) + "\n")
    else:
        print(f"Checkpoint: {count}/{len(plan)} rows completed; resume with the same plan arguments", flush=True)


if __name__ == "__main__":
    main()
