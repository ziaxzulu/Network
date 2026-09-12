# Benchmark figure contracts

Delivery: standalone PNG/SVG figures accompanying the repository's Markdown
comparison. Matplotlib is the scientific plotting renderer; the environment's
system Python has Matplotlib 3.10.8. `plot_results.py` records its renderer version,
script hash, source ledger-prefix hash and the actual chart data. No HTML or
hosted dashboard is involved.

| Figure | Question and intended reading | Data and chart form |
| --- | --- | --- |
| `throughput-curves` | How much requested application load is delivered, and where do runs fail or miss the target? No winner is selected before examining the complete matrix. | Seven payload facets, four requested rates, two transports, three repetitions: 168 planned observations. Dot and min–max interval for each available group. Missing measurements remain absent and their counts are shown. |
| `cost-and-latency-25mbps` | At a common low requested rate, how do CPU cost and observed probe RTT vary with payload? Interpret cost separately from throughput and tail stability. | Seven payloads, two transports, three repetitions: 42 planned observations. Paired horizontal dot/interval panels for combined CPU and p99 RTT. RTT uses a labelled log scale. The problematic 64-byte case remains visible. |

Both figures use a blue/orange palette with a hard two-color-family limit, circle
versus diamond markers, open versus filled markers and neutral reference lines.
Whiskers show measured min–max, not confidence intervals. Filled markers require
three complete, sufficiently delivered runs and no execution/load/probe-coverage
flags; the p99-spread flag does not determine the throughput marker fill. Latency
spread and probe returns must still be examined before claiming a stable latency
advantage. See the full generated report for every qualification.

Figures label the fixed measurement boundaries, sample count, units, combined
client/server CPU scope and single-host limitation. An incomplete campaign requires
`--allow-incomplete` and receives a visible PRELIMINARY title. These figures cover
established transport; Warden signalling/host handoff has separate evidence.

QA: inspect the exported PNG at its natural and reader-facing size, check the SVG
and source manifest, and reconcile plotted data to the hash-verified raw results.
Re-render the final complete campaign and inspect it again: preliminary plots do
not approve an unseen final figure. Keep labels and incomplete-count annotations
clear of axes, neighboring panels and footnotes.
