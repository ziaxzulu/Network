# Network Benchmarks

This module contains non-published benchmark tooling for established transport channels.

The current runner targets RakNet established-channel bandwidth, latency, fanout, fairness, disappearing-client, batched game-traffic, and impaired-network matrix runs. See [`docs/benchmarking.md`](docs/benchmarking.md) for usage, [`docs/baseline-matrix.md`](docs/baseline-matrix.md) for the recurring baseline plan, [`docs/production-usage-evidence.md`](docs/production-usage-evidence.md) for source evidence behind the synthetic, and [`docs/lab-baseline-runbook.md`](docs/lab-baseline-runbook.md) for lab baseline capture.

Use `scripts/run-baseline-matrix.sh` to produce recurring suite artifacts and `scripts/compare-baseline-suite.sh` to compare a candidate run against a saved baseline.
