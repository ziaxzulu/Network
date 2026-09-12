#!/usr/bin/env python3
"""Reproducible static benchmark figures from verified raw campaign results."""
import argparse
import hashlib
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

from summarize import summarize

COLORS = {"raknet": "#2563EB", "nethernet": "#D98A20"}
MARKERS = {"raknet": "o", "nethernet": "D"}
NAMES = {"raknet": "Production-pinned RakNet", "nethernet": "Proposed NXS NetherNet"}
PAYLOADS = [64, 256, 512, 1200, 1340, 1400, 262144]
RATES = [25, 100, 250, 500]


def load_clean(row):
    if not row or row["measuredRepetitions"] != 3:
        return False
    flags = set(row["qualification"].split(";")) - {"", "p99-spread"}
    return not flags and row["deliveredMbps"] >= .95 * row["targetMbps"]


def style(ax):
    ax.spines[["top", "right"]].set_visible(False)
    ax.spines[["left", "bottom"]].set_color("#9CA3AF")
    ax.grid(axis="y", color="#E5E7EB", linewidth=.6)
    ax.set_axisbelow(True)
    ax.tick_params(colors="#374151", labelsize=9)


def export(fig, folder, name):
    for extension in ["png", "svg"]:
        fig.savefig(folder / f"{name}.{extension}", dpi=170, facecolor="white")
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--allow-incomplete", action="store_true")
    args = parser.parse_args()
    rows, quality, completed, planned = summarize(args.input, args.allow_incomplete)
    if not any(row["case"].startswith("curve-") for row in rows):
        raise ValueError("These figures require the full campaign's payload/rate curves")
    ledger_prefix = b"".join((args.input / "completed.jsonl").read_bytes().splitlines(keepends=True)[:completed])
    lookup = {(r["case"], r["transport"]): r for r in rows}
    folder = args.input / "figures"
    folder.mkdir(exist_ok=True)
    prefix = "PRELIMINARY — " if completed < planned else ""
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10,
                         "text.color": "#1F2937", "axes.labelcolor": "#374151"})
    legend = [Line2D([], [], color=COLORS[t], marker=MARKERS[t], linestyle="none", label=NAMES[t])
              for t in COLORS]

    fig, axes = plt.subplots(2, 4, figsize=(13, 7.5), sharex=True, sharey=True)
    fig.subplots_adjust(left=.07, right=.98, bottom=.23, top=.78, hspace=.43, wspace=.2)
    fig.suptitle(prefix + "Delivered throughput by payload and requested rate", x=.07, y=.97, ha="left", fontsize=17)
    fig.text(.07, .915, f"One client · 5 s warmup + 10 s measurement · 3 planned repetitions per transport/rate · {completed}/{planned} campaign rows recorded", fontsize=10)
    fig.legend(handles=legend, loc="upper left", bbox_to_anchor=(.065, .9), ncol=2, frameon=False)
    for ax, payload in zip(axes.flat, PAYLOADS):
        style(ax)
        ax.set_title("256 KiB payload" if payload == 262144 else f"{payload:,} byte payload", loc="left", fontsize=11)
        ax.plot([0, 520], [0, 520], color="#9CA3AF", linestyle=":", linewidth=.8)
        for t, offset in [("raknet", -5), ("nethernet", 5)]:
            for rate in RATES:
                row = lookup.get((f"curve-p{payload}-{rate}mbps", t))
                if row is None:
                    continue
                value = row["deliveredMbps"]
                if value is not None:
                    ax.errorbar(rate + offset, value,
                        yerr=[[value - row["deliveredMbpsMin"]], [row["deliveredMbpsMax"] - value]],
                        fmt=MARKERS[t], color=COLORS[t], markersize=5, capsize=3, linewidth=1,
                        markerfacecolor=COLORS[t] if load_clean(row) else "white")
                if row["measuredRepetitions"] < 3:
                    ax.text(rate + offset, -.19 if t == "raknet" else -.29,
                        f'{row["measuredRepetitions"]}/3', transform=ax.get_xaxis_transform(),
                        color=COLORS[t], fontsize=8, ha="center")
        ax.set_xlim(0, 525); ax.set_ylim(-5, 525)
        ax.set_xticks(RATES); ax.set_yticks([0, 100, 250, 500])
    axes.flat[-1].axis("off")
    axes.flat[-1].text(0, 1, "Reading the figures\n\nDots: median delivered Mbps\nWhiskers: measured min–max\nDotted line: requested rate\n\nFilled: load completed cleanly*\nOpen: errors or unmet load*\nLabels: measured runs / 3\n(shown when fewer than 3)\n\nAbsent measurements have no dot.",
                        transform=axes.flat[-1].transAxes, va="top", fontsize=10, linespacing=1.35)
    fig.supxlabel("Requested application rate (Mbps); symbols offset slightly for readability", y=.105, fontsize=10)
    fig.supylabel("Delivered application throughput (Mbps)", x=.018, fontsize=10)
    fig.text(.07, .05, "*Filled dots require three complete runs, ≥95% target goodput and no execution/load flags. This does not certify stable p99 latency.", fontsize=9)
    fig.text(.07, .02, "Single host; server + clients share four CPUs. Loopback development evidence, not production capacity. Source: retained plan and raw result.json files.", fontsize=9)
    export(fig, folder, "throughput-curves")

    # A common low target compares costs before most overload failures. Include
    # the error-prone tiny-payload case, with its qualification visible.
    fig, (cpu_ax, latency_ax) = plt.subplots(1, 2, figsize=(12, 6.6), sharey=True)
    fig.subplots_adjust(left=.1, right=.97, bottom=.20, top=.76, wspace=.24)
    fig.suptitle(prefix + "CPU cost and probe latency at 25 Mbps", x=.1, y=.97, ha="left", fontsize=17)
    fig.text(.1, .915, "One client · median and min–max across available runs · CPU includes server and clients · RTT includes queueing and echo", fontsize=10)
    fig.legend(handles=legend, loc="upper left", bbox_to_anchor=(.095, .90), ncol=2, frameon=False)
    entries = [json.loads(line) for line in ledger_prefix.splitlines() if line]
    values = {}
    for e in entries:
        path = args.input / Path(e["out"]).name / "result.json"
        if path.exists():
            result = json.loads(path.read_text())
            if result.get("deliveredMbps") is not None:
                values.setdefault((e["case"], e["transport"]), []).append(result)
    for ax, field, median_field in [(cpu_ax, "processCpuCores", "processCpuCores"),
                                  (latency_ax, "probeP99Ms", "probeP99Ms")]:
        style(ax)
        for index, payload in enumerate(PAYLOADS):
            for t, offset in [("raknet", -.12), ("nethernet", .12)]:
                key = (f"curve-p{payload}-25mbps", t)
                row = lookup.get(key)
                points = [r[field] for r in values.get(key, []) if r.get(field) is not None]
                if row is None or not points:
                    continue
                mid = row[median_field]
                ax.errorbar(mid, index + offset, xerr=[[mid - min(points)], [max(points) - mid]],
                    fmt=MARKERS[t], color=COLORS[t], markersize=6, capsize=3, linewidth=1,
                    markerfacecolor=COLORS[t] if load_clean(row) else "white")
        ax.set_yticks(range(len(PAYLOADS)), ["256 KiB" if p == 262144 else f"{p:,} B" for p in PAYLOADS])
        ax.set_ylim(len(PAYLOADS) - .5, -.5)
    cpu_ax.set_xlim(left=0); cpu_ax.set_xlabel("Combined process CPU (cores)")
    latency_ax.set_xscale("log"); latency_ax.set_xlabel("Probe RTT p99 (ms, logarithmic scale)")
    latency_ax.grid(axis="x", color="#E5E7EB", linewidth=.6)
    fig.text(.1, .1, "Filled/open symbols use the throughput figure's load qualifications. Whiskers show repetition variation, not confidence intervals.", fontsize=9)
    fig.text(.1, .065, "p99 is conditional on replies received during measurement. Inspect probe return rates and latency-spread flags before ranking transports.", fontsize=9)
    fig.text(.1, .03, "Single-host development experiment, 2026-09-07. Source: hash-verified campaign results; incomplete rows stay absent.", fontsize=9)
    export(fig, folder, "cost-and-latency-25mbps")

    manifest = {"renderer": "Matplotlib", "version": matplotlib.__version__,
        "completedRows": completed, "plannedRows": planned, "preliminary": completed < planned,
        "palettePolicy": "hard two-root cap", "colors": COLORS,
        "scriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "sourceLedgerPrefixSha256": hashlib.sha256(ledger_prefix).hexdigest(),
        "charts": [{"name": "throughput-curves", "family": "faceted dot and interval",
                    "question": "How much requested load was delivered across sizes and repetitions?",
                    "takeaway": "Expose throughput limits and failed or incomplete runs without a winner preselection."},
                   {"name": "cost-and-latency-25mbps", "family": "paired dot and interval",
                    "question": "At the same low requested rate, how do combined CPU and probe RTT vary by payload?",
                    "takeaway": "Show CPU cost and observed tail variability separately from throughput."}]}
    (folder / "chart-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (folder / "chart-data.json").write_text(json.dumps({"aggregates": rows,
        "cpuAndLatencyPoints": [{"case": case, "transport": transport,
            **{field: record.get(field) for field in ["offeredMbps", "deliveredMbps", "processCpuCores",
                "probeP99Ms", "probeSamples", "probesSent", "writeFailures", "status"]}}
            for (case, transport), records in values.items() if case.endswith("-25mbps")
            for record in records],
        "sourceResultHashes": [{"case": e["case"], "transport": e["transport"], "iteration": e["iteration"],
            "sha256": e.get("resultSha256")} for e in entries]}, indent=2) + "\n")
    print(f"Exported two figures in PNG and SVG for {completed}/{planned} rows: {folder}")


if __name__ == "__main__":
    main()
