/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.benchmark;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BenchmarkResultWriter {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper JSON_LINE = new ObjectMapper();
    private static final CsvMapper CSV = new CsvMapper();
    private static final CsvSchema TIMESERIES_SCHEMA = CSV.schemaFor(TimeseriesCsv.class).withHeader();
    private static final CsvSchema CAPACITY_SCHEMA = CSV.schemaFor(CapacityCsv.class).withHeader();

    public File write(BenchmarkRunResult result) throws IOException {
        File directory = result.outputDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create benchmark output directory: " + directory);
        }
        writeSummaryJson(result, new File(directory, "summary.json"));
        writeTimeline(result, new File(directory, "timeline.jsonl"));
        writeTimeseriesCsv(result, new File(directory, "timeseries.csv"));
        writeLatencyData(result, new File(directory, "latency.hdr"));
        writeReport(result, new File(directory, "report.md"));
        writeCapacityArtifacts(result, directory);
        return directory;
    }

    private static void writeSummaryJson(BenchmarkRunResult result, File file) throws IOException {
        JSON.writeValue(file, SummaryJson.from(result));
    }

    private static void writeTimeline(BenchmarkRunResult result, File file) throws IOException {
        if (result.timelineWasStreamed()) {
            return;
        }
        try (BufferedWriter writer = writer(file)) {
            for (BenchmarkTimeline.Record record : result.timelineRecords()) {
                writer.write(JSON_LINE.writeValueAsString(record));
                writer.write('\n');
            }
        }
    }

    private static void writeTimeseriesCsv(BenchmarkRunResult result, File file) throws IOException {
        List<TimeseriesCsv> rows = new ArrayList<>();
        for (BenchmarkIterationResult iteration : result.iterations()) {
            rows.add(TimeseriesCsv.from(result.config(), iteration));
        }
        CSV.writer(TIMESERIES_SCHEMA).writeValue(file, rows);
    }

    private static void writeLatencyData(BenchmarkRunResult result, File file) throws IOException {
        try (BufferedWriter writer = writer(file)) {
            writer.write("# Simple latency sample export. Values are probe RTT nanoseconds, grouped by iteration.\n");
            for (BenchmarkIterationResult iteration : result.iterations()) {
                writer.write("# " + iteration.name + " iteration=" + iteration.iteration + "\n");
                for (Long sample : iteration.probeRtt.sortedNanos()) {
                    writer.write(Long.toString(sample));
                    writer.write('\n');
                }
            }
        }
    }

    private static void writeReport(BenchmarkRunResult result, File file) throws IOException {
        try (BufferedWriter writer = writer(file)) {
            writer.write("# RakNet Established Channel Benchmark\n\n");
            writer.write("- Run ID: `" + result.runId() + "`\n");
            writer.write("- Scenario: `" + result.config().scenario().cliName() + "`\n");
            writer.write("- Role: `" + result.config().role().name().toLowerCase(Locale.ROOT) + "`\n");
            writer.write("- Recovery mode: `" + result.config().recoveryModeName() + "`\n");
            writer.write("- Packet limit: `" + optionalLimit(result.config().packetLimit()) + "`\n");
            writer.write("- Global packet limit: `" + optionalLimit(result.config().globalPacketLimit()) + "`\n");
            writer.write("- Max queued bytes cap: `" + optionalLimit(result.config().maxQueuedBytes()) + "`\n");
            writer.write("- Impairment: `" + impairmentSummary(result.config()) + "`\n");
            writer.write("- Start at epoch ms: `" + startAt(result.config()) + "`\n");
            writer.write("- Measurement window semantics: `" + result.config().measurementWindowSemantics() + "`\n");
            writer.write("- Event timeline: `timeline.jsonl` at `" + result.config().timelineSampleIntervalMillis() + "ms` cadence\n");
            writer.write("- Git revision: `" + result.environment().gitRevision + "`\n");
            writer.write("- JDK: `" + result.environment().javaVersion + "` / `" + result.environment().javaVm + "`\n\n");
            BenchmarkTimelineSummary.Snapshot timeline = result.timelineSummary();
            if (timeline.sampleCount() > 0) {
                writer.write("- Timeline max sampled cohort queued bytes: `" + timeline.maxSampledAllCurrentQueuedBytes()
                        + "` (healthy `" + timeline.maxSampledHealthyCurrentQueuedBytes()
                        + "`, affected `" + timeline.maxSampledAffectedCurrentQueuedBytes() + "`)\n");
                writer.write("- Timeline max heap/direct-buffer/RSS bytes: `" + timeline.maxHeapUsedBytes()
                        + "` / `" + valueOrUnavailable(timeline.maxDirectBufferPoolMemoryUsedBytes())
                        + "` / `" + valueOrUnavailable(timeline.maxResidentSetSizeBytes()) + "`\n\n");
            }
            writer.write("| Name | Iteration | Clients | Active | Open | Disconnected State | Payload | Batch ms | Logical/batch | Groups | Target Mbps | Target/client Mbps | Disappear Mode | Delivered Gbps | Logical pkt/s | Healthy Gbps | Affected Gbps | Undelivered Gbps | Affected Undelivered Gbps | Client Mbps p50 | Client Mbps p99 | Healthy Mbps p50 | Affected Mbps p50 | Send/Deliver | Affected Send/Deliver | Datagram Out/s | Affected Datagram Out/s | Stale/s | NACK Out/s | p95 RTT ms | p99 RTT ms | Fairness | Healthy Fairness | Affected Fairness | Disconnects | Blackhole In | Blackhole Out | Max Queue |\n");
            writer.write("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (BenchmarkIterationResult iteration : result.iterations()) {
                writer.write("| " + iteration.name
                        + " | " + iteration.iteration
                        + " | " + iteration.clients
                        + " | " + iteration.activePeers
                        + " | " + iteration.openPeers
                        + " | " + iteration.disconnectedStatePeers
                        + " | " + iteration.payloadSize
                        + " | " + iteration.batchIntervalMillis
                        + " | " + iteration.logicalPacketsPerBatch
                        + " | " + iteration.batchGroups
                        + " | " + format(iteration.targetMbps)
                        + " | " + format(iteration.targetClientMbps)
                        + " | " + iteration.disappearanceMode.cliName()
                        + " | " + format(iteration.deliveredGbps)
                        + " | " + format(iteration.deliveredLogicalPacketsPerSecond)
                        + " | " + format(iteration.healthyDeliveredGbps)
                        + " | " + format(iteration.affectedDeliveredGbps)
                        + " | " + format(iteration.undeliveredServerGbps)
                        + " | " + format(iteration.affectedUndeliveredServerGbps)
                        + " | " + format(iteration.perClientThroughput.p50Mbps())
                        + " | " + format(iteration.perClientThroughput.p99Mbps())
                        + " | " + format(iteration.healthyClientThroughput.p50Mbps())
                        + " | " + format(iteration.affectedClientThroughput.p50Mbps())
                        + " | " + format(iteration.sentToDeliveredBytesRatio)
                        + " | " + format(iteration.affectedSentToDeliveredBytesRatio)
                        + " | " + format(iteration.serverDatagramsOutPerSecond)
                        + " | " + format(iteration.affectedServerDatagramsOutPerSecond)
                        + " | " + format(iteration.staleDatagramsPerSecond)
                        + " | " + format(iteration.nackOutPerSecond)
                        + " | " + format(iteration.probeRtt.percentileMillis(95.0D))
                        + " | " + format(iteration.probeRtt.percentileMillis(99.0D))
                        + " | " + format(iteration.fairnessIndex)
                        + " | " + format(iteration.healthyFairnessIndex)
                        + " | " + format(iteration.affectedFairnessIndex)
                        + " | " + iteration.disconnects
                        + " | " + iteration.blackholedDatagramsIn
                        + " | " + iteration.blackholedDatagramsOut
                        + " | " + iteration.maxQueuedBytes
                        + " |\n");
            }
            writer.write('\n');
            writer.write("## Stability\n\n");
            if ("longitudinal-shared-session-windows".equals(result.config().measurementWindowSemantics())) {
                writer.write("These rows are longitudinal windows from one shared connection cohort; they are not independent run repetitions.\n\n");
            }
            writer.write(stabilitySummary(result.iterations()));
            List<CapacityRow> capacityRows = capacityRows(result);
            if (!capacityRows.isEmpty()) {
                writer.write("\n## Direct Bandwidth Capacity\n\n");
                writer.write("Direct capacity artifacts are written to `bandwidth-capacity.jsonl`, `bandwidth-capacity.csv`, and `bandwidth-capacity.md`.\n");
            }
        }
    }

    private static void writeCapacityArtifacts(BenchmarkRunResult result, File directory) throws IOException {
        List<CapacityRow> rows = capacityRows(result);
        if (rows.isEmpty()) {
            return;
        }

        File jsonl = new File(directory, "bandwidth-capacity.jsonl");
        try (BufferedWriter writer = writer(jsonl)) {
            for (CapacityRow row : rows) {
                writer.write(JSON_LINE.writeValueAsString(row));
                writer.write('\n');
            }
        }

        List<CapacityCsv> csvRows = new ArrayList<>();
        for (CapacityRow row : rows) {
            csvRows.add(CapacityCsv.from(row));
        }
        CSV.writer(CAPACITY_SCHEMA).writeValue(new File(directory, "bandwidth-capacity.csv"), csvRows);
        writeCapacityReport(rows, result, new File(directory, "bandwidth-capacity.md"));
    }

    private static void writeCapacityReport(List<CapacityRow> rows, BenchmarkRunResult result, File file) throws IOException {
        try (BufferedWriter writer = writer(file)) {
            writer.write("# Direct Stable Bandwidth Capacity\n\n");
            writer.write("- Run ID: `" + result.runId() + "`\n");
            writer.write("- Scenario: `" + result.config().scenario().cliName() + "`\n");
            writer.write("- Recovery mode: `" + result.config().recoveryModeName() + "`\n");
            writer.write("- Minimum iterations: `3`\n");
            writer.write("- Allow unstable rows: `false`\n\n");
            writer.write("| Case | Payload | Reliability | Selected | Stable Gbps | Stable target Mbps | Stable p99 ms | Stable spread | Best observed Gbps | Best observed target Mbps | Best observed reasons |\n");
            writer.write("| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
            for (CapacityRow row : rows) {
                CapacityCandidate selected = row.selectedCandidate;
                CapacityCandidate best = row.bestObservedCandidate;
                writer.write("| `" + row.caseName
                        + "` | " + row.payloadSize
                        + " | `" + row.reliability
                        + "` | " + row.selected
                        + " | " + candidateField(selected, selected == null ? "" : format(selected.deliveredGbps))
                        + " | " + candidateField(selected, selected == null ? "" : format(selected.targetMbps))
                        + " | " + candidateField(selected, selected == null ? "" : format(selected.probeRttP99Millis))
                        + " | " + candidateField(selected, selected == null ? "" : format(selected.deliveredGbpsSpreadPct) + "%")
                        + " | " + candidateField(best, best == null ? "" : format(best.deliveredGbps))
                        + " | " + candidateField(best, best == null ? "" : format(best.targetMbps))
                        + " | `" + (best == null ? "" : String.join(",", best.rejectionReasons)) + "` |\n");
            }
            writer.write("\nStable Gbps is the highest delivered row that has at least three measured iterations, is not marked unstable, has positive delivery, and has no disconnects. Best observed Gbps is shown separately so failed high-rate rows remain visible.\n");
        }
    }

    private static String candidateField(CapacityCandidate candidate, String value) {
        return candidate == null ? "n/a" : value;
    }

    static String stabilitySummary(List<BenchmarkIterationResult> iterations) {
        StringBuilder summary = new StringBuilder();
        summary.append("Stability is calculated per benchmark case.\n\n");
        summary.append("| Name | Iterations | Delivered Gbps Spread | Probe p99 RTT Spread | Unstable | Reasons |\n");
        summary.append("| --- | ---: | ---: | ---: | --- | --- |\n");
        for (StabilityRow row : stabilityRows(iterations)) {
            summary.append("| ")
                    .append(row.name)
                    .append(" | ")
                    .append(row.iterations)
                    .append(" | ")
                    .append(format(row.deliveredGbpsRelativeSpreadPct))
                    .append("% | ")
                    .append(format(row.probeP99RelativeSpreadPct))
                    .append("% | ")
                    .append(row.unstable)
                    .append(" | `")
                    .append(String.join(",", row.unstableReasons))
                    .append("` |\n");
        }
        summary.append('\n');
        summary.append("Rows with zero delivered throughput, fewer than three measured iterations, or spread above 10% should be treated as unstable and repeated with longer duration or less host contention.\n");
        return summary.toString();
    }

    private static List<StabilityRow> stabilityRows(List<BenchmarkIterationResult> iterations) {
        Map<String, List<BenchmarkIterationResult>> byName = new LinkedHashMap<>();
        for (BenchmarkIterationResult iteration : iterations) {
            byName.computeIfAbsent(iteration.name, ignored -> new ArrayList<>()).add(iteration);
        }

        List<StabilityRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<BenchmarkIterationResult>> entry : byName.entrySet()) {
            rows.add(stabilityRow(entry.getKey(), entry.getValue()));
        }
        return rows;
    }

    private static List<CapacityRow> capacityRows(BenchmarkRunResult result) {
        if (!writesCapacityArtifacts(result.config().scenario())) {
            return Collections.emptyList();
        }

        Map<String, List<BenchmarkIterationResult>> byName = new LinkedHashMap<>();
        for (BenchmarkIterationResult iteration : result.iterations()) {
            byName.computeIfAbsent(iteration.name, ignored -> new ArrayList<>()).add(iteration);
        }
        if (byName.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, StabilityRow> stabilityByName = new LinkedHashMap<>();
        for (StabilityRow row : stabilityRows(result.iterations())) {
            stabilityByName.put(row.name, row);
        }

        Map<CapacityGroupKey, List<CapacityCandidate>> byGroup = new LinkedHashMap<>();
        for (Map.Entry<String, List<BenchmarkIterationResult>> entry : byName.entrySet()) {
            List<BenchmarkIterationResult> iterations = entry.getValue();
            BenchmarkIterationResult first = iterations.get(0);
            CapacityGroupKey key = CapacityGroupKey.from(result, first);
            CapacityCandidate candidate = capacityCandidate(entry.getKey(), iterations, stabilityByName.get(entry.getKey()));
            byGroup.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }

        List<CapacityRow> rows = new ArrayList<>();
        for (Map.Entry<CapacityGroupKey, List<CapacityCandidate>> entry : byGroup.entrySet()) {
            List<CapacityCandidate> candidates = entry.getValue();
            CapacityCandidate selected = null;
            CapacityCandidate bestObserved = null;
            for (CapacityCandidate candidate : candidates) {
                if (bestObserved == null || capacityCompare(candidate, bestObserved) < 0) {
                    bestObserved = candidate;
                }
                if (candidate.rejectionReasons.isEmpty()
                        && (selected == null || capacityCompare(candidate, selected) < 0)) {
                    selected = candidate;
                }
            }
            rows.add(CapacityRow.from(entry.getKey(), candidates, selected, bestObserved));
        }
        return rows;
    }

    private static boolean writesCapacityArtifacts(BenchmarkScenario scenario) {
        return scenario == BenchmarkScenario.BASELINE_BANDWIDTH
                || scenario == BenchmarkScenario.BANDWIDTH_LATENCY_CURVE
                || scenario == BenchmarkScenario.MATRIX;
    }

    private static CapacityCandidate capacityCandidate(String name, List<BenchmarkIterationResult> iterations,
                                                       StabilityRow stability) {
        List<Double> delivered = new ArrayList<>();
        List<Double> p99 = new ArrayList<>();
        long maxQueuedBytes = 0L;
        long disconnects = 0L;
        double maxSendDeliverRatio = 0.0D;
        double maxNackOutPerSecond = 0.0D;
        for (BenchmarkIterationResult iteration : iterations) {
            delivered.add(iteration.deliveredGbps);
            p99.add(iteration.probeRtt.percentileMillis(99.0D));
            maxQueuedBytes = Math.max(maxQueuedBytes, iteration.maxQueuedBytes);
            disconnects += iteration.disconnects;
            maxSendDeliverRatio = Math.max(maxSendDeliverRatio, iteration.sentToDeliveredBytesRatio);
            maxNackOutPerSecond = Math.max(maxNackOutPerSecond, iteration.nackOutPerSecond);
        }

        BenchmarkIterationResult first = iterations.get(0);
        double medianDeliveredGbps = median(delivered);
        List<String> rejectionReasons = new ArrayList<>();
        if (stability != null && stability.unstable) {
            rejectionReasons.addAll(stability.unstableReasons);
        }
        if (medianDeliveredGbps <= 0.0D && !rejectionReasons.contains("zero-delivery")) {
            rejectionReasons.add("zero-delivery");
        }
        if (disconnects > 0L) {
            rejectionReasons.add("disconnects");
        }

        return new CapacityCandidate(
                name,
                first.targetMbps,
                medianDeliveredGbps,
                median(p99),
                stability == null ? 0.0D : stability.deliveredGbpsRelativeSpreadPct,
                stability == null ? 0.0D : stability.probeP99RelativeSpreadPct,
                maxQueuedBytes,
                maxSendDeliverRatio,
                maxNackOutPerSecond,
                disconnects,
                iterations.size(),
                stability != null && stability.unstable,
                rejectionReasons
        );
    }

    private static int capacityCompare(CapacityCandidate left, CapacityCandidate right) {
        int delivered = Double.compare(right.deliveredGbps, left.deliveredGbps);
        if (delivered != 0) {
            return delivered;
        }
        int p99 = Double.compare(left.probeRttP99Millis, right.probeRttP99Millis);
        if (p99 != 0) {
            return p99;
        }
        return Long.compare(left.maxQueuedBytes, right.maxQueuedBytes);
    }

    private static StabilityRow stabilityRow(String name, List<BenchmarkIterationResult> iterations) {
        List<Double> throughput = new ArrayList<>();
        List<Double> p99 = new ArrayList<>();
        for (BenchmarkIterationResult iteration : iterations) {
            throughput.add(iteration.deliveredGbps);
            p99.add(iteration.probeRtt.percentileMillis(99.0D));
        }

        double throughputSpreadPct = relativeSpread(throughput) * 100.0D;
        double p99SpreadPct = relativeSpread(p99) * 100.0D;
        List<String> unstableReasons = new ArrayList<>();
        if (iterations.size() < 3) {
            unstableReasons.add("insufficient-iterations");
        }
        if (throughputSpreadPct > 10.0D) {
            unstableReasons.add("throughput-spread");
        }
        if (p99SpreadPct > 10.0D) {
            unstableReasons.add("p99-spread");
        }
        if (!throughput.isEmpty() && Collections.max(throughput) <= 0.0D) {
            unstableReasons.add("zero-delivery");
        }
        return new StabilityRow(name, iterations.size(), throughputSpreadPct, p99SpreadPct, !unstableReasons.isEmpty(), unstableReasons);
    }

    private static double relativeSpread(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        Collections.sort(values);
        double min = values.get(0);
        double max = values.get(values.size() - 1);
        double median = values.get(values.size() / 2);
        if (median == 0.0D) {
            return max == min ? 0.0D : 1.0D;
        }
        return (max - min) / median;
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String optionalLimit(int value) {
        return value > 0 ? Integer.toString(value) : "library default";
    }

    private static String valueOrUnavailable(Long value) {
        return value == null ? "unavailable" : value.toString();
    }

    private static String startAt(BenchmarkConfig config) {
        return config.startAtEpochMillis() > 0L ? Long.toString(config.startAtEpochMillis()) : "not configured";
    }

    private static String impairmentSummary(BenchmarkConfig config) {
        if (config.impairmentLatencyMillis() == 0L && config.impairmentJitterMillis() == 0L
                && config.impairmentLossPercent() == 0.0D) {
            return "none";
        }
        return config.impairmentLatencyMillis() + "ms latency, " + config.impairmentJitterMillis() + "ms jitter, "
                + format(config.impairmentLossPercent()) + "% loss";
    }

    private static BufferedWriter writer(File file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
    }

    private record StabilityRow(
            String name,
            int iterations,
            double deliveredGbpsRelativeSpreadPct,
            double probeP99RelativeSpreadPct,
            boolean unstable,
            List<String> unstableReasons
    ) {
    }

    private record CapacityGroupKey(
            String runId,
            String scenario,
            String recoveryMode,
            String caseName,
            int clients,
            int payloadSize,
            String reliability,
            String impairmentProfile,
            long impairmentLatencyMillis,
            long impairmentJitterMillis,
            double impairmentLossPercent,
            Integer packetLimit,
            Integer globalPacketLimit,
            Integer configuredMaxQueuedBytes
    ) {
        static CapacityGroupKey from(BenchmarkRunResult result, BenchmarkIterationResult iteration) {
            BenchmarkConfig config = result.config();
            return new CapacityGroupKey(
                    result.runId(),
                    config.scenario().cliName(),
                    config.recoveryModeName(),
                    result.runId(),
                    iteration.clients,
                    iteration.payloadSize,
                    iteration.reliability.name(),
                    impairmentSummary(config),
                    config.impairmentLatencyMillis(),
                    config.impairmentJitterMillis(),
                    config.impairmentLossPercent(),
                    config.packetLimit() > 0 ? config.packetLimit() : null,
                    config.globalPacketLimit() > 0 ? config.globalPacketLimit() : null,
                    config.maxQueuedBytes() > 0 ? config.maxQueuedBytes() : null
            );
        }
    }

    private record CapacityCandidate(
            String benchmarkName,
            double targetMbps,
            double deliveredGbps,
            double probeRttP99Millis,
            double deliveredGbpsSpreadPct,
            double probeP99SpreadPct,
            long maxQueuedBytes,
            double sentToDeliveredBytesRatio,
            double nackOutPerSecond,
            long disconnects,
            int iterations,
            boolean unstable,
            List<String> rejectionReasons
    ) {
    }

    private record CapacityRow(
            String summaryKind,
            String runId,
            String scenario,
            String recoveryMode,
            String caseName,
            int clients,
            int payloadSize,
            String reliability,
            String impairmentProfile,
            long impairmentLatencyMillis,
            long impairmentJitterMillis,
            double impairmentLossPercent,
            Integer packetLimit,
            Integer globalPacketLimit,
            Integer configuredMaxQueuedBytes,
            int minIterations,
            int candidateCount,
            int eligibleCandidateCount,
            boolean selected,
            CapacityCandidate selectedCandidate,
            CapacityCandidate bestObservedCandidate,
            List<CapacityCandidate> rejectedCandidates
    ) {
        static CapacityRow from(CapacityGroupKey key, List<CapacityCandidate> candidates,
                                CapacityCandidate selected, CapacityCandidate bestObserved) {
            List<CapacityCandidate> rejected = new ArrayList<>();
            for (CapacityCandidate candidate : candidates) {
                if (!candidate.rejectionReasons.isEmpty()) {
                    rejected.add(candidate);
                }
            }
            int eligible = candidates.size() - rejected.size();
            return new CapacityRow(
                    "direct-bandwidth-capacity",
                    key.runId,
                    key.scenario,
                    key.recoveryMode,
                    key.caseName,
                    key.clients,
                    key.payloadSize,
                    key.reliability,
                    key.impairmentProfile,
                    key.impairmentLatencyMillis,
                    key.impairmentJitterMillis,
                    key.impairmentLossPercent,
                    key.packetLimit,
                    key.globalPacketLimit,
                    key.configuredMaxQueuedBytes,
                    3,
                    candidates.size(),
                    eligible,
                    selected != null,
                    selected,
                    bestObserved,
                    rejected
            );
        }
    }

    @JsonPropertyOrder({
            "case",
            "recovery_mode",
            "clients",
            "payload_size",
            "reliability",
            "impairment_profile",
            "packet_limit",
            "global_packet_limit",
            "configured_max_queued_bytes",
            "selected",
            "eligible_candidates",
            "candidate_count",
            "selected_benchmark",
            "selected_target_mbps",
            "selected_delivered_gbps",
            "selected_p99_ms",
            "selected_spread_pct",
            "selected_max_queue_bytes",
            "selected_send_deliver_ratio",
            "selected_nack_out_s",
            "best_observed_benchmark",
            "best_observed_target_mbps",
            "best_observed_delivered_gbps",
            "best_observed_p99_ms",
            "best_observed_reasons"
    })
    private record CapacityCsv(
            @JsonProperty("case") String caseName,
            @JsonProperty("recovery_mode") String recoveryMode,
            int clients,
            @JsonProperty("payload_size") int payloadSize,
            String reliability,
            @JsonProperty("impairment_profile") String impairmentProfile,
            @JsonProperty("packet_limit") Integer packetLimit,
            @JsonProperty("global_packet_limit") Integer globalPacketLimit,
            @JsonProperty("configured_max_queued_bytes") Integer configuredMaxQueuedBytes,
            boolean selected,
            @JsonProperty("eligible_candidates") int eligibleCandidates,
            @JsonProperty("candidate_count") int candidateCount,
            @JsonProperty("selected_benchmark") String selectedBenchmark,
            @JsonProperty("selected_target_mbps") Double selectedTargetMbps,
            @JsonProperty("selected_delivered_gbps") Double selectedDeliveredGbps,
            @JsonProperty("selected_p99_ms") Double selectedP99Millis,
            @JsonProperty("selected_spread_pct") Double selectedSpreadPct,
            @JsonProperty("selected_max_queue_bytes") Long selectedMaxQueueBytes,
            @JsonProperty("selected_send_deliver_ratio") Double selectedSendDeliverRatio,
            @JsonProperty("selected_nack_out_s") Double selectedNackOutPerSecond,
            @JsonProperty("best_observed_benchmark") String bestObservedBenchmark,
            @JsonProperty("best_observed_target_mbps") Double bestObservedTargetMbps,
            @JsonProperty("best_observed_delivered_gbps") Double bestObservedDeliveredGbps,
            @JsonProperty("best_observed_p99_ms") Double bestObservedP99Millis,
            @JsonProperty("best_observed_reasons") String bestObservedReasons
    ) {
        static CapacityCsv from(CapacityRow row) {
            CapacityCandidate selected = row.selectedCandidate;
            CapacityCandidate best = row.bestObservedCandidate;
            return new CapacityCsv(
                    row.caseName,
                    row.recoveryMode,
                    row.clients,
                    row.payloadSize,
                    row.reliability,
                    row.impairmentProfile,
                    row.packetLimit,
                    row.globalPacketLimit,
                    row.configuredMaxQueuedBytes,
                    row.selected,
                    row.eligibleCandidateCount,
                    row.candidateCount,
                    selected == null ? null : selected.benchmarkName,
                    selected == null ? null : selected.targetMbps,
                    selected == null ? null : selected.deliveredGbps,
                    selected == null ? null : selected.probeRttP99Millis,
                    selected == null ? null : selected.deliveredGbpsSpreadPct,
                    selected == null ? null : selected.maxQueuedBytes,
                    selected == null ? null : selected.sentToDeliveredBytesRatio,
                    selected == null ? null : selected.nackOutPerSecond,
                    best == null ? null : best.benchmarkName,
                    best == null ? null : best.targetMbps,
                    best == null ? null : best.deliveredGbps,
                    best == null ? null : best.probeRttP99Millis,
                    best == null ? "" : String.join(";", best.rejectionReasons)
            );
        }
    }

    @JsonPropertyOrder({
            "name",
            "recovery_mode",
            "iteration",
            "clients",
            "open_peers",
            "active_peers",
            "state_connected_peers",
            "state_disconnecting_peers",
            "state_disconnected_peers",
            "state_unconnected_peers",
            "payload_size",
            "reliability",
            "batched",
            "batch_interval_ms",
            "logical_packets_per_batch",
            "batch_groups",
            "target_mbps",
            "target_client_mbps",
            "disappearance_mode",
            "elapsed_ms",
            "offered_gbps",
            "delivered_gbps",
            "healthy_delivered_gbps",
            "affected_delivered_gbps",
            "undelivered_server_bytes_out",
            "undelivered_server_gbps",
            "healthy_undelivered_server_bytes_out",
            "healthy_undelivered_server_gbps",
            "affected_undelivered_server_bytes_out",
            "affected_undelivered_server_gbps",
            "server_bytes_out",
            "server_datagrams_out",
            "server_datagrams_out_s",
            "healthy_server_bytes_out",
            "affected_server_bytes_out",
            "healthy_server_datagrams_out",
            "affected_server_datagrams_out",
            "healthy_server_datagrams_out_s",
            "affected_server_datagrams_out_s",
            "sent_delivered_bytes_ratio",
            "healthy_sent_delivered_bytes_ratio",
            "affected_sent_delivered_bytes_ratio",
            "client_mbps_min",
            "client_mbps_p50",
            "client_mbps_p95",
            "client_mbps_p99",
            "client_mbps_max",
            "healthy_client_mbps_min",
            "healthy_client_mbps_p50",
            "healthy_client_mbps_p95",
            "healthy_client_mbps_p99",
            "healthy_client_mbps_max",
            "affected_client_mbps_min",
            "affected_client_mbps_p50",
            "affected_client_mbps_p95",
            "affected_client_mbps_p99",
            "affected_client_mbps_max",
            "delivered_msg_s",
            "delivered_logical_packets_s",
            "logical_packets_sent",
            "logical_packets_received",
            "p50_ms",
            "p95_ms",
            "p99_ms",
            "max_ms",
            "fairness",
            "healthy_fairness",
            "affected_fairness",
            "affected_clients",
            "disconnects",
            "blackholed_datagrams_in",
            "blackholed_datagrams_out",
            "stale_datagrams",
            "stale_datagrams_s",
            "nack_in",
            "nack_in_s",
            "nack_out",
            "nack_out_s",
            "max_queued_bytes"
    })
    private record TimeseriesCsv(
            String name,
            @JsonProperty("recovery_mode") String recoveryMode,
            int iteration,
            int clients,
            @JsonProperty("open_peers") int openPeers,
            @JsonProperty("active_peers") int activePeers,
            @JsonProperty("state_connected_peers") int connectedStatePeers,
            @JsonProperty("state_disconnecting_peers") int disconnectingStatePeers,
            @JsonProperty("state_disconnected_peers") int disconnectedStatePeers,
            @JsonProperty("state_unconnected_peers") int unconnectedStatePeers,
            @JsonProperty("payload_size") int payloadSize,
            String reliability,
            boolean batched,
            @JsonProperty("batch_interval_ms") long batchIntervalMillis,
            @JsonProperty("logical_packets_per_batch") int logicalPacketsPerBatch,
            @JsonProperty("batch_groups") int batchGroups,
            @JsonProperty("target_mbps") double targetMbps,
            @JsonProperty("target_client_mbps") double targetClientMbps,
            @JsonProperty("disappearance_mode") String disappearanceMode,
            @JsonProperty("elapsed_ms") long elapsedMillis,
            @JsonProperty("offered_gbps") double offeredGbps,
            @JsonProperty("delivered_gbps") double deliveredGbps,
            @JsonProperty("healthy_delivered_gbps") double healthyDeliveredGbps,
            @JsonProperty("affected_delivered_gbps") double affectedDeliveredGbps,
            @JsonProperty("undelivered_server_bytes_out") long undeliveredServerBytesOut,
            @JsonProperty("undelivered_server_gbps") double undeliveredServerGbps,
            @JsonProperty("healthy_undelivered_server_bytes_out") long healthyUndeliveredServerBytesOut,
            @JsonProperty("healthy_undelivered_server_gbps") double healthyUndeliveredServerGbps,
            @JsonProperty("affected_undelivered_server_bytes_out") long affectedUndeliveredServerBytesOut,
            @JsonProperty("affected_undelivered_server_gbps") double affectedUndeliveredServerGbps,
            @JsonProperty("server_bytes_out") long serverBytesOut,
            @JsonProperty("server_datagrams_out") long serverDatagramsOut,
            @JsonProperty("server_datagrams_out_s") double serverDatagramsOutPerSecond,
            @JsonProperty("healthy_server_bytes_out") long healthyServerBytesOut,
            @JsonProperty("affected_server_bytes_out") long affectedServerBytesOut,
            @JsonProperty("healthy_server_datagrams_out") long healthyServerDatagramsOut,
            @JsonProperty("affected_server_datagrams_out") long affectedServerDatagramsOut,
            @JsonProperty("healthy_server_datagrams_out_s") double healthyServerDatagramsOutPerSecond,
            @JsonProperty("affected_server_datagrams_out_s") double affectedServerDatagramsOutPerSecond,
            @JsonProperty("sent_delivered_bytes_ratio") double sentToDeliveredBytesRatio,
            @JsonProperty("healthy_sent_delivered_bytes_ratio") double healthySentToDeliveredBytesRatio,
            @JsonProperty("affected_sent_delivered_bytes_ratio") double affectedSentToDeliveredBytesRatio,
            @JsonProperty("client_mbps_min") double clientMbpsMin,
            @JsonProperty("client_mbps_p50") double clientMbpsP50,
            @JsonProperty("client_mbps_p95") double clientMbpsP95,
            @JsonProperty("client_mbps_p99") double clientMbpsP99,
            @JsonProperty("client_mbps_max") double clientMbpsMax,
            @JsonProperty("healthy_client_mbps_min") double healthyClientMbpsMin,
            @JsonProperty("healthy_client_mbps_p50") double healthyClientMbpsP50,
            @JsonProperty("healthy_client_mbps_p95") double healthyClientMbpsP95,
            @JsonProperty("healthy_client_mbps_p99") double healthyClientMbpsP99,
            @JsonProperty("healthy_client_mbps_max") double healthyClientMbpsMax,
            @JsonProperty("affected_client_mbps_min") double affectedClientMbpsMin,
            @JsonProperty("affected_client_mbps_p50") double affectedClientMbpsP50,
            @JsonProperty("affected_client_mbps_p95") double affectedClientMbpsP95,
            @JsonProperty("affected_client_mbps_p99") double affectedClientMbpsP99,
            @JsonProperty("affected_client_mbps_max") double affectedClientMbpsMax,
            @JsonProperty("delivered_msg_s") double deliveredMessagesPerSecond,
            @JsonProperty("delivered_logical_packets_s") double deliveredLogicalPacketsPerSecond,
            @JsonProperty("logical_packets_sent") long logicalPacketsSent,
            @JsonProperty("logical_packets_received") long logicalPacketsReceived,
            @JsonProperty("p50_ms") double p50Millis,
            @JsonProperty("p95_ms") double p95Millis,
            @JsonProperty("p99_ms") double p99Millis,
            @JsonProperty("max_ms") double maxMillis,
            double fairness,
            @JsonProperty("healthy_fairness") double healthyFairness,
            @JsonProperty("affected_fairness") double affectedFairness,
            @JsonProperty("affected_clients") int affectedClients,
            long disconnects,
            @JsonProperty("blackholed_datagrams_in") long blackholedDatagramsIn,
            @JsonProperty("blackholed_datagrams_out") long blackholedDatagramsOut,
            @JsonProperty("stale_datagrams") long staleDatagrams,
            @JsonProperty("stale_datagrams_s") double staleDatagramsPerSecond,
            @JsonProperty("nack_in") long nackIn,
            @JsonProperty("nack_in_s") double nackInPerSecond,
            @JsonProperty("nack_out") long nackOut,
            @JsonProperty("nack_out_s") double nackOutPerSecond,
            @JsonProperty("max_queued_bytes") long maxQueuedBytes
    ) {
        static TimeseriesCsv from(BenchmarkConfig config, BenchmarkIterationResult iteration) {
            return new TimeseriesCsv(
                    iteration.name,
                    config.recoveryModeName(),
                    iteration.iteration,
                    iteration.clients,
                    iteration.openPeers,
                    iteration.activePeers,
                    iteration.connectedStatePeers,
                    iteration.disconnectingStatePeers,
                    iteration.disconnectedStatePeers,
                    iteration.unconnectedStatePeers,
                    iteration.payloadSize,
                    iteration.reliability.name(),
                    iteration.batched,
                    iteration.batchIntervalMillis,
                    iteration.logicalPacketsPerBatch,
                    iteration.batchGroups,
                    iteration.targetMbps,
                    iteration.targetClientMbps,
                    iteration.disappearanceMode.cliName(),
                    iteration.elapsedMillis,
                    iteration.offeredGbps,
                    iteration.deliveredGbps,
                    iteration.healthyDeliveredGbps,
                    iteration.affectedDeliveredGbps,
                    iteration.undeliveredServerBytesOut,
                    iteration.undeliveredServerGbps,
                    iteration.healthyUndeliveredServerBytesOut,
                    iteration.healthyUndeliveredServerGbps,
                    iteration.affectedUndeliveredServerBytesOut,
                    iteration.affectedUndeliveredServerGbps,
                    iteration.serverBytesOut,
                    iteration.serverDatagramsOut,
                    iteration.serverDatagramsOutPerSecond,
                    iteration.healthyServerBytesOut,
                    iteration.affectedServerBytesOut,
                    iteration.healthyServerDatagramsOut,
                    iteration.affectedServerDatagramsOut,
                    iteration.healthyServerDatagramsOutPerSecond,
                    iteration.affectedServerDatagramsOutPerSecond,
                    iteration.sentToDeliveredBytesRatio,
                    iteration.healthySentToDeliveredBytesRatio,
                    iteration.affectedSentToDeliveredBytesRatio,
                    iteration.perClientThroughput.minMbps(),
                    iteration.perClientThroughput.p50Mbps(),
                    iteration.perClientThroughput.p95Mbps(),
                    iteration.perClientThroughput.p99Mbps(),
                    iteration.perClientThroughput.maxMbps(),
                    iteration.healthyClientThroughput.minMbps(),
                    iteration.healthyClientThroughput.p50Mbps(),
                    iteration.healthyClientThroughput.p95Mbps(),
                    iteration.healthyClientThroughput.p99Mbps(),
                    iteration.healthyClientThroughput.maxMbps(),
                    iteration.affectedClientThroughput.minMbps(),
                    iteration.affectedClientThroughput.p50Mbps(),
                    iteration.affectedClientThroughput.p95Mbps(),
                    iteration.affectedClientThroughput.p99Mbps(),
                    iteration.affectedClientThroughput.maxMbps(),
                    iteration.deliveredMessagesPerSecond,
                    iteration.deliveredLogicalPacketsPerSecond,
                    iteration.logicalPacketsSent,
                    iteration.logicalPacketsReceived,
                    iteration.probeRtt.percentileMillis(50.0D),
                    iteration.probeRtt.percentileMillis(95.0D),
                    iteration.probeRtt.percentileMillis(99.0D),
                    iteration.probeRtt.maxMillis(),
                    iteration.fairnessIndex,
                    iteration.healthyFairnessIndex,
                    iteration.affectedFairnessIndex,
                    iteration.affectedClients,
                    iteration.disconnects,
                    iteration.blackholedDatagramsIn,
                    iteration.blackholedDatagramsOut,
                    iteration.staleDatagrams,
                    iteration.staleDatagramsPerSecond,
                    iteration.nackIn,
                    iteration.nackInPerSecond,
                    iteration.nackOut,
                    iteration.nackOutPerSecond,
                    iteration.maxQueuedBytes
            );
        }
    }

    private record SummaryJson(
            String runId,
            String scenario,
            String role,
            String recoveryMode,
            int clients,
            int impairedClients,
            int disappearingClients,
            Double perClientTargetMbps,
            Integer packetLimit,
            Integer globalPacketLimit,
            Integer configuredMaxQueuedBytes,
            long impairmentLatencyMillis,
            long impairmentJitterMillis,
            double impairmentLossPercent,
            long disappearAfterMillis,
            String disappearanceMode,
            long batchIntervalMillis,
            int logicalPacketsPerBatch,
            int batchGroups,
            List<Integer> batchPayloadSizes,
            long warmupMillis,
            long durationMillis,
            long startAtEpochMillis,
            Long externalImpairmentAtEpochMillis,
            Long externalBlackholeAtEpochMillis,
            Long externalRecoveryAtEpochMillis,
            long timelineSampleIntervalMillis,
            String measurementWindowSemantics,
            String timelineArtifact,
            BenchmarkTimelineSummary.Snapshot timelineSummary,
            int iterationsRequested,
            EnvironmentJson environment,
            List<StabilityJson> stability,
            List<IterationJson> iterations
    ) {
        static SummaryJson from(BenchmarkRunResult result) {
            BenchmarkConfig config = result.config();
            List<IterationJson> iterations = new ArrayList<>();
            for (BenchmarkIterationResult iteration : result.iterations()) {
                iterations.add(IterationJson.from(iteration));
            }
            List<StabilityJson> stability = new ArrayList<>();
            for (StabilityRow row : stabilityRows(result.iterations())) {
                stability.add(StabilityJson.from(row));
            }
            return new SummaryJson(
                    result.runId(),
                    config.scenario().cliName(),
                    config.role().name().toLowerCase(Locale.ROOT),
                    config.recoveryModeName(),
                    config.clients(),
                    config.impairedClients(),
                    config.disappearingClients(),
                    config.perClientRateMbps() >= 0.0D ? config.perClientRateMbps() : null,
                    config.packetLimit() > 0 ? config.packetLimit() : null,
                    config.globalPacketLimit() > 0 ? config.globalPacketLimit() : null,
                    config.maxQueuedBytes() > 0 ? config.maxQueuedBytes() : null,
                    config.impairmentLatencyMillis(),
                    config.impairmentJitterMillis(),
                    config.impairmentLossPercent(),
                    config.disappearAfterMillis(),
                    config.disappearanceMode().cliName(),
                    config.batchIntervalMillis(),
                    config.logicalPacketsPerBatch(),
                    config.batchGroups(),
                    config.batchPayloadSizes(),
                    config.warmupMillis(),
                    config.durationMillis(),
                    config.startAtEpochMillis(),
                    config.externalImpairmentAtEpochMillis() > 0L ? config.externalImpairmentAtEpochMillis() : null,
                    config.externalBlackholeAtEpochMillis() > 0L ? config.externalBlackholeAtEpochMillis() : null,
                    config.externalRecoveryAtEpochMillis() > 0L ? config.externalRecoveryAtEpochMillis() : null,
                    config.timelineSampleIntervalMillis(),
                    config.measurementWindowSemantics(),
                    "timeline.jsonl",
                    result.timelineSummary(),
                    config.iterations(),
                    EnvironmentJson.from(result.environment()),
                    stability,
                    iterations
            );
        }
    }

    private record StabilityJson(
            String name,
            int iterations,
            double deliveredGbpsRelativeSpreadPct,
            double probeP99RelativeSpreadPct,
            boolean unstable,
            List<String> unstableReasons
    ) {
        static StabilityJson from(StabilityRow row) {
            return new StabilityJson(
                    row.name,
                    row.iterations,
                    row.deliveredGbpsRelativeSpreadPct,
                    row.probeP99RelativeSpreadPct,
                    row.unstable,
                    row.unstableReasons
            );
        }
    }

    private record EnvironmentJson(
            String javaVersion,
            String javaVm,
            String osName,
            String osVersion,
            String osArch,
            int processors,
            long maxHeapBytes,
            String gitRevision,
            List<String> garbageCollectors
    ) {
        static EnvironmentJson from(EnvironmentInfo env) {
            return new EnvironmentJson(
                    env.javaVersion,
                    env.javaVm,
                    env.osName,
                    env.osVersion,
                    env.osArch,
                    env.processors,
                    env.maxHeapBytes,
                    env.gitRevision,
                    env.garbageCollectors
            );
        }
    }

    private record IterationJson(
            String name,
            int iteration,
            int clients,
            int openPeers,
            int activePeers,
            int connectedStatePeers,
            int disconnectingStatePeers,
            int disconnectedStatePeers,
            int unconnectedStatePeers,
            int payloadSize,
            String reliability,
            boolean batched,
            long batchIntervalMillis,
            int logicalPacketsPerBatch,
            int batchGroups,
            double targetMbps,
            double targetClientMbps,
            String disappearanceMode,
            long elapsedMillis,
            long bulkSentMessages,
            long bulkSentBytes,
            long logicalPacketsSent,
            long bulkReceivedMessages,
            long bulkReceivedBytes,
            long logicalPacketsReceived,
            double deliveredGbps,
            double offeredGbps,
            double healthyDeliveredGbps,
            double affectedDeliveredGbps,
            long undeliveredServerBytesOut,
            double undeliveredServerGbps,
            long healthyUndeliveredServerBytesOut,
            double healthyUndeliveredServerGbps,
            long affectedUndeliveredServerBytesOut,
            double affectedUndeliveredServerGbps,
            long serverBytesOut,
            long serverDatagramsOut,
            double serverDatagramsOutPerSecond,
            long healthyServerBytesOut,
            long affectedServerBytesOut,
            long healthyServerDatagramsOut,
            long affectedServerDatagramsOut,
            double healthyServerDatagramsOutPerSecond,
            double affectedServerDatagramsOutPerSecond,
            double sentToDeliveredBytesRatio,
            double healthySentToDeliveredBytesRatio,
            double affectedSentToDeliveredBytesRatio,
            ThroughputDistribution perClientThroughput,
            ThroughputDistribution healthyClientThroughput,
            ThroughputDistribution affectedClientThroughput,
            double deliveredMessagesPerSecond,
            double deliveredLogicalPacketsPerSecond,
            double fairnessIndex,
            double healthyFairnessIndex,
            double affectedFairnessIndex,
            int affectedClients,
            long disconnects,
            long blackholedDatagramsIn,
            long blackholedDatagramsOut,
            long probesSent,
            long probesAcked,
            int probeRttCount,
            double probeRttP50Millis,
            double probeRttP95Millis,
            double probeRttP99Millis,
            double probeRttMaxMillis,
            long staleDatagrams,
            double staleDatagramsPerSecond,
            long nackIn,
            double nackInPerSecond,
            long nackOut,
            double nackOutPerSecond,
            long maxQueuedBytes,
            List<PeerJson> peers
    ) {
        static IterationJson from(BenchmarkIterationResult iteration) {
            List<PeerJson> peers = new ArrayList<>();
            for (PeerStats.Snapshot peer : iteration.peers) {
                peers.add(PeerJson.from(peer));
            }
            return new IterationJson(
                    iteration.name,
                    iteration.iteration,
                    iteration.clients,
                    iteration.openPeers,
                    iteration.activePeers,
                    iteration.connectedStatePeers,
                    iteration.disconnectingStatePeers,
                    iteration.disconnectedStatePeers,
                    iteration.unconnectedStatePeers,
                    iteration.payloadSize,
                    iteration.reliability.name(),
                    iteration.batched,
                    iteration.batchIntervalMillis,
                    iteration.logicalPacketsPerBatch,
                    iteration.batchGroups,
                    iteration.targetMbps,
                    iteration.targetClientMbps,
                    iteration.disappearanceMode.cliName(),
                    iteration.elapsedMillis,
                    iteration.bulkSentMessages,
                    iteration.bulkSentBytes,
                    iteration.logicalPacketsSent,
                    iteration.bulkReceivedMessages,
                    iteration.bulkReceivedBytes,
                    iteration.logicalPacketsReceived,
                    iteration.deliveredGbps,
                    iteration.offeredGbps,
                    iteration.healthyDeliveredGbps,
                    iteration.affectedDeliveredGbps,
                    iteration.undeliveredServerBytesOut,
                    iteration.undeliveredServerGbps,
                    iteration.healthyUndeliveredServerBytesOut,
                    iteration.healthyUndeliveredServerGbps,
                    iteration.affectedUndeliveredServerBytesOut,
                    iteration.affectedUndeliveredServerGbps,
                    iteration.serverBytesOut,
                    iteration.serverDatagramsOut,
                    iteration.serverDatagramsOutPerSecond,
                    iteration.healthyServerBytesOut,
                    iteration.affectedServerBytesOut,
                    iteration.healthyServerDatagramsOut,
                    iteration.affectedServerDatagramsOut,
                    iteration.healthyServerDatagramsOutPerSecond,
                    iteration.affectedServerDatagramsOutPerSecond,
                    iteration.sentToDeliveredBytesRatio,
                    iteration.healthySentToDeliveredBytesRatio,
                    iteration.affectedSentToDeliveredBytesRatio,
                    iteration.perClientThroughput,
                    iteration.healthyClientThroughput,
                    iteration.affectedClientThroughput,
                    iteration.deliveredMessagesPerSecond,
                    iteration.deliveredLogicalPacketsPerSecond,
                    iteration.fairnessIndex,
                    iteration.healthyFairnessIndex,
                    iteration.affectedFairnessIndex,
                    iteration.affectedClients,
                    iteration.disconnects,
                    iteration.blackholedDatagramsIn,
                    iteration.blackholedDatagramsOut,
                    iteration.probesSent,
                    iteration.probesAcked,
                    iteration.probeRtt.count(),
                    iteration.probeRtt.percentileMillis(50.0D),
                    iteration.probeRtt.percentileMillis(95.0D),
                    iteration.probeRtt.percentileMillis(99.0D),
                    iteration.probeRtt.maxMillis(),
                    iteration.staleDatagrams,
                    iteration.staleDatagramsPerSecond,
                    iteration.nackIn,
                    iteration.nackInPerSecond,
                    iteration.nackOut,
                    iteration.nackOutPerSecond,
                    iteration.maxQueuedBytes,
                    peers
            );
        }
    }

    private record PeerJson(
            int id,
            boolean impaired,
            String address,
            boolean channelOpen,
            boolean channelActive,
            long serverBytesIn,
            long serverBytesOut,
            long serverDatagramsIn,
            long serverDatagramsOut,
            long encapsulatedIn,
            long encapsulatedOut,
            long staleDatagrams,
            long ackIn,
            long ackOut,
            long nackIn,
            long nackOut,
            long bulkSentMessages,
            long bulkSentBytes,
            long logicalPacketsSent,
            long bulkReceivedMessages,
            long bulkReceivedBytes,
            long logicalPacketsReceived,
            long probesSent,
            long probesAcked,
            long disconnects,
            long blackholedDatagramsIn,
            long blackholedDatagramsOut,
            long maxQueuedBytes,
            String lastState
    ) {
        static PeerJson from(PeerStats.Snapshot peer) {
            return new PeerJson(
                    peer.id,
                    peer.impaired,
                    String.valueOf(peer.address),
                    peer.channelOpen,
                    peer.channelActive,
                    peer.serverBytesIn,
                    peer.serverBytesOut,
                    peer.serverDatagramsIn,
                    peer.serverDatagramsOut,
                    peer.encapsulatedIn,
                    peer.encapsulatedOut,
                    peer.staleDatagrams,
                    peer.ackIn,
                    peer.ackOut,
                    peer.nackIn,
                    peer.nackOut,
                    peer.bulkSentMessages,
                    peer.bulkSentBytes,
                    peer.logicalPacketsSent,
                    peer.bulkReceivedMessages,
                    peer.bulkReceivedBytes,
                    peer.logicalPacketsReceived,
                    peer.probesSent,
                    peer.probesAcked,
                    peer.disconnects,
                    peer.blackholedDatagramsIn,
                    peer.blackholedDatagramsOut,
                    peer.maxQueuedBytes,
                    peer.lastState.name()
            );
        }
    }
}
