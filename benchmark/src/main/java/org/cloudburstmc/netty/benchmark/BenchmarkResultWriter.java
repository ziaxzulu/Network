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
import java.util.List;
import java.util.Locale;

public final class BenchmarkResultWriter {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final CsvMapper CSV = new CsvMapper();
    private static final CsvSchema TIMESERIES_SCHEMA = CSV.schemaFor(TimeseriesCsv.class).withHeader();

    public File write(BenchmarkRunResult result) throws IOException {
        File directory = result.outputDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create benchmark output directory: " + directory);
        }
        writeSummaryJson(result, new File(directory, "summary.json"));
        writeTimeseriesCsv(result, new File(directory, "timeseries.csv"));
        writeLatencyData(result, new File(directory, "latency.hdr"));
        writeReport(result, new File(directory, "report.md"));
        return directory;
    }

    private static void writeSummaryJson(BenchmarkRunResult result, File file) throws IOException {
        JSON.writeValue(file, SummaryJson.from(result));
    }

    private static void writeTimeseriesCsv(BenchmarkRunResult result, File file) throws IOException {
        List<TimeseriesCsv> rows = new ArrayList<>();
        for (BenchmarkIterationResult iteration : result.iterations()) {
            rows.add(TimeseriesCsv.from(iteration));
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
            writer.write("- Packet limit: `" + optionalLimit(result.config().packetLimit()) + "`\n");
            writer.write("- Global packet limit: `" + optionalLimit(result.config().globalPacketLimit()) + "`\n");
            writer.write("- Git revision: `" + result.environment().gitRevision + "`\n");
            writer.write("- JDK: `" + result.environment().javaVersion + "` / `" + result.environment().javaVm + "`\n\n");
            writer.write("| Name | Iteration | Clients | Payload | Batch ms | Logical/batch | Groups | Target Mbps | Target/client Mbps | Disappear Mode | Delivered Gbps | Logical pkt/s | Healthy Gbps | p95 RTT ms | p99 RTT ms | Fairness | Healthy Fairness | Disconnects | Blackhole In | Blackhole Out | Stale | NACK In | Max Queue |\n");
            writer.write("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (BenchmarkIterationResult iteration : result.iterations()) {
                writer.write("| " + iteration.name
                        + " | " + iteration.iteration
                        + " | " + iteration.clients
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
                        + " | " + format(iteration.probeRtt.percentileMillis(95.0D))
                        + " | " + format(iteration.probeRtt.percentileMillis(99.0D))
                        + " | " + format(iteration.fairnessIndex)
                        + " | " + format(iteration.healthyFairnessIndex)
                        + " | " + iteration.disconnects
                        + " | " + iteration.blackholedDatagramsIn
                        + " | " + iteration.blackholedDatagramsOut
                        + " | " + iteration.staleDatagrams
                        + " | " + iteration.nackIn
                        + " | " + iteration.maxQueuedBytes
                        + " |\n");
            }
            writer.write('\n');
            writer.write("## Stability\n\n");
            writer.write(stabilitySummary(result.iterations()));
        }
    }

    static String stabilitySummary(List<BenchmarkIterationResult> iterations) {
        if (iterations.size() < 2) {
            return "Only one measured iteration was recorded; variance cannot be calculated.\n";
        }
        List<Double> throughput = new ArrayList<>();
        List<Double> p99 = new ArrayList<>();
        for (BenchmarkIterationResult iteration : iterations) {
            throughput.add(iteration.deliveredGbps);
            p99.add(iteration.probeRtt.percentileMillis(99.0D));
        }
        double throughputVariance = relativeSpread(throughput);
        double p99Variance = relativeSpread(p99);
        return "Delivered throughput relative spread: `" + format(throughputVariance * 100.0D) + "%`.\n"
                + "Probe p99 RTT relative spread: `" + format(p99Variance * 100.0D) + "%`.\n"
                + "Runs with spread above 10% should be treated as unstable and repeated with longer duration or less host contention.\n";
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

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String optionalLimit(int value) {
        return value > 0 ? Integer.toString(value) : "library default";
    }

    private static BufferedWriter writer(File file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
    }

    @JsonPropertyOrder({
            "name",
            "iteration",
            "clients",
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
            "nack_in",
            "nack_out",
            "max_queued_bytes"
    })
    private record TimeseriesCsv(
            String name,
            int iteration,
            int clients,
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
            @JsonProperty("nack_in") long nackIn,
            @JsonProperty("nack_out") long nackOut,
            @JsonProperty("max_queued_bytes") long maxQueuedBytes
    ) {
        static TimeseriesCsv from(BenchmarkIterationResult iteration) {
            return new TimeseriesCsv(
                    iteration.name,
                    iteration.iteration,
                    iteration.clients,
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
                    iteration.nackIn,
                    iteration.nackOut,
                    iteration.maxQueuedBytes
            );
        }
    }

    private record SummaryJson(
            String runId,
            String scenario,
            String role,
            int clients,
            int impairedClients,
            int disappearingClients,
            Double perClientTargetMbps,
            Integer packetLimit,
            Integer globalPacketLimit,
            long disappearAfterMillis,
            String disappearanceMode,
            long batchIntervalMillis,
            int logicalPacketsPerBatch,
            int batchGroups,
            List<Integer> batchPayloadSizes,
            long warmupMillis,
            long durationMillis,
            int iterationsRequested,
            EnvironmentJson environment,
            List<IterationJson> iterations
    ) {
        static SummaryJson from(BenchmarkRunResult result) {
            BenchmarkConfig config = result.config();
            List<IterationJson> iterations = new ArrayList<>();
            for (BenchmarkIterationResult iteration : result.iterations()) {
                iterations.add(IterationJson.from(iteration));
            }
            return new SummaryJson(
                    result.runId(),
                    config.scenario().cliName(),
                    config.role().name().toLowerCase(Locale.ROOT),
                    config.clients(),
                    config.impairedClients(),
                    config.disappearingClients(),
                    config.perClientRateMbps() >= 0.0D ? config.perClientRateMbps() : null,
                    config.packetLimit() > 0 ? config.packetLimit() : null,
                    config.globalPacketLimit() > 0 ? config.globalPacketLimit() : null,
                    config.disappearAfterMillis(),
                    config.disappearanceMode().cliName(),
                    config.batchIntervalMillis(),
                    config.logicalPacketsPerBatch(),
                    config.batchGroups(),
                    config.batchPayloadSizes(),
                    config.warmupMillis(),
                    config.durationMillis(),
                    config.iterations(),
                    EnvironmentJson.from(result.environment()),
                    iterations
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
            long nackIn,
            long nackOut,
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
                    iteration.nackIn,
                    iteration.nackOut,
                    iteration.maxQueuedBytes,
                    peers
            );
        }
    }

    private record PeerJson(
            int id,
            boolean impaired,
            String address,
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
