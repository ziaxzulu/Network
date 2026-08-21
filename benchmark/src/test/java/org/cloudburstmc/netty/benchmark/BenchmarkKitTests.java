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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BenchmarkKitTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CsvMapper CSV = new CsvMapper();

    @Test
    public void testEnvironmentInfoUsesConfiguredGitRevision() {
        String originalRevision = System.getProperty("benchmark.gitRevision");
        try {
            System.setProperty("benchmark.gitRevision", "installed-snapshot-1234");
            Assertions.assertEquals("installed-snapshot-1234", EnvironmentInfo.capture().gitRevision);
        } finally {
            restoreProperty("benchmark.gitRevision", originalRevision);
        }
    }

    @Test
    public void testConfigParsing() {
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "bandwidth-latency-curve",
                "--clients", "7",
                "--duration", "2s",
                "--warmup=250ms",
                "--payload-size", "128",
                "--reliability", "unreliable",
                "--target-gbps", "1.5",
                "--per-client-mbps", "5",
                "--batch-interval", "10ms",
                "--logical-packets-per-batch", "4",
                "--batch-payload-sizes", "64,512",
                "--batch-groups", "4",
                "--packet-limit", "1000",
                "--global-packet-limit", "1000000",
                "--max-queued-bytes", "1048576",
                "--start-at-epoch-ms", "1767225600000",
                "--impairment-latency", "50ms",
                "--impairment-jitter", "5ms",
                "--impairment-loss", "2%",
                "--rates-mbps", "100,unlimited"
        });

        Assertions.assertEquals(BenchmarkScenario.BANDWIDTH_LATENCY_CURVE, config.scenario());
        Assertions.assertEquals(7, config.clients());
        Assertions.assertEquals(2000, config.durationMillis());
        Assertions.assertEquals(250, config.warmupMillis());
        Assertions.assertEquals(128, config.payloadSize());
        Assertions.assertEquals(RakReliability.UNRELIABLE, config.reliability());
        Assertions.assertEquals(1500.0D, config.rateMbps(), 0.001D);
        Assertions.assertEquals(5.0D, config.perClientRateMbps(), 0.001D);
        Assertions.assertEquals(35.0D, config.effectiveTargetMbps(config.rateMbps(), config.clients()), 0.001D);
        Assertions.assertEquals(10, config.batchIntervalMillis());
        Assertions.assertEquals(4, config.logicalPacketsPerBatch());
        Assertions.assertEquals(Arrays.asList(64, 512), config.batchPayloadSizes());
        Assertions.assertEquals(4, config.batchGroups());
        Assertions.assertEquals(1000, config.packetLimit());
        Assertions.assertEquals(1_000_000, config.globalPacketLimit());
        Assertions.assertEquals(1_048_576, config.maxQueuedBytes());
        Assertions.assertEquals(1_767_225_600_000L, config.startAtEpochMillis());
        Assertions.assertEquals(50, config.impairmentLatencyMillis());
        Assertions.assertEquals(5, config.impairmentJitterMillis());
        Assertions.assertEquals(2.0D, config.impairmentLossPercent(), 0.001D);
        Assertions.assertEquals(Arrays.asList(100.0D, 0.0D), config.ratesMbps());
    }

    @Test
    public void testConfigResolvesGradleRelativeOutputFromRepoRoot() {
        String originalRepoRoot = System.getProperty("benchmark.repoRoot");
        String originalDefaultOutputRoot = System.getProperty("benchmark.defaultOutputRoot");
        try {
            System.setProperty("benchmark.repoRoot", "/repo");
            System.setProperty("benchmark.defaultOutputRoot", "/repo/benchmark/build/benchmark-results");

            BenchmarkConfig defaultConfig = BenchmarkConfig.parse(new String[]{"baseline-bandwidth"});
            Assertions.assertEquals(new java.io.File("/repo/benchmark/build/benchmark-results"),
                    defaultConfig.outputRoot());

            BenchmarkConfig explicitRelative = BenchmarkConfig.parse(new String[]{
                    "baseline-bandwidth",
                    "--out", "benchmark/build/benchmark-results/local"
            });
            Assertions.assertEquals(new java.io.File("/repo/benchmark/build/benchmark-results/local"),
                    explicitRelative.outputRoot());

            BenchmarkConfig explicitAbsolute = BenchmarkConfig.parse(new String[]{
                    "baseline-bandwidth",
                    "--out", "/tmp/raknet-benchmark"
            });
            Assertions.assertEquals(new java.io.File("/tmp/raknet-benchmark"), explicitAbsolute.outputRoot());
        } finally {
            restoreProperty("benchmark.repoRoot", originalRepoRoot);
            restoreProperty("benchmark.defaultOutputRoot", originalDefaultOutputRoot);
        }
    }

    @Test
    public void testProductionRateAndDisappearingParsing() {
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "disappearing-clients",
                "--clients", "100",
                "--per-client-mbps", "5",
                "--disappearing-clients", "10",
                "--disappear-mode", "stop-reading",
                "--disappear-after", "250ms",
                "--duration", "1s",
                "--payload-size", "500"
        });

        Assertions.assertEquals(BenchmarkScenario.DISAPPEARING_CLIENTS, config.scenario());
        Assertions.assertEquals(10, config.disappearingClients());
        Assertions.assertEquals(DisappearanceMode.STOP_READING, config.disappearanceMode());
        Assertions.assertEquals(DisappearanceMode.BLACKHOLE, DisappearanceMode.parse("blackhole"));
        Assertions.assertEquals(250, config.disappearAfterMillis());
        Assertions.assertEquals(500.0D, config.effectiveTargetMbps(config.rateMbps(), config.clients()), 0.001D);
        Assertions.assertEquals(5.0D, config.effectiveTargetClientMbps(500.0D, config.clients()), 0.001D);
        Assertions.assertEquals(125000L, config.effectiveMessageRate(config.payloadSize(), config.rateMbps(), config.clients()));
    }

    @Test
    public void testResourcePackTransferParsing() {
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "resource-pack-transfer",
                "--clients", "20",
                "--chunk-size", "262144",
                "--chunk-interval", "200ms",
                "--duration", "1s"
        });

        Assertions.assertEquals(BenchmarkScenario.RESOURCE_PACK_TRANSFER, config.scenario());
        Assertions.assertEquals(20, config.clients());
        Assertions.assertEquals(262144, config.payloadSize());
        Assertions.assertEquals(200, config.batchIntervalMillis());
    }

    @Test
    public void testLatencyHistogramPercentiles() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(1_000_000L);
        histogram.record(2_000_000L);
        histogram.record(3_000_000L);
        histogram.record(4_000_000L);

        LatencyHistogram.Snapshot snapshot = histogram.snapshot();
        Assertions.assertEquals(4, snapshot.count());
        Assertions.assertEquals(2.0D, snapshot.percentileMillis(50.0D), 0.001D);
        Assertions.assertEquals(4.0D, snapshot.percentileMillis(99.0D), 0.001D);
        Assertions.assertEquals(4.0D, snapshot.maxMillis(), 0.001D);
    }

    @Test
    public void testBatchPayloadEncoding() {
        ByteBuf payload = BenchmarkPayload.batch(UnpooledByteBufAllocator.DEFAULT, 64, 42L, 4);
        try {
            Assertions.assertEquals(BenchmarkPayload.BATCH, BenchmarkPayload.type(payload));
            Assertions.assertEquals(42L, BenchmarkPayload.sequence(payload));
            Assertions.assertEquals(4, BenchmarkPayload.logicalPackets(payload));
            Assertions.assertEquals(64, payload.readableBytes());
        } finally {
            payload.release();
        }
    }

    @Test
    public void testDatagramBlackholeHandlerDropsAfterEnabled() {
        PeerStats peer = new PeerStats(0, true);
        DatagramBlackholeHandler handler = new DatagramBlackholeHandler(peer);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        ByteBuf passThrough = UnpooledByteBufAllocator.DEFAULT.buffer(1).writeByte(1);
        Assertions.assertTrue(channel.writeInbound(passThrough));
        ByteBuf forwarded = channel.readInbound();
        forwarded.release();

        handler.enable();
        ByteBuf inbound = UnpooledByteBufAllocator.DEFAULT.buffer(1).writeByte(2);
        ByteBuf outbound = UnpooledByteBufAllocator.DEFAULT.buffer(1).writeByte(3);
        Assertions.assertFalse(channel.writeInbound(inbound));
        Assertions.assertFalse(channel.writeOutbound(outbound));

        PeerStats.Snapshot snapshot = peer.snapshot();
        Assertions.assertEquals(1, snapshot.blackholedDatagramsIn);
        Assertions.assertEquals(1, snapshot.blackholedDatagramsOut);
        Assertions.assertFalse(snapshot.channelOpen);
        Assertions.assertFalse(snapshot.channelActive);
        channel.finishAndReleaseAll();
    }

    @Test
    public void testDatagramImpairmentHandlerDropsAfterEnabled() {
        DatagramImpairmentHandler handler = new DatagramImpairmentHandler(0, 0, 100.0D, 0);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        ByteBuf passThrough = UnpooledByteBufAllocator.DEFAULT.buffer(1).writeByte(1);
        Assertions.assertTrue(channel.writeInbound(passThrough));
        ByteBuf forwarded = channel.readInbound();
        forwarded.release();

        handler.enable();
        ByteBuf inbound = UnpooledByteBufAllocator.DEFAULT.buffer(1).writeByte(2);
        ByteBuf outbound = UnpooledByteBufAllocator.DEFAULT.buffer(1).writeByte(3);
        Assertions.assertFalse(channel.writeInbound(inbound));
        Assertions.assertFalse(channel.writeOutbound(outbound));

        channel.finishAndReleaseAll();
    }

    @Test
    public void testDatagramImpairmentHandlerReleasesDelayedInboundOnClose() {
        DatagramImpairmentHandler handler = new DatagramImpairmentHandler(1000, 0, 0.0D, 0);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        handler.enable();

        ByteBuf inbound = UnpooledByteBufAllocator.DEFAULT.buffer(1).writeByte(1);
        Assertions.assertFalse(channel.writeInbound(inbound));
        Assertions.assertEquals(1, inbound.refCnt());

        channel.close().syncUninterruptibly();
        Assertions.assertEquals(0, inbound.refCnt());
        channel.finishAndReleaseAll();
    }

    @Test
    public void testDatagramImpairmentHandlerReleasesDelayedOutboundOnClose() {
        DatagramImpairmentHandler handler = new DatagramImpairmentHandler(1000, 0, 0.0D, 0);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        handler.enable();

        ByteBuf outbound = UnpooledByteBufAllocator.DEFAULT.buffer(1).writeByte(1);
        Assertions.assertFalse(channel.writeOutbound(outbound));
        Assertions.assertEquals(1, outbound.refCnt());

        channel.close().syncUninterruptibly();
        Assertions.assertEquals(0, outbound.refCnt());
        channel.finishAndReleaseAll();
    }

    @Test
    public void testPeerStatsResetClearsMeasurementWindowCounters() {
        PeerStats peer = new PeerStats(0, false);
        peer.addBulkSent(64);
        peer.addBulkReceived(64);
        peer.addProbeSent();
        peer.addProbeAcked();
        peer.addProbeAckSpillover();
        peer.addDisconnect();
        peer.addBlackholedDatagramIn();
        peer.addBlackholedDatagramOut();
        peer.queuedBytes(128);

        peer.resetMeasurement();

        PeerStats.Snapshot snapshot = peer.snapshot();
        Assertions.assertEquals(0, snapshot.bulkSentMessages);
        Assertions.assertEquals(0, snapshot.bulkReceivedMessages);
        Assertions.assertEquals(0, snapshot.probesSent);
        Assertions.assertEquals(0, snapshot.probesAcked);
        Assertions.assertEquals(0, snapshot.probeAckSpillover);
        Assertions.assertEquals(0, snapshot.disconnects);
        Assertions.assertEquals(0, snapshot.blackholedDatagramsIn);
        Assertions.assertEquals(0, snapshot.blackholedDatagramsOut);
        Assertions.assertEquals(0, snapshot.maxQueuedBytes);
        Assertions.assertFalse(snapshot.channelOpen);
        Assertions.assertFalse(snapshot.channelActive);
    }

    @Test
    public void testFairnessCalculation() {
        Assertions.assertEquals(1.0D, BenchmarkMath.jainFairness(Arrays.asList(100L, 100L, 100L)), 0.001D);
        Assertions.assertTrue(BenchmarkMath.jainFairness(Arrays.asList(100L, 0L, 0L)) < 0.34D);
    }

    @Test
    public void testThroughputDistributionCalculation() {
        ThroughputDistribution distribution = ThroughputDistribution.fromBytes(
                Arrays.asList(125_000L, 250_000L, 500_000L),
                1000
        );

        Assertions.assertEquals(1.0D, distribution.minMbps(), 0.001D);
        Assertions.assertEquals(2.0D, distribution.p50Mbps(), 0.001D);
        Assertions.assertEquals(4.0D, distribution.p95Mbps(), 0.001D);
        Assertions.assertEquals(4.0D, distribution.p99Mbps(), 0.001D);
        Assertions.assertEquals(4.0D, distribution.maxMbps(), 0.001D);
    }

    @Test
    public void testStabilitySummaryGroupsByCaseName() {
        String summary = BenchmarkResultWriter.stabilitySummary(Arrays.asList(
                iteration("case-a", 1, 1000, 1.0D),
                iteration("case-a", 2, 1010, 1.0D),
                iteration("case-b", 1, 2000, 1.0D),
                iteration("case-b", 2, 1000, 2.0D)
        ));

        Assertions.assertTrue(summary.contains("| case-a | 2 |"));
        Assertions.assertTrue(summary.contains("| case-b | 2 |"));
        Assertions.assertTrue(summary.contains("| case-a | 2 | 10 | 1.000000 | 0.990099% | 0.000000% | true | `insufficient-iterations` |"));
        Assertions.assertTrue(summary.contains("| case-b | 2 | 10 | 1.000000 | 50.000000% | 50.000000% | true | `insufficient-iterations,throughput-spread,p99-spread` |"));
    }

    @Test
    public void testStabilitySummaryRejectsZeroDelivery() {
        String summary = BenchmarkResultWriter.stabilitySummary(Arrays.asList(
                iteration("case-zero", 1, 0, 0.0D),
                iteration("case-zero", 2, 0, 0.0D),
                iteration("case-zero", 3, 0, 0.0D)
        ));

        Assertions.assertTrue(summary.contains("| case-zero | 3 | 10 | 1.000000 | 0.000000% | 0.000000% | true | `zero-delivery` |"));
    }

    @Test
    public void testNetemPlannerDryRunCommands() {
        List<String> apply = NetemCommandPlanner.apply("eth0", "50ms", "5ms", "2%");
        Assertions.assertEquals("tc qdisc replace dev eth0 root netem delay 50ms 5ms loss 2%", apply.get(0));

        Assertions.assertEquals("tc qdisc del dev eth0 root", NetemCommandPlanner.clear("eth0").get(0));
        Assertions.assertEquals("tc qdisc show dev eth0", NetemCommandPlanner.status("eth0").get(0));
    }

    @Test
    public void testNetemShellDryRunAddsExplicitQueueLimit() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/raknet-netem.sh").toString(),
                "--interface", "eth0",
                "--action", "dry-run",
                "--latency", "200ms",
                "--jitter", "20ms",
                "--loss", "10%",
                "--limit", "10000"
        );

        Assertions.assertEquals(0, result.exitCode, result.output);
        Assertions.assertEquals(
                "tc qdisc replace dev eth0 root netem delay 200ms 20ms loss 10% limit 10000",
                result.output.trim());
    }

    @Test
    public void testPilotBaselineMatrixDryRunProducesRepresentativeManifest() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-pilot-matrix-test");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/run-baseline-matrix.sh").toString(),
                "--profile", "pilot",
                "--dry-run",
                "--out", output.toString()
        );
        Assertions.assertEquals(0, result.exitCode, result.output);

        List<String> manifest = Files.readAllLines(output.resolve("manifest.jsonl"), StandardCharsets.UTF_8);
        Assertions.assertEquals(7, manifest.size());
        Assertions.assertTrue(manifest.stream().allMatch(row -> row.contains("\"status\":\"dry-run\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-curve-1c-mtu\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-fanout-100x5\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-immediate-100x1-p256\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-fairness-100-10poor\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-disappear-100-blackhole\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-batch-100-20ms\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-resource-100-8k-200ms\"")));
    }

    @Test
    public void testBaselineMatrixAggregatesMockBenchmarkArtifacts() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-matrix-aggregate-test");
        Path suite = output.resolve("suite");
        Path mockGradle = output.resolve("mock-gradle.sh");
        Files.writeString(mockGradle, mockGradleScript(), StandardCharsets.UTF_8);
        Assertions.assertTrue(mockGradle.toFile().setExecutable(true));

        ProcessResult result = runProcess(root, Duration.ofSeconds(20),
                "bash",
                root.resolve("benchmark/scripts/run-baseline-matrix.sh").toString(),
                "--profile", "smoke",
                "--out", suite.toString(),
                "--gradle", mockGradle.toString()
        );
        Assertions.assertEquals(0, result.exitCode, result.output);

        List<JsonNode> summaryRows = readJsonLines(suite.resolve("suite-summary.jsonl"));
        Assertions.assertEquals(10, summaryRows.size());
        Assertions.assertTrue(summaryRows.stream().allMatch(JsonNode::isObject));
        Assertions.assertTrue(summaryRows.stream().allMatch(row -> row.path("openPeers").asInt() == row.path("clients").asInt()));
        Assertions.assertTrue(summaryRows.stream().allMatch(row -> row.path("activePeers").asInt() == row.path("clients").asInt()));
        Assertions.assertTrue(summaryRows.stream().anyMatch(row -> row.path("scenario").asText().equals("multi-client-fanout")));
        Assertions.assertTrue(summaryRows.stream().anyMatch(row -> row.path("scenario").asText().equals("fairness")));
        Assertions.assertTrue(summaryRows.stream().anyMatch(row -> row.path("scenario").asText().equals("disappearing-clients")));
        Assertions.assertTrue(summaryRows.stream().anyMatch(row -> row.path("scenario").asText().equals("batched-game-traffic")));
        Assertions.assertTrue(summaryRows.stream().anyMatch(row -> row.path("scenario").asText().equals("resource-pack-transfer")));
        Assertions.assertTrue(summaryRows.stream().allMatch(row -> row.has("undeliveredServerGbps")));
        Assertions.assertTrue(summaryRows.stream().allMatch(row -> row.has("affectedServerDatagramsOutPerSecond")));

        List<JsonNode> aggregateRows = readJsonLines(suite.resolve("suite-aggregate.jsonl"));
        Assertions.assertEquals(10, aggregateRows.size());
        Assertions.assertTrue(aggregateRows.stream().allMatch(JsonNode::isObject));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.path("summaryKind").asText().equals("aggregate")));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.has("activePeers")));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.has("stateDisconnectedPeers")));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.has("undeliveredServerGbps")));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.has("affectedUndeliveredServerGbps")));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.has("affectedServerDatagramsOutPerSecond")));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.path("probeReliability").asText()
                .equals("UNRELIABLE")));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.path("probePriority").asText()
                .equals("HIGH")));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.path("probesSent").asInt() == 10));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.path("probesAcked").asInt() == 10));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.path("minimumProbeResponses").asInt() == 10));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.path("minimumProbeResponseRate").asDouble()
                == 1.0D));
        Assertions.assertTrue(aggregateRows.stream().noneMatch(row -> row.path("unstableReasons").toString()
                .contains("probe-return")));
        Assertions.assertTrue(aggregateRows.stream().noneMatch(row -> row.path("unstableReasons").toString()
                .contains("probe-transport-provenance")));
        Assertions.assertTrue(aggregateRows.stream().allMatch(row -> row.path("unstableReasons").toString()
                .contains("insufficient-iterations")));
        Assertions.assertTrue(aggregateRows.stream().anyMatch(row -> row.path("case").asText().equals("curve-1c-mtu")
                && row.path("benchmarkName").asText().equals("curve-100_0mbps")
                && row.path("deliveredGbps").asDouble() > 0.09D));

        List<JsonNode> capacityRows = readJsonLines(suite.resolve("bandwidth-capacity.jsonl"));
        Assertions.assertEquals(1, capacityRows.size());
        JsonNode capacity = capacityRows.get(0);
        Assertions.assertEquals("bandwidth-capacity", capacity.path("summaryKind").asText());
        Assertions.assertFalse(capacity.path("selected").asBoolean());
        Assertions.assertTrue(capacity.path("selectedCandidate").isNull());
        Assertions.assertEquals("curve-100_0mbps", capacity.path("bestObservedCandidate").path("benchmarkName").asText());
        Assertions.assertEquals(10, capacity.path("bestObservedCandidate").path("probesSent").asInt());
        Assertions.assertEquals(1.0D,
                capacity.path("bestObservedCandidate").path("minimumProbeResponseRate").asDouble(), 0.000001D);
    }

    @Test
    public void testStableBandwidthSelectionFailsClosedOnCensoredProbeLatency() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-stable-bandwidth-probe-test");
        Path input = output.resolve("suite-aggregate.jsonl");
        String common = "\"summaryKind\":\"aggregate\",\"case\":\"curve\",\"clients\":1,"
                + "\"payloadSize\":1200,\"reliability\":\"RELIABLE_ORDERED\","
                + "\"probeReliability\":\"UNRELIABLE\",\"probePriority\":\"HIGH\","
                + "\"probeSemantics\":\"UNRELIABLE/HIGH best-effort non-ordering through the weighted scheduler; lost probes are omitted from RTT samples\",\"measuredIterations\":3,"
                + "\"disconnects\":0,\"maxQueuedBytes\":0,\"sentToDeliveredBytesRatio\":1,"
                + "\"nackOutPerSecond\":0,\"unstable\":false,\"unstableReasons\":[],";
        Files.writeString(input,
                "{" + common + "\"benchmarkName\":\"curve-missing\",\"targetMbps\":200,"
                        + "\"deliveredGbps\":0.2,\"probeRttP99Millis\":null,\"probesSent\":30,"
                        + "\"probesAcked\":0,\"probeAckSpillover\":0,\"probeResponseRate\":0,"
                        + "\"minimumProbeResponses\":0,\"minimumProbeResponseRate\":0}\n"
                        + "{" + common + "\"benchmarkName\":\"curve-valid\",\"targetMbps\":100,"
                        + "\"deliveredGbps\":0.1,\"probeRttP99Millis\":10,\"probesSent\":36,"
                        + "\"probesAcked\":30,\"probeAckSpillover\":0,\"probeResponseRate\":0.833333333333,"
                        + "\"minimumProbeResponses\":10,\"minimumProbeResponseRate\":0.833333333333}\n"
                        + "{" + common + "\"benchmarkName\":\"curve-low-return\",\"targetMbps\":300,"
                        + "\"deliveredGbps\":0.3,\"probeRttP99Millis\":5,\"probesSent\":300,"
                        + "\"probesAcked\":30,\"probeAckSpillover\":0,\"probeResponseRate\":0.1,"
                        + "\"minimumProbeResponses\":10,\"minimumProbeResponseRate\":0.1}\n"
                        + "{" + common + "\"benchmarkName\":\"curve-spillover\",\"targetMbps\":400,"
                        + "\"deliveredGbps\":0.4,\"probeRttP99Millis\":4,\"probesSent\":30,"
                        + "\"probesAcked\":30,\"probeAckSpillover\":3,\"probeResponseRate\":1,"
                        + "\"minimumProbeResponses\":10,\"minimumProbeResponseRate\":1}\n",
                StandardCharsets.UTF_8);

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/select-stable-bandwidth.sh").toString(),
                "--input", input.toString(),
                "--out", output.toString());
        Assertions.assertEquals(0, result.exitCode, result.output);

        JsonNode capacity = readJsonLines(output.resolve("bandwidth-capacity.jsonl")).get(0);
        Assertions.assertEquals("curve-valid",
                capacity.path("selectedCandidate").path("benchmarkName").asText());
        Assertions.assertEquals("UNRELIABLE", capacity.path("probeReliability").asText());
        Assertions.assertEquals("HIGH", capacity.path("probePriority").asText());
        Assertions.assertEquals("curve-valid",
                capacity.path("bestObservedCandidate").path("benchmarkName").asText(),
                "missing or insufficient-return latency evidence must sort after valid evidence");
        Assertions.assertEquals(36, capacity.path("selectedCandidate").path("probesSent").asInt());
        Assertions.assertEquals(30, capacity.path("selectedCandidate").path("probesAcked").asInt());
        Assertions.assertEquals(0, capacity.path("selectedCandidate").path("probeAckSpillover").asInt());

        JsonNode missing = candidate(capacity.path("rejectedCandidates"), "curve-missing");
        Assertions.assertTrue(missing.path("probeRttP99Millis").isNull());
        Assertions.assertTrue(missing.path("rejectionReasons").toString().contains("missing-probe-p99"));
        Assertions.assertTrue(missing.path("rejectionReasons").toString()
                .contains("insufficient-probe-responses"));
        JsonNode lowReturn = candidate(capacity.path("rejectedCandidates"), "curve-low-return");
        Assertions.assertFalse(lowReturn.path("rejectionReasons").toString()
                .contains("insufficient-probe-responses"));
        Assertions.assertTrue(lowReturn.path("rejectionReasons").toString()
                .contains("insufficient-probe-return-rate"));
        JsonNode spillover = candidate(capacity.path("rejectedCandidates"), "curve-spillover");
        Assertions.assertTrue(spillover.path("rejectionReasons").toString().contains("probe-ack-spillover"));

        String csv = Files.readString(output.resolve("bandwidth-capacity.csv"), StandardCharsets.UTF_8);
        Assertions.assertTrue(csv.lines().findFirst().orElseThrow()
                .contains("probe_reliability,probe_priority,probe_semantics"));
        Assertions.assertTrue(csv.lines().findFirst().orElseThrow().contains("selected_probes_sent"));
        Assertions.assertTrue(csv.lines().findFirst().orElseThrow().contains("best_observed_probe_response_rate"));
        String report = Files.readString(output.resolve("bandwidth-capacity.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(report.contains("Stable probes ACKed/sent"));
        Assertions.assertTrue(report.contains("30/36"));

        Path invalidInput = output.resolve("invalid-provenance.jsonl");
        Path invalidOutput = output.resolve("invalid-provenance");
        Files.writeString(invalidInput,
                "{" + common.replace("\"probePriority\":\"HIGH\"", "\"probePriority\":\"LOW\"")
                        + "\"benchmarkName\":\"curve-invalid-provenance\",\"targetMbps\":100,"
                        + "\"deliveredGbps\":0.1,\"probeRttP99Millis\":10,\"probesSent\":30,"
                        + "\"probesAcked\":30,\"probeAckSpillover\":-1,\"probeResponseRate\":1,"
                        + "\"minimumProbeResponses\":10,\"minimumProbeResponseRate\":1}\n",
                StandardCharsets.UTF_8);
        ProcessResult invalidResult = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/select-stable-bandwidth.sh").toString(),
                "--input", invalidInput.toString(),
                "--out", invalidOutput.toString());
        Assertions.assertEquals(0, invalidResult.exitCode, invalidResult.output);
        JsonNode invalidCapacity = readJsonLines(invalidOutput.resolve("bandwidth-capacity.jsonl")).get(0);
        Assertions.assertFalse(invalidCapacity.path("selected").asBoolean());
        Assertions.assertTrue(invalidCapacity.path("bestObservedCandidate").path("rejectionReasons").toString()
                .contains("invalid-probe-transport-provenance"));
        Assertions.assertTrue(invalidCapacity.path("bestObservedCandidate").path("rejectionReasons").toString()
                .contains("invalid-probe-ack-spillover"));
    }

    @Test
    public void testRemoteContentionPlannerExpandsResourcePackRows() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-resource-contention-test");
        Path plan = output.resolve("plan");
        Path artifacts = output.resolve("artifacts");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/plan-remote-contention.sh").toString(),
                "--out", plan.toString(),
                "--artifact-root", artifacts.toString(),
                "--server-host", "127.0.0.1",
                "--clients", "4",
                "--cases", "resource-pack,batched,disappear-blackhole",
                "--reliability", "reliable",
                "--resource-pack-chunk-sizes", "8192,262144",
                "--resource-pack-interval", "200ms",
                "--batch-intervals", "20ms",
                "--batch-payload-sizes", "128,512,1200",
                "--logical-packets-per-batch", "8",
                "--batch-groups", "4",
                "--disappearing-clients", "1",
                "--disappear-after", "1s",
                "--warmup", "1s",
                "--duration", "2s",
                "--iterations", "1",
                "--start-delay", "1s",
                "--start-offset", "180s"
        );
        Assertions.assertEquals(0, result.exitCode, result.output);

        List<String> manifest = Files.readAllLines(plan.resolve("manifest.jsonl"), StandardCharsets.UTF_8);
        Assertions.assertEquals(4, manifest.size());
        Assertions.assertTrue(manifest.stream().allMatch(row -> row.contains("\"reliability\":\"reliable\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"benchmarkName\":\"batched-game-traffic\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"benchmarkName\":\"batched-game-traffic\"")
                && row.contains("\"batchIntervalMillis\":20")
                && row.contains("\"logicalPacketsPerBatch\":8")
                && row.contains("\"batchGroups\":4")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"payloadSize\":8192")
                && row.contains("\"perClientMbps\":0.327680000")
                && row.contains("\"batchIntervalMillis\":200")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"payloadSize\":262144")
                && row.contains("\"perClientMbps\":10.485760000")
                && row.contains("\"batchIntervalMillis\":200")));
        JsonNode blackhole = readJsonLines(plan.resolve("manifest.jsonl")).stream()
                .filter(row -> row.path("affectedKind").asText().equals("disappearing-blackhole"))
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals("blackhole", blackhole.path("disappearanceMode").asText());
        Assertions.assertEquals(1, blackhole.path("affectedClients").asInt());
        Assertions.assertEquals(1000, blackhole.path("warmupMillis").asLong());
        Assertions.assertEquals(2000, blackhole.path("durationMillis").asLong());
        Assertions.assertEquals(1000, blackhole.path("disappearAfterMillis").asLong());
        Assertions.assertTrue(blackhole.path("blackholeAtEpochMillis").asLong()
                > blackhole.path("startAtEpochMillis").asLong());

        String serverCommands = Files.readString(plan.resolve("server-commands.sh"), StandardCharsets.UTF_8);
        Assertions.assertTrue(serverCommands.contains("batched-game-traffic --role server"));
        Assertions.assertTrue(serverCommands.contains("--reliability reliable"));
        Assertions.assertTrue(serverCommands.contains("--batch-interval 20ms --logical-packets-per-batch 8"));
        Assertions.assertTrue(serverCommands.contains("resource-pack-transfer --role server"));
        Assertions.assertTrue(serverCommands.contains("--chunk-size 8192 --chunk-interval 200ms"));
        Assertions.assertTrue(serverCommands.contains("--chunk-size 262144 --chunk-interval 200ms"));
        Assertions.assertTrue(serverCommands.contains("disappearing-clients --role server"));
        Assertions.assertTrue(serverCommands.contains("--disappear-mode blackhole"));
    }

    @Test
    public void testLabHandoffGeneratorProducesBaselineAndImpairmentPlans() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-handoff-test");
        Path handoff = output.resolve("handoff");
        Path artifacts = output.resolve("artifacts");
        Path sourceAudit = output.resolve("source-audit.json");
        Duration handoffCheckTimeout = Duration.ofSeconds(60);
        writeReadySourceAudit(sourceAudit);

        ProcessResult result = runProcess(root, Duration.ofSeconds(30),
                "bash",
                root.resolve("benchmark/scripts/prepare-lab-baseline-handoff.sh").toString(),
                "--out", handoff.toString(),
                "--artifact-root", artifacts.toString(),
                "--source-audit", sourceAudit.toString(),
                "--server-host", "127.0.0.1",
                "--interface", "lo",
                "--expect-mtu", "1500",
                "--expect-min-cpus", "2",
                "--curve-receiver", "receiver-a=1",
                "--contention-receiver", "receiver-a=2",
                "--profiles", "perfect,near-loss",
                "--contention-cases", "fanout",
                "--contention-payload-size", "64",
                "--per-client-mbps", "1",
                "--raised-packet-limit", "1000",
                "--raised-global-packet-limit", "10000",
                "--max-queued-bytes", "1048576",
                "--warmup", "1s",
                "--duration", "1s",
                "--iterations", "1",
                "--start-delay", "1s",
                "--start-offset", "180s"
        );

        Assertions.assertEquals(0, result.exitCode, result.output);
        Assertions.assertTrue(Files.exists(handoff.resolve("perfect-plan/check-plan-freshness.sh")));
        Assertions.assertTrue(Files.exists(handoff.resolve("perfect-plan/merge-all.sh")));
        Assertions.assertTrue(Files.isExecutable(handoff.resolve("perfect-plan/curve-plan/server-commands.sh")));
        Assertions.assertTrue(Files.isExecutable(handoff.resolve("perfect-plan/curve-plan/receiver-receiver-a-commands.sh")));
        Assertions.assertTrue(Files.isExecutable(handoff.resolve("perfect-plan/curve-plan/merge-commands.sh")));
        Assertions.assertTrue(Files.isExecutable(handoff.resolve("perfect-plan/curve-raised-plan/server-commands.sh")));
        Assertions.assertTrue(Files.isExecutable(handoff.resolve("perfect-plan/curve-raised-plan/receiver-receiver-a-commands.sh")));
        Assertions.assertTrue(Files.isExecutable(handoff.resolve("perfect-plan/curve-raised-plan/merge-commands.sh")));
        Assertions.assertTrue(Files.isExecutable(handoff.resolve("perfect-plan/contention-plan/server-commands.sh")));
        Assertions.assertTrue(Files.isExecutable(handoff.resolve("perfect-plan/contention-plan/receiver-receiver-a-commands.sh")));
        Assertions.assertTrue(Files.isExecutable(handoff.resolve("perfect-plan/contention-plan/merge-commands.sh")));
        Assertions.assertTrue(Files.exists(handoff.resolve("impairment-plan/check-plan-freshness.sh")));
        Assertions.assertTrue(Files.exists(handoff.resolve("impairment-plan/validate-all.sh")));
        Assertions.assertTrue(Files.exists(handoff.resolve("impairment-plan/summarize-campaign.sh")));
        Assertions.assertTrue(Files.exists(handoff.resolve("impairment-plan/manifest.jsonl")));
        Assertions.assertTrue(Files.exists(handoff.resolve("handoff-manifest.json")));
        Path promoteScript = handoff.resolve("promote-and-check.sh");
        Path prereqScript = handoff.resolve("prereq-commands.sh");
        Path artifactCollectionJson = handoff.resolve("artifact-collection.json");
        Path artifactCollectionMd = handoff.resolve("artifact-collection.md");
        Assertions.assertTrue(Files.exists(promoteScript));
        Assertions.assertTrue(Files.isExecutable(promoteScript));
        Assertions.assertTrue(Files.exists(prereqScript));
        Assertions.assertTrue(Files.isExecutable(prereqScript));
        Assertions.assertTrue(Files.exists(artifactCollectionJson));
        Assertions.assertTrue(Files.exists(artifactCollectionMd));
        Assertions.assertTrue(result.output.contains("Handoff manifest:"));
        Assertions.assertTrue(result.output.contains("Artifact collection JSON:"));
        Assertions.assertTrue(result.output.contains("Artifact collection checklist:"));
        Assertions.assertTrue(result.output.contains("Prereq helper:"));
        Assertions.assertTrue(result.output.contains("Promotion/readiness helper:"));

        ProcessResult promoteSyntax = runProcess(root, Duration.ofSeconds(10),
                "bash",
                "-n",
                promoteScript.toString()
        );
        Assertions.assertEquals(0, promoteSyntax.exitCode, promoteSyntax.output);
        ProcessResult prereqSyntax = runProcess(root, Duration.ofSeconds(10),
                "bash",
                "-n",
                prereqScript.toString()
        );
        Assertions.assertEquals(0, prereqSyntax.exitCode, prereqSyntax.output);
        ProcessResult prereqRoles = runProcess(root, Duration.ofSeconds(10),
                "bash",
                prereqScript.toString(),
                "--list-roles"
        );
        Assertions.assertEquals(0, prereqRoles.exitCode, prereqRoles.output);
        Assertions.assertTrue(prereqRoles.output.contains("server"));
        Assertions.assertTrue(prereqRoles.output.contains("receiver-a"));
        ProcessResult prereqPrint = runProcess(root, Duration.ofSeconds(10),
                "bash",
                prereqScript.toString(),
                "--role", "receiver-a",
                "--print-command"
        );
        Assertions.assertEquals(0, prereqPrint.exitCode, prereqPrint.output);
        Assertions.assertTrue(prereqPrint.output.contains("check-lab-host-prereqs.sh"));
        Assertions.assertTrue(prereqPrint.output.contains("--host-role receiver-a"));
        Assertions.assertTrue(prereqPrint.output.contains("--expect-mtu 1500"));
        Assertions.assertTrue(prereqPrint.output.contains("--expect-min-cpus 2"));
        Assertions.assertTrue(prereqPrint.output.contains("--require-clock-sync"));
        Assertions.assertTrue(prereqPrint.output.contains("--require-no-netem"));

        String readme = Files.readString(handoff.resolve("README.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(readme.contains("RakNet Lab Baseline Handoff"));
        Assertions.assertTrue(readme.contains("check-lab-handoff.sh"));
        Assertions.assertTrue(readme.contains("handoff-manifest.json"));
        Assertions.assertTrue(readme.contains("artifact-collection.json"));
        Assertions.assertTrue(readme.contains("artifact-collection.md"));
        Assertions.assertTrue(readme.contains("Prereq helper: `" + prereqScript + "`"));
        Assertions.assertTrue(readme.contains("Promotion/readiness helper: `" + promoteScript + "`"));
        Assertions.assertTrue(readme.contains("prereq-commands.sh"));
        Assertions.assertTrue(readme.contains("Valid roles for this handoff are `server,receiver-a`"));
        Assertions.assertTrue(readme.contains("promote-and-check.sh"));
        Assertions.assertTrue(readme.contains("promote-lab-baseline.sh"));
        Assertions.assertTrue(readme.contains("--min-contention-clients \"2\""));
        Assertions.assertTrue(readme.contains("--min-contention-target-client-mbps \"1\""));
        Assertions.assertTrue(readme.contains("check-lab-host-prereqs.sh"));
        Assertions.assertTrue(readme.contains("--expect-mtu 1500"));
        Assertions.assertTrue(readme.contains("--expect-min-cpus 2"));
        Assertions.assertTrue(readme.contains("--require-clock-sync"));
        Assertions.assertTrue(readme.contains("--require-no-netem"));
        Assertions.assertTrue(readme.contains("check-baseline-readiness.sh"));
        Assertions.assertTrue(readme.contains("--handoff \"" + handoff + "\""));
        Assertions.assertTrue(readme.contains("--required-min-contention-clients \"2\""));
        Assertions.assertTrue(readme.contains("--required-min-contention-target-client-mbps \"1\""));
        Assertions.assertTrue(readme.contains("Production evidence document: `benchmark/docs/production-usage-evidence.md`"));
        Assertions.assertTrue(readme.contains("Production evidence SHA-256: `"));
        Assertions.assertTrue(readme.contains("Production source audit: `" + sourceAudit + "`"));
        Assertions.assertTrue(readme.contains("Production source audit SHA-256: `"));
        Assertions.assertTrue(readme.contains("Reliability: `reliable_ordered`"));
        Assertions.assertTrue(readme.contains("--handoff-manifest \"" + handoff.resolve("handoff-manifest.json") + "\""));

        String promoteScriptContent = Files.readString(promoteScript, StandardCharsets.UTF_8);
        Assertions.assertTrue(promoteScriptContent.contains("check-lab-handoff.sh"));
        Assertions.assertTrue(promoteScriptContent.contains("--handoff \"" + handoff + "\""));
        Assertions.assertTrue(promoteScriptContent.contains("--out \"$PREFLIGHT_OUT\" --require-source-audit"));
        Assertions.assertTrue(promoteScriptContent.contains("promote-lab-baseline.sh"));
        Assertions.assertTrue(promoteScriptContent.contains("--handoff-manifest \"" + handoff.resolve("handoff-manifest.json") + "\""));
        Assertions.assertTrue(promoteScriptContent.contains("--manifest \"" + handoff.resolve("perfect-plan/curve-plan/manifest.jsonl") + "\""));
        Assertions.assertTrue(promoteScriptContent.contains("--manifest \"" + handoff.resolve("perfect-plan/curve-raised-plan/manifest.jsonl") + "\""));
        Assertions.assertTrue(promoteScriptContent.contains("--manifest \"" + handoff.resolve("perfect-plan/contention-plan/manifest.jsonl") + "\""));
        Assertions.assertTrue(promoteScriptContent.contains("--input \"" + artifacts.resolve("perfect/combined") + "\""));
        Assertions.assertTrue(promoteScriptContent.contains("--input \"" + artifacts.resolve("impairment/campaign-summary") + "\""));
        Assertions.assertTrue(promoteScriptContent.contains("check-baseline-readiness.sh"));
        Assertions.assertTrue(promoteScriptContent.contains("--handoff \"" + handoff + "\""));
        Assertions.assertTrue(promoteScriptContent.contains("--required-min-contention-clients \"2\""));
        Assertions.assertTrue(promoteScriptContent.contains("--required-min-contention-target-client-mbps \"1\""));

        JsonNode handoffManifest = JSON.readTree(Files.readString(handoff.resolve("handoff-manifest.json"),
                StandardCharsets.UTF_8));
        Assertions.assertEquals("raknet-lab-handoff", handoffManifest.path("kind").asText());
        Assertions.assertEquals(root.toString(), handoffManifest.path("repoRoot").asText());
        Assertions.assertEquals(handoff.toString(), handoffManifest.path("outputRoot").asText());
        Assertions.assertEquals(artifacts.toString(), handoffManifest.path("artifactRoot").asText());
        Assertions.assertEquals(promoteScript.toString(), handoffManifest.path("promoteScript").asText());
        Assertions.assertEquals(prereqScript.toString(), handoffManifest.path("prereqScript").asText());
        Assertions.assertEquals(artifactCollectionJson.toString(), handoffManifest.path("artifactCollectionJson").asText());
        Assertions.assertEquals(artifactCollectionMd.toString(), handoffManifest.path("artifactCollectionMd").asText());
        Assertions.assertEquals(handoff.resolve("perfect-plan").toString(), handoffManifest.path("perfectPlan").asText());
        Assertions.assertEquals(handoff.resolve("impairment-plan").toString(),
                handoffManifest.path("impairmentPlan").asText());
        Assertions.assertEquals(artifacts.resolve("perfect").toString(),
                handoffManifest.path("perfectArtifacts").asText());
        Assertions.assertEquals(artifacts.resolve("impairment").toString(),
                handoffManifest.path("impairmentArtifacts").asText());
        Assertions.assertEquals("benchmark/docs/production-usage-evidence.md",
                handoffManifest.path("productionEvidence").path("document").asText());
        Assertions.assertTrue(handoffManifest.path("productionEvidence").path("exists").asBoolean());
        Assertions.assertTrue(handoffManifest.path("productionEvidence").path("sha256").asText()
                .matches("[0-9a-f]{64}"));
        Assertions.assertEquals(sourceAudit.toString(), handoffManifest.path("sourceAudit").path("document").asText());
        Assertions.assertTrue(handoffManifest.path("sourceAudit").path("ready").asBoolean());
        Assertions.assertEquals(0, handoffManifest.path("sourceAudit").path("issueCount").asInt());
        Assertions.assertFalse(handoffManifest.path("sourceAudit").path("networkDirtyTrackedFiles").asBoolean());
        Assertions.assertTrue(handoffManifest.path("sourceAudit").path("sha256").asText()
                .matches("[0-9a-f]{64}"));
        Assertions.assertEquals(2, handoffManifest.path("sourceAudit").path("sources").size());
        Assertions.assertTrue(handoffManifest.path("sourceAudit").path("sources").get(0).path("pathIncluded").isBoolean());
        Assertions.assertEquals("127.0.0.1", handoffManifest.path("serverHost").asText());
        Assertions.assertEquals("0.0.0.0", handoffManifest.path("bindHost").asText());
        Assertions.assertEquals(19132, handoffManifest.path("port").asInt());
        Assertions.assertEquals("lo", handoffManifest.path("interface").asText());
        Assertions.assertEquals(1500, handoffManifest.path("expectedMtu").asInt());
        Assertions.assertEquals(2, handoffManifest.path("expectedMinCpus").asInt());
        Assertions.assertFalse(handoffManifest.path("requireCpuPerformance").asBoolean());
        Assertions.assertEquals("receiver-a", handoffManifest.path("targetHostRole").asText());
        Assertions.assertEquals(2, handoffManifest.path("prereqRoles").size());
        Assertions.assertEquals("server", handoffManifest.path("prereqRoles").get(0).asText());
        Assertions.assertEquals("receiver-a", handoffManifest.path("prereqRoles").get(1).asText());
        Assertions.assertEquals(2, handoffManifest.path("profiles").size());
        Assertions.assertEquals("perfect", handoffManifest.path("profiles").get(0).asText());
        Assertions.assertEquals("near-loss", handoffManifest.path("profiles").get(1).asText());
        Assertions.assertEquals(1, handoffManifest.path("curveReceivers").size());
        Assertions.assertEquals("receiver-a=1", handoffManifest.path("curveReceivers").get(0).asText());
        Assertions.assertEquals(7, handoffManifest.path("curvePayloadSizes").size());
        Assertions.assertEquals(64, handoffManifest.path("curvePayloadSizes").get(0).asInt());
        Assertions.assertEquals(262144, handoffManifest.path("curvePayloadSizes").get(6).asInt());
        Assertions.assertEquals(8, handoffManifest.path("curveRatesMbps").size());
        Assertions.assertEquals("100", handoffManifest.path("curveRatesMbps").get(0).asText());
        Assertions.assertEquals("unlimited", handoffManifest.path("curveRatesMbps").get(7).asText());
        Assertions.assertEquals(1, handoffManifest.path("contentionReceivers").size());
        Assertions.assertEquals("receiver-a=2", handoffManifest.path("contentionReceivers").get(0).asText());
        Assertions.assertEquals(2, handoffManifest.path("contentionClientTotal").asInt());
        Assertions.assertEquals(1, handoffManifest.path("contentionCases").size());
        Assertions.assertEquals("fanout", handoffManifest.path("contentionCases").get(0).asText());
        Assertions.assertEquals(3, handoffManifest.path("batchIntervals").size());
        Assertions.assertEquals("10ms", handoffManifest.path("batchIntervals").get(0).asText());
        Assertions.assertEquals(3, handoffManifest.path("batchPayloadSizes").size());
        Assertions.assertEquals(128, handoffManifest.path("batchPayloadSizes").get(0).asInt());
        Assertions.assertEquals(8, handoffManifest.path("logicalPacketsPerBatch").asInt());
        Assertions.assertEquals(4, handoffManifest.path("batchGroups").asInt());
        Assertions.assertEquals(2, handoffManifest.path("resourcePackChunkSizes").size());
        Assertions.assertEquals(8192, handoffManifest.path("resourcePackChunkSizes").get(0).asInt());
        Assertions.assertEquals(262144, handoffManifest.path("resourcePackChunkSizes").get(1).asInt());
        Assertions.assertEquals("200ms", handoffManifest.path("resourcePackInterval").asText());
        Assertions.assertEquals(64, handoffManifest.path("contentionPayloadSize").asInt());
        Assertions.assertEquals("reliable_ordered", handoffManifest.path("reliability").asText());
        Assertions.assertEquals(1.0D, handoffManifest.path("perClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(256, handoffManifest.path("immediatePayloadSize").asInt());
        Assertions.assertEquals(1.0D, handoffManifest.path("immediatePerClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(1000, handoffManifest.path("raisedPacketLimit").asInt());
        Assertions.assertEquals(10000, handoffManifest.path("raisedGlobalPacketLimit").asInt());
        Assertions.assertEquals(1048576, handoffManifest.path("maxQueuedBytes").asInt());
        Assertions.assertEquals("1s", handoffManifest.path("warmup").asText());
        Assertions.assertEquals("1s", handoffManifest.path("duration").asText());
        Assertions.assertEquals(1, handoffManifest.path("iterations").asInt());
        Assertions.assertEquals("1s", handoffManifest.path("startDelay").asText());
        Assertions.assertEquals("180s", handoffManifest.path("startOffset").asText());
        Assertions.assertFalse(handoffManifest.path("sudoNetem").asBoolean());

        JsonNode artifactCollection = JSON.readTree(Files.readString(artifactCollectionJson, StandardCharsets.UTF_8));
        Assertions.assertEquals("raknet-lab-artifact-collection", artifactCollection.path("kind").asText());
        Assertions.assertEquals(handoff.resolve("handoff-manifest.json").toString(),
                artifactCollection.path("handoffManifest").asText());
        Assertions.assertEquals(artifacts.resolve("perfect").toString(),
                artifactCollection.path("perfectArtifacts").asText());
        Assertions.assertEquals(artifacts.resolve("impairment").toString(),
                artifactCollection.path("impairmentArtifacts").asText());
        Assertions.assertTrue(textValues(artifactCollection.path("requiredBeforePromotion"))
                .contains("perfect-combined-artifacts"));
        Assertions.assertTrue(textValues(artifactCollection.path("requiredBeforePromotion"))
                .contains("impairment-netem-evidence"));
        Assertions.assertTrue(artifactCollection.path("collectionGroups").findValuesAsText("id")
                .contains("perfect-prereq-reports"));
        Assertions.assertTrue(artifactCollection.path("collectionGroups").findValuesAsText("id")
                .contains("impairment-campaign-summary"));
        Assertions.assertEquals(2, artifactCollection.path("collectionGroups").findValues("profiles").get(0).size());
        Assertions.assertTrue(Files.readString(artifactCollectionMd, StandardCharsets.UTF_8)
                .contains("RakNet Lab Artifact Collection"));

        List<String> profiles = Files.readAllLines(handoff.resolve("impairment-plan/manifest.jsonl"), StandardCharsets.UTF_8);
        Assertions.assertEquals(2, profiles.size());
        Assertions.assertTrue(profiles.get(0).contains("\"profile\":\"perfect\""));
        Assertions.assertTrue(profiles.get(1).contains("\"profile\":\"near-loss\""));

        List<String> defaultCurveRows = Files.readAllLines(handoff.resolve("perfect-plan/curve-plan/manifest.jsonl"),
                StandardCharsets.UTF_8);
        Assertions.assertEquals(56, defaultCurveRows.size());
        Assertions.assertTrue(defaultCurveRows.stream().allMatch(row -> row.contains("\"reliability\":\"reliable_ordered\"")));
        Assertions.assertTrue(defaultCurveRows.stream().anyMatch(row -> row.contains("\"payloadSize\":64")));
        Assertions.assertTrue(defaultCurveRows.stream().anyMatch(row -> row.contains("\"payloadSize\":262144")));

        List<String> raisedCurveRows = Files.readAllLines(handoff.resolve("perfect-plan/curve-raised-plan/manifest.jsonl"),
                StandardCharsets.UTF_8);
        Assertions.assertEquals(56, raisedCurveRows.size());
        Assertions.assertTrue(raisedCurveRows.stream().allMatch(row -> row.contains("\"reliability\":\"reliable_ordered\"")));
        Assertions.assertTrue(raisedCurveRows.stream().anyMatch(row -> row.contains("\"payloadSize\":64")));
        Assertions.assertTrue(raisedCurveRows.stream().anyMatch(row -> row.contains("\"payloadSize\":262144")));

        List<String> contentionRows = Files.readAllLines(handoff.resolve("perfect-plan/contention-plan/manifest.jsonl"),
                StandardCharsets.UTF_8);
        Assertions.assertTrue(contentionRows.stream().allMatch(row -> row.contains("\"reliability\":\"reliable_ordered\"")));

        Path defaultHandoffPreflight = output.resolve("handoff-preflight-default");
        ProcessResult defaultHandoffCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", defaultHandoffPreflight.toString()
        );
        Assertions.assertEquals(1, defaultHandoffCheck.exitCode, defaultHandoffCheck.output);
        JsonNode defaultHandoffCheckJson = JSON.readTree(Files.readString(
                defaultHandoffPreflight.resolve("handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(defaultHandoffCheckJson.path("ready").asBoolean());
        Assertions.assertEquals(500, defaultHandoffCheckJson.path("requiredMinContentionClients").asInt());
        Assertions.assertEquals(5.0D, defaultHandoffCheckJson.path("requiredMinContentionTargetClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals("reliable_ordered", defaultHandoffCheckJson.path("expectedReliability").asText());
        Assertions.assertTrue(defaultHandoffCheckJson.findValuesAsText("code")
                .contains("handoff-contention-clients-below-threshold"));
        Assertions.assertTrue(defaultHandoffCheckJson.findValuesAsText("code")
                .contains("handoff-contention-target-client-mbps-below-threshold"));
        Assertions.assertTrue(defaultHandoffCheckJson.findValuesAsText("code")
                .contains("handoff-iterations-below-threshold"));

        Path handoffPreflight = output.resolve("handoff-preflight");
        ProcessResult handoffCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", handoffPreflight.toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1",
                "--required-min-iterations", "1",
                "--require-source-audit"
        );
        Assertions.assertEquals(0, handoffCheck.exitCode, handoffCheck.output);
        JsonNode handoffCheckJson = JSON.readTree(Files.readString(handoffPreflight.resolve("handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(handoffCheckJson.path("ready").asBoolean());
        Assertions.assertEquals(0, handoffCheckJson.path("issueCount").asInt());
        Assertions.assertEquals(56, handoffCheckJson.path("expectedCurveRowsPerCurvePlan").asInt());
        Assertions.assertEquals(56, handoffCheckJson.path("actualPerfectCurveRows").asInt());
        Assertions.assertEquals(56, handoffCheckJson.path("actualPerfectRaisedCurveRows").asInt());
        Assertions.assertEquals(1, handoffCheckJson.path("actualPerfectContentionRows").asInt());
        Assertions.assertEquals(2, handoffCheckJson.path("expectedProfiles").size());
        Assertions.assertEquals(2, handoffCheckJson.path("expectedContentionClients").asInt());
        Assertions.assertEquals("reliable_ordered", handoffCheckJson.path("expectedReliability").asText());
        Assertions.assertEquals(1.0D, handoffCheckJson.path("expectedPerClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(1, handoffCheckJson.path("expectedIterations").asInt());
        Assertions.assertEquals(1, handoffCheckJson.path("requiredMinIterations").asInt());
        Assertions.assertEquals(1500, handoffCheckJson.path("expectedMtu").asInt());
        Assertions.assertEquals(2, handoffCheckJson.path("expectedMinCpus").asInt());
        Assertions.assertTrue(textValues(handoffCheckJson.path("expectedPrereqRoles")).contains("server"));
        Assertions.assertTrue(textValues(handoffCheckJson.path("expectedPrereqRoles")).contains("receiver-a"));
        Assertions.assertEquals(textValues(handoffCheckJson.path("expectedPrereqRoles")),
                textValues(handoffCheckJson.path("manifestPrereqRoles")));
        Assertions.assertEquals(textValues(handoffCheckJson.path("expectedPrereqRoles")),
                textValues(handoffCheckJson.path("helperPrereqRoles")));
        Assertions.assertEquals(artifactCollectionJson.toString(),
                handoffCheckJson.path("artifactCollectionJson").asText());
        Assertions.assertEquals(artifactCollectionMd.toString(),
                handoffCheckJson.path("artifactCollectionMd").asText());
        Assertions.assertTrue(textValues(handoffCheckJson.path("artifactCollectionGroups"))
                .contains("perfect-worker-artifacts"));
        Assertions.assertTrue(textValues(handoffCheckJson.path("artifactCollectionGroups"))
                .contains("impairment-netem-evidence"));
        Assertions.assertEquals("benchmark/docs/production-usage-evidence.md",
                handoffCheckJson.path("productionEvidence").path("document").asText());
        Assertions.assertTrue(handoffCheckJson.path("productionEvidence").path("sha256").asText()
                .matches("[0-9a-f]{64}"));
        Assertions.assertEquals(handoffCheckJson.path("productionEvidence").path("sha256").asText(),
                handoffCheckJson.path("productionEvidenceActualSha256").asText());
        Assertions.assertTrue(handoffCheckJson.path("requireSourceAudit").asBoolean());
        Assertions.assertFalse(handoffCheckJson.path("requireCurrentRevision").asBoolean());
        Assertions.assertTrue(handoffCheckJson.path("sourceAudit").path("ready").asBoolean());
        Assertions.assertTrue(handoffCheckJson.path("sourceAuditActualReady").asBoolean());
        Assertions.assertEquals(handoffCheckJson.path("sourceAudit").path("sha256").asText(),
                handoffCheckJson.path("sourceAuditActualSha256").asText());
        Assertions.assertEquals(2, handoffCheckJson.path("actualImpairmentProfileRows").size());
        for (JsonNode profileRows : handoffCheckJson.path("actualImpairmentProfileRows")) {
            Assertions.assertEquals(56, profileRows.path("curveRows").asInt());
            Assertions.assertEquals(56, profileRows.path("raisedCurveRows").asInt());
            Assertions.assertEquals(1, profileRows.path("contentionRows").asInt());
        }

        Path prereqRoleManifestPath = handoff.resolve("handoff-manifest.json");
        String originalPrereqRoleManifest = Files.readString(prereqRoleManifestPath, StandardCharsets.UTF_8);
        ObjectNode tamperedManifest = (ObjectNode) JSON.readTree(originalPrereqRoleManifest);
        ArrayNode tamperedRoles = JSON.createArrayNode();
        tamperedRoles.add("server");
        tamperedManifest.set("prereqRoles", tamperedRoles);
        Files.writeString(prereqRoleManifestPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tamperedManifest),
                StandardCharsets.UTF_8);
        ProcessResult tamperedPrereqRolesCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-prereq-roles-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1",
                "--required-min-iterations", "1",
                "--require-source-audit"
        );
        Assertions.assertEquals(1, tamperedPrereqRolesCheck.exitCode, tamperedPrereqRolesCheck.output);
        JsonNode tamperedPrereqRolesJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-prereq-roles-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(tamperedPrereqRolesJson.findValuesAsText("code")
                .contains("handoff-prereq-role-missing"));
        Files.writeString(prereqRoleManifestPath, originalPrereqRoleManifest, StandardCharsets.UTF_8);

        String originalArtifactCollection = Files.readString(artifactCollectionJson, StandardCharsets.UTF_8);
        Files.delete(artifactCollectionJson);
        ProcessResult missingArtifactCollectionCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-artifact-collection-missing").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1",
                "--required-min-iterations", "1",
                "--require-source-audit"
        );
        Assertions.assertEquals(1, missingArtifactCollectionCheck.exitCode, missingArtifactCollectionCheck.output);
        JsonNode missingArtifactCollectionJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-artifact-collection-missing/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(missingArtifactCollectionJson.findValuesAsText("code")
                .contains("missing-path"));
        Files.writeString(artifactCollectionJson, originalArtifactCollection, StandardCharsets.UTF_8);

        ObjectNode tamperedArtifactCollection = (ObjectNode) JSON.readTree(originalArtifactCollection);
        ArrayNode groupsWithoutNetem = JSON.createArrayNode();
        for (JsonNode group : tamperedArtifactCollection.path("collectionGroups")) {
            if (!"impairment-netem-evidence".equals(group.path("id").asText())) {
                groupsWithoutNetem.add(group);
            }
        }
        tamperedArtifactCollection.set("collectionGroups", groupsWithoutNetem);
        Files.writeString(artifactCollectionJson, JSON.writeValueAsString(tamperedArtifactCollection),
                StandardCharsets.UTF_8);
        ProcessResult tamperedArtifactCollectionCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-artifact-collection-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1",
                "--required-min-iterations", "1",
                "--require-source-audit"
        );
        Assertions.assertEquals(1, tamperedArtifactCollectionCheck.exitCode,
                tamperedArtifactCollectionCheck.output);
        JsonNode tamperedArtifactCollectionJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-artifact-collection-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(tamperedArtifactCollectionJson.findValuesAsText("code")
                .contains("artifact-collection-group-missing"));
        Files.writeString(artifactCollectionJson, originalArtifactCollection, StandardCharsets.UTF_8);

        Path currentRevisionPreflight = output.resolve("handoff-preflight-current-revision");
        ProcessResult currentRevisionCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", currentRevisionPreflight.toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1",
                "--required-min-iterations", "1",
                "--require-source-audit",
                "--require-current-revision"
        );
        Assertions.assertEquals(1, currentRevisionCheck.exitCode, currentRevisionCheck.output);
        JsonNode currentRevisionJson = JSON.readTree(Files.readString(
                currentRevisionPreflight.resolve("handoff-check.json"), StandardCharsets.UTF_8));
        Assertions.assertTrue(currentRevisionJson.path("requireCurrentRevision").asBoolean());
        Assertions.assertTrue(currentRevisionJson.path("currentNetworkRevision").asText().length() >= 12);
        Assertions.assertTrue(currentRevisionJson.findValuesAsText("code")
                .contains("handoff-source-audit-revision-mismatch"));

        Path handoffReadme = handoff.resolve("README.md");
        Files.writeString(handoffReadme,
                Files.readString(handoffReadme, StandardCharsets.UTF_8)
                        .replace("--required-min-contention-clients \"2\"",
                                "--required-min-contention-clients \"1\""),
                StandardCharsets.UTF_8);
        ProcessResult tamperedReadmeCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-readme-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, tamperedReadmeCheck.exitCode, tamperedReadmeCheck.output);
        JsonNode tamperedReadmeCheckJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-readme-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(tamperedReadmeCheckJson.findValuesAsText("code")
                .contains("missing-readme-command"));
        Files.writeString(handoffReadme,
                Files.readString(handoffReadme, StandardCharsets.UTF_8)
                        .replace("--required-min-contention-clients \"1\"",
                                "--required-min-contention-clients \"2\""),
                StandardCharsets.UTF_8);

        Path promoteScriptPath = handoff.resolve("promote-and-check.sh");
        String originalPromoteScript = Files.readString(promoteScriptPath, StandardCharsets.UTF_8);
        Files.writeString(promoteScriptPath,
                originalPromoteScript.replace("benchmark/scripts/check-baseline-readiness.sh",
                        "benchmark/scripts/check-baseline-readiness-disabled.sh"),
                StandardCharsets.UTF_8);
        ProcessResult tamperedPromoteCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-promote-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, tamperedPromoteCheck.exitCode, tamperedPromoteCheck.output);
        JsonNode tamperedPromoteJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-promote-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(tamperedPromoteJson.findValuesAsText("code")
                .contains("missing-helper-command"));
        Files.writeString(promoteScriptPath, originalPromoteScript, StandardCharsets.UTF_8);

        Files.writeString(promoteScriptPath, originalPromoteScript + "\nif broken\n", StandardCharsets.UTF_8);
        ProcessResult invalidPromoteSyntaxCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-promote-syntax-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, invalidPromoteSyntaxCheck.exitCode, invalidPromoteSyntaxCheck.output);
        JsonNode invalidPromoteSyntaxJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-promote-syntax-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(invalidPromoteSyntaxJson.findValuesAsText("code")
                .contains("invalid-shell-syntax"));
        Files.writeString(promoteScriptPath, originalPromoteScript, StandardCharsets.UTF_8);

        Path perfectMergeScript = handoff.resolve("perfect-plan/merge-all.sh");
        String originalPerfectMergeScript = Files.readString(perfectMergeScript, StandardCharsets.UTF_8);
        Files.writeString(perfectMergeScript, originalPerfectMergeScript + "\nif broken\n", StandardCharsets.UTF_8);
        ProcessResult invalidMergeSyntaxCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-merge-syntax-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, invalidMergeSyntaxCheck.exitCode, invalidMergeSyntaxCheck.output);
        JsonNode invalidMergeSyntaxJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-merge-syntax-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(invalidMergeSyntaxJson.findValuesAsText("code")
                .contains("invalid-shell-syntax"));
        Files.writeString(perfectMergeScript, originalPerfectMergeScript, StandardCharsets.UTF_8);

        Path receiverScript = handoff.resolve("perfect-plan/curve-plan/receiver-receiver-a-commands.sh");
        String originalReceiverScript = Files.readString(receiverScript, StandardCharsets.UTF_8);
        Files.writeString(receiverScript, originalReceiverScript + "\nif broken\n", StandardCharsets.UTF_8);
        ProcessResult invalidReceiverSyntaxCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-receiver-syntax-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, invalidReceiverSyntaxCheck.exitCode, invalidReceiverSyntaxCheck.output);
        JsonNode invalidReceiverSyntaxJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-receiver-syntax-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(invalidReceiverSyntaxJson.findValuesAsText("code")
                .contains("invalid-shell-syntax"));
        Files.writeString(receiverScript, originalReceiverScript, StandardCharsets.UTF_8);

        Files.writeString(receiverScript,
                originalReceiverScript.replace("--iterations 1 --reliability",
                        "--iterations 2 --reliability"),
                StandardCharsets.UTF_8);
        ProcessResult driftedReceiverCommandCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-receiver-command-drift").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1",
                "--required-min-iterations", "1"
        );
        Assertions.assertEquals(1, driftedReceiverCommandCheck.exitCode, driftedReceiverCommandCheck.output);
        JsonNode driftedReceiverCommandJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-receiver-command-drift/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(driftedReceiverCommandJson.findValuesAsText("code")
                .contains("worker-command-mismatch"));
        Files.writeString(receiverScript, originalReceiverScript, StandardCharsets.UTF_8);

        Path impairmentReceiverScript = handoff.resolve("impairment-plan/near-loss-plan/contention-plan/receiver-receiver-a-commands.sh");
        String originalImpairmentReceiverScript = Files.readString(impairmentReceiverScript, StandardCharsets.UTF_8);
        Files.delete(impairmentReceiverScript);
        ProcessResult missingReceiverCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-receiver-missing").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, missingReceiverCheck.exitCode, missingReceiverCheck.output);
        JsonNode missingReceiverJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-receiver-missing/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(missingReceiverJson.findValuesAsText("code")
                .contains("missing-receiver-command-script"));
        Files.writeString(impairmentReceiverScript, originalImpairmentReceiverScript, StandardCharsets.UTF_8);
        Assertions.assertTrue(impairmentReceiverScript.toFile().setExecutable(true));

        Path handoffManifestPath = handoff.resolve("handoff-manifest.json");
        String originalHandoffManifest = Files.readString(handoffManifestPath, StandardCharsets.UTF_8);
        JsonNode manifestWithStaleEvidence = JSON.readTree(originalHandoffManifest);
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifestWithStaleEvidence.path("productionEvidence"))
                .put("sha256", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        Files.writeString(handoffManifestPath, JSON.writeValueAsString(manifestWithStaleEvidence),
                StandardCharsets.UTF_8);
        ProcessResult staleEvidenceCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-evidence-stale").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, staleEvidenceCheck.exitCode, staleEvidenceCheck.output);
        JsonNode staleEvidenceCheckJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-evidence-stale/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(staleEvidenceCheckJson.findValuesAsText("code")
                .contains("handoff-production-evidence-sha-mismatch"));
        Assertions.assertTrue(staleEvidenceCheckJson.path("productionEvidenceActualSha256").asText()
                .matches("[0-9a-f]{64}"));
        Files.writeString(handoffManifestPath, originalHandoffManifest, StandardCharsets.UTF_8);

        JsonNode manifestWithoutEvidence = JSON.readTree(originalHandoffManifest);
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifestWithoutEvidence).remove("productionEvidence");
        Files.writeString(handoffManifestPath, JSON.writeValueAsString(manifestWithoutEvidence), StandardCharsets.UTF_8);
        ProcessResult missingEvidenceCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-evidence-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, missingEvidenceCheck.exitCode, missingEvidenceCheck.output);
        JsonNode missingEvidenceCheckJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-evidence-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(missingEvidenceCheckJson.findValuesAsText("code")
                .contains("handoff-missing-production-evidence"));
        Files.writeString(handoffManifestPath, originalHandoffManifest, StandardCharsets.UTF_8);

        Path curveManifest = handoff.resolve("perfect-plan/curve-plan/manifest.jsonl");
        String originalCurveManifest = Files.readString(curveManifest, StandardCharsets.UTF_8);
        Files.writeString(curveManifest,
                originalCurveManifest.replaceFirst("\"reliability\":\"reliable_ordered\"",
                        "\"reliability\":\"unreliable\""),
                StandardCharsets.UTF_8);
        ProcessResult curveReliabilityCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-curve-reliability-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, curveReliabilityCheck.exitCode, curveReliabilityCheck.output);
        JsonNode curveReliabilityCheckJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-curve-reliability-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(curveReliabilityCheckJson.findValuesAsText("code")
                .contains("curve-reliability-mismatch"));
        Files.writeString(curveManifest, originalCurveManifest, StandardCharsets.UTF_8);

        Path contentionManifest = handoff.resolve("perfect-plan/contention-plan/manifest.jsonl");
        String originalContentionManifest = Files.readString(contentionManifest, StandardCharsets.UTF_8);
        Files.writeString(contentionManifest,
                originalContentionManifest.replaceFirst("\"reliability\":\"reliable_ordered\"",
                        "\"reliability\":\"unreliable\""),
                StandardCharsets.UTF_8);
        ProcessResult contentionReliabilityCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-contention-reliability-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, contentionReliabilityCheck.exitCode, contentionReliabilityCheck.output);
        JsonNode contentionReliabilityCheckJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-contention-reliability-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(contentionReliabilityCheckJson.findValuesAsText("code")
                .contains("contention-reliability-mismatch"));
        Files.writeString(contentionManifest, originalContentionManifest, StandardCharsets.UTF_8);

        Files.writeString(contentionManifest,
                originalContentionManifest.replaceFirst("\"clients\":2", "\"clients\":1"),
                StandardCharsets.UTF_8);
        ProcessResult tamperedHandoffCheck = runProcess(root, handoffCheckTimeout,
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-tampered").toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
        );
        Assertions.assertEquals(1, tamperedHandoffCheck.exitCode, tamperedHandoffCheck.output);
        JsonNode tamperedHandoffCheckJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-tampered/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(tamperedHandoffCheckJson.findValuesAsText("code")
                .contains("contention-client-count-mismatch"));
        Files.writeString(contentionManifest, originalContentionManifest, StandardCharsets.UTF_8);

        ProcessResult freshness = runProcess(root, Duration.ofSeconds(10),
                "bash",
                handoff.resolve("perfect-plan/check-plan-freshness.sh").toString()
        );
        Assertions.assertEquals(0, freshness.exitCode, freshness.output);
        Assertions.assertTrue(freshness.output.contains("result=fresh"));
        Assertions.assertTrue(freshness.output.contains("freshness_json="));
        JsonNode planFreshnessJson = JSON.readTree(Files.readString(
                handoff.resolve("perfect-plan/plan-freshness.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals("raknet-lab-plan-freshness", planFreshnessJson.path("kind").asText());
        Assertions.assertTrue(planFreshnessJson.path("passed").asBoolean());
        Assertions.assertEquals(3, planFreshnessJson.path("manifests").size());
        Assertions.assertTrue(planFreshnessJson.path("manifests").findValuesAsText("result")
                .stream().allMatch("fresh"::equals));

        ProcessResult impairmentFreshness = runProcess(root, Duration.ofSeconds(20),
                "bash",
                handoff.resolve("impairment-plan/check-plan-freshness.sh").toString()
        );
        Assertions.assertEquals(0, impairmentFreshness.exitCode, impairmentFreshness.output);
        Assertions.assertTrue(impairmentFreshness.output.contains("==> profile perfect"));
        Assertions.assertTrue(impairmentFreshness.output.contains("==> profile near-loss"));
        Assertions.assertTrue(impairmentFreshness.output.contains("result=fresh"));
        Assertions.assertTrue(impairmentFreshness.output.contains("freshness_json="));
        JsonNode impairmentFreshnessJson = JSON.readTree(Files.readString(
                handoff.resolve("impairment-plan/plan-freshness.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals("raknet-lab-impairment-plan-freshness",
                impairmentFreshnessJson.path("kind").asText());
        Assertions.assertTrue(impairmentFreshnessJson.path("passed").asBoolean());
        Assertions.assertEquals(2, impairmentFreshnessJson.path("profiles").size());
        Assertions.assertTrue(impairmentFreshnessJson.path("profiles").findValuesAsText("profile")
                .containsAll(List.of("perfect", "near-loss")));
        Assertions.assertTrue(impairmentFreshnessJson.path("profiles").findValuesAsText("result")
                .stream().allMatch("fresh"::equals));
    }

    @Test
    public void testFreshLabHandoffWrapperRefreshesSourceAuditAndPreflights() throws Exception {
        assumeShellTooling();
        assumeGit();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-fresh-handoff-test");
        Path handoff = output.resolve("handoff");
        Path artifacts = output.resolve("artifacts");
        Path geyser = initGitRepo(output.resolve("geyser"));
        Path protocol = initGitRepo(output.resolve("protocol"));

        ProcessResult result = runProcess(root, Duration.ofSeconds(60),
                "bash",
                root.resolve("benchmark/scripts/prepare-fresh-lab-handoff.sh").toString(),
                "--out", handoff.toString(),
                "--artifact-root", artifacts.toString(),
                "--require-sources", "geyser,cloudburst-protocol",
                "--geyser", geyser.toString(),
                "--cloudburst-protocol", protocol.toString(),
                "--server-host", "127.0.0.1",
                "--interface", "lo",
                "--expect-mtu", "1500",
                "--expect-min-cpus", "1",
                "--profiles", "perfect",
                "--warmup", "1s",
                "--duration", "60s",
                "--iterations", "3",
                "--start-delay", "1s",
                "--start-offset", "300s"
        );

        Assertions.assertEquals(0, result.exitCode, result.output);
        Assertions.assertTrue(result.output.contains("Fresh lab handoff:"));
        Path sourceAudit = handoff.resolve("production-evidence/source-audit.json");
        Path preflight = handoff.resolve("preflight/handoff-check.json");
        Path summary = handoff.resolve("fresh-handoff-summary.json");
        Assertions.assertTrue(Files.exists(sourceAudit));
        Assertions.assertTrue(Files.exists(preflight));
        Assertions.assertTrue(Files.exists(summary));

        JsonNode sourceAuditJson = JSON.readTree(Files.readString(sourceAudit, StandardCharsets.UTF_8));
        Assertions.assertTrue(sourceAuditJson.path("ready").asBoolean());
        Assertions.assertEquals(0, sourceAuditJson.path("issueCount").asInt());
        Assertions.assertEquals(2, sourceAuditJson.path("requiredSources").size());
        Assertions.assertTrue(findSource(sourceAuditJson, "geyser").path("available").asBoolean());
        Assertions.assertTrue(findSource(sourceAuditJson, "cloudburst-protocol").path("available").asBoolean());

        JsonNode handoffManifest = JSON.readTree(Files.readString(handoff.resolve("handoff-manifest.json"),
                StandardCharsets.UTF_8));
        Assertions.assertEquals(sourceAudit.toString(), handoffManifest.path("sourceAudit").path("document").asText());
        Assertions.assertTrue(handoffManifest.path("sourceAudit").path("ready").asBoolean());
        Assertions.assertEquals(sourceAuditJson.path("networkDirtyTrackedFiles").asBoolean(),
                handoffManifest.path("sourceAudit").path("networkDirtyTrackedFiles").asBoolean());
        Assertions.assertEquals(500, handoffManifest.path("contentionClientTotal").asInt());
        Assertions.assertEquals("reliable_ordered", handoffManifest.path("reliability").asText());
        Assertions.assertEquals(5.0D, handoffManifest.path("perClientMbps").asDouble(), 0.001D);

        JsonNode preflightJson = JSON.readTree(Files.readString(preflight, StandardCharsets.UTF_8));
        Assertions.assertTrue(preflightJson.path("ready").asBoolean(), preflightJson.toPrettyString());
        Assertions.assertEquals(0, preflightJson.path("issueCount").asInt());
        Assertions.assertTrue(preflightJson.path("requireSourceAudit").asBoolean());
        Assertions.assertTrue(preflightJson.path("requireCurrentRevision").asBoolean());
        Assertions.assertEquals("reliable_ordered", preflightJson.path("expectedReliability").asText());
        Assertions.assertEquals(sourceAuditJson.path("networkRevision").asText(),
                preflightJson.path("currentNetworkRevision").asText());
        Assertions.assertTrue(preflightJson.path("sourceAuditActualReady").asBoolean());
        Assertions.assertEquals(preflightJson.path("sourceAudit").path("sha256").asText(),
                preflightJson.path("sourceAuditActualSha256").asText());

        JsonNode summaryJson = JSON.readTree(Files.readString(summary, StandardCharsets.UTF_8));
        Assertions.assertEquals("raknet-fresh-lab-handoff", summaryJson.path("kind").asText());
        Assertions.assertTrue(summaryJson.path("ready").asBoolean(), summaryJson.toPrettyString());
        Assertions.assertEquals(sourceAuditJson.path("networkRevision").asText(),
                summaryJson.path("networkRevision").asText());
        Assertions.assertEquals(sourceAuditJson.path("networkShortRevision").asText(),
                summaryJson.path("networkShortRevision").asText());
        Assertions.assertTrue(summaryJson.path("networkDirtyTrackedFiles").isBoolean());
        Assertions.assertEquals(0, summaryJson.path("issueCount").asInt());
        Assertions.assertEquals(0, summaryJson.path("sourceAuditIssueCount").asInt());
        Assertions.assertEquals(0, summaryJson.path("handoffIssueCount").asInt());
        Assertions.assertEquals(sourceAuditJson.path("evidenceDocument").path("document").asText(),
                summaryJson.path("productionEvidence").path("document").asText());
        Assertions.assertEquals(sourceAuditJson.path("evidenceDocument").path("sha256").asText(),
                summaryJson.path("productionEvidence").path("sha256").asText());
        Assertions.assertEquals(preflightJson.path("sourceAuditActualSha256").asText(),
                summaryJson.path("sourceAudit").path("sha256").asText());
        Assertions.assertEquals("127.0.0.1", summaryJson.path("executionEnvironment").path("serverHost").asText());
        Assertions.assertEquals("lo", summaryJson.path("executionEnvironment").path("interface").asText());
        Assertions.assertFalse(summaryJson.path("executionEnvironment").path("labExecutable").asBoolean());
        Assertions.assertTrue(summaryJson.path("executionEnvironment").path("advisory").asText()
                .contains("local placeholder topology values"));
        Assertions.assertTrue(textValues(summaryJson.path("executionEnvironment").path("advisoryReasons"))
                .contains("loopback-server-host"));
        Assertions.assertTrue(textValues(summaryJson.path("executionEnvironment").path("advisoryReasons"))
                .contains("loopback-interface"));
        Assertions.assertEquals(handoff.resolve("handoff-manifest.json").toString(),
                summaryJson.path("execution").path("handoffManifest").asText());
        Assertions.assertEquals(handoffManifest.path("readme").asText(),
                summaryJson.path("execution").path("readme").asText());
        Assertions.assertEquals(handoffManifest.path("artifactCollectionJson").asText(),
                summaryJson.path("execution").path("artifactCollectionJson").asText());
        Assertions.assertEquals(handoffManifest.path("prereqScript").asText(),
                summaryJson.path("execution").path("prereqScript").asText());
        Assertions.assertEquals(handoffManifest.path("promoteScript").asText(),
                summaryJson.path("execution").path("promoteScript").asText());
        Assertions.assertEquals(handoffManifest.path("perfectPlan").asText(),
                summaryJson.path("execution").path("perfectPlan").asText());
        Assertions.assertEquals(handoffManifest.path("impairmentPlan").asText(),
                summaryJson.path("execution").path("impairmentPlan").asText());
        Assertions.assertEquals(56, summaryJson.path("plannedRows").path("perfectCurve").asInt());
        Assertions.assertEquals(56, summaryJson.path("plannedRows").path("perfectRaisedCurve").asInt());
        Assertions.assertEquals(9, summaryJson.path("plannedRows").path("perfectContention").asInt());
        Assertions.assertEquals(1, summaryJson.path("plannedRows").path("impairmentProfiles").size());
        Assertions.assertEquals("perfect", summaryJson.path("plannedRows").path("impairmentProfiles").get(0)
                .path("profile").asText());
        Assertions.assertTrue(summaryJson.path("sourceAudit").path("ready").asBoolean());
        Assertions.assertEquals(0, summaryJson.path("sourceAudit").path("issueCount").asInt());
        Assertions.assertEquals(2, summaryJson.path("sourceAudit").path("requiredSources").size());
        Assertions.assertTrue(summaryJson.path("sourceAudit").path("sourceCount").asInt() >= 2);
        Assertions.assertEquals(sourceAuditJson.path("evidenceDocument").path("document").asText(),
                summaryJson.path("sourceAudit").path("evidenceDocument").path("document").asText());
        Assertions.assertEquals(sourceAuditJson.path("evidenceDocument").path("sha256").asText(),
                summaryJson.path("sourceAudit").path("evidenceDocument").path("sha256").asText());
        JsonNode summarySources = summaryJson.path("sourceAudit").path("sources");
        Assertions.assertTrue(summarySources.isArray());
        Assertions.assertTrue(findSource(summaryJson.path("sourceAudit"), "geyser").path("required").asBoolean());
        Assertions.assertTrue(findSource(summaryJson.path("sourceAudit"), "geyser").path("available").asBoolean());
        Assertions.assertTrue(findSource(summaryJson.path("sourceAudit"), "cloudburst-protocol")
                .path("required").asBoolean());
        Assertions.assertFalse(findSource(summaryJson.path("sourceAudit"), "teamziax-ebpf")
                .path("required").asBoolean());
        Assertions.assertEquals("reliable_ordered", summaryJson.path("requirements")
                .path("expectedReliability").asText());
        Assertions.assertEquals(7, summaryJson.path("requirements").path("expectedCurvePayloadSizes").size());
        Assertions.assertEquals(500, summaryJson.path("requirements").path("expectedContentionClients").asInt());
        Assertions.assertEquals(5.0D, summaryJson.path("requirements").path("expectedPerClientMbps").asDouble(),
                0.001D);
        Assertions.assertEquals(1.0D, summaryJson.path("requirements")
                .path("expectedImmediatePerClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(3, summaryJson.path("requirements").path("expectedIterations").asInt());
        Assertions.assertEquals(3, summaryJson.path("requirements").path("requiredMinIterations").asInt());
        Assertions.assertEquals(500, summaryJson.path("requirements")
                .path("requiredMinContentionClients").asInt());
        Assertions.assertEquals(5.0D, summaryJson.path("requirements")
                .path("requiredMinContentionTargetClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(3, summaryJson.path("requirements").path("requiredBatchIntervalsMillis").size());
        Assertions.assertEquals(2, summaryJson.path("requirements")
                .path("requiredResourcePackPayloadSizes").size());
        Assertions.assertEquals(1, summaryJson.path("requirements")
                .path("requiredResourcePackIntervalsMillis").size());
        Assertions.assertEquals("blackhole", summaryJson.path("requirements")
                .path("requiredDisappearanceModes").get(0).asText());
        Assertions.assertTrue(summaryJson.path("requirements").path("requireSourceAudit").asBoolean());
        Assertions.assertTrue(summaryJson.path("requirements").path("requireCurrentRevision").asBoolean());
        Assertions.assertTrue(summaryJson.path("preflight").path("ready").asBoolean());
        Assertions.assertEquals(0, summaryJson.path("preflight").path("issueCount").asInt());
        Assertions.assertTrue(summaryJson.path("preflight").path("issues").isArray());
    }

    @Test
    public void testDefaultLabHandoffPassesProductionScalePreflight() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-default-handoff-test");
        Path handoff = output.resolve("handoff");
        Path artifacts = output.resolve("artifacts");

        ProcessResult result = runProcess(root, Duration.ofSeconds(45),
                "bash",
                root.resolve("benchmark/scripts/prepare-lab-baseline-handoff.sh").toString(),
                "--out", handoff.toString(),
                "--artifact-root", artifacts.toString(),
                "--server-host", "127.0.0.1",
                "--interface", "lo",
                "--expect-mtu", "1500",
                "--expect-min-cpus", "2",
                "--profiles", "perfect",
                "--warmup", "1s",
                "--duration", "60s",
                "--iterations", "3",
                "--start-delay", "1s",
                "--start-offset", "180s"
        );
        Assertions.assertEquals(0, result.exitCode, result.output);

        JsonNode handoffManifest = JSON.readTree(Files.readString(handoff.resolve("handoff-manifest.json"),
                StandardCharsets.UTF_8));
        Assertions.assertEquals(500, handoffManifest.path("contentionClientTotal").asInt());
        Assertions.assertEquals("reliable_ordered", handoffManifest.path("reliability").asText());
        Assertions.assertEquals(5.0D, handoffManifest.path("perClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(6, handoffManifest.path("contentionCases").size());
        Assertions.assertTrue(handoffManifest.path("contentionCases").toString().contains("\"immediate\""));
        Assertions.assertEquals(256, handoffManifest.path("immediatePayloadSize").asInt());
        Assertions.assertEquals(1.0D, handoffManifest.path("immediatePerClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals("receiver-a=250", handoffManifest.path("contentionReceivers").get(0).asText());
        Assertions.assertEquals("receiver-b=250", handoffManifest.path("contentionReceivers").get(1).asText());

        ProcessResult handoffCheck = runProcess(root, Duration.ofSeconds(20),
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight").toString()
        );
        Assertions.assertEquals(0, handoffCheck.exitCode, handoffCheck.output);
        JsonNode handoffCheckJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(handoffCheckJson.path("ready").asBoolean());
        Assertions.assertEquals(0, handoffCheckJson.path("issueCount").asInt());
        Assertions.assertEquals(500, handoffCheckJson.path("expectedContentionClients").asInt());
        Assertions.assertEquals("reliable_ordered", handoffCheckJson.path("expectedReliability").asText());
        Assertions.assertEquals(5.0D, handoffCheckJson.path("expectedPerClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(3, handoffCheckJson.path("expectedIterations").asInt());
        Assertions.assertEquals(3, handoffCheckJson.path("requiredMinIterations").asInt());
        Assertions.assertEquals(500, handoffCheckJson.path("requiredMinContentionClients").asInt());
        Assertions.assertEquals(5.0D, handoffCheckJson.path("requiredMinContentionTargetClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(3, handoffCheckJson.path("requiredBatchIntervalsMillis").size());
        Assertions.assertEquals(2, handoffCheckJson.path("requiredResourcePackPayloadSizes").size());
        Assertions.assertEquals(1, handoffCheckJson.path("requiredResourcePackIntervalsMillis").size());
        Assertions.assertEquals(56, handoffCheckJson.path("actualPerfectCurveRows").asInt());
        Assertions.assertEquals(56, handoffCheckJson.path("actualPerfectRaisedCurveRows").asInt());
        Assertions.assertEquals(9, handoffCheckJson.path("actualPerfectContentionRows").asInt());
        Assertions.assertEquals(1, handoffCheckJson.path("actualImpairmentProfileRows").size());
        Assertions.assertEquals(9, handoffCheckJson.path("actualImpairmentProfileRows").get(0).path("contentionRows").asInt());
        Assertions.assertTrue(handoffCheckJson.path("expectedContentionScenarios").toString()
                .contains("\"batched-game-traffic\""));
        Assertions.assertTrue(handoffCheckJson.path("expectedContentionScenarios").toString()
                .contains("\"resource-pack-transfer\""));

        JsonNode immediateRow = readJsonLines(handoff.resolve("perfect-plan/contention-plan/manifest.jsonl")).stream()
                .filter(row -> row.path("affectedKind").asText().equals("immediate"))
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals(256, immediateRow.path("payloadSize").asInt());
        Assertions.assertEquals(1.0D, immediateRow.path("perClientMbps").asDouble(), 0.001D);

        ProcessResult strictShapeCheck = runProcess(root, Duration.ofSeconds(20),
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-strict-shape").toString(),
                "--required-batch-intervals-ms", "10,20,50,75",
                "--required-resource-pack-chunk-sizes", "8192,262144,524288",
                "--required-resource-pack-intervals-ms", "200,500"
        );
        Assertions.assertEquals(1, strictShapeCheck.exitCode, strictShapeCheck.output);
        JsonNode strictShapeJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-strict-shape/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(strictShapeJson.findValuesAsText("code")
                .contains("handoff-missing-required-batch-interval"));
        Assertions.assertTrue(strictShapeJson.findValuesAsText("code")
                .contains("handoff-missing-required-resource-pack-payload"));
        Assertions.assertTrue(strictShapeJson.findValuesAsText("code")
                .contains("handoff-missing-required-resource-pack-interval"));
        Assertions.assertTrue(strictShapeJson.findValuesAsText("code")
                .contains("contention-missing-resource-pack-shape"));

        ProcessResult missingSourceAuditCheck = runProcess(root, Duration.ofSeconds(20),
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", output.resolve("handoff-preflight-source-audit-required").toString(),
                "--require-source-audit"
        );
        Assertions.assertEquals(1, missingSourceAuditCheck.exitCode, missingSourceAuditCheck.output);
        JsonNode missingSourceAuditJson = JSON.readTree(Files.readString(
                output.resolve("handoff-preflight-source-audit-required/handoff-check.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(missingSourceAuditJson.findValuesAsText("code")
                .contains("handoff-missing-source-audit"));
    }

    @Test
    public void testProductionSourceAuditCapturesRevisionsWithoutPrivatePaths() throws Exception {
        assumeShellTooling();
        assumeGit();

        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-source-audit-test");
        Path geyser = initGitRepo(output.resolve("geyser"));
        Path cubecraft = initGitRepo(output.resolve("cubecraft"));
        Path audit = output.resolve("audit");

        ProcessResult result = runProcess(root, Duration.ofSeconds(20),
                "bash",
                root.resolve("benchmark/scripts/capture-production-evidence.sh").toString(),
                "--out", audit.toString(),
                "--geyser", geyser.toString(),
                "--cubecraft", cubecraft.toString(),
                "--require-sources", "geyser,cubecraft"
        );
        Assertions.assertEquals(0, result.exitCode, result.output);

        String auditJsonText = Files.readString(audit.resolve("source-audit.json"), StandardCharsets.UTF_8);
        JsonNode auditJson = JSON.readTree(auditJsonText);
        Assertions.assertEquals("raknet-production-source-audit", auditJson.path("kind").asText());
        Assertions.assertTrue(auditJson.path("ready").asBoolean());
        Assertions.assertFalse(auditJson.path("includePaths").asBoolean());
        Assertions.assertTrue(auditJson.has("networkDirtyTrackedFiles"));
        Assertions.assertEquals(0, auditJson.path("issueCount").asInt());
        Assertions.assertEquals("benchmark/docs/production-usage-evidence.md",
                auditJson.path("evidenceDocument").path("document").asText());
        Assertions.assertTrue(auditJson.path("evidenceDocument").path("sha256").asText()
                .matches("[0-9a-f]{64}"));
        Assertions.assertFalse(auditJsonText.contains(cubecraft.toString()),
                "private checkout paths should be omitted unless --include-paths is used");
        JsonNode cubecraftSource = findSource(auditJson, "cubecraft");
        Assertions.assertEquals("private", cubecraftSource.path("visibility").asText());
        Assertions.assertTrue(cubecraftSource.path("available").asBoolean());
        Assertions.assertTrue(cubecraftSource.path("shortRevision").asText().matches("[0-9a-f]{12}"));
        Assertions.assertTrue(cubecraftSource.path("path").isNull());

        Path missingAudit = output.resolve("missing-audit");
        ProcessResult missingResult = runProcess(root, Duration.ofSeconds(20),
                "bash",
                root.resolve("benchmark/scripts/capture-production-evidence.sh").toString(),
                "--out", missingAudit.toString(),
                "--cloudburst-protocol", output.resolve("missing-protocol").toString(),
                "--require-sources", "cloudburst-protocol"
        );
        Assertions.assertEquals(1, missingResult.exitCode, missingResult.output);
        JsonNode missingAuditJson = JSON.readTree(Files.readString(missingAudit.resolve("source-audit.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingAuditJson.path("ready").asBoolean());
        Assertions.assertTrue(missingAuditJson.findValuesAsText("code")
                .contains("required-source-unavailable"));
    }

    @Test
    public void testLabHostPrereqCheckProducesReports() throws Exception {
        assumeShellTooling();
        Assumptions.assumeTrue(commandAvailable("ip"), "ip is required for host prereq script tests");
        Assumptions.assumeTrue(commandAvailable("tc"), "tc is required for host prereq script tests");
        Assumptions.assumeTrue(commandAvailable("java"), "java is required for host prereq script tests");
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-host-prereq-test");
        ProcessResult mtuProbe = runProcess(root, Duration.ofSeconds(5),
                "bash",
                "-lc",
                "ip -o link show dev lo | sed -n 's/.* mtu \\([0-9][0-9]*\\).*/\\1/p' | head -n 1"
        );
        Assertions.assertEquals(0, mtuProbe.exitCode, mtuProbe.output);
        String loopbackMtu = mtuProbe.output.trim();
        Assumptions.assumeFalse(loopbackMtu.isEmpty(), "loopback MTU must be parseable for host prereq script tests");

        ProcessResult ready = runProcess(root, Duration.ofSeconds(20),
                "bash",
                root.resolve("benchmark/scripts/check-lab-host-prereqs.sh").toString(),
                "--out", output.resolve("ready").toString(),
                "--interface", "lo",
                "--host-role", "server",
                "--expect-mtu", loopbackMtu,
                "--expect-min-cpus", "1",
                "--require-no-netem"
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
        JsonNode readyJson = JSON.readTree(Files.readString(output.resolve("ready/prereq.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(readyJson.path("ready").asBoolean());
        Assertions.assertEquals("server", readyJson.path("hostRole").asText());
        Assertions.assertEquals("lo", readyJson.path("interface").asText());
        Assertions.assertEquals(Integer.parseInt(loopbackMtu), readyJson.path("interfaceMtu").asInt());
        Assertions.assertEquals(Integer.parseInt(loopbackMtu), readyJson.path("expectedMtu").asInt());
        Assertions.assertTrue(readyJson.path("cpuCount").asInt() >= 1);
        Assertions.assertEquals(1, readyJson.path("expectedMinCpus").asInt());
        Assertions.assertTrue(readyJson.path("requireNoNetem").asBoolean());
        Assertions.assertEquals(0, readyJson.path("errorCount").asInt());
        Assertions.assertTrue(readyJson.findValuesAsText("name").contains("java-version"));
        Assertions.assertTrue(readyJson.findValuesAsText("name").contains("interface-mtu"));
        Assertions.assertTrue(readyJson.findValuesAsText("name").contains("tc-netem"));
        Assertions.assertTrue(readyJson.findValuesAsText("name").contains("cpu-count-minimum"));
        Assertions.assertTrue(Files.readString(output.resolve("ready/prereq.md"), StandardCharsets.UTF_8)
                .contains("Lab Host Prerequisites"));

        ProcessResult missingInterface = runProcess(root, Duration.ofSeconds(20),
                "bash",
                root.resolve("benchmark/scripts/check-lab-host-prereqs.sh").toString(),
                "--out", output.resolve("missing").toString(),
                "--interface", "raknet-missing0",
                "--host-role", "receiver-a"
        );
        Assertions.assertEquals(1, missingInterface.exitCode, missingInterface.output);
        JsonNode missingJson = JSON.readTree(Files.readString(output.resolve("missing/prereq.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingJson.path("ready").asBoolean());
        Assertions.assertTrue(missingJson.findValuesAsText("code").contains("missing-interface"));
    }

    @Test
    public void testLabValidationRequiresReadyPrereqReports() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-lab-validation-prereq-test");

        Path missingPrereqs = output.resolve("missing-prereqs");
        writeValidationLabArtifacts(missingPrereqs, 1, 1);
        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/validate-lab-baseline.sh").toString(),
                "--input", missingPrereqs.toString(),
                "--out", missingPrereqs.resolve("validation").toString()
        );
        Assertions.assertEquals(1, missing.exitCode, missing.output);
        JsonNode missingJson = JSON.readTree(Files.readString(
                missingPrereqs.resolve("validation/validation.json"), StandardCharsets.UTF_8));
        Assertions.assertTrue(missingJson.findValuesAsText("code").contains("missing-prereq-reports"));
        Assertions.assertTrue(missingJson.findValuesAsText("code")
                .contains("not-enough-ready-prereq-reports"));
        Assertions.assertTrue(missingJson.findValuesAsText("code")
                .contains("missing-prereq-distinct-hosts"));

        Path failedPrereqs = output.resolve("failed-prereqs");
        writeValidationLabArtifacts(failedPrereqs, 2, 1);
        ProcessResult failed = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/validate-lab-baseline.sh").toString(),
                "--input", failedPrereqs.toString(),
                "--out", failedPrereqs.resolve("validation").toString()
        );
        Assertions.assertEquals(1, failed.exitCode, failed.output);
        JsonNode failedJson = JSON.readTree(Files.readString(
                failedPrereqs.resolve("validation/validation.json"), StandardCharsets.UTF_8));
        Assertions.assertTrue(failedJson.findValuesAsText("code")
                .contains("not-ready-prereq-reports"));

        Path readyPrereqs = output.resolve("ready-prereqs");
        writeValidationLabArtifacts(readyPrereqs, 2, 2);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/validate-lab-baseline.sh").toString(),
                "--input", readyPrereqs.toString(),
                "--out", readyPrereqs.resolve("validation").toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
        JsonNode readyJson = JSON.readTree(Files.readString(
                readyPrereqs.resolve("validation/validation.json"), StandardCharsets.UTF_8));
        Assertions.assertTrue(readyJson.path("passed").asBoolean());
        Assertions.assertEquals(2, readyJson.path("prereqReportCount").asInt());
        Assertions.assertEquals(2, readyJson.path("readyPrereqReportCount").asInt());
        Assertions.assertEquals(2, readyJson.path("prereqDistinctHostnameCount").asInt());
    }

    @Test
    public void testLabValidationRequiresStrictPrereqGates() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-lab-validation-strict-prereq-test");
        Path loosePrereqs = output.resolve("loose-prereqs");
        writeValidationLabArtifacts(loosePrereqs, 2, 2, false);

        ProcessResult strict = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/validate-lab-baseline.sh").toString(),
                "--input", loosePrereqs.toString(),
                "--out", loosePrereqs.resolve("validation").toString()
        );
        Assertions.assertEquals(1, strict.exitCode, strict.output);
        JsonNode strictJson = JSON.readTree(Files.readString(
                loosePrereqs.resolve("validation/validation.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals(0, strictJson.path("strictPrereqReportCount").asInt());
        Assertions.assertTrue(strictJson.findValuesAsText("code")
                .contains("missing-strict-prereq-gates"));
        Assertions.assertTrue(strictJson.findValuesAsText("code")
                .contains("missing-strict-prereq-distinct-hosts"));

        ProcessResult smokeBypass = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/validate-lab-baseline.sh").toString(),
                "--input", loosePrereqs.toString(),
                "--out", loosePrereqs.resolve("validation-bypass").toString(),
                "--allow-loose-prereq-gates"
        );
        Assertions.assertEquals(0, smokeBypass.exitCode, smokeBypass.output);
        JsonNode bypassJson = JSON.readTree(Files.readString(
                loosePrereqs.resolve("validation-bypass/validation.json"), StandardCharsets.UTF_8));
        Assertions.assertTrue(bypassJson.path("allowLoosePrereqGates").asBoolean());
    }

    @Test
    public void testLabValidationRejectsPlannedBatchShapeMismatch() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-lab-validation-batch-shape-test");
        Path lab = output.resolve("lab");
        writeValidationLabArtifacts(lab, 2, 2);
        Path manifest = output.resolve("manifest.jsonl");
        Files.writeString(manifest,
                "{\"case\":\"batched-game-traffic\","
                        + "\"benchmarkName\":\"batched-game-traffic\","
                        + "\"clients\":100,"
                        + "\"payloadSize\":512,"
                        + "\"perClientMbps\":5,"
                        + "\"batchIntervalMillis\":20,"
                        + "\"logicalPacketsPerBatch\":8,"
                        + "\"batchGroups\":4}\n",
                StandardCharsets.UTF_8);

        ProcessResult mismatched = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/validate-lab-baseline.sh").toString(),
                "--input", lab.toString(),
                "--manifest", manifest.toString(),
                "--out", output.resolve("validation-mismatched").toString()
        );
        Assertions.assertEquals(1, mismatched.exitCode, mismatched.output);
        JsonNode mismatchedJson = JSON.readTree(Files.readString(
                output.resolve("validation-mismatched/validation.json"), StandardCharsets.UTF_8));
        List<String> issueCodes = mismatchedJson.findValuesAsText("code");
        Assertions.assertTrue(issueCodes.contains("planned-batch-interval-mismatch"));
        Assertions.assertTrue(issueCodes.contains("planned-logical-packets-per-batch-mismatch"));
        Assertions.assertTrue(issueCodes.contains("planned-batch-groups-mismatch"));

        String aggregate = Files.readString(lab.resolve("suite-aggregate.jsonl"), StandardCharsets.UTF_8)
                .replace("\"benchmarkName\":\"batched-game-traffic\",",
                        "\"benchmarkName\":\"batched-game-traffic\","
                                + "\"batchIntervalMillis\":20,"
                                + "\"logicalPacketsPerBatch\":8,"
                                + "\"batchGroups\":4,");
        Files.writeString(lab.resolve("suite-aggregate.jsonl"), aggregate, StandardCharsets.UTF_8);
        ProcessResult matched = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/validate-lab-baseline.sh").toString(),
                "--input", lab.toString(),
                "--manifest", manifest.toString(),
                "--out", output.resolve("validation-matched").toString()
        );
        Assertions.assertEquals(0, matched.exitCode, matched.output);
    }

    @Test
    public void testLabValidationRequiresConcreteSelectedCapacityCandidate() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-lab-validation-capacity-test");
        Path lab = output.resolve("lab");
        writeValidationLabArtifacts(lab, 2, 2);
        Files.writeString(lab.resolve("bandwidth-capacity.jsonl"),
                "{\"summaryKind\":\"bandwidth-capacity\",\"case\":\"curve-100_0mbps\",\"payloadSize\":512,"
                        + "\"selected\":true}\n",
                StandardCharsets.UTF_8);

        ProcessResult invalid = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/validate-lab-baseline.sh").toString(),
                "--input", lab.toString(),
                "--out", lab.resolve("validation").toString()
        );
        Assertions.assertEquals(1, invalid.exitCode, invalid.output);
        JsonNode invalidJson = JSON.readTree(Files.readString(
                lab.resolve("validation/validation.json"), StandardCharsets.UTF_8));
        Assertions.assertTrue(invalidJson.findValuesAsText("code")
                .contains("invalid-selected-capacity"));
    }

    @Test
    public void testPromotionRejectsValidationBypassesByDefault() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-lab-promotion-bypass-test");
        Path handoffManifest = output.resolve("handoff-manifest.json");
        writeHandoffManifest(handoffManifest);

        Path strictLab = output.resolve("strict-lab");
        writeValidationLabArtifacts(strictLab, 2, 2);
        ProcessResult strictPromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-baseline.sh").toString(),
                "--input", strictLab.toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "strict",
                "--handoff-manifest", handoffManifest.toString(),
                "--no-latest"
        );
        Assertions.assertEquals(0, strictPromotion.exitCode, strictPromotion.output);
        JsonNode strictManifest = JSON.readTree(Files.readString(
                output.resolve("baselines/strict/baseline-manifest.json"), StandardCharsets.UTF_8));
        Assertions.assertFalse(strictManifest.path("allowValidationBypasses").asBoolean());
        Assertions.assertEquals("benchmark/docs/production-usage-evidence.md",
                strictManifest.path("productionEvidence").path("document").asText());
        Assertions.assertTrue(strictManifest.path("productionEvidence").path("sha256").asText()
                .matches("[0-9a-f]{64}"));
        Assertions.assertTrue(strictManifest.path("sourceAudit").path("ready").asBoolean());
        Assertions.assertTrue(strictManifest.path("sourceAudit").path("sha256").asText()
                .matches("[0-9a-f]{64}"));
        Assertions.assertEquals("0123456789ab",
                strictManifest.path("sourceAudit").path("networkShortRevision").asText());
        Assertions.assertEquals(handoffManifest.toString(),
                strictManifest.path("sourcePaths").path("handoffManifest").asText());
        Assertions.assertEquals(handoffManifest.getParent().resolve("artifact-collection.json").toString(),
                strictManifest.path("sourcePaths").path("artifactCollectionJson").asText());
        Assertions.assertEquals(handoffManifest.getParent().resolve("artifact-collection.md").toString(),
                strictManifest.path("sourcePaths").path("artifactCollectionMd").asText());
        Assertions.assertTrue(Files.exists(output.resolve("baselines/strict/handoff-manifest.json")));
        Assertions.assertTrue(Files.exists(output.resolve("baselines/strict/artifact-collection.json")));
        Assertions.assertTrue(Files.exists(output.resolve("baselines/strict/artifact-collection.md")));
        String strictReport = Files.readString(output.resolve("baselines/strict/BASELINE.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(strictReport.contains("Production evidence: `benchmark/docs/production-usage-evidence.md`"));
        Assertions.assertTrue(strictReport.contains("Handoff manifest: `handoff-manifest.json`"));
        Assertions.assertTrue(strictReport.contains("Artifact collection: `artifact-collection.json`"));
        Assertions.assertTrue(strictReport.contains("Artifact collection checklist: `artifact-collection.md`"));

        Path looseLab = output.resolve("loose-lab");
        writeValidationLabArtifacts(looseLab, 2, 2, false);
        ProcessResult rejectedPromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-baseline.sh").toString(),
                "--input", looseLab.toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "loose",
                "--no-latest",
                "--",
                "--allow-loose-prereq-gates"
        );
        Assertions.assertEquals(1, rejectedPromotion.exitCode, rejectedPromotion.output);
        Assertions.assertTrue(rejectedPromotion.output.contains("validation used baseline bypass flags"));
        Assertions.assertFalse(Files.exists(output.resolve("baselines/loose/baseline-manifest.json")));

        ProcessResult smokePromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-baseline.sh").toString(),
                "--input", looseLab.toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "loose-smoke",
                "--no-latest",
                "--allow-validation-bypasses",
                "--",
                "--allow-loose-prereq-gates"
        );
        Assertions.assertEquals(0, smokePromotion.exitCode, smokePromotion.output);
        JsonNode smokeManifest = JSON.readTree(Files.readString(
                output.resolve("baselines/loose-smoke/baseline-manifest.json"), StandardCharsets.UTF_8));
        Assertions.assertTrue(smokeManifest.path("allowValidationBypasses").asBoolean());
        Assertions.assertTrue(smokeManifest.path("validation").path("allowLoosePrereqGates").asBoolean());
    }

    @Test
    public void testPromotionRejectsMissingRetryPressureFields() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-lab-promotion-retry-fields-test");
        Path lab = output.resolve("lab");
        writeValidationLabArtifacts(lab, 2, 2);
        Files.writeString(lab.resolve("suite-aggregate.jsonl"),
                Files.readString(lab.resolve("suite-aggregate.jsonl"), StandardCharsets.UTF_8)
                        .replace("\"undeliveredServerGbps\":0,", ""),
                StandardCharsets.UTF_8);

        ProcessResult rejectedPromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-baseline.sh").toString(),
                "--input", lab.toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "missing-retry-fields",
                "--no-latest"
        );
        Assertions.assertEquals(1, rejectedPromotion.exitCode, rejectedPromotion.output);
        Assertions.assertTrue(rejectedPromotion.output.contains("missing required retry-pressure fields"));
        Assertions.assertTrue(rejectedPromotion.output.contains("undeliveredServerGbps"));
        Assertions.assertFalse(Files.exists(output.resolve(
                "baselines/missing-retry-fields/baseline-manifest.json")));
    }

    @Test
    public void testComparisonRejectsValidationBypassesByDefault() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-compare-bypass-test");
        Path baseline = output.resolve("baseline");
        Path candidate = output.resolve("candidate");
        writeComparableSuite(baseline, false);
        writeComparableSuite(candidate, true);

        ProcessResult rejectedComparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-baseline-suite.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("comparison.md").toString(),
                "--require-validation"
        );
        Assertions.assertEquals(1, rejectedComparison.exitCode, rejectedComparison.output);
        String rejectedReport = Files.readString(output.resolve("comparison.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(rejectedReport.contains("| Validation bypass inputs | 1 |"));
        Assertions.assertTrue(rejectedReport.contains("allowUnstable"));
        Assertions.assertTrue(rejectedReport.contains("1 validation bypass input(s)"));

        ProcessResult smokeComparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-baseline-suite.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("comparison-smoke.md").toString(),
                "--require-validation",
                "--allow-validation-bypasses"
        );
        Assertions.assertEquals(0, smokeComparison.exitCode, smokeComparison.output);
        String smokeReport = Files.readString(output.resolve("comparison-smoke.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(smokeReport.contains("- Allow validation bypasses: `true`"));
        Assertions.assertTrue(smokeReport.contains("Comparison passed."));
    }

    @Test
    public void testComparisonRejectsMissingRetryPressureFields() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-compare-retry-fields-test");
        Path baseline = output.resolve("baseline");
        Path candidate = output.resolve("candidate");
        writeComparableSuite(baseline, false);
        writeComparableSuite(candidate, false);
        Files.writeString(candidate.resolve("suite-aggregate.jsonl"),
                Files.readString(candidate.resolve("suite-aggregate.jsonl"), StandardCharsets.UTF_8)
                        .replace(",\"affectedServerDatagramsOutPerSecond\":0", ""),
                StandardCharsets.UTF_8);

        ProcessResult rejectedComparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-baseline-suite.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("comparison.md").toString()
        );
        Assertions.assertEquals(1, rejectedComparison.exitCode, rejectedComparison.output);
        String rejectedReport = Files.readString(output.resolve("comparison.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(rejectedReport.contains("| Missing retry-pressure fields | 1 |"));
        Assertions.assertTrue(rejectedReport.contains("affectedServerDatagramsOutPerSecond"));
        Assertions.assertTrue(rejectedReport.contains("1 missing retry-pressure field(s)"));

        ProcessResult smokeComparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-baseline-suite.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("comparison-smoke.md").toString(),
                "--allow-missing-retry-pressure-fields"
        );
        Assertions.assertEquals(0, smokeComparison.exitCode, smokeComparison.output);
        String smokeReport = Files.readString(output.resolve("comparison-smoke.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(smokeReport.contains("- Allow missing retry-pressure fields: `true`"));
        Assertions.assertTrue(smokeReport.contains("Comparison passed."));
    }

    @Test
    public void testComparisonRejectsHealthyFairnessAndRetryPressureRegressions() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-compare-fairness-retry-test");
        Path baseline = output.resolve("baseline");
        Path candidate = output.resolve("candidate");
        writeComparableSuite(baseline, false);
        writeComparableSuite(candidate, false);
        Files.writeString(candidate.resolve("suite-aggregate.jsonl"),
                Files.readString(candidate.resolve("suite-aggregate.jsonl"), StandardCharsets.UTF_8)
                        .replace("\"healthyDeliveredGbps\":1,", "\"healthyDeliveredGbps\":0.8,")
                        .replace("\"healthyClientMbpsP50\":5,", "\"healthyClientMbpsP50\":4,")
                        .replace("\"healthyFairnessIndex\":1,", "\"healthyFairnessIndex\":0.95,")
                        .replace("\"sentToDeliveredBytesRatio\":1,", "\"sentToDeliveredBytesRatio\":2,")
                        .replace("\"healthySentToDeliveredBytesRatio\":1,", "\"healthySentToDeliveredBytesRatio\":2,")
                        .replace("\"affectedSentToDeliveredBytesRatio\":0,", "\"affectedSentToDeliveredBytesRatio\":1,")
                        .replace("\"staleDatagramsPerSecond\":0,", "\"staleDatagramsPerSecond\":10,")
                        .replace("\"nackOutPerSecond\":0,", "\"nackOutPerSecond\":5,")
                        .replace("\"affectedUndeliveredServerGbps\":0,", "\"affectedUndeliveredServerGbps\":0.2,")
                        .replace("\"affectedServerDatagramsOutPerSecond\":0", "\"affectedServerDatagramsOutPerSecond\":100"),
                StandardCharsets.UTF_8);

        ProcessResult comparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-baseline-suite.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("comparison.md").toString()
        );
        Assertions.assertEquals(1, comparison.exitCode, comparison.output);
        JsonNode row = readJsonLines(output.resolve("comparison.jsonl")).get(0);
        List<String> reasons = JSON.convertValue(row.path("statusReasons"), new TypeReference<>() {
        });
        Assertions.assertTrue(reasons.contains("healthy-throughput-regression"));
        Assertions.assertTrue(reasons.contains("healthy-client-throughput-regression"));
        Assertions.assertTrue(reasons.contains("healthy-fairness-regression"));
        Assertions.assertTrue(reasons.contains("send-deliver-regression"));
        Assertions.assertTrue(reasons.contains("healthy-send-deliver-regression"));
        Assertions.assertTrue(reasons.contains("affected-send-deliver-regression"));
        Assertions.assertTrue(reasons.contains("affected-undelivered-send-work-regression"));
        Assertions.assertTrue(reasons.contains("affected-datagram-rate-regression"));
        Assertions.assertTrue(reasons.contains("stale-rate-regression"));
        Assertions.assertTrue(reasons.contains("nack-rate-regression"));
        String report = Files.readString(output.resolve("comparison.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(report.contains("- Healthy throughput regression threshold: `10%`"));
        Assertions.assertTrue(report.contains("- Healthy fairness regression threshold: `0.02`"));
        Assertions.assertTrue(report.contains("- Send-work regression threshold: `50%`"));
        Assertions.assertTrue(report.contains("- Retry-pressure regression threshold: `50%`"));
    }

    @Test
    public void testComparisonTreatsBatchShapeAsMatrixShape() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-compare-batch-shape-test");
        Path baseline = output.resolve("baseline");
        Path candidate = output.resolve("candidate");
        writeComparableBatchShapeSuite(baseline, 20, 8, 4);
        writeComparableBatchShapeSuite(candidate, 50, 4, 2);

        ProcessResult comparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-baseline-suite.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("comparison.md").toString()
        );
        Assertions.assertEquals(1, comparison.exitCode, comparison.output);
        JsonNode row = readJsonLines(output.resolve("comparison.jsonl")).get(0);
        List<String> reasons = JSON.convertValue(row.path("statusReasons"), new TypeReference<>() {
        });
        Assertions.assertTrue(reasons.contains("batch-interval-mismatch"));
        Assertions.assertTrue(reasons.contains("logical-packets-per-batch-mismatch"));
        Assertions.assertTrue(reasons.contains("batch-groups-mismatch"));
        String report = Files.readString(output.resolve("comparison.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(report.contains("50ms/4lp/2g / 20ms/8lp/4g"));
    }

    @Test
    public void testImpairmentSummaryAndPromotionRejectValidationBypasses() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-impairment-bypass-test");
        Path campaign = output.resolve("campaign");
        Path profileRoot = campaign.resolve("profiles/perfect");
        Path combined = profileRoot.resolve("combined");
        Path netem = campaign.resolve("netem");
        Files.createDirectories(combined);
        Files.createDirectories(netem);

        Files.writeString(campaign.resolve("manifest.jsonl"),
                "{\"profile\":\"perfect\",\"latency\":\"0ms\",\"jitter\":\"0ms\",\"loss\":\"0%\","
                        + "\"artifactRoot\":\"" + profileRoot + "\","
                        + "\"netemEvidenceDir\":\"" + netem + "\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(combined.resolve("validation.json"),
                "{\"passed\":true,\"allowLoosePrereqGates\":true,\"rowCount\":1,\"capacityRowCount\":1,\"issues\":[]}\n",
                StandardCharsets.UTF_8);
        Files.writeString(combined.resolve("suite-aggregate.jsonl"),
                "{\"case\":\"perfect\",\"benchmarkName\":\"multi-client-fanout\",\"deliveredGbps\":1,"
                        + "\"probeRttP99Millis\":1"
                        + readinessRetryFieldsJson() + "}\n",
                StandardCharsets.UTF_8);
        Files.writeString(combined.resolve("bandwidth-capacity.jsonl"),
                "{\"summaryKind\":\"bandwidth-capacity\",\"case\":\"perfect\",\"payloadSize\":512,"
                        + "\"selected\":true}\n",
                StandardCharsets.UTF_8);
        Files.writeString(netem.resolve("perfect-status-before.txt"), "qdisc noqueue 0: root\n", StandardCharsets.UTF_8);

        ProcessResult strictSummary = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/summarize-lab-impairment.sh").toString(),
                "--manifest", campaign.resolve("manifest.jsonl").toString(),
                "--out", campaign.resolve("summary-strict").toString()
        );
        Assertions.assertEquals(1, strictSummary.exitCode, strictSummary.output);
        JsonNode strictSummaryJson = JSON.readTree(Files.readString(
                campaign.resolve("summary-strict/impairment-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertFalse(strictSummaryJson.path("passed").asBoolean());
        Assertions.assertTrue(strictSummaryJson.findValuesAsText("code")
                .contains("validation-bypass-flags"));

        ProcessResult smokeSummary = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/summarize-lab-impairment.sh").toString(),
                "--manifest", campaign.resolve("manifest.jsonl").toString(),
                "--out", campaign.resolve("summary-smoke").toString(),
                "--allow-validation-bypasses"
        );
        Assertions.assertEquals(0, smokeSummary.exitCode, smokeSummary.output);
        JsonNode smokeSummaryJson = JSON.readTree(Files.readString(
                campaign.resolve("summary-smoke/impairment-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertTrue(smokeSummaryJson.path("passed").asBoolean());
        Assertions.assertTrue(smokeSummaryJson.path("allowValidationBypasses").asBoolean());
        Assertions.assertEquals("allowLoosePrereqGates",
                smokeSummaryJson.path("profiles").get(0).path("validation").path("bypassFlags").get(0).asText());
        Assertions.assertTrue(smokeSummaryJson.path("profiles").get(0).path("aggregate")
                .path("contentionRows").get(0).has("undeliveredServerGbps"));
        Assertions.assertTrue(smokeSummaryJson.path("profiles").get(0).path("aggregate")
                .path("contentionRows").get(0).has("affectedServerDatagramsOutPerSecond"));

        ProcessResult rejectedPromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-impairment.sh").toString(),
                "--input", campaign.resolve("summary-smoke").toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "impairment-smoke",
                "--no-latest"
        );
        Assertions.assertEquals(1, rejectedPromotion.exitCode, rejectedPromotion.output);
        Assertions.assertTrue(rejectedPromotion.output.contains("allowed profile validation bypasses"));

        ProcessResult smokePromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-impairment.sh").toString(),
                "--input", campaign.resolve("summary-smoke").toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "impairment-smoke",
                "--no-latest",
                "--allow-validation-bypasses"
        );
        Assertions.assertEquals(0, smokePromotion.exitCode, smokePromotion.output);
        JsonNode promotionManifest = JSON.readTree(Files.readString(
                output.resolve("baselines/impairment-smoke/impairment-baseline-manifest.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(promotionManifest.path("allowValidationBypasses").asBoolean());
        Assertions.assertTrue(promotionManifest.path("summary").path("allowValidationBypasses").asBoolean());

        Files.writeString(combined.resolve("suite-aggregate.jsonl"),
                Files.readString(combined.resolve("suite-aggregate.jsonl"), StandardCharsets.UTF_8)
                        .replace("\"undeliveredServerGbps\":0,", ""),
                StandardCharsets.UTF_8);
        ProcessResult missingRetrySummary = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/summarize-lab-impairment.sh").toString(),
                "--manifest", campaign.resolve("manifest.jsonl").toString(),
                "--out", campaign.resolve("summary-missing-retry").toString(),
                "--allow-validation-bypasses"
        );
        Assertions.assertEquals(1, missingRetrySummary.exitCode, missingRetrySummary.output);
        JsonNode missingRetrySummaryJson = JSON.readTree(Files.readString(
                campaign.resolve("summary-missing-retry/impairment-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertFalse(missingRetrySummaryJson.path("passed").asBoolean());
        Assertions.assertTrue(missingRetrySummaryJson.findValuesAsText("code")
                .contains("missing-retry-pressure-field"));
        Assertions.assertFalse(missingRetrySummaryJson.path("profiles").get(0).path("aggregate")
                .path("contentionRows").get(0).has("undeliveredServerGbps"));

        ProcessResult smokeMissingRetrySummary = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/summarize-lab-impairment.sh").toString(),
                "--manifest", campaign.resolve("manifest.jsonl").toString(),
                "--out", campaign.resolve("summary-missing-retry-smoke").toString(),
                "--allow-validation-bypasses",
                "--allow-missing-retry-pressure-fields"
        );
        Assertions.assertEquals(0, smokeMissingRetrySummary.exitCode, smokeMissingRetrySummary.output);
        JsonNode smokeMissingRetrySummaryJson = JSON.readTree(Files.readString(
                campaign.resolve("summary-missing-retry-smoke/impairment-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertTrue(smokeMissingRetrySummaryJson.path("allowMissingRetryPressureFields").asBoolean());
        Assertions.assertEquals("undeliveredServerGbps",
                smokeMissingRetrySummaryJson.path("requiredRetryPressureFields").get(0).asText());

        ProcessResult missingRetryPromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-impairment.sh").toString(),
                "--input", campaign.resolve("summary-missing-retry-smoke").toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "impairment-missing-summary-retry",
                "--no-latest",
                "--allow-validation-bypasses",
                "--allow-missing-retry-pressure-fields"
        );
        Assertions.assertEquals(1, missingRetryPromotion.exitCode, missingRetryPromotion.output);
        Assertions.assertTrue(missingRetryPromotion.output.contains("undeliveredServerGbps"));
    }

    @Test
    public void testImpairmentPromotionRejectsMissingRetryPressureFields() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-impairment-promotion-retry-fields-test");
        Path summary = output.resolve("summary");
        writeComparableImpairmentSummary(summary, false);
        Files.writeString(summary.resolve("impairment-summary.json"),
                Files.readString(summary.resolve("impairment-summary.json"), StandardCharsets.UTF_8)
                        .replace(",\"affectedServerDatagramsOutPerSecond\":0", ""),
                StandardCharsets.UTF_8);

        ProcessResult rejectedPromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-impairment.sh").toString(),
                "--input", summary.toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "impairment-missing-retry-fields",
                "--no-latest"
        );
        Assertions.assertEquals(1, rejectedPromotion.exitCode, rejectedPromotion.output);
        Assertions.assertTrue(rejectedPromotion.output.contains("missing required retry-pressure fields"));
        Assertions.assertTrue(rejectedPromotion.output.contains("affectedServerDatagramsOutPerSecond"));
        Assertions.assertFalse(Files.exists(output.resolve(
                "baselines/impairment-missing-retry-fields/impairment-baseline-manifest.json")));
    }

    @Test
    public void testImpairmentPromotionRejectsMissingRetryPressureBypassMarker() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-impairment-promotion-retry-bypass-test");
        Path summary = output.resolve("summary");
        writeComparableImpairmentSummary(summary, false);
        Files.writeString(summary.resolve("impairment-summary.json"),
                Files.readString(summary.resolve("impairment-summary.json"), StandardCharsets.UTF_8)
                        .replace("\"allowValidationBypasses\":false,",
                                "\"allowValidationBypasses\":false,\"allowMissingRetryPressureFields\":true,"),
                StandardCharsets.UTF_8);

        ProcessResult rejectedPromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-impairment.sh").toString(),
                "--input", summary.toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "impairment-retry-bypass",
                "--no-latest"
        );
        Assertions.assertEquals(1, rejectedPromotion.exitCode, rejectedPromotion.output);
        Assertions.assertTrue(rejectedPromotion.output.contains("allowed missing retry-pressure fields"));
        Assertions.assertFalse(Files.exists(output.resolve(
                "baselines/impairment-retry-bypass/impairment-baseline-manifest.json")));

        ProcessResult smokePromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-impairment.sh").toString(),
                "--input", summary.toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "impairment-retry-bypass-smoke",
                "--no-latest",
                "--allow-missing-retry-pressure-fields"
        );
        Assertions.assertEquals(0, smokePromotion.exitCode, smokePromotion.output);
        JsonNode promotionManifest = JSON.readTree(Files.readString(
                output.resolve("baselines/impairment-retry-bypass-smoke/impairment-baseline-manifest.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(promotionManifest.path("allowMissingRetryPressureFields").asBoolean());
        Assertions.assertTrue(promotionManifest.path("summary").path("allowMissingRetryPressureFields").asBoolean());
    }

    @Test
    public void testImpairmentComparisonRejectsValidationBypassesByDefault() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-impairment-compare-bypass-test");
        Path baseline = output.resolve("baseline");
        Path candidate = output.resolve("candidate");
        writeComparableImpairmentSummary(baseline, false);
        writeComparableImpairmentSummary(candidate, true);

        ProcessResult rejectedComparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-lab-impairment.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("impairment-comparison.md").toString()
        );
        Assertions.assertEquals(1, rejectedComparison.exitCode, rejectedComparison.output);
        String rejectedReport = Files.readString(output.resolve("impairment-comparison.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(rejectedReport.contains("| Validation bypass summaries | 1 |"));
        Assertions.assertTrue(rejectedReport.contains("allowValidationBypasses"));
        Assertions.assertTrue(rejectedReport.contains("allowUnstable"));
        Assertions.assertTrue(rejectedReport.contains("1 validation bypass summary input(s)"));

        ProcessResult smokeComparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-lab-impairment.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("impairment-comparison-smoke.md").toString(),
                "--allow-validation-bypasses"
        );
        Assertions.assertEquals(0, smokeComparison.exitCode, smokeComparison.output);
        String smokeReport = Files.readString(output.resolve("impairment-comparison-smoke.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(smokeReport.contains("- Allow validation bypasses: `true`"));
        Assertions.assertTrue(smokeReport.contains("Comparison passed."));
    }

    @Test
    public void testImpairmentComparisonRejectsMissingRetryPressureBypasses() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-impairment-compare-retry-fields-test");
        Path baseline = output.resolve("baseline");
        Path candidate = output.resolve("candidate");
        writeComparableImpairmentSummary(baseline, false);
        writeComparableImpairmentSummary(candidate, false);
        Files.writeString(candidate.resolve("impairment-summary.json"),
                Files.readString(candidate.resolve("impairment-summary.json"), StandardCharsets.UTF_8)
                        .replace("\"allowValidationBypasses\":false,",
                                "\"allowValidationBypasses\":false,\"allowMissingRetryPressureFields\":true,")
                        .replace(",\"affectedServerDatagramsOutPerSecond\":0", ""),
                StandardCharsets.UTF_8);

        ProcessResult rejectedComparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-lab-impairment.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("impairment-comparison.md").toString()
        );
        Assertions.assertEquals(1, rejectedComparison.exitCode, rejectedComparison.output);
        String rejectedReport = Files.readString(output.resolve("impairment-comparison.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(rejectedReport.contains("| Missing retry-pressure policy | 1 |"));
        Assertions.assertTrue(rejectedReport.contains("affectedServerDatagramsOutPerSecond"));
        Assertions.assertTrue(rejectedReport.contains("1 retry-pressure field policy issue(s)"));

        ProcessResult smokeComparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-lab-impairment.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("impairment-comparison-smoke.md").toString(),
                "--allow-missing-retry-pressure-fields"
        );
        Assertions.assertEquals(0, smokeComparison.exitCode, smokeComparison.output);
        String smokeReport = Files.readString(output.resolve("impairment-comparison-smoke.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(smokeReport.contains("- Allow missing retry-pressure fields: `true`"));
        Assertions.assertTrue(smokeReport.contains("Comparison passed."));
    }

    @Test
    public void testImpairmentComparisonSeparatesBatchShapeRows() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-impairment-compare-batch-shape-test");
        Path baseline = output.resolve("baseline");
        Path candidate = output.resolve("candidate");
        writeComparableImpairmentBatchShapeSummary(baseline, 20, 50);
        writeComparableImpairmentBatchShapeSummary(candidate, 50);

        ProcessResult comparison = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/compare-lab-impairment.sh").toString(),
                "--baseline", baseline.toString(),
                "--candidate", candidate.toString(),
                "--out", output.resolve("impairment-comparison.md").toString()
        );
        Assertions.assertEquals(1, comparison.exitCode, comparison.output);
        List<JsonNode> rows = readJsonLines(output.resolve("impairment-comparison.jsonl"));
        Assertions.assertTrue(rows.stream().anyMatch(row -> row.path("status").asText().equals("missing-candidate")
                && row.path("baseline").path("batchIntervalMillis").asInt() == 20));
        String report = Files.readString(output.resolve("impairment-comparison.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(report.contains("n/a / 20ms/8lp/4g"));
    }

    @Test
    public void testNetnsWorkerSmokeDryRunProducesManifest() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-netns-test").resolve("netns");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/run-netns-worker-smoke.sh").toString(),
                "--out", output.toString(),
                "--namespace-prefix", "rb-test",
                "--case", "fairness",
                "--clients", "10",
                "--affected-clients", "2",
                "--latency", "50ms",
                "--jitter", "5ms",
                "--loss", "2%",
                "--direction", "both",
                "--payload-size", "64",
                "--per-client-mbps", "1",
                "--warmup", "1s",
                "--duration", "2s",
                "--iterations", "1",
                "--start-offset", "30s"
        );

        Assertions.assertEquals(0, result.exitCode, result.output);
        Assertions.assertTrue(result.output.contains("Dry-run only"));
        Assertions.assertTrue(result.output.contains("raknet-netem.sh --interface srvi --action apply"));
        Assertions.assertTrue(result.output.contains("raknet-netem.sh --interface rcvi --action apply"));
        Assertions.assertTrue(result.output.contains("--limit 10000"));

        JsonNode manifest = JSON.readTree(Files.readString(output.resolve("manifest.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals("raknet-netns-worker-smoke", manifest.path("kind").asText());
        Assertions.assertEquals("fairness", manifest.path("case").asText());
        Assertions.assertEquals(10, manifest.path("clients").asInt());
        Assertions.assertEquals(8, manifest.path("healthyClients").asInt());
        Assertions.assertEquals(2, manifest.path("affectedClients").asInt());
        Assertions.assertEquals("50ms", manifest.path("latency").asText());
        Assertions.assertEquals("5ms", manifest.path("jitter").asText());
        Assertions.assertEquals("2%", manifest.path("loss").asText());
        Assertions.assertEquals("both", manifest.path("direction").asText());
        Assertions.assertEquals("legacy", manifest.path("recoveryMode").asText());
        Assertions.assertTrue(manifest.path("packetLimit").isNull());
        Assertions.assertTrue(manifest.path("globalPacketLimit").isNull());
        Assertions.assertTrue(manifest.path("maxQueuedBytes").isNull());
        Assertions.assertTrue(manifest.path("workers").isNull());
        Assertions.assertEquals(402_653_184L,
                manifest.path("resourceSafetyMaxAggregateQueuedBytes").asLong());
        Assertions.assertEquals(805_306_368L,
                manifest.path("resourceSafetyMaxDirectMemoryUsedBytes").asLong());
        Assertions.assertEquals(10_000, manifest.path("netemLimitPackets").asInt());
        Assertions.assertEquals(root.resolve("benchmark/build/install/benchmark").toString(),
                manifest.path("benchmarkDistribution").asText());
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--clients 10"));
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--recovery-mode legacy"));
        Assertions.assertTrue(manifest.path("serverArgs").asText()
                .contains("--resource-safety-max-aggregate-queued-bytes 402653184"));
        Assertions.assertTrue(manifest.path("healthyReceiverArgs").asText()
                .contains("--resource-safety-max-direct-memory-used-bytes 805306368"));
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--external-impairment-at-epoch-ms "));
        Assertions.assertTrue(manifest.path("healthyReceiverArgs").asText().contains("--clients 8"));
        Assertions.assertTrue(manifest.path("healthyReceiverArgs").asText().contains("--recovery-mode legacy"));
        Assertions.assertTrue(manifest.path("healthyReceiverArgs").asText().contains("--external-impairment-at-epoch-ms "));
        Assertions.assertTrue(manifest.path("affectedReceiverArgs").asText().contains("--clients 2"));
        Assertions.assertTrue(manifest.path("affectedReceiverArgs").asText().contains("--recovery-mode legacy"));
        Assertions.assertTrue(manifest.path("affectedReceiverArgs").asText().contains("--external-impairment-at-epoch-ms "));

        String readme = Files.readString(output.resolve("README.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(readme.contains("single-host smoke harness"));
        Assertions.assertTrue(readme.contains("does not prove NIC line-rate"));
        JsonNode caseStatus = JSON.readTree(Files.readString(output.resolve("case-status.json")));
        Assertions.assertEquals("planned", caseStatus.path("status").asText());
    }

    @Test
    public void testNetnsWorkerSmokeDryRunPlansExternalBlackhole() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-netns-blackhole-test").resolve("netns");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/run-netns-worker-smoke.sh").toString(),
                "--out", output.toString(),
                "--namespace-prefix", "rb-bh",
                "--case", "blackhole",
                "--clients", "10",
                "--affected-clients", "2",
                "--direction", "server-to-client",
                "--payload-size", "64",
                "--per-client-mbps", "1",
                "--warmup", "1s",
                "--duration", "2s",
                "--iterations", "1",
                "--probe-interval", "250ms",
                "--start-offset", "30s",
                "--blackhole-after", "1s",
                "--blackhole-duration", "500ms"
        );

        Assertions.assertEquals(0, result.exitCode, result.output);
        Assertions.assertTrue(result.output.contains("External blackhole scheduled"));
        Assertions.assertTrue(result.output.contains("apply 100% loss to server-to-client affected path"));
        Assertions.assertTrue(result.output.contains("--external-blackhole-at-epoch-ms"));
        Assertions.assertTrue(result.output.contains("--external-recovery-at-epoch-ms"));
        Assertions.assertFalse(result.output.contains("initial-netem"));

        JsonNode manifest = JSON.readTree(Files.readString(output.resolve("manifest.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals("blackhole", manifest.path("case").asText());
        Assertions.assertEquals("disappearing-clients", manifest.path("benchmarkName").asText());
        Assertions.assertEquals(10, manifest.path("clients").asInt());
        Assertions.assertEquals(8, manifest.path("healthyClients").asInt());
        Assertions.assertEquals(2, manifest.path("affectedClients").asInt());
        Assertions.assertEquals("server-to-client", manifest.path("direction").asText());
        Assertions.assertEquals(10_000, manifest.path("netemLimitPackets").asInt());
        Assertions.assertTrue(manifest.path("blackholeAtEpochMillis").asLong()
                > manifest.path("startAtEpochMillis").asLong());
        Assertions.assertEquals(250L, manifest.path("probeIntervalMillis").asLong());
        Assertions.assertEquals(500L, manifest.path("warmupDrainMillis").asLong());
        Assertions.assertEquals(2_500L,
                manifest.path("blackholeAtEpochMillis").asLong()
                        - manifest.path("startAtEpochMillis").asLong());
        Assertions.assertEquals(500L,
                manifest.path("recoveryAtEpochMillis").asLong()
                        - manifest.path("blackholeAtEpochMillis").asLong());
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--impaired-clients 2"));
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--external-blackhole-at-epoch-ms "));
        Assertions.assertTrue(manifest.path("affectedReceiverArgs").asText().contains("--impaired-clients 2"));
        Assertions.assertTrue(manifest.path("affectedReceiverArgs").asText().contains("--external-blackhole-at-epoch-ms "));
        Assertions.assertTrue(manifest.path("affectedReceiverArgs").asText().contains("--clients 2"));
        Assertions.assertTrue(manifest.path("healthyReceiverArgs").asText().contains("--clients 8"));
        Assertions.assertTrue(manifest.path("healthyReceiverArgs").asText().contains("--external-blackhole-at-epoch-ms "));
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--external-recovery-at-epoch-ms "));
        Assertions.assertTrue(manifest.path("affectedReceiverArgs").asText().contains("--external-recovery-at-epoch-ms "));
        Assertions.assertTrue(manifest.path("healthyReceiverArgs").asText().contains("--external-recovery-at-epoch-ms "));

        String readme = Files.readString(output.resolve("README.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(readme.contains("External recovery at epoch ms: `"));
    }

    @Test
    public void testNetnsWorkerChildSupervisionFailsFastEscalatesAndReaps() throws Exception {
        assumeShellTooling();
        Path script = repoRoot().resolve("benchmark/scripts/run-netns-worker-smoke.sh");
        String source = Files.readString(script, StandardCharsets.UTF_8);
        String beginMarker = "# BEGIN benchmark child supervision";
        String endMarker = "# END benchmark child supervision";
        int begin = source.indexOf(beginMarker);
        int end = source.indexOf(endMarker);
        Assertions.assertTrue(begin >= 0 && end > begin, "child-supervision source markers must exist");
        Assertions.assertTrue(source.contains("setsid bash -c \"$sampler_program\""));
        Assertions.assertTrue(source.contains("start_netem_helper \"$netem_at_ms\""));
        String functions = source.substring(begin + beginMarker.length(), end);
        Path nestedPidFile = Files.createTempFile("raknet-nested-helper-pid", ".txt");
        Files.delete(nestedPidFile);
        Path interruptedHelperPidFile = Files.createTempFile("raknet-interrupted-helper-pid", ".txt");
        Files.delete(interruptedHelperPidFile);
        Path interruptedWorkerPidFile = Files.createTempFile("raknet-interrupted-worker-pid", ".txt");
        Files.delete(interruptedWorkerPidFile);
        String harness = "set -euo pipefail\n" + functions + "\n"
                + "worker_stop_grace_seconds=1\n"
                + "setsid bash -c 'trap \"\" TERM; while :; do sleep 1; done' & stubborn=$!\n"
                + "setsid sleep 30 & sibling=$!\n"
                + "setsid bash -c 'exit 7' & failed=$!\n"
                + "worker_pids=(\"$stubborn\" \"$sibling\" \"$failed\")\n"
                + "if wait_for_workers; then exit 20; fi\n"
                + "\"$worker_termination_escalated\"\n"
                + "! kill -0 \"$stubborn\" 2>/dev/null\n"
                + "! kill -0 \"$sibling\" 2>/dev/null\n"
                + "[[ ${#worker_pids[@]} -eq 0 ]]\n"
                + "nested_pid_file=$1\n"
                + "setsid bash -c 'sleep 30 & nested=$!; printf \"%s\\n\" \"$nested\" >\"$1\"; exit 9' -- \"$nested_pid_file\" & failed_helper=$!\n"
                + "setsid sleep 30 & helper_sibling=$!\n"
                + "for ignored in {1..100}; do [[ -s \"$nested_pid_file\" ]] && break; sleep 0.01; done\n"
                + "nested_helper=$(cat \"$nested_pid_file\")\n"
                + "helper_pids=(\"$failed_helper\" \"$helper_sibling\")\n"
                + "if wait_for_helpers; then exit 21; fi\n"
                + "! kill -0 \"$helper_sibling\" 2>/dev/null\n"
                + "! kill -0 \"$nested_helper\" 2>/dev/null\n"
                + "[[ ${#helper_pids[@]} -eq 0 ]]\n"
                + "setsid bash -c 'exit 0' & successful_helper=$!\n"
                + "helper_pids=(\"$successful_helper\")\n"
                + "wait_for_helpers\n"
                + "[[ ${#helper_pids[@]} -eq 0 ]]\n"
                + "interrupted_helper_pid_file=$2\n"
                + "(\n"
                + "  worker_stop_grace_seconds=1\n"
                + "  setsid bash -c 'trap \"\" TERM; while :; do sleep 1; done' & interrupted_helper=$!\n"
                + "  printf '%s\\n' \"$interrupted_helper\" >\"$interrupted_helper_pid_file\"\n"
                + "  helper_pids=(\"$interrupted_helper\")\n"
                + "  trap 'terminate_and_reap_process_groups \"${helper_pids[@]:-}\"' EXIT\n"
                + "  interrupted_parent=$BASHPID\n"
                + "  (sleep 0.2; kill -TERM \"$interrupted_parent\") &\n"
                + "  wait_for_helpers\n"
                + ") || true\n"
                + "interrupted_helper=$(cat \"$interrupted_helper_pid_file\")\n"
                + "! kill -0 \"$interrupted_helper\" 2>/dev/null\n"
                + "interrupted_worker_pid_file=$3\n"
                + "(\n"
                + "  worker_stop_grace_seconds=1\n"
                + "  setsid bash -c 'trap \"\" TERM; (trap \"\" TERM; while :; do sleep 1; done) & nested=$!; printf \"%s\\n\" \"$nested\" >\"$1\"; exit 0' -- \"$interrupted_worker_pid_file\" & interrupted_worker=$!\n"
                + "  worker_pids=(\"$interrupted_worker\")\n"
                + "  trap 'terminate_and_reap_process_groups \"${worker_pids[@]:-}\"' EXIT\n"
                + "  for ignored in {1..100}; do [[ -s \"$interrupted_worker_pid_file\" ]] && break; sleep 0.01; done\n"
                + "  interrupted_parent=$BASHPID\n"
                + "  (sleep 0.2; kill -TERM \"$interrupted_parent\") &\n"
                + "  wait_for_workers\n"
                + ") || true\n"
                + "interrupted_worker_child=$(cat \"$interrupted_worker_pid_file\")\n"
                + "! kill -0 \"$interrupted_worker_child\" 2>/dev/null\n";

        ProcessResult result = runProcess(repoRoot(), Duration.ofSeconds(10),
                "bash", "-c", harness, "--", nestedPidFile.toString(), interruptedHelperPidFile.toString(),
                interruptedWorkerPidFile.toString());

        Assertions.assertEquals(0, result.exitCode, result.output);
    }

    @Test
    public void testAutonomousNetnsGoalLauncherIsConstrained() throws Exception {
        Path root = repoRoot();
        String launcher = Files.readString(root.resolve("benchmark/scripts/raknet-netns-goal-root"),
                StandardCharsets.UTF_8);
        Assertions.assertTrue(launcher.contains("This privileged goal launcher does not accept arguments"));
        Assertions.assertTrue(launcher.contains("pilot|transition|long-hold|cap-sweep|cohort-sweep|all"));
        Assertions.assertTrue(launcher.contains("recovery_mode_file=\"$state_root/recovery-mode\""));
        Assertions.assertTrue(launcher.contains("legacy|bounded|model_based"));
        Assertions.assertTrue(launcher.contains("stat -c %U:%G \"$recovery_mode_file\""));
        Assertions.assertTrue(launcher.contains("stat -c %a \"$recovery_mode_file\""));
        Assertions.assertTrue(launcher.contains("stat -c %s \"$recovery_mode_file\""));
        Assertions.assertTrue(launcher.contains("--recovery-mode \"$recovery_mode\""));
        Assertions.assertTrue(launcher.contains("require_trusted_path \"$launcher_path\""));
        Assertions.assertTrue(launcher.contains("flock -n 9"));
        Assertions.assertTrue(launcher.contains("timeout --signal=TERM --kill-after=30s"));
        Assertions.assertTrue(launcher.contains("result_owner=\"zulu\""));
        Assertions.assertTrue(launcher.contains("benchmark_user=\"$result_owner\""));
        Assertions.assertTrue(launcher.contains("BENCHMARK_RUN_USER=\"$benchmark_user\""));
        Assertions.assertFalse(launcher.contains("rakbench"));
        Assertions.assertTrue(launcher.contains("--direction both"));
        Assertions.assertTrue(launcher.contains("run_campaign pilot-disappearance"));
        Assertions.assertTrue(launcher.contains("--duration 40s"));
        Assertions.assertTrue(launcher.contains(
                "require_trusted_path \"$install_root/benchmark/scripts/validate-qdisc-timeseries.sh\""));
        Assertions.assertTrue(launcher.contains("cp -P --no-preserve=mode,ownership,timestamps"));
        Assertions.assertTrue(launcher.contains("must contain only top-level regular jar files"));
        Assertions.assertTrue(launcher.contains("-printf '%f\\0'"));
        Assertions.assertFalse(launcher.contains("cp -LR"));
        Assertions.assertFalse(launcher.contains("eval "));

        String worker = Files.readString(root.resolve("benchmark/scripts/run-netns-worker-smoke.sh"),
                StandardCharsets.UTF_8);
        Assertions.assertTrue(worker.contains(
                "mkdir -p \"$server_out\" \"$healthy_out\" \"$affected_out\" \"$merged_out\""));
        Assertions.assertTrue(worker.contains("chown \"$benchmark_run_user:$benchmark_run_group\""));
        Assertions.assertTrue(worker.contains("runuser --user \"$benchmark_run_user\" -- env"));
        Assertions.assertTrue(worker.contains("merge_args=(\"$script_dir/merge-worker-results.sh\""));
        Assertions.assertTrue(worker.contains("validate-qdisc-timeseries.sh"));
        Assertions.assertTrue(worker.contains("if ! stop_qdisc_samplers; then"));

        String installer = Files.readString(root.resolve("benchmark/scripts/install-raknet-netns-goal"),
                StandardCharsets.UTF_8);
        Assertions.assertTrue(installer.contains("This installer does not accept arguments"));
        Assertions.assertTrue(installer.contains("result_owner=\"zulu\""));
        Assertions.assertTrue(installer.contains("launcher_target=\"/usr/local/sbin/raknet-netns-pilot\""));
        Assertions.assertTrue(installer.contains("  validate-qdisc-timeseries.sh\n)"));
        Assertions.assertTrue(installer.contains(
                "\"$source_root/validate-qdisc-timeseries.sh\""));
        Assertions.assertFalse(installer.contains("useradd"));
        Assertions.assertFalse(installer.contains("benchmark_home"));
        Assertions.assertFalse(installer.contains("rakbench"));
        Assertions.assertFalse(installer.contains("/etc/sudoers"));
        Assertions.assertTrue(installer.contains("recovery_mode_file=\"$state_root/recovery-mode\""));
        Assertions.assertTrue(installer.contains("printf 'legacy\\n' >\"$recovery_mode_file\""));
        Assertions.assertTrue(installer.indexOf("if [[ -L \"$recovery_mode_file\" ]]")
                < installer.indexOf("elif [[ ! -e \"$recovery_mode_file\" ]]"));
        Assertions.assertTrue(installer.contains(
                "Select bounded recovery: printf '%s\\\\n' bounded > $recovery_mode_file"));
        Assertions.assertTrue(installer.contains(
                "Select model-based recovery: printf '%s\\\\n' model_based > $recovery_mode_file"));

        String documentation = Files.readString(root.resolve("benchmark/docs/netns-autonomous-goal.md"),
                StandardCharsets.UTF_8);
        Assertions.assertTrue(documentation.contains("Candidate Java runs as the explicitly configured `zulu`"));
        Assertions.assertTrue(documentation.contains("normal filesystem access of the `zulu` account"));
        Assertions.assertTrue(documentation.contains(
                "zulu ALL=(root) NOPASSWD: /usr/local/sbin/raknet-netns-pilot \"\""));
        Assertions.assertTrue(documentation.contains(
                "printf '%s\\n' bounded > /var/lib/raknet-netns-benchmark/recovery-mode"));
        Assertions.assertTrue(documentation.contains(
                "printf '%s\\n' model_based > /var/lib/raknet-netns-benchmark/recovery-mode"));
    }

    @Test
    public void testQdiscTimeseriesValidatorRejectsCollectorErrorsAndMissingTargets() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-qdisc-validator-test");
        Path validator = root.resolve("benchmark/scripts/validate-qdisc-timeseries.sh");
        Path valid = output.resolve("valid.jsonl");
        Files.writeString(valid,
                "{\"epochMillis\":1000,\"namespace\":\"server\",\"interface\":\"eth0\",\"qdisc\":[]}\n"
                        + "{\"epochMillis\":1001,\"namespace\":\"client\",\"interface\":\"eth1\",\"qdisc\":[]}\n",
                StandardCharsets.UTF_8);

        ProcessResult validResult = runProcess(root, Duration.ofSeconds(10),
                "bash", validator.toString(), valid.toString(), "server:eth0", "client:eth1");
        Assertions.assertEquals(0, validResult.exitCode, validResult.output);

        Path failed = output.resolve("failed.jsonl");
        Files.writeString(failed,
                "{\"epochMillis\":1000,\"namespace\":\"server\",\"interface\":\"eth0\","
                        + "\"qdisc\":[],\"error\":\"tc failed\"}\n",
                StandardCharsets.UTF_8);
        ProcessResult failedResult = runProcess(root, Duration.ofSeconds(10),
                "bash", validator.toString(), failed.toString(), "server:eth0");
        Assertions.assertEquals(1, failedResult.exitCode, failedResult.output);
        Assertions.assertTrue(failedResult.output.contains("malformed or failed samples"));

        ProcessResult missingTarget = runProcess(root, Duration.ofSeconds(10),
                "bash", validator.toString(), valid.toString(), "missing:eth9");
        Assertions.assertEquals(1, missingTarget.exitCode, missingTarget.output);
        Assertions.assertTrue(missingTarget.output.contains("no successful sample"));
    }

    @Test
    public void testWorkerMergeRecordsExternalNetemImpairment() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-worker-merge-netem-test");
        Path server = output.resolve("server");
        Path receiver = output.resolve("receiver");
        Path evidence = output.resolve("netem");
        Path merged = output.resolve("merged");
        Files.createDirectories(server);
        Files.createDirectories(receiver);
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("qdisc.txt"), "netem evidence\n", StandardCharsets.UTF_8);

        String serverIteration = "{\"iteration\":%d,\"clients\":2,\"payloadSize\":512,"
                + "\"reliability\":\"RELIABLE_ORDERED\",\"targetMbps\":10,\"targetClientMbps\":5,"
                + "\"elapsedMillis\":1000,\"offeredGbps\":0.01,\"serverBytesOut\":750000,"
                + "\"healthyServerBytesOut\":625000,\"affectedServerBytesOut\":125000,"
                + "\"serverDatagramsOut\":1500,\"healthyServerDatagramsOut\":1250,"
                + "\"affectedServerDatagramsOut\":250,\"probesSent\":12,\"probesAcked\":10,"
                + "\"probeAckSpillover\":0,\"probeResponseRate\":0.833333333333,\"probeRttCount\":10,"
                + "\"probeRttP95Millis\":9,\"probeRttP99Millis\":10,\"maxQueuedBytes\":4096}";
        Files.writeString(server.resolve("summary.json"),
                "{\"runId\":\"server-run\",\"scenario\":\"multi-client-fanout\",\"role\":\"server\",\"recoveryMode\":\"model_based\","
                        + "\"probeReliability\":\"UNRELIABLE\",\"probePriority\":\"HIGH\","
                        + "\"probeSemantics\":\"UNRELIABLE/HIGH best-effort non-ordering through the weighted scheduler; lost probes are omitted from RTT samples\","
                        + "\"startAtEpochMillis\":1000,\"impairmentLatencyMillis\":0,"
                        + "\"impairmentJitterMillis\":0,\"impairmentLossPercent\":0,"
                        + "\"environment\":{\"gitRevision\":\"test-revision\"},\"iterations\":["
                        + serverIteration.formatted(1) + "," + serverIteration.formatted(2) + ","
                        + serverIteration.formatted(3) + "]}\n",
                StandardCharsets.UTF_8);

        String receiverIteration = "{\"iteration\":%d,\"clients\":2,\"affectedClients\":1,"
                + "\"elapsedMillis\":1000,\"bulkReceivedBytes\":600000,"
                + "\"bulkReceivedMessages\":1200,\"logicalPacketsReceived\":1200,\"peers\":["
                + "{\"id\":0,\"impaired\":false,\"bulkReceivedBytes\":500000},"
                + "{\"id\":1,\"impaired\":true,\"bulkReceivedBytes\":100000}]}";
        Files.writeString(receiver.resolve("summary.json"),
                "{\"runId\":\"receiver-run\",\"scenario\":\"multi-client-fanout\",\"role\":\"client\",\"recoveryMode\":\"model_based\","
                        + "\"probeReliability\":\"UNRELIABLE\",\"probePriority\":\"HIGH\","
                        + "\"probeSemantics\":\"UNRELIABLE/HIGH best-effort non-ordering through the weighted scheduler; lost probes are omitted from RTT samples\","
                        + "\"startAtEpochMillis\":1000,\"iterations\":["
                        + receiverIteration.formatted(1) + "," + receiverIteration.formatted(2) + ","
                        + receiverIteration.formatted(3) + "]}\n",
                StandardCharsets.UTF_8);

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/merge-worker-results.sh").toString(),
                "--server", server.toString(),
                "--receiver", receiver.toString(),
                "--out", merged.toString(),
                "--case", "external-poor",
                "--benchmark-name", "fairness",
                "--external-impairment-latency-ms", "100",
                "--external-impairment-jitter-ms", "10",
                "--external-impairment-loss-percent", "5",
                "--external-netem-limit-packets", "10000",
                "--netem-evidence", evidence.toString()
        );

        Assertions.assertEquals(0, result.exitCode, result.output);
        JsonNode summary = JSON.readTree(Files.readString(merged.resolve("lab-summary.json"),
                StandardCharsets.UTF_8));
        Assertions.assertEquals("100ms/10ms/5%", summary.path("aggregate").path("impairmentProfile").asText());
        Assertions.assertTrue(summary.path("aggregate").path("externalImpairment").asBoolean());
        Assertions.assertEquals(10_000,
                summary.path("aggregate").path("externalNetemLimitPackets").asInt());
        Assertions.assertEquals(evidence.toString(), summary.path("aggregate").path("netemEvidenceDir").asText());
        Assertions.assertEquals(100, summary.path("externalImpairment").path("latencyMillis").asInt());
        Assertions.assertEquals(10, summary.path("externalImpairment").path("jitterMillis").asInt());
        Assertions.assertEquals(5.0D, summary.path("externalImpairment").path("lossPercent").asDouble(), 0.001D);
        Assertions.assertEquals(10_000,
                summary.path("externalImpairment").path("limitPackets").asInt());
        Assertions.assertEquals("UNRELIABLE", summary.path("aggregate").path("probeReliability").asText());
        Assertions.assertTrue(summary.path("aggregate").path("probeTransportProvenanceValid").asBoolean());
        Assertions.assertTrue(summary.path("aggregate").path("recoveryModeProvenanceValid").asBoolean());
        Assertions.assertEquals("model_based", summary.path("aggregate").path("recoveryMode").asText());
        Assertions.assertEquals("model_based", summary.path("server").path("recoveryMode").asText());
        Assertions.assertEquals("model_based", summary.path("receivers").get(0).path("recoveryMode").asText());
        Assertions.assertEquals("HIGH", summary.path("receivers").get(0).path("probePriority").asText());
        Assertions.assertEquals(36, summary.path("aggregate").path("probesSent").asInt());
        Assertions.assertEquals(30, summary.path("aggregate").path("probesAcked").asInt());
        Assertions.assertEquals(0, summary.path("aggregate").path("probeAckSpillover").asInt());
        Assertions.assertEquals(10.0D / 12.0D,
                summary.path("aggregate").path("minimumProbeResponseRate").asDouble(), 0.000001D);
        Assertions.assertFalse(summary.path("aggregate").path("unstable").asBoolean());

        String report = Files.readString(merged.resolve("README.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(report.contains("Impairment: `100ms/10ms/5%` (external qdisc: `true`)"));
        Assertions.assertTrue(report.contains("Netem queue limit: `10000 packets`"));
        Assertions.assertTrue(report.contains("Netem evidence: `" + evidence + "`"));
        String csv = Files.readString(merged.resolve("lab-summary.csv"), StandardCharsets.UTF_8);
        Assertions.assertTrue(csv.lines().findFirst().orElseThrow()
                .contains("reliability,recovery_mode,probe_reliability,probe_priority,probe_semantics"));
        Assertions.assertTrue(csv.lines().findFirst().orElseThrow().contains(
                "impairment_profile,external_impairment,external_blackhole_at_epoch_ms,"
                        + "external_recovery_at_epoch_ms,netem_limit_packets,netem_evidence_dir"));
        Assertions.assertTrue(csv.contains("\"100ms/10ms/5%\",true,,,10000,\"" + evidence + "\""));

        Path wrongReceiver = output.resolve("receiver-wrong-provenance");
        Path wrongReceiverMerged = output.resolve("merged-wrong-receiver-provenance");
        Files.createDirectories(wrongReceiver);
        Files.writeString(wrongReceiver.resolve("summary.json"),
                Files.readString(receiver.resolve("summary.json"), StandardCharsets.UTF_8)
                        .replace("\"probePriority\":\"HIGH\"", "\"probePriority\":\"IMMEDIATE\""),
                StandardCharsets.UTF_8);
        ProcessResult wrongReceiverResult = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/merge-worker-results.sh").toString(),
                "--server", server.toString(),
                "--receiver", receiver.toString(),
                "--receiver", wrongReceiver.toString(),
                "--out", wrongReceiverMerged.toString(),
                "--case", "wrong-receiver-provenance");
        Assertions.assertEquals(0, wrongReceiverResult.exitCode, wrongReceiverResult.output);
        JsonNode wrongReceiverSummary = JSON.readTree(Files.readString(
                wrongReceiverMerged.resolve("lab-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertFalse(wrongReceiverSummary.path("aggregate")
                .path("probeTransportProvenanceValid").asBoolean());
        Assertions.assertTrue(wrongReceiverSummary.path("aggregate").path("probeReliability").isNull());
        Assertions.assertTrue(wrongReceiverSummary.path("aggregate").path("probePriority").isNull());
        Assertions.assertTrue(wrongReceiverSummary.path("aggregate").path("probeSemantics").isNull());
        Assertions.assertTrue(wrongReceiverSummary.path("aggregate").path("unstableReasons").toString()
                .contains("invalid-probe-transport-provenance"));
        Assertions.assertEquals("IMMEDIATE",
                wrongReceiverSummary.path("receivers").get(1).path("probePriority").asText());

        Path wrongRecoveryReceiver = output.resolve("receiver-wrong-recovery");
        Path wrongRecoveryMerged = output.resolve("merged-wrong-recovery");
        Files.createDirectories(wrongRecoveryReceiver);
        Files.writeString(wrongRecoveryReceiver.resolve("summary.json"),
                Files.readString(receiver.resolve("summary.json"), StandardCharsets.UTF_8)
                        .replace("\"recoveryMode\":\"model_based\"", "\"recoveryMode\":\"bounded\""),
                StandardCharsets.UTF_8);
        ProcessResult wrongRecoveryResult = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/merge-worker-results.sh").toString(),
                "--server", server.toString(),
                "--receiver", wrongRecoveryReceiver.toString(),
                "--out", wrongRecoveryMerged.toString(),
                "--case", "wrong-recovery-provenance");
        Assertions.assertNotEquals(0, wrongRecoveryResult.exitCode, wrongRecoveryResult.output);
        JsonNode wrongRecoverySummary = JSON.readTree(Files.readString(
                wrongRecoveryMerged.resolve("lab-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertFalse(wrongRecoverySummary.path("aggregate")
                .path("recoveryModeProvenanceValid").asBoolean());
        Assertions.assertTrue(wrongRecoverySummary.path("aggregate").path("recoveryMode").isNull());
        Assertions.assertTrue(wrongRecoverySummary.path("aggregate").path("unstableReasons").toString()
                .contains("invalid-recovery-mode-provenance"));

        Path missingRecoveryReceiver = output.resolve("receiver-missing-recovery");
        Path missingRecoveryMerged = output.resolve("merged-missing-recovery");
        Files.createDirectories(missingRecoveryReceiver);
        Files.writeString(missingRecoveryReceiver.resolve("summary.json"),
                Files.readString(receiver.resolve("summary.json"), StandardCharsets.UTF_8)
                        .replace(",\"recoveryMode\":\"model_based\"", ""),
                StandardCharsets.UTF_8);
        ProcessResult missingRecoveryResult = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/merge-worker-results.sh").toString(),
                "--server", server.toString(),
                "--receiver", missingRecoveryReceiver.toString(),
                "--out", missingRecoveryMerged.toString(),
                "--case", "missing-recovery-provenance");
        Assertions.assertNotEquals(0, missingRecoveryResult.exitCode, missingRecoveryResult.output);
        JsonNode missingRecoverySummary = JSON.readTree(Files.readString(
                missingRecoveryMerged.resolve("lab-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertFalse(missingRecoverySummary.path("aggregate")
                .path("recoveryModeProvenanceValid").asBoolean());
        Assertions.assertTrue(missingRecoverySummary.path("aggregate").path("recoveryMode").isNull());

        Path unsupportedRecoveryReceiver = output.resolve("receiver-unsupported-recovery");
        Path unsupportedRecoveryMerged = output.resolve("merged-unsupported-recovery");
        Files.createDirectories(unsupportedRecoveryReceiver);
        Files.writeString(unsupportedRecoveryReceiver.resolve("summary.json"),
                Files.readString(receiver.resolve("summary.json"), StandardCharsets.UTF_8)
                        .replace("\"recoveryMode\":\"model_based\"", "\"recoveryMode\":\"experimental\""),
                StandardCharsets.UTF_8);
        ProcessResult unsupportedRecoveryResult = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/merge-worker-results.sh").toString(),
                "--server", server.toString(),
                "--receiver", unsupportedRecoveryReceiver.toString(),
                "--out", unsupportedRecoveryMerged.toString(),
                "--case", "unsupported-recovery-provenance");
        Assertions.assertNotEquals(0, unsupportedRecoveryResult.exitCode, unsupportedRecoveryResult.output);
        JsonNode unsupportedRecoverySummary = JSON.readTree(Files.readString(
                unsupportedRecoveryMerged.resolve("lab-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertFalse(unsupportedRecoverySummary.path("aggregate")
                .path("recoveryModeProvenanceValid").asBoolean());
        Assertions.assertTrue(unsupportedRecoverySummary.path("aggregate").path("recoveryMode").isNull());

        Path missingReceiver = output.resolve("receiver-missing-provenance");
        Path missingReceiverMerged = output.resolve("merged-missing-receiver-provenance");
        Files.createDirectories(missingReceiver);
        Files.writeString(missingReceiver.resolve("summary.json"),
                Files.readString(receiver.resolve("summary.json"), StandardCharsets.UTF_8)
                        .replace(",\"probeSemantics\":\"UNRELIABLE/HIGH best-effort non-ordering through the weighted scheduler; lost probes are omitted from RTT samples\"", ""),
                StandardCharsets.UTF_8);
        ProcessResult missingReceiverResult = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/merge-worker-results.sh").toString(),
                "--server", server.toString(),
                "--receiver", missingReceiver.toString(),
                "--out", missingReceiverMerged.toString(),
                "--case", "missing-receiver-provenance");
        Assertions.assertEquals(0, missingReceiverResult.exitCode, missingReceiverResult.output);
        JsonNode missingReceiverSummary = JSON.readTree(Files.readString(
                missingReceiverMerged.resolve("lab-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertFalse(missingReceiverSummary.path("aggregate")
                .path("probeTransportProvenanceValid").asBoolean());
        Assertions.assertTrue(missingReceiverSummary.path("aggregate").path("unstableReasons").toString()
                .contains("invalid-probe-transport-provenance"));

        Path missingProbeServer = output.resolve("server-missing-probes");
        Path missingProbeMerged = output.resolve("merged-missing-probes");
        Files.createDirectories(missingProbeServer);
        Files.writeString(missingProbeServer.resolve("summary.json"),
                Files.readString(server.resolve("summary.json"), StandardCharsets.UTF_8)
                        .replace("\"probeResponseRate\":0.833333333333,\"probeRttCount\":10,"
                                        + "\"probeRttP95Millis\":9,\"probeRttP99Millis\":10",
                                "\"probeResponseRate\":0,\"probeRttCount\":0,"
                                        + "\"probeRttP95Millis\":null,\"probeRttP99Millis\":null"),
                StandardCharsets.UTF_8);
        ProcessResult missingProbeResult = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/merge-worker-results.sh").toString(),
                "--server", missingProbeServer.toString(),
                "--receiver", receiver.toString(),
                "--out", missingProbeMerged.toString(),
                "--case", "missing-probes");
        Assertions.assertEquals(0, missingProbeResult.exitCode, missingProbeResult.output);
        JsonNode missingProbeSummary = JSON.readTree(Files.readString(
                missingProbeMerged.resolve("lab-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertTrue(missingProbeSummary.path("aggregate").path("probeRttP99Millis").isNull());
        Assertions.assertTrue(missingProbeSummary.path("aggregate").path("unstableReasons").toString()
                .contains("missing-probe-p99"));
        Assertions.assertTrue(missingProbeSummary.path("aggregate").path("unstableReasons").toString()
                .contains("insufficient-probe-responses"));

        Path spilloverServer = output.resolve("server-spillover");
        Path spilloverMerged = output.resolve("merged-spillover");
        Files.createDirectories(spilloverServer);
        Files.writeString(spilloverServer.resolve("summary.json"),
                Files.readString(server.resolve("summary.json"), StandardCharsets.UTF_8)
                        .replace("\"probeAckSpillover\":0", "\"probeAckSpillover\":1"),
                StandardCharsets.UTF_8);
        ProcessResult spilloverResult = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/merge-worker-results.sh").toString(),
                "--server", spilloverServer.toString(),
                "--receiver", receiver.toString(),
                "--out", spilloverMerged.toString(),
                "--case", "probe-spillover");
        Assertions.assertEquals(0, spilloverResult.exitCode, spilloverResult.output);
        JsonNode spilloverSummary = JSON.readTree(Files.readString(
                spilloverMerged.resolve("lab-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals(3, spilloverSummary.path("aggregate").path("probeAckSpillover").asInt());
        Assertions.assertTrue(spilloverSummary.path("aggregate").path("unstableReasons").toString()
                .contains("probe-ack-spillover"));

        Path blackholeMerged = output.resolve("blackhole-merged");
        ProcessResult blackholeResult = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/merge-worker-results.sh").toString(),
                "--server", server.toString(),
                "--receiver", receiver.toString(),
                "--out", blackholeMerged.toString(),
                "--case", "external-blackhole",
                "--benchmark-name", "disappearing-clients",
                "--external-blackhole-at-epoch-ms", "1234567890",
                "--external-recovery-at-epoch-ms", "1234570890",
                "--external-netem-limit-packets", "10000",
                "--netem-evidence", evidence.toString()
        );
        Assertions.assertEquals(0, blackholeResult.exitCode, blackholeResult.output);
        JsonNode blackholeSummary = JSON.readTree(Files.readString(
                blackholeMerged.resolve("lab-summary.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals("blackhole-transition",
                blackholeSummary.path("externalImpairment").path("kind").asText());
        Assertions.assertEquals(100.0D,
                blackholeSummary.path("externalImpairment").path("blackholeLossPercent").asDouble(), 0.001D);
        Assertions.assertEquals(1_234_567_890L,
                blackholeSummary.path("aggregate").path("externalBlackholeAtEpochMillis").asLong());
        Assertions.assertEquals(1_234_570_890L,
                blackholeSummary.path("aggregate").path("externalRecoveryAtEpochMillis").asLong());
        Assertions.assertEquals("external-blackhole-transition",
                blackholeSummary.path("aggregate").path("impairmentProfile").asText());
        Assertions.assertTrue(blackholeSummary.path("aggregate").path("externalImpairment").asBoolean());
        Assertions.assertTrue(blackholeSummary.path("aggregate").path("externalBlackhole").asBoolean());
        Assertions.assertTrue(blackholeSummary.path("aggregate").path("externalRecovery").asBoolean());
        Assertions.assertEquals(10_000,
                blackholeSummary.path("externalImpairment").path("limitPackets").asInt());
    }

    @Test
    public void testNetnsPilotCampaignDryRunProducesDefinedProfileMatrix() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-netns-pilot-test").resolve("pilot");

        ProcessResult result = runProcess(root, Duration.ofSeconds(20),
                "bash",
                root.resolve("benchmark/scripts/run-netns-pilot-campaign.sh").toString(),
                "--out", output.toString(),
                "--clients", "10",
                "--affected-clients", "2",
                "--warmup", "1s",
                "--duration", "1s",
                "--iterations", "1",
                "--start-offset", "10s",
                "--netem-before-start", "2s"
        );

        Assertions.assertEquals(0, result.exitCode, result.output);
        Assertions.assertTrue(result.output.contains("Dry-run only"));
        JsonNode plan = JSON.readTree(Files.readString(output.resolve("campaign-plan.json"),
                StandardCharsets.UTF_8));
        Assertions.assertEquals("raknet-netns-pilot-campaign", plan.path("kind").asText());
        Assertions.assertFalse(plan.path("execute").asBoolean());
        Assertions.assertEquals(6, plan.path("profiles").size());
        Assertions.assertEquals("perfect", plan.path("profiles").get(0).path("profile").asText());
        Assertions.assertEquals("near-loss", plan.path("profiles").get(1).path("profile").asText());
        Assertions.assertEquals("regional-loss", plan.path("profiles").get(2).path("profile").asText());
        Assertions.assertEquals("poor", plan.path("profiles").get(3).path("profile").asText());
        Assertions.assertEquals("severe", plan.path("profiles").get(4).path("profile").asText());
        Assertions.assertEquals("blackhole", plan.path("profiles").get(5).path("profile").asText());
        Assertions.assertEquals(10_000, plan.path("parameters").path("netemLimitPackets").asInt());
        Assertions.assertEquals("legacy", plan.path("parameters").path("recoveryMode").asText());
        Assertions.assertTrue(Files.exists(output.resolve("cases/01-perfect/manifest.json")));
        Assertions.assertTrue(Files.exists(output.resolve("cases/06-blackhole/manifest.json")));

        JsonNode summary = JSON.readTree(Files.readString(output.resolve("campaign-summary.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(summary.path("executed").asBoolean());
        Assertions.assertFalse(summary.path("executionPassed").asBoolean());
        Assertions.assertEquals(6, summary.path("statuses").size());
        String report = Files.readString(output.resolve("campaign-summary.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(report.contains("Stability gate: `not-run`"));
    }

    @Test
    public void testNetnsPilotCampaignDryRunPlansTransition() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-netns-transition-test").resolve("transition");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/run-netns-pilot-campaign.sh").toString(),
                "--out", output.toString(),
                "--profiles", "blackhole",
                "--clients", "10",
                "--affected-clients", "2",
                "--warmup", "1s",
                "--duration", "1s",
                "--iterations", "1",
                "--start-offset", "10s",
                "--blackhole-after", "100ms",
                "--blackhole-duration", "200ms",
                "--direction", "both",
                "--recovery-mode", "model_based",
                "--packet-limit", "100",
                "--global-packet-limit", "200",
                "--max-queued-bytes", "4096",
                "--workers", "2"
        );

        Assertions.assertEquals(0, result.exitCode, result.output);
        JsonNode plan = JSON.readTree(Files.readString(output.resolve("campaign-plan.json"),
                StandardCharsets.UTF_8));
        Assertions.assertEquals(1, plan.path("profiles").size());
        Assertions.assertEquals("blackhole", plan.path("profiles").get(0).path("profile").asText());
        Assertions.assertEquals("200ms", plan.path("parameters").path("blackholeDuration").asText());
        Assertions.assertEquals("both", plan.path("parameters").path("direction").asText());
        Assertions.assertEquals("model_based", plan.path("parameters").path("recoveryMode").asText());
        Assertions.assertEquals(100, plan.path("parameters").path("packetLimit").asInt());
        Assertions.assertEquals(200, plan.path("parameters").path("globalPacketLimit").asInt());
        Assertions.assertEquals(4096, plan.path("parameters").path("maxQueuedBytes").asInt());
        Assertions.assertEquals(2, plan.path("parameters").path("workers").asInt());
        Assertions.assertEquals(402_653_184L,
                plan.path("parameters").path("resourceSafetyMaxAggregateQueuedBytes").asLong());
        Assertions.assertEquals(805_306_368L,
                plan.path("parameters").path("resourceSafetyMaxDirectMemoryUsedBytes").asLong());

        JsonNode manifest = JSON.readTree(Files.readString(
                output.resolve("cases/01-blackhole/manifest.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals(200L,
                manifest.path("recoveryAtEpochMillis").asLong()
                        - manifest.path("blackholeAtEpochMillis").asLong());
        Assertions.assertEquals("model_based", manifest.path("recoveryMode").asText());
        Assertions.assertEquals(100, manifest.path("packetLimit").asInt());
        Assertions.assertEquals(200, manifest.path("globalPacketLimit").asInt());
        Assertions.assertEquals(4096, manifest.path("maxQueuedBytes").asInt());
        Assertions.assertEquals(2, manifest.path("workers").asInt());
        Assertions.assertEquals(402_653_184L,
                manifest.path("resourceSafetyMaxAggregateQueuedBytes").asLong());
        Assertions.assertEquals(805_306_368L,
                manifest.path("resourceSafetyMaxDirectMemoryUsedBytes").asLong());
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--recovery-mode model_based"));
        Assertions.assertTrue(manifest.path("healthyReceiverArgs").asText().contains("--recovery-mode model_based"));
        Assertions.assertTrue(manifest.path("affectedReceiverArgs").asText().contains("--recovery-mode model_based"));
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--packet-limit 100"));
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--global-packet-limit 200"));
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--max-queued-bytes 4096"));
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--workers 2"));
        Assertions.assertTrue(manifest.path("serverArgs").asText()
                .contains("--external-recovery-at-epoch-ms "));
    }

    @Test
    public void testNetnsPilotCampaignRejectsInvalidOptionalKnobBeforePlanning() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-netns-invalid-optional-test").resolve("pilot");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash", root.resolve("benchmark/scripts/run-netns-pilot-campaign.sh").toString(),
                "--out", output.toString(), "--workers", "0");

        Assertions.assertEquals(2, result.exitCode, result.output);
        Assertions.assertTrue(result.output.contains("--workers must be a positive integer"));
        Assertions.assertFalse(Files.exists(output.resolve("campaign-plan.json")));
    }

    @Test
    public void testNetnsPilotCampaignRejectsInvalidResourceSafetyThresholdBeforePlanning() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-netns-invalid-safety-test").resolve("pilot");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash", root.resolve("benchmark/scripts/run-netns-pilot-campaign.sh").toString(),
                "--out", output.toString(),
                "--resource-safety-max-direct-memory-used-bytes", "0");

        Assertions.assertEquals(2, result.exitCode, result.output);
        Assertions.assertTrue(result.output.contains(
                "--resource-safety-max-direct-memory-used-bytes must be a positive integer"));
        Assertions.assertFalse(Files.exists(output.resolve("campaign-plan.json")));
    }

    @Test
    public void testBaselineReadinessReportsMissingBaselineRunOrder() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-readiness-run-order-test");
        Path labBaseline = output.resolve("missing-lab");
        Path impairmentBaseline = output.resolve("missing-impairment");
        Path readiness = output.resolve("readiness");

        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missing.exitCode, missing.output);
        JsonNode readinessJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        List<String> actionCodes = readinessJson.path("nextActions").findValuesAsText("code");
        Assertions.assertTrue(actionCodes.contains("prepare-fresh-lab-handoff"));
        Assertions.assertTrue(actionCodes.contains("run-perfect-lab-plan"));
        Assertions.assertTrue(actionCodes.contains("promote-lab-baseline"));
        Assertions.assertTrue(actionCodes.contains("run-impairment-campaign"));
        Assertions.assertTrue(actionCodes.contains("promote-impairment-baseline"));
        Assertions.assertEquals(4, readinessJson.path("proofChecklist").size());
        Assertions.assertEquals("Fresh handoff", readinessJson.path("proofChecklist").get(0).path("stage").asText());
        String proofChecklist = readinessJson.path("proofChecklist").toString();
        Assertions.assertTrue(proofChecklist.contains("combined/bandwidth-capacity.jsonl"));
        Assertions.assertTrue(proofChecklist.contains("promote-and-check.sh"));

        String report = Files.readString(readiness.resolve("readiness.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(report.contains("prepare-fresh-lab-handoff.sh"));
        Assertions.assertTrue(report.contains("perfect-plan/merge-all.sh"));
        Assertions.assertTrue(report.contains("impairment-plan/summarize-campaign.sh"));
        Assertions.assertTrue(report.contains("## Baseline Proof Checklist"));
        Assertions.assertTrue(report.contains("combined/bandwidth-capacity.jsonl"));
        Assertions.assertTrue(report.contains("impairment-baseline-manifest.json"));
    }

    @Test
    public void testBaselineReadinessCanAttachFreshHandoffEvidence() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-readiness-handoff-current-test");
        Path labBaseline = output.resolve("missing-lab");
        Path impairmentBaseline = output.resolve("missing-impairment");
        Path handoff = output.resolve("handoff");
        Path readiness = output.resolve("readiness");
        writeReadyFreshHandoff(handoff);

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--handoff", handoff.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, result.exitCode, result.output);
        JsonNode readinessJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(readinessJson.path("handoff").path("provided").asBoolean());
        Assertions.assertTrue(readinessJson.path("handoff").path("ready").asBoolean());
        Assertions.assertTrue(readinessJson.path("handoff").path("summary").path("ready").asBoolean());
        Assertions.assertTrue(readinessJson.path("handoff").path("preflight").path("ready").asBoolean());

        List<String> actionCodes = readinessJson.path("nextActions").findValuesAsText("code");
        Assertions.assertFalse(actionCodes.contains("prepare-fresh-lab-handoff"));
        Assertions.assertTrue(actionCodes.contains("run-perfect-lab-plan"));
        Assertions.assertTrue(actionCodes.contains("run-impairment-campaign"));

        Path perfectPlan = handoff.resolve("perfect-plan");
        JsonNode perfectAction = findAction(readinessJson, "run-perfect-lab-plan");
        Assertions.assertEquals(handoff.resolve("README.md").toString(), perfectAction.path("readme").asText());
        Assertions.assertEquals(perfectPlan.toString(), perfectAction.path("plan").asText());
        Assertions.assertEquals(handoff.resolve("perfect-artifacts").toString(),
                perfectAction.path("artifactRoot").asText());
        Assertions.assertTrue(perfectAction.path("command").asText()
                .contains(perfectPlan.resolve("check-plan-freshness.sh").toString()));
        Assertions.assertTrue(perfectAction.path("command").asText()
                .contains(perfectPlan.resolve("merge-all.sh").toString()));

        JsonNode promotePerfectAction = findAction(readinessJson, "promote-lab-baseline");
        Assertions.assertEquals(handoff.resolve("promote-and-check.sh").toString(),
                promotePerfectAction.path("helper").asText());
        Assertions.assertEquals(handoff.resolve("handoff-manifest.json").toString(),
                promotePerfectAction.path("handoffManifest").asText());
        Assertions.assertEquals(handoff.resolve("perfect-artifacts").toString(),
                promotePerfectAction.path("artifactRoot").asText());
        Assertions.assertTrue(promotePerfectAction.path("command").asText()
                .contains(handoff.resolve("perfect-artifacts").resolve("combined").toString()));
        Assertions.assertTrue(promotePerfectAction.path("command").asText()
                .contains(perfectPlan.resolve("curve-plan").resolve("manifest.jsonl").toString()));

        Path impairmentPlan = handoff.resolve("impairment-plan");
        JsonNode impairmentAction = findAction(readinessJson, "run-impairment-campaign");
        Assertions.assertEquals(handoff.resolve("README.md").toString(), impairmentAction.path("readme").asText());
        Assertions.assertEquals(impairmentPlan.toString(), impairmentAction.path("plan").asText());
        Assertions.assertEquals(handoff.resolve("impairment-artifacts").toString(),
                impairmentAction.path("artifactRoot").asText());
        Assertions.assertTrue(impairmentAction.path("command").asText()
                .contains(impairmentPlan.resolve("summarize-campaign.sh").toString()));

        JsonNode promoteImpairmentAction = findAction(readinessJson, "promote-impairment-baseline");
        Assertions.assertEquals(handoff.resolve("promote-and-check.sh").toString(),
                promoteImpairmentAction.path("helper").asText());
        Assertions.assertEquals(handoff.resolve("impairment-artifacts").toString(),
                promoteImpairmentAction.path("artifactRoot").asText());
        Assertions.assertTrue(promoteImpairmentAction.path("command").asText()
                .contains(handoff.resolve("impairment-artifacts").resolve("campaign-summary").toString()));

        String report = Files.readString(readiness.resolve("readiness.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(report.contains("Fresh handoff ready: `true`"));
        Assertions.assertTrue(report.contains("Fresh handoff lab executable: `false`"));
        Assertions.assertTrue(report.contains("Fresh handoff topology advisory: `Handoff is structurally ready"
                + " but uses local placeholder topology values; regenerate with the real server host and lab NIC"
                + " before separate-host execution.`"));
        Assertions.assertTrue(report.contains("Fresh handoff topology advisory reasons:"
                + " `loopback-server-host,loopback-interface`"));
        Assertions.assertTrue(report.contains("Fresh handoff README: `" + handoff.resolve("README.md") + "`"));
        Assertions.assertTrue(report.contains("Fresh handoff artifact collection JSON: `"
                + handoff.resolve("artifact-collection.json") + "`"));
        Assertions.assertTrue(report.contains("Fresh handoff artifact collection checklist: `"
                + handoff.resolve("artifact-collection.md") + "`"));
        Assertions.assertTrue(report.contains("Fresh handoff perfect-network plan: `" + perfectPlan + "`"));
        Assertions.assertTrue(report.contains("Fresh handoff impairment plan: `" + impairmentPlan + "`"));
        Assertions.assertTrue(report.contains("Fresh handoff promotion helper: `"
                + handoff.resolve("promote-and-check.sh") + "`"));
        Assertions.assertTrue(report.contains("Fresh perfect artifact root: `"
                + handoff.resolve("perfect-artifacts") + "`"));
        Assertions.assertTrue(report.contains("Fresh impairment artifact root: `"
                + handoff.resolve("impairment-artifacts") + "`"));
    }

    @Test
    public void testBaselineReadinessRequiresCurvePayloadCoverage() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400);
        writeReadinessImpairmentBaseline(impairmentBaseline);

        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missing.exitCode, missing.output);
        JsonNode missingReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingReadiness.path("ready").asBoolean());
        Assertions.assertTrue(missingReadiness.path("requiredCurvePayloadSizes").isArray());
        Assertions.assertTrue(missingReadiness.findValuesAsText("code").contains("lab-missing-curve-payload"));
        Assertions.assertTrue(missingReadiness.findValuesAsText("code").contains("lab-missing-capacity-payload"));
        Assertions.assertTrue(missingReadiness.path("nextActions").findValuesAsText("code")
                .contains("rerun-perfect-baseline"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
        JsonNode readyReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(readyReadiness.path("ready").asBoolean());
        Assertions.assertEquals(7, readyReadiness.path("requiredCurvePayloadSizes").size());
        Assertions.assertEquals(0, readyReadiness.path("nextActions").size());
    }

    @Test
    public void testBaselineReadinessRequiresImpairmentProfilePayloadCoverage() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-impairment-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline, false);

        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missing.exitCode, missing.output);
        JsonNode missingReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingReadiness.path("ready").asBoolean());
        Assertions.assertTrue(missingReadiness.path("requiredImpairmentContentionScenarios").isArray());
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("impairment-missing-capacity-payload"));

        writeReadinessImpairmentBaseline(impairmentBaseline, true);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
        JsonNode readyReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(readyReadiness.path("ready").asBoolean());
    }

    @Test
    public void testBaselineReadinessRequiresImpairmentContentionCoverage() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-impairment-contention-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline, true, false);

        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missing.exitCode, missing.output);
        JsonNode missingReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingReadiness.path("ready").asBoolean());
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("impairment-missing-contention-scenario"));

        writeReadinessImpairmentBaseline(impairmentBaseline, true, true);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
    }

    @Test
    public void testBaselineReadinessRequiresBatchAndResourceShapes() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-shape-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        List<String> labRows = new ArrayList<>(Files.readAllLines(labBaseline.resolve("suite-aggregate.jsonl"),
                StandardCharsets.UTF_8));
        labRows.removeIf(row -> row.contains("\"case\":\"batch-50ms\"")
                || row.contains("\"case\":\"resource-pack-262144\""));
        Files.write(labBaseline.resolve("suite-aggregate.jsonl"), labRows, StandardCharsets.UTF_8);

        writeReadinessImpairmentBaseline(impairmentBaseline);
        String impairmentSummary = Files.readString(impairmentBaseline.resolve("impairment-summary.json"),
                StandardCharsets.UTF_8)
                .replace(",{\"benchmarkName\":\"batched-game-traffic\",\"batchIntervalMillis\":50,"
                        + "\"logicalPacketsPerBatch\":8,\"batchGroups\":4"
                        + readinessRetryFieldsJson() + "}", "")
                .replace(",{\"benchmarkName\":\"resource-pack-transfer\",\"payloadSize\":262144,"
                        + "\"batchIntervalMillis\":200"
                        + readinessRetryFieldsJson() + "}", "");
        Files.writeString(impairmentBaseline.resolve("impairment-summary.json"), impairmentSummary,
                StandardCharsets.UTF_8);

        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missing.exitCode, missing.output);
        JsonNode missingReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingReadiness.path("ready").asBoolean());
        Assertions.assertEquals(3, missingReadiness.path("requiredBatchIntervalsMillis").size());
        Assertions.assertEquals(2, missingReadiness.path("requiredResourcePackChunkSizes").size());
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("lab-missing-batch-interval"));
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("lab-missing-resource-pack-shape"));
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("impairment-missing-batch-interval"));
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("impairment-missing-resource-pack-shape"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
    }

    @Test
    public void testBaselineReadinessRequiresImmediateShape() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-immediate-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        Files.writeString(labBaseline.resolve("suite-aggregate.jsonl"),
                Files.readString(labBaseline.resolve("suite-aggregate.jsonl"), StandardCharsets.UTF_8)
                        .replace("{\"case\":\"immediate-100x1-p256\",\"benchmarkName\":\"multi-client-fanout\","
                                + "\"payloadSize\":256,\"targetClientMbps\":1,\"affectedKind\":\"immediate\""
                                + readinessRetryFieldsJson() + "}\n", ""),
                StandardCharsets.UTF_8);

        writeReadinessImpairmentBaseline(impairmentBaseline);
        Files.writeString(impairmentBaseline.resolve("impairment-summary.json"),
                Files.readString(impairmentBaseline.resolve("impairment-summary.json"), StandardCharsets.UTF_8)
                        .replace("{\"case\":\"immediate-100x1-p256\",\"benchmarkName\":\"multi-client-fanout\","
                                + "\"payloadSize\":256,\"targetClientMbps\":1,\"affectedKind\":\"immediate\""
                                + readinessRetryFieldsJson() + "},", ""),
                StandardCharsets.UTF_8);

        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missing.exitCode, missing.output);
        JsonNode missingReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingReadiness.path("ready").asBoolean());
        Assertions.assertEquals(256, missingReadiness.path("requiredImmediatePayloadSizes").get(0).asInt());
        Assertions.assertEquals(1.0D, missingReadiness.path("requiredImmediateTargetClientMbps").asDouble(), 0.001D);
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("lab-missing-immediate-shape"));
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("impairment-missing-immediate-shape"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
    }

    @Test
    public void testBaselineReadinessRequiresBlackholeDisappearanceMode() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-disappearance-mode-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        String labAggregate = Files.readString(labBaseline.resolve("suite-aggregate.jsonl"), StandardCharsets.UTF_8)
                .replace("\"case\":\"disappear-blackhole\",\"benchmarkName\":\"disappearing-clients\","
                                + "\"payloadSize\":512,\"disappearanceMode\":\"blackhole\"",
                        "\"case\":\"disappear-close\",\"benchmarkName\":\"disappearing-clients\","
                                + "\"payloadSize\":512,\"disappearanceMode\":\"close\"");
        Files.writeString(labBaseline.resolve("suite-aggregate.jsonl"), labAggregate, StandardCharsets.UTF_8);

        writeReadinessImpairmentBaseline(impairmentBaseline);
        String impairmentSummary = Files.readString(impairmentBaseline.resolve("impairment-summary.json"),
                        StandardCharsets.UTF_8)
                .replace("\"benchmarkName\":\"disappearing-clients\",\"case\":\"disappear-blackhole\","
                                + "\"disappearanceMode\":\"blackhole\"",
                        "\"benchmarkName\":\"disappearing-clients\",\"case\":\"disappear-close\","
                                + "\"disappearanceMode\":\"close\"");
        Files.writeString(impairmentBaseline.resolve("impairment-summary.json"), impairmentSummary,
                StandardCharsets.UTF_8);

        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missing.exitCode, missing.output);
        JsonNode missingReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingReadiness.path("ready").asBoolean());
        Assertions.assertEquals("blackhole",
                missingReadiness.path("requiredDisappearanceModes").get(0).asText());
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("lab-missing-disappearance-mode"));
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("impairment-missing-disappearance-mode"));
    }

    @Test
    public void testBaselineReadinessRequiresRetryPressureFields() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-retry-field-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        Files.writeString(labBaseline.resolve("suite-aggregate.jsonl"),
                Files.readString(labBaseline.resolve("suite-aggregate.jsonl"), StandardCharsets.UTF_8)
                        .replace("\"undeliveredServerGbps\":0,", ""),
                StandardCharsets.UTF_8);

        writeReadinessImpairmentBaseline(impairmentBaseline);
        Files.writeString(impairmentBaseline.resolve("impairment-summary.json"),
                Files.readString(impairmentBaseline.resolve("impairment-summary.json"), StandardCharsets.UTF_8)
                        .replace("\"undeliveredServerGbps\":0,", ""),
                StandardCharsets.UTF_8);

        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missing.exitCode, missing.output);
        JsonNode missingReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingReadiness.path("ready").asBoolean());
        Assertions.assertTrue(missingReadiness.path("requiredRetryPressureFields").isArray());
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("lab-missing-retry-pressure-field"));
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("impairment-missing-retry-pressure-field"));
    }

    @Test
    public void testBaselineReadinessRequiresConcreteSelectedCapacityCandidates() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-capacity-candidate-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        writeInvalidReadinessLabCapacity(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);

        ProcessResult invalidLabCapacity = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, invalidLabCapacity.exitCode, invalidLabCapacity.output);
        JsonNode invalidLabReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(invalidLabReadiness.findValuesAsText("code")
                .contains("lab-invalid-selected-capacity"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        Files.writeString(impairmentBaseline.resolve("impairment-summary.json"),
                Files.readString(impairmentBaseline.resolve("impairment-summary.json"), StandardCharsets.UTF_8)
                        .replace("\"selectedDeliveredGbps\":1", "\"selectedDeliveredGbps\":0"),
                StandardCharsets.UTF_8);

        ProcessResult invalidImpairmentCapacity = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, invalidImpairmentCapacity.exitCode, invalidImpairmentCapacity.output);
        JsonNode invalidImpairmentReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(invalidImpairmentReadiness.findValuesAsText("code")
                .contains("impairment-invalid-selected-capacity"));
    }

    @Test
    public void testBaselineReadinessRequiresContentionValidationGates() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-contention-gate-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 499, 5.0D, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);

        ProcessResult weakClients = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, weakClients.exitCode, weakClients.output);
        JsonNode weakClientsReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(weakClientsReadiness.path("ready").asBoolean());
        Assertions.assertEquals(500, weakClientsReadiness.path("requiredMinContentionClients").asInt());
        Assertions.assertTrue(weakClientsReadiness.findValuesAsText("code")
                .contains("lab-contention-client-gate-too-low"));

        writeReadinessLabBaseline(labBaseline, 500, 4.9D, 64, 256, 512, 1200, 1340, 1400, 262144);
        ProcessResult weakRate = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, weakRate.exitCode, weakRate.output);
        JsonNode weakRateReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(weakRateReadiness.path("ready").asBoolean());
        Assertions.assertEquals(5.0D, weakRateReadiness.path("requiredMinContentionTargetClientMbps").asDouble(), 0.001D);
        Assertions.assertTrue(weakRateReadiness.findValuesAsText("code")
                .contains("lab-contention-target-client-mbps-gate-too-low"));

        writeReadinessLabBaseline(labBaseline, 500, 5.0D, 64, 256, 512, 1200, 1340, 1400, 262144);
        Path validationJson = labBaseline.resolve("validation.json");
        Files.writeString(validationJson,
                Files.readString(validationJson, StandardCharsets.UTF_8)
                        .replace("\"minIterations\":3", "\"minIterations\":2"),
                StandardCharsets.UTF_8);
        ProcessResult weakIterations = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, weakIterations.exitCode, weakIterations.output);
        JsonNode weakIterationsReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(weakIterationsReadiness.path("ready").asBoolean());
        Assertions.assertEquals(3, weakIterationsReadiness.path("requiredMinIterations").asInt());
        Assertions.assertTrue(weakIterationsReadiness.findValuesAsText("code")
                .contains("lab-iteration-gate-too-low"));

        writeReadinessLabBaseline(labBaseline, 500, 5.0D, 64, 256, 512, 1200, 1340, 1400, 262144);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
    }

    @Test
    public void testBaselineReadinessRejectsValidationBypasses() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-readiness-bypass-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        Files.writeString(labBaseline.resolve("baseline-manifest.json"),
                "{\"baselineKind\":\"raknet-lab-baseline\",\"allowValidationBypasses\":true}\n",
                StandardCharsets.UTF_8);
        Files.writeString(labBaseline.resolve("validation.json"),
                Files.readString(labBaseline.resolve("validation.json"), StandardCharsets.UTF_8)
                        .replace("\"passed\":true", "\"passed\":true,\"allowUnstable\":true"),
                StandardCharsets.UTF_8);
        Files.writeString(impairmentBaseline.resolve("impairment-baseline-manifest.json"),
                "{\"baselineKind\":\"raknet-lab-impairment-campaign\",\"allowValidationBypasses\":true}\n",
                StandardCharsets.UTF_8);

        ProcessResult bypassed = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, bypassed.exitCode, bypassed.output);
        JsonNode bypassedReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(bypassedReadiness.path("ready").asBoolean());
        Assertions.assertTrue(bypassedReadiness.findValuesAsText("code")
                .contains("lab-validation-bypasses-allowed"));
        Assertions.assertTrue(bypassedReadiness.findValuesAsText("code")
                .contains("lab-validation-bypass-flags"));
        Assertions.assertTrue(bypassedReadiness.findValuesAsText("code")
                .contains("impairment-validation-bypasses-allowed"));
        Assertions.assertTrue(bypassedReadiness.findValues("bypassFlags").toString()
                .contains("allowUnstable"));
    }

    @Test
    public void testBaselineReadinessRejectsMissingRetryPressureBypasses() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-readiness-retry-policy-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        Files.writeString(impairmentBaseline.resolve("impairment-baseline-manifest.json"),
                Files.readString(impairmentBaseline.resolve("impairment-baseline-manifest.json"), StandardCharsets.UTF_8)
                        .replace("\"baselineKind\":\"raknet-lab-impairment-campaign\"",
                                "\"baselineKind\":\"raknet-lab-impairment-campaign\",\"allowMissingRetryPressureFields\":true"),
                StandardCharsets.UTF_8);
        Files.writeString(impairmentBaseline.resolve("impairment-summary.json"),
                Files.readString(impairmentBaseline.resolve("impairment-summary.json"), StandardCharsets.UTF_8)
                        .replace("\"passed\":true,",
                                "\"passed\":true,\"allowMissingRetryPressureFields\":true,"),
                StandardCharsets.UTF_8);

        ProcessResult bypassed = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, bypassed.exitCode, bypassed.output);
        JsonNode bypassedReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(bypassedReadiness.path("ready").asBoolean());
        Assertions.assertTrue(bypassedReadiness.findValuesAsText("code")
                .contains("impairment-missing-retry-pressure-bypass-allowed"));
        Assertions.assertTrue(bypassedReadiness.findValuesAsText("code")
                .contains("impairment-summary-missing-retry-pressure-bypass-allowed"));

        String report = Files.readString(readiness.resolve("readiness.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(report.contains("- Allow missing retry-pressure fields: `true`"));
    }

    @Test
    public void testBaselineReadinessRequiresProductionEvidenceFingerprint() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-production-evidence-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        Files.writeString(labBaseline.resolve("baseline-manifest.json"),
                "{\"baselineKind\":\"raknet-lab-baseline\"}\n",
                StandardCharsets.UTF_8);

        ProcessResult missingEvidence = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missingEvidence.exitCode, missingEvidence.output);
        JsonNode missingEvidenceJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingEvidenceJson.path("ready").asBoolean());
        Assertions.assertTrue(missingEvidenceJson.findValuesAsText("code")
                .contains("lab-missing-production-evidence"));
    }

    @Test
    public void testBaselineReadinessRequiresMatchingHandoffProductionEvidence() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-readiness-handoff-evidence-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        Files.writeString(labBaseline.resolve("handoff-manifest.json"),
                Files.readString(labBaseline.resolve("handoff-manifest.json"), StandardCharsets.UTF_8)
                        .replace("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
                StandardCharsets.UTF_8);

        ProcessResult mismatched = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, mismatched.exitCode, mismatched.output);
        JsonNode mismatchedReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(mismatchedReadiness.path("ready").asBoolean());
        Assertions.assertTrue(mismatchedReadiness.findValuesAsText("code")
                .contains("lab-production-evidence-handoff-mismatch"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        Files.delete(labBaseline.resolve("handoff-manifest.json"));

        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missing.exitCode, missing.output);
        JsonNode missingReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingReadiness.path("ready").asBoolean());
        Assertions.assertTrue(missingReadiness.findValuesAsText("code")
                .contains("lab-missing-handoff-manifest"));
    }

    @Test
    public void testBaselineReadinessRequiresSourceAuditFingerprint() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-readiness-source-audit-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        Path labManifest = labBaseline.resolve("baseline-manifest.json");
        JsonNode manifestWithoutSourceAudit = JSON.readTree(Files.readString(labManifest, StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifestWithoutSourceAudit).remove("sourceAudit");
        Files.writeString(labManifest, JSON.writeValueAsString(manifestWithoutSourceAudit), StandardCharsets.UTF_8);

        ProcessResult missingBaselineSourceAudit = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missingBaselineSourceAudit.exitCode, missingBaselineSourceAudit.output);
        JsonNode missingBaselineSourceAuditJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(missingBaselineSourceAuditJson.path("requireSourceAudit").asBoolean());
        Assertions.assertTrue(missingBaselineSourceAuditJson.findValuesAsText("code")
                .contains("lab-missing-source-audit"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        Path handoffManifest = labBaseline.resolve("handoff-manifest.json");
        JsonNode handoffWithoutSourceAudit = JSON.readTree(Files.readString(handoffManifest, StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode) handoffWithoutSourceAudit).remove("sourceAudit");
        Files.writeString(handoffManifest, JSON.writeValueAsString(handoffWithoutSourceAudit), StandardCharsets.UTF_8);

        ProcessResult missingHandoffSourceAudit = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missingHandoffSourceAudit.exitCode, missingHandoffSourceAudit.output);
        JsonNode missingHandoffSourceAuditJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(missingHandoffSourceAuditJson.findValuesAsText("code")
                .contains("lab-handoff-missing-source-audit"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        JsonNode manifestWithMismatchedSourceAudit = JSON.readTree(Files.readString(labManifest, StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifestWithMismatchedSourceAudit.path("sourceAudit"))
                .put("sha256", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        Files.writeString(labManifest, JSON.writeValueAsString(manifestWithMismatchedSourceAudit),
                StandardCharsets.UTF_8);

        ProcessResult mismatchedSourceAudit = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, mismatchedSourceAudit.exitCode, mismatchedSourceAudit.output);
        JsonNode mismatchedSourceAuditJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(mismatchedSourceAuditJson.findValuesAsText("code")
                .contains("lab-source-audit-handoff-mismatch"));
    }

    @Test
    public void testBaselineReadinessRequiresPrereqReports() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-prereq-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaselineWithMetadata(labBaseline, 500, 5.0D, 1, 1, 1,
                64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);

        ProcessResult missingPrereqs = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missingPrereqs.exitCode, missingPrereqs.output);
        JsonNode missingPrereqsReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingPrereqsReadiness.path("ready").asBoolean());
        Assertions.assertEquals(2, missingPrereqsReadiness.path("requiredMinPrereqReports").asInt());
        Assertions.assertTrue(missingPrereqsReadiness.findValuesAsText("code")
                .contains("lab-missing-prereq-reports"));
        Assertions.assertTrue(missingPrereqsReadiness.findValuesAsText("code")
                .contains("lab-prereq-not-ready"));
        Assertions.assertTrue(missingPrereqsReadiness.findValuesAsText("code")
                .contains("lab-prereq-not-separate-hosts"));

        writeReadinessLabBaselineWithMetadata(labBaseline, 500, 5.0D, 2, 1, 2,
                64, 256, 512, 1200, 1340, 1400, 262144);
        ProcessResult notReadyPrereq = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, notReadyPrereq.exitCode, notReadyPrereq.output);
        JsonNode notReadyPrereqReadiness = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(notReadyPrereqReadiness.findValuesAsText("code")
                .contains("lab-prereq-not-ready"));
        Assertions.assertTrue(notReadyPrereqReadiness.findValuesAsText("code")
                .contains("lab-prereq-report-failed"));

        writeReadinessLabBaselineWithMetadata(labBaseline, 500, 5.0D, 2, 2, 2,
                64, 256, 512, 1200, 1340, 1400, 262144);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
    }

    @Test
    public void testBaselineReadinessRequiresPackagedHostAndPrereqReports() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-packaged-prereq-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        clearDirectory(labBaseline.resolve("host-reports"));
        clearDirectory(labBaseline.resolve("prereq-reports"));

        ProcessResult missingPackagedReports = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missingPackagedReports.exitCode, missingPackagedReports.output);
        JsonNode readinessJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(readinessJson.path("ready").asBoolean());
        Assertions.assertEquals(0, readinessJson.path("labBaseline").path("packagedHostReportCount").asInt());
        Assertions.assertEquals(0, readinessJson.path("labBaseline").path("packagedPrereq")
                .path("reportCount").asInt());
        Assertions.assertTrue(readinessJson.findValuesAsText("code")
                .contains("lab-missing-packaged-host-reports"));
        Assertions.assertTrue(readinessJson.findValuesAsText("code")
                .contains("lab-missing-packaged-prereq-reports"));
        Assertions.assertTrue(readinessJson.findValuesAsText("code")
                .contains("lab-packaged-prereq-not-ready"));
        Assertions.assertTrue(readinessJson.findValuesAsText("code")
                .contains("lab-packaged-prereq-not-separate-hosts"));
        Assertions.assertTrue(readinessJson.findValuesAsText("code")
                .contains("lab-packaged-prereq-strict-gates-missing"));
        Assertions.assertTrue(readinessJson.path("nextActions").findValuesAsText("code")
                .contains("fix-lab-evidence"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
    }

    @Test
    public void testBaselineReadinessRequiresPackagedArtifactCollection() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-packaged-collection-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        Files.delete(labBaseline.resolve("artifact-collection.json"));

        ProcessResult missingCollection = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missingCollection.exitCode, missingCollection.output);
        JsonNode missingCollectionJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(missingCollectionJson.path("ready").asBoolean());
        Assertions.assertTrue(missingCollectionJson.findValuesAsText("code")
                .contains("lab-missing-artifact-collection-json"));
        Assertions.assertTrue(missingCollectionJson.path("nextActions").findValuesAsText("code")
                .contains("promote-lab-baseline"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        ObjectNode collection = (ObjectNode) JSON.readTree(Files.readString(
                labBaseline.resolve("artifact-collection.json"), StandardCharsets.UTF_8));
        ArrayNode groups = JSON.createArrayNode();
        for (JsonNode group : collection.path("collectionGroups")) {
            if (!"impairment-netem-evidence".equals(group.path("id").asText())) {
                groups.add(group);
            }
        }
        collection.set("collectionGroups", groups);
        Files.writeString(labBaseline.resolve("artifact-collection.json"), JSON.writeValueAsString(collection),
                StandardCharsets.UTF_8);

        ProcessResult tamperedCollection = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, tamperedCollection.exitCode, tamperedCollection.output);
        JsonNode tamperedCollectionJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(tamperedCollectionJson.findValuesAsText("code")
                .contains("lab-artifact-collection-group-missing"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
        JsonNode readyJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertEquals(9, readyJson.path("labBaseline").path("artifactCollectionGroups").size());
    }

    @Test
    public void testBaselineReadinessRequiresPackagedImpairmentProfileEvidence() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-packaged-impairment-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        clearDirectory(impairmentBaseline.resolve("profiles"));

        ProcessResult missingPackagedProfiles = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missingPackagedProfiles.exitCode, missingPackagedProfiles.output);
        JsonNode readinessJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(readinessJson.path("ready").asBoolean());
        Assertions.assertEquals(5, readinessJson.path("impairmentBaseline").path("packagedProfiles").size());
        Assertions.assertTrue(readinessJson.findValuesAsText("code")
                .contains("impairment-missing-packaged-profile"));
        Assertions.assertTrue(readinessJson.path("nextActions").findValuesAsText("code")
                .contains("rerun-impairment-campaign"));

        writeReadinessImpairmentBaseline(impairmentBaseline);
        Files.delete(impairmentBaseline.resolve("profiles/poor/netem/poor-status-before.txt"));
        ProcessResult missingNetem = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missingNetem.exitCode, missingNetem.output);
        JsonNode missingNetemJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertTrue(missingNetemJson.findValuesAsText("code")
                .contains("impairment-missing-packaged-netem-status"));

        writeReadinessImpairmentBaseline(impairmentBaseline);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
    }

    @Test
    public void testBaselineReadinessRequiresPackagedPlanningManifests() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-packaged-manifest-readiness-test");
        Path labBaseline = output.resolve("lab");
        Path impairmentBaseline = output.resolve("impairment");
        Path readiness = output.resolve("readiness");

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        clearDirectory(labBaseline.resolve("manifests"));
        Files.delete(impairmentBaseline.resolve("campaign-manifest.jsonl"));

        ProcessResult missingManifests = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(1, missingManifests.exitCode, missingManifests.output);
        JsonNode readinessJson = JSON.readTree(Files.readString(readiness.resolve("readiness.json"),
                StandardCharsets.UTF_8));
        Assertions.assertFalse(readinessJson.path("ready").asBoolean());
        Assertions.assertEquals(0, readinessJson.path("labBaseline").path("packagedManifestCount").asInt());
        Assertions.assertFalse(readinessJson.path("impairmentBaseline")
                .path("packagedCampaignManifestExists").asBoolean());
        Assertions.assertTrue(readinessJson.findValuesAsText("code")
                .contains("lab-missing-packaged-manifests"));
        Assertions.assertTrue(readinessJson.findValuesAsText("code")
                .contains("impairment-missing-packaged-campaign-manifest"));

        writeReadinessLabBaseline(labBaseline, 64, 256, 512, 1200, 1340, 1400, 262144);
        writeReadinessImpairmentBaseline(impairmentBaseline);
        ProcessResult ready = runProcess(root, Duration.ofSeconds(10),
                "bash",
                root.resolve("benchmark/scripts/check-baseline-readiness.sh").toString(),
                "--lab-baseline", labBaseline.toString(),
                "--impairment-baseline", impairmentBaseline.toString(),
                "--out", readiness.toString()
        );
        Assertions.assertEquals(0, ready.exitCode, ready.output);
    }

    @Test
    public void testResultWriterProducesArtifacts() throws Exception {
        Path output = Files.createTempDirectory("raknet-benchmark-test");
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth",
                "--out", output.toString(),
                "--run-id", "unit",
                "--duration", "1s",
                "--warmup", "0ms",
                "--iterations", "1",
                "--payload-size", "64",
                "--max-queued-bytes", "1048576"
        });
        BenchmarkRunResult run = new BenchmarkRunResult(config, EnvironmentInfo.capture());
        LatencyHistogram histogram = new LatencyHistogram();
        for (int i = 0; i < 10; i++) {
            histogram.record(i % 2 == 0 ? 1_000_000L : 2_000_000L);
        }
        PeerStats peer = new PeerStats(0, false);
        peer.addBulkSent(64, 4);
        peer.addBulkReceived(64, 4);
        peer.addServerBytesOut(128);
        peer.addServerDatagramsOut(2);
        peer.addStaleDatagrams(3);
        peer.addNackIn(4);
        peer.addNackOut(5);
        for (int i = 0; i < 10; i++) {
            peer.addProbeSent();
            peer.addProbeAcked();
        }
        peer.addBlackholedDatagramIn();
        peer.addBlackholedDatagramOut();
        peer.queuedBytes(4_096);
        peer.recoveryState(1_000L, 2_048, 8_192.0D, 0.0D,
                25.0D, 5.0D, 500L, 1, 900L, 800L);
        peer.congestionModelState(1_000L, 100_000.0D, 120_000.0D,
                20L, 0.02D, 42L, false, false, 1L, 2L);
        run.add(new BenchmarkIterationResult(
                "unit",
                1,
                1,
                64,
                RakReliability.RELIABLE_ORDERED,
                0.0D,
                0.0D,
                DisappearanceMode.CLOSE,
                true,
                20,
                4,
                2,
                1000,
                histogram.snapshot(),
                Arrays.asList(peer.snapshot(true, true))
        ));

        Path directory = new BenchmarkResultWriter().write(run).toPath();
        Assertions.assertTrue(Files.exists(directory.resolve("summary.json")));
        Assertions.assertTrue(Files.exists(directory.resolve("timeline.jsonl")));
        Assertions.assertTrue(Files.exists(directory.resolve("timeseries.csv")));
        Assertions.assertTrue(Files.exists(directory.resolve("latency.hdr")));
        Assertions.assertTrue(Files.exists(directory.resolve("report.md")));
        Assertions.assertTrue(Files.exists(directory.resolve("bandwidth-capacity.jsonl")));
        Assertions.assertTrue(Files.exists(directory.resolve("bandwidth-capacity.csv")));
        Assertions.assertTrue(Files.exists(directory.resolve("bandwidth-capacity.md")));
        Assertions.assertTrue(Files.readString(directory.resolve("report.md"), StandardCharsets.UTF_8)
                .contains("| unit | 1 | 10 | 1.000000 | 0.000000% | 0.000000% | true | `insufficient-iterations` |"));
        Assertions.assertTrue(Files.readString(directory.resolve("report.md"), StandardCharsets.UTF_8)
                .contains("Direct capacity artifacts are written to"));
        JsonNode summary = JSON.readTree(Files.readString(directory.resolve("summary.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals("baseline-bandwidth", summary.path("scenario").asText());
        Assertions.assertEquals("unit", summary.path("runId").asText());
        Assertions.assertTrue(summary.has("perClientTargetMbps"));
        Assertions.assertTrue(summary.has("packetLimit"));
        Assertions.assertTrue(summary.has("globalPacketLimit"));
        Assertions.assertEquals(1_048_576, summary.path("configuredMaxQueuedBytes").asInt());
        Assertions.assertEquals(402_653_184L,
                summary.path("resourceSafetyMaxAggregateQueuedBytes").asLong());
        Assertions.assertEquals(805_306_368L,
                summary.path("resourceSafetyMaxDirectMemoryUsedBytes").asLong());
        Assertions.assertEquals("completed-no-abort", summary.path("resourceSafetyStatus").asText());
        Assertions.assertTrue(summary.has("startAtEpochMillis"));
        Assertions.assertEquals("timeline.jsonl", summary.path("timelineArtifact").asText());
        Assertions.assertEquals("independent-session-iterations", summary.path("measurementWindowSemantics").asText());
        Assertions.assertEquals(0, summary.path("timelineSummary").path("sampleCount").asInt());
        Assertions.assertTrue(summary.has("impairmentLatencyMillis"));
        Assertions.assertTrue(summary.has("impairmentJitterMillis"));
        Assertions.assertTrue(summary.has("impairmentLossPercent"));
        Assertions.assertTrue(summary.has("stability"));
        Assertions.assertEquals("unit", summary.path("stability").get(0).path("name").asText());
        Assertions.assertTrue(summary.path("stability").get(0).path("unstable").asBoolean());
        Assertions.assertEquals("insufficient-iterations", summary.path("stability").get(0).path("unstableReasons").get(0).asText());
        Assertions.assertEquals(10, summary.path("stability").get(0).path("minimumProbeResponses").asInt());
        Assertions.assertEquals(1.0D,
                summary.path("stability").get(0).path("minimumProbeResponseRate").asDouble(), 0.001D);
        Assertions.assertTrue(summary.has("disappearingClients"));
        Assertions.assertEquals("close", summary.path("disappearanceMode").asText());
        Assertions.assertEquals(20, summary.path("batchIntervalMillis").asLong());
        Assertions.assertEquals(8, summary.path("logicalPacketsPerBatch").asInt());
        Assertions.assertTrue(summary.path("batchPayloadSizes").isArray());
        Assertions.assertEquals(1, summary.path("iterations").size());
        Assertions.assertTrue(summary.path("iterations").get(0).has("deliveredGbps"));
        Assertions.assertEquals("close", summary.path("iterations").get(0).path("disappearanceMode").asText());
        Assertions.assertEquals(4, summary.path("iterations").get(0).path("logicalPacketsReceived").asLong());
        Assertions.assertTrue(summary.path("iterations").get(0).has("healthyFairnessIndex"));
        Assertions.assertEquals(128, summary.path("iterations").get(0).path("serverBytesOut").asLong());
        Assertions.assertEquals(2.0D, summary.path("iterations").get(0).path("serverDatagramsOutPerSecond").asDouble(), 0.001D);
        Assertions.assertEquals(64, summary.path("iterations").get(0).path("undeliveredServerBytesOut").asLong());
        Assertions.assertEquals(0.000000512D, summary.path("iterations").get(0).path("undeliveredServerGbps").asDouble(), 0.000000001D);
        Assertions.assertEquals(64, summary.path("iterations").get(0).path("healthyUndeliveredServerBytesOut").asLong());
        Assertions.assertEquals(0, summary.path("iterations").get(0).path("affectedUndeliveredServerBytesOut").asLong());
        Assertions.assertEquals(2.0D, summary.path("iterations").get(0).path("healthyServerDatagramsOutPerSecond").asDouble(), 0.001D);
        Assertions.assertEquals(0.0D, summary.path("iterations").get(0).path("affectedServerDatagramsOutPerSecond").asDouble(), 0.001D);
        Assertions.assertEquals(2.0D, summary.path("iterations").get(0).path("sentToDeliveredBytesRatio").asDouble(), 0.001D);
        Assertions.assertEquals(3.0D, summary.path("iterations").get(0).path("staleDatagramsPerSecond").asDouble(), 0.001D);
        Assertions.assertEquals(5.0D, summary.path("iterations").get(0).path("nackOutPerSecond").asDouble(), 0.001D);
        Assertions.assertEquals(0.000512D, summary.path("iterations").get(0).path("perClientThroughput").path("p50Mbps").asDouble(), 0.000001D);
        Assertions.assertTrue(summary.path("iterations").get(0).has("healthyClientThroughput"));
        Assertions.assertTrue(summary.path("iterations").get(0).has("affectedClientThroughput"));
        Assertions.assertTrue(summary.path("iterations").get(0).has("disconnects"));
        Assertions.assertEquals(1, summary.path("iterations").get(0).path("openPeers").asInt());
        Assertions.assertEquals(1, summary.path("iterations").get(0).path("activePeers").asInt());
        Assertions.assertEquals(1, summary.path("iterations").get(0).path("blackholedDatagramsIn").asLong());
        Assertions.assertEquals(1, summary.path("iterations").get(0).path("blackholedDatagramsOut").asLong());
        Assertions.assertEquals(4, summary.path("iterations").get(0).path("peers").get(0).path("logicalPacketsReceived").asLong());
        Assertions.assertTrue(summary.path("iterations").get(0).path("peers").get(0).path("channelOpen").asBoolean());
        Assertions.assertTrue(summary.path("iterations").get(0).path("peers").get(0).path("channelActive").asBoolean());
        Assertions.assertEquals(1, summary.path("iterations").get(0).path("peers").get(0).path("blackholedDatagramsIn").asLong());
        Assertions.assertEquals(1, summary.path("iterations").get(0).path("peers").get(0).path("blackholedDatagramsOut").asLong());
        Assertions.assertTrue(summary.path("iterations").get(0).path("peers").get(0).has("serverBytesOut"));
        JsonNode peerJson = summary.path("iterations").get(0).path("peers").get(0);
        Assertions.assertEquals(4_096L, peerJson.path("currentQueuedBytes").asLong());
        Assertions.assertEquals(2_048L, peerJson.path("bytesInFlight").asLong());
        Assertions.assertEquals(8_192.0D, peerJson.path("congestionWindowBytes").asDouble(), 0.001D);
        Assertions.assertEquals(20L, peerJson.path("congestionModel").path("minimumRttMillis").asLong());
        Assertions.assertEquals(120_000.0D,
                peerJson.path("congestionModel").path("pacingRateBytesPerSecond").asDouble(), 0.001D);
        Assertions.assertEquals(1L,
                peerJson.path("congestionModel").path("hardLossResponseCount").asLong());
        Assertions.assertEquals(2L,
                peerJson.path("congestionModel").path("delayLossResponseCount").asLong());

        List<Map<String, String>> rows = CSV
                .readerFor(new TypeReference<Map<String, String>>() {
                })
                .with(CsvSchema.emptySchema().withHeader())
                .<Map<String, String>>readValues(directory.resolve("timeseries.csv").toFile())
                .readAll();
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("unit", rows.get(0).get("name"));
        Assertions.assertEquals("RELIABLE_ORDERED", rows.get(0).get("reliability"));
        Assertions.assertTrue(rows.get(0).containsKey("delivered_gbps"));
        Assertions.assertTrue(rows.get(0).containsKey("target_client_mbps"));
        Assertions.assertTrue(rows.get(0).containsKey("server_datagrams_out_s"));
        Assertions.assertEquals("64", rows.get(0).get("undelivered_server_bytes_out"));
        Assertions.assertTrue(rows.get(0).containsKey("undelivered_server_gbps"));
        Assertions.assertEquals("64", rows.get(0).get("healthy_undelivered_server_bytes_out"));
        Assertions.assertEquals("0", rows.get(0).get("affected_undelivered_server_bytes_out"));
        Assertions.assertTrue(rows.get(0).containsKey("healthy_server_datagrams_out_s"));
        Assertions.assertTrue(rows.get(0).containsKey("affected_server_datagrams_out_s"));
        Assertions.assertTrue(rows.get(0).containsKey("sent_delivered_bytes_ratio"));
        Assertions.assertTrue(rows.get(0).containsKey("stale_datagrams_s"));
        Assertions.assertTrue(rows.get(0).containsKey("nack_out_s"));
        Assertions.assertTrue(rows.get(0).containsKey("client_mbps_p50"));
        Assertions.assertTrue(rows.get(0).containsKey("client_mbps_p99"));
        Assertions.assertTrue(rows.get(0).containsKey("healthy_client_mbps_p50"));
        Assertions.assertTrue(rows.get(0).containsKey("affected_client_mbps_p50"));
        Assertions.assertEquals("close", rows.get(0).get("disappearance_mode"));
        Assertions.assertEquals("true", rows.get(0).get("batched"));
        Assertions.assertEquals("4", rows.get(0).get("logical_packets_received"));
        Assertions.assertTrue(rows.get(0).containsKey("delivered_logical_packets_s"));
        Assertions.assertEquals("UNRELIABLE", rows.get(0).get("probe_reliability"));
        Assertions.assertEquals("HIGH", rows.get(0).get("probe_priority"));
        Assertions.assertEquals("10", rows.get(0).get("probes_sent"));
        Assertions.assertEquals("10", rows.get(0).get("probes_acked"));
        Assertions.assertEquals("0", rows.get(0).get("probe_ack_spillover"));
        Assertions.assertEquals("1.0", rows.get(0).get("probe_response_rate"));
        Assertions.assertEquals("10", rows.get(0).get("probe_rtt_count"));
        Assertions.assertTrue(rows.get(0).containsKey("healthy_fairness"));
        Assertions.assertTrue(rows.get(0).containsKey("disconnects"));
        Assertions.assertEquals("1", rows.get(0).get("open_peers"));
        Assertions.assertEquals("1", rows.get(0).get("active_peers"));
        Assertions.assertEquals("1", rows.get(0).get("blackholed_datagrams_in"));
        Assertions.assertEquals("1", rows.get(0).get("blackholed_datagrams_out"));
        Assertions.assertTrue(rows.get(0).containsKey("max_queued_bytes"));

        JsonNode capacity = readJsonLines(directory.resolve("bandwidth-capacity.jsonl")).get(0);
        Assertions.assertEquals("direct-bandwidth-capacity", capacity.path("summaryKind").asText());
        Assertions.assertFalse(capacity.path("selected").asBoolean());
        Assertions.assertEquals("unit", capacity.path("bestObservedCandidate").path("benchmarkName").asText());
        Assertions.assertTrue(capacity.path("bestObservedCandidate").path("rejectionReasons").toString()
                .contains("insufficient-iterations"));
    }

    @Test
    public void testDirectCurveResultWriterSelectsStableCapacity() throws Exception {
        Path output = Files.createTempDirectory("raknet-benchmark-capacity-test");
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "bandwidth-latency-curve",
                "--out", output.toString(),
                "--run-id", "capacity",
                "--duration", "1s",
                "--warmup", "0ms",
                "--iterations", "3",
                "--payload-size", "1200",
                "--rates-mbps", "100,250",
                "--packet-limit", "100000",
                "--global-packet-limit", "1000000",
                "--max-queued-bytes", "67108864"
        });
        BenchmarkRunResult run = new BenchmarkRunResult(config, EnvironmentInfo.capture());
        for (int iteration = 1; iteration <= 3; iteration++) {
            addCapacityIteration(run, "curve-100_0mbps", iteration, 100.0D, 12_500_000);
            addCapacityIteration(run, "curve-250_0mbps", iteration, 250.0D, 31_250_000);
        }

        Path directory = new BenchmarkResultWriter().write(run).toPath();
        List<JsonNode> capacityRows = readJsonLines(directory.resolve("bandwidth-capacity.jsonl"));
        Assertions.assertEquals(1, capacityRows.size());
        JsonNode capacity = capacityRows.get(0);
        Assertions.assertEquals("direct-bandwidth-capacity", capacity.path("summaryKind").asText());
        Assertions.assertTrue(capacity.path("selected").asBoolean());
        Assertions.assertEquals(2, capacity.path("candidateCount").asInt());
        Assertions.assertEquals(2, capacity.path("eligibleCandidateCount").asInt());
        Assertions.assertEquals("capacity", capacity.path("caseName").asText());
        Assertions.assertEquals("curve-250_0mbps", capacity.path("selectedCandidate").path("benchmarkName").asText());
        Assertions.assertEquals(0.25D, capacity.path("selectedCandidate").path("deliveredGbps").asDouble(), 0.000001D);
        Assertions.assertEquals("curve-250_0mbps", capacity.path("bestObservedCandidate").path("benchmarkName").asText());
        Assertions.assertEquals("UNRELIABLE", capacity.path("probeReliability").asText());
        Assertions.assertEquals("HIGH", capacity.path("probePriority").asText());
        Assertions.assertEquals(10, capacity.path("minimumProbeResponsesPerIteration").asInt());
        Assertions.assertEquals(0.5D, capacity.path("minimumProbeResponseRate").asDouble(), 0.000001D);
        Assertions.assertEquals(30, capacity.path("selectedCandidate").path("probesSent").asLong());
        Assertions.assertEquals(30, capacity.path("selectedCandidate").path("probesAcked").asLong());

        List<Map<String, String>> rows = CSV
                .readerFor(new TypeReference<Map<String, String>>() {
                })
                .with(CsvSchema.emptySchema().withHeader())
                .<Map<String, String>>readValues(directory.resolve("bandwidth-capacity.csv").toFile())
                .readAll();
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("true", rows.get(0).get("selected"));
        Assertions.assertEquals("curve-250_0mbps", rows.get(0).get("selected_benchmark"));
        Assertions.assertEquals("UNRELIABLE", rows.get(0).get("probe_reliability"));
        Assertions.assertEquals("HIGH", rows.get(0).get("probe_priority"));
        Assertions.assertEquals("1.0", rows.get(0).get("selected_probe_response_rate"));
        Assertions.assertTrue(Files.readString(directory.resolve("bandwidth-capacity.md"), StandardCharsets.UTF_8)
                .contains("| `capacity` | 1200 | `RELIABLE_ORDERED` | true | 0.250000 | 250.000000 |"));
    }

    private static void writeReadinessLabBaseline(Path labBaseline, int... payloadSizes) throws Exception {
        writeReadinessLabBaseline(labBaseline, 500, 5.0D, payloadSizes);
    }

    private static String productionEvidenceJson() {
        return "{\"document\":\"benchmark/docs/production-usage-evidence.md\","
                + "\"exists\":true,"
                + "\"sha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}";
    }

    private static String sourceAuditJson() {
        return "{\"document\":\"/tmp/source-audit.json\","
                + "\"exists\":true,"
                + "\"sha256\":\"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789\","
                + "\"ready\":true,"
                + "\"issueCount\":0,"
                + "\"networkRevision\":\"0123456789abcdef\","
                + "\"networkShortRevision\":\"0123456789ab\","
                + "\"networkDirtyTrackedFiles\":false,"
                + "\"evidenceDocument\":" + productionEvidenceJson() + ","
                + "\"requiredSources\":[\"geyser\",\"cubecraft\"],"
                + "\"sources\":["
                + "{\"id\":\"geyser\",\"visibility\":\"public\",\"available\":true,"
                + "\"revision\":\"abcdef0123456789\",\"shortRevision\":\"abcdef012345\","
                + "\"dirtyTrackedFiles\":false,\"pathIncluded\":false},"
                + "{\"id\":\"cubecraft\",\"visibility\":\"private\",\"available\":true,"
                + "\"revision\":\"fedcba9876543210\",\"shortRevision\":\"fedcba987654\","
                + "\"dirtyTrackedFiles\":false,\"pathIncluded\":false}"
                + "]}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeReadySourceAudit(Path sourceAudit) throws Exception {
        Files.createDirectories(sourceAudit.getParent());
        Files.writeString(sourceAudit,
                "{\"kind\":\"raknet-production-source-audit\","
                        + "\"ready\":true,"
                        + "\"issueCount\":0,"
                        + "\"networkRevision\":\"0123456789abcdef\","
                        + "\"networkShortRevision\":\"0123456789ab\","
                        + "\"networkDirtyTrackedFiles\":false,"
                        + "\"evidenceDocument\":{\"document\":\"benchmark/docs/production-usage-evidence.md\","
                        + "\"sha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"},"
                        + "\"requiredSources\":[\"geyser\",\"cubecraft\"],"
                        + "\"sources\":["
                        + "{\"id\":\"geyser\",\"visibility\":\"public\",\"available\":true,"
                        + "\"revision\":\"abcdef0123456789\",\"shortRevision\":\"abcdef012345\","
                        + "\"dirtyTrackedFiles\":false,\"path\":null},"
                        + "{\"id\":\"cubecraft\",\"visibility\":\"private\",\"available\":true,"
                        + "\"revision\":\"fedcba9876543210\",\"shortRevision\":\"fedcba987654\","
                        + "\"dirtyTrackedFiles\":false,\"path\":null}"
                        + "]}\n",
                StandardCharsets.UTF_8);
    }

    private static String readinessRetryFieldsJson() {
        return ",\"undeliveredServerGbps\":0,"
                + "\"affectedUndeliveredServerGbps\":0,"
                + "\"affectedServerDatagramsOutPerSecond\":0";
    }

    private static void writeHandoffManifest(Path handoffManifest) throws Exception {
        Path handoff = handoffManifest.getParent();
        Files.createDirectories(handoff);
        Path artifactCollectionJson = handoff.resolve("artifact-collection.json");
        Path artifactCollectionMd = handoff.resolve("artifact-collection.md");
        Path readme = handoff.resolve("README.md");
        Path prereqScript = handoff.resolve("prereq-commands.sh");
        Path promoteScript = handoff.resolve("promote-and-check.sh");
        Path perfectPlan = handoff.resolve("perfect-plan");
        Path impairmentPlan = handoff.resolve("impairment-plan");
        String perfectArtifacts = handoff.resolve("perfect-artifacts").toString();
        String impairmentArtifacts = handoff.resolve("impairment-artifacts").toString();
        writeArtifactCollection(artifactCollectionJson, artifactCollectionMd, handoffManifest,
                perfectArtifacts, impairmentArtifacts);
        Files.writeString(handoffManifest,
                "{\"kind\":\"raknet-lab-handoff\",\"productionEvidence\":"
                        + productionEvidenceJson() + ",\"sourceAudit\":"
                        + sourceAuditJson()
                        + ",\"perfectArtifacts\":\"" + jsonEscape(perfectArtifacts) + "\""
                        + ",\"impairmentArtifacts\":\"" + jsonEscape(impairmentArtifacts) + "\""
                        + ",\"artifactCollectionJson\":\"" + jsonEscape(artifactCollectionJson.toString()) + "\""
                        + ",\"artifactCollectionMd\":\"" + jsonEscape(artifactCollectionMd.toString()) + "\""
                        + ",\"readme\":\"" + jsonEscape(readme.toString()) + "\""
                        + ",\"prereqScript\":\"" + jsonEscape(prereqScript.toString()) + "\""
                        + ",\"promoteScript\":\"" + jsonEscape(promoteScript.toString()) + "\""
                        + ",\"perfectPlan\":\"" + jsonEscape(perfectPlan.toString()) + "\""
                        + ",\"impairmentPlan\":\"" + jsonEscape(impairmentPlan.toString()) + "\""
                        + "}\n",
                StandardCharsets.UTF_8);
    }

    private static void writeReadyFreshHandoff(Path handoff) throws Exception {
        writeHandoffManifest(handoff.resolve("handoff-manifest.json"));
        Path handoffManifest = handoff.resolve("handoff-manifest.json");
        Path artifactCollectionJson = handoff.resolve("artifact-collection.json");
        Path artifactCollectionMd = handoff.resolve("artifact-collection.md");
        Path readme = handoff.resolve("README.md");
        Path prereqScript = handoff.resolve("prereq-commands.sh");
        Path promoteScript = handoff.resolve("promote-and-check.sh");
        Path perfectPlan = handoff.resolve("perfect-plan");
        Path impairmentPlan = handoff.resolve("impairment-plan");
        Path perfectArtifacts = handoff.resolve("perfect-artifacts");
        Path impairmentArtifacts = handoff.resolve("impairment-artifacts");
        Files.writeString(handoff.resolve("fresh-handoff-summary.json"),
                "{\"kind\":\"raknet-fresh-lab-handoff\","
                        + "\"ready\":true,"
                        + "\"issueCount\":0,"
                        + "\"networkDirtyTrackedFiles\":false,"
                        + "\"sourceAuditIssueCount\":0,"
                        + "\"handoffIssueCount\":0,"
                        + "\"executionEnvironment\":{"
                        + "\"serverHost\":\"127.0.0.1\","
                        + "\"bindHost\":\"0.0.0.0\","
                        + "\"port\":19132,"
                        + "\"interface\":\"lo\","
                        + "\"labExecutable\":false,"
                        + "\"advisoryReasons\":[\"loopback-server-host\",\"loopback-interface\"],"
                        + "\"advisory\":\"Handoff is structurally ready but uses local placeholder topology values; regenerate with the real server host and lab NIC before separate-host execution.\""
                        + "},"
                        + "\"execution\":{"
                        + "\"handoffManifest\":\"" + jsonEscape(handoffManifest.toString()) + "\","
                        + "\"readme\":\"" + jsonEscape(readme.toString()) + "\","
                        + "\"artifactCollectionJson\":\"" + jsonEscape(artifactCollectionJson.toString()) + "\","
                        + "\"artifactCollectionMd\":\"" + jsonEscape(artifactCollectionMd.toString()) + "\","
                        + "\"prereqScript\":\"" + jsonEscape(prereqScript.toString()) + "\","
                        + "\"promoteScript\":\"" + jsonEscape(promoteScript.toString()) + "\","
                        + "\"perfectPlan\":\"" + jsonEscape(perfectPlan.toString()) + "\","
                        + "\"impairmentPlan\":\"" + jsonEscape(impairmentPlan.toString()) + "\","
                        + "\"perfectArtifacts\":\"" + jsonEscape(perfectArtifacts.toString()) + "\","
                        + "\"impairmentArtifacts\":\"" + jsonEscape(impairmentArtifacts.toString()) + "\""
                        + "}}\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(handoff.resolve("preflight"));
        Files.writeString(handoff.resolve("preflight/handoff-check.json"),
                "{\"ready\":true,\"issueCount\":0,\"issues\":[]}\n",
                StandardCharsets.UTF_8);
    }

    private static void writeArtifactCollection(Path artifactCollectionJson,
                                                Path artifactCollectionMd,
                                                Path handoffManifest,
                                                String perfectArtifacts,
                                                String impairmentArtifacts) throws Exception {
        Files.writeString(artifactCollectionJson,
                "{\"kind\":\"raknet-lab-artifact-collection\","
                        + "\"handoffManifest\":\"" + jsonEscape(handoffManifest.toString()) + "\","
                        + "\"perfectArtifacts\":\"" + jsonEscape(perfectArtifacts) + "\","
                        + "\"impairmentArtifacts\":\"" + jsonEscape(impairmentArtifacts) + "\","
                        + "\"prereqRoles\":[\"server\",\"receiver-a\",\"receiver-b\"],"
                        + "\"profiles\":[\"perfect\",\"near-loss\",\"regional-loss\",\"poor\",\"severe\"],"
                        + "\"collectionGroups\":["
                        + "{\"id\":\"perfect-topology\"},"
                        + "{\"id\":\"perfect-host-captures\"},"
                        + "{\"id\":\"perfect-prereq-reports\"},"
                        + "{\"id\":\"perfect-worker-artifacts\"},"
                        + "{\"id\":\"perfect-combined-artifacts\"},"
                        + "{\"id\":\"impairment-profile-artifacts\"},"
                        + "{\"id\":\"impairment-netem-evidence\"},"
                        + "{\"id\":\"impairment-campaign-summary\"},"
                        + "{\"id\":\"promotion-readiness\"}"
                        + "]}\n",
                StandardCharsets.UTF_8);
        Files.writeString(artifactCollectionMd,
                "# RakNet Lab Artifact Collection\n",
                StandardCharsets.UTF_8);
    }

    private static void writeReadinessLabBaseline(Path labBaseline,
                                                  int minContentionClients,
                                                  double minContentionTargetClientMbps,
                                                  int... payloadSizes) throws Exception {
        writeReadinessLabBaselineWithMetadata(labBaseline, minContentionClients, minContentionTargetClientMbps,
                2, 2, 2, payloadSizes);
    }

    private static void writeReadinessLabBaselineWithMetadata(Path labBaseline,
                                                              int minContentionClients,
                                                              double minContentionTargetClientMbps,
                                                              int prereqReportCount,
                                                              int readyPrereqReportCount,
                                                              int prereqDistinctHostnameCount,
                                                              int... payloadSizes) throws Exception {
        Files.createDirectories(labBaseline);
        Files.writeString(labBaseline.resolve("baseline-manifest.json"),
                "{\"baselineKind\":\"raknet-lab-baseline\",\"productionEvidence\":"
                        + productionEvidenceJson() + ",\"sourceAudit\":"
                        + sourceAuditJson() + "}\n",
                StandardCharsets.UTF_8);
        writeHandoffManifest(labBaseline.resolve("handoff-manifest.json"));
        Files.writeString(labBaseline.resolve("validation.json"),
                "{\"passed\":true,\"distinctHostnameCount\":2,\"hostReportCount\":2,\"rowCount\":"
                        + (payloadSizes.length + 9)
                        + ",\"capacityRowCount\":" + payloadSizes.length
                        + ",\"prereqReportCount\":" + prereqReportCount
                        + ",\"readyPrereqReportCount\":" + readyPrereqReportCount
                        + ",\"notReadyPrereqReportCount\":" + Math.max(0, prereqReportCount - readyPrereqReportCount)
                        + ",\"strictPrereqReportCount\":" + readyPrereqReportCount
                        + ",\"prereqDistinctHostnameCount\":" + prereqDistinctHostnameCount
                        + ",\"strictPrereqDistinctHostnameCount\":" + Math.min(readyPrereqReportCount, prereqDistinctHostnameCount)
                        + ",\"minIterations\":3"
                        + ",\"minContentionClients\":" + minContentionClients
                        + ",\"minContentionTargetClientMbps\":" + minContentionTargetClientMbps
                        + ",\"scenarioCounts\":{\"curve\":" + payloadSizes.length
                        + ",\"multi-client-fanout\":2,\"fairness\":1,\"disappearing-clients\":1,"
                        + "\"batched-game-traffic\":3,\"resource-pack-transfer\":2}}\n",
                StandardCharsets.UTF_8);

        Path manifests = labBaseline.resolve("manifests");
        clearDirectory(manifests);
        Files.createDirectories(manifests);
        Files.writeString(manifests.resolve("01-curve-plan-manifest.jsonl"),
                "{\"benchmarkName\":\"bandwidth-latency-curve\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(manifests.resolve("02-curve-raised-plan-manifest.jsonl"),
                "{\"benchmarkName\":\"bandwidth-latency-curve\",\"packetLimit\":100000}\n",
                StandardCharsets.UTF_8);
        Files.writeString(manifests.resolve("03-contention-plan-manifest.jsonl"),
                "{\"benchmarkName\":\"multi-client-fanout\"}\n",
                StandardCharsets.UTF_8);

        Path hostReports = labBaseline.resolve("host-reports");
        clearDirectory(hostReports);
        Files.createDirectories(hostReports);
        for (int host = 0; host < 2; host++) {
            Files.writeString(hostReports.resolve("host-" + host + "-host-report.md"),
                    "# Host Report\n\n- Hostname: `host-" + host + "`\n",
                    StandardCharsets.UTF_8);
        }

        Path prereqReports = labBaseline.resolve("prereq-reports");
        clearDirectory(prereqReports);
        Files.createDirectories(prereqReports);
        int distinctHostCount = Math.max(1, prereqDistinctHostnameCount);
        for (int prereq = 0; prereq < prereqReportCount; prereq++) {
            boolean ready = prereq < readyPrereqReportCount;
            int host = prereq % distinctHostCount;
            String strictFields = ready
                    ? ",\"requireClockSync\":true,\"requireNoNetem\":true,\"expectedMtu\":1500,"
                    + "\"interfaceMtu\":1500,\"expectedMinCpus\":1,\"cpuCount\":8"
                    : "";
            String prefix = "prereq-host-" + host + "-" + prereq;
            Files.writeString(prereqReports.resolve(prefix + "-prereq.json"),
                    "{\"ready\":" + ready
                            + ",\"hostname\":\"host-" + host + "\""
                            + strictFields + "}\n",
                    StandardCharsets.UTF_8);
            Files.writeString(prereqReports.resolve(prefix + "-prereq.md"),
                    "# Prereq\n",
                    StandardCharsets.UTF_8);
        }

        StringBuilder aggregate = new StringBuilder();
        StringBuilder capacity = new StringBuilder();
        for (int payloadSize : payloadSizes) {
            aggregate.append("{\"case\":\"lab-curve-p")
                    .append(payloadSize)
                    .append("\",\"benchmarkName\":\"curve-100_0mbps\",\"scenario\":\"curve\",\"payloadSize\":")
                    .append(payloadSize)
                    .append(readinessRetryFieldsJson())
                    .append("}\n");
            capacity.append("{\"summaryKind\":\"bandwidth-capacity\",\"case\":\"lab-curve-p")
                    .append(payloadSize)
                    .append("\",\"payloadSize\":")
                    .append(payloadSize)
                    .append(",\"selected\":true,\"selectedCandidate\":{\"benchmarkName\":\"curve-100_0mbps\",")
                    .append("\"deliveredGbps\":1.0,\"probeRttP99Millis\":1.0}}\n");
        }
        aggregate.append("{\"case\":\"fanout\",\"benchmarkName\":\"multi-client-fanout\",\"payloadSize\":512")
                .append(readinessRetryFieldsJson()).append("}\n");
        aggregate.append("{\"case\":\"immediate-100x1-p256\",\"benchmarkName\":\"multi-client-fanout\",")
                .append("\"payloadSize\":256,\"targetClientMbps\":1,\"affectedKind\":\"immediate\"")
                .append(readinessRetryFieldsJson()).append("}\n");
        aggregate.append("{\"case\":\"fairness\",\"benchmarkName\":\"fairness\",\"payloadSize\":512")
                .append(readinessRetryFieldsJson()).append("}\n");
        aggregate.append("{\"case\":\"disappear-blackhole\",\"benchmarkName\":\"disappearing-clients\",")
                .append("\"payloadSize\":512,\"disappearanceMode\":\"blackhole\"")
                .append(readinessRetryFieldsJson()).append("}\n");
        for (int batchIntervalMillis : new int[]{10, 20, 50}) {
            aggregate.append("{\"case\":\"batch-")
                    .append(batchIntervalMillis)
                    .append("ms\",\"benchmarkName\":\"batched-game-traffic\",\"payloadSize\":512,")
                    .append("\"batchIntervalMillis\":")
                    .append(batchIntervalMillis)
                    .append(",\"logicalPacketsPerBatch\":8,\"batchGroups\":4")
                    .append(readinessRetryFieldsJson()).append("}\n");
        }
        for (int chunkSize : new int[]{8192, 262144}) {
            aggregate.append("{\"case\":\"resource-pack-")
                    .append(chunkSize)
                    .append("\",\"benchmarkName\":\"resource-pack-transfer\",\"payloadSize\":")
                    .append(chunkSize)
                    .append(",\"batchIntervalMillis\":200")
                    .append(readinessRetryFieldsJson()).append("}\n");
        }
        Files.writeString(labBaseline.resolve("suite-aggregate.jsonl"), aggregate.toString(), StandardCharsets.UTF_8);
        Files.writeString(labBaseline.resolve("bandwidth-capacity.jsonl"), capacity.toString(), StandardCharsets.UTF_8);
    }

    private static void writeInvalidReadinessLabCapacity(Path labBaseline, int... payloadSizes) throws Exception {
        StringBuilder capacity = new StringBuilder();
        for (int payloadSize : payloadSizes) {
            capacity.append("{\"summaryKind\":\"bandwidth-capacity\",\"case\":\"lab-curve-p")
                    .append(payloadSize)
                    .append("\",\"payloadSize\":")
                    .append(payloadSize)
                    .append(",\"selected\":true}\n");
        }
        Files.writeString(labBaseline.resolve("bandwidth-capacity.jsonl"), capacity.toString(), StandardCharsets.UTF_8);
    }

    private static void writeValidationLabArtifacts(Path labRoot,
                                                    int prereqReportCount,
                                                    int readyPrereqReportCount) throws Exception {
        writeValidationLabArtifacts(labRoot, prereqReportCount, readyPrereqReportCount, true);
    }

    private static void writeValidationLabArtifacts(Path labRoot,
                                                    int prereqReportCount,
                                                    int readyPrereqReportCount,
                                                    boolean strictReadyPrereqs) throws Exception {
        Files.createDirectories(labRoot);
        Files.writeString(labRoot.resolve("topology.md"), "# Topology\n", StandardCharsets.UTF_8);
        for (int host = 0; host < 2; host++) {
            Path hostDir = labRoot.resolve("host-" + host);
            Files.createDirectories(hostDir);
            Files.writeString(hostDir.resolve("host-report.md"),
                    "# Host Report\n\n- Hostname: `host-" + host + "`\n",
                    StandardCharsets.UTF_8);
        }
        for (int prereq = 0; prereq < prereqReportCount; prereq++) {
            Path prereqDir = labRoot.resolve("prereq-host-" + prereq);
            Files.createDirectories(prereqDir);
            boolean ready = prereq < readyPrereqReportCount;
            String strictFields = ready && strictReadyPrereqs
                    ? ",\"requireClockSync\":true,\"requireNoNetem\":true,\"expectedMtu\":1500,"
                    + "\"interfaceMtu\":1500,\"expectedMinCpus\":1,\"cpuCount\":8"
                    : "";
            Files.writeString(prereqDir.resolve("prereq.json"),
                    "{\"ready\":" + ready
                            + ",\"hostname\":\"host-" + prereq + "\""
                            + strictFields + "}\n",
                    StandardCharsets.UTF_8);
            Files.writeString(prereqDir.resolve("prereq.md"), "# Prereq\n", StandardCharsets.UTF_8);
        }

        String[] benchmarks = {"curve-100_0mbps", "multi-client-fanout", "fairness", "disappearing-clients",
                "batched-game-traffic", "resource-pack-transfer"};
        StringBuilder aggregate = new StringBuilder();
        for (String benchmark : benchmarks) {
            Path artifact = labRoot.resolve("artifacts").resolve(benchmark);
            Files.createDirectories(artifact);
            Files.writeString(artifact.resolve("summary.json"), "{}\n", StandardCharsets.UTF_8);
            Files.writeString(artifact.resolve("timeseries.csv"), "name\nunit\n", StandardCharsets.UTF_8);
            Files.writeString(artifact.resolve("latency.hdr"), "histogram\n", StandardCharsets.UTF_8);
            Files.writeString(artifact.resolve("report.md"), "# Report\n", StandardCharsets.UTF_8);
            aggregate.append("{\"case\":\"").append(benchmark).append("\",")
                    .append("\"benchmarkName\":\"").append(benchmark).append("\",")
                    .append("\"measuredIterations\":3,")
                    .append("\"clients\":100,")
                    .append("\"payloadSize\":512,")
                    .append("\"reliability\":\"RELIABLE_ORDERED\",")
                    .append("\"targetClientMbps\":5,")
                    .append("\"deliveredGbps\":1,")
                    .append("\"probeRttP99Millis\":1,")
                    .append("\"deliveredGbpsSpreadPct\":0,")
                    .append("\"probeRttP99MillisSpreadPct\":0,")
                    .append("\"maxQueuedBytes\":0,")
                    .append("\"sentToDeliveredBytesRatio\":1,")
                    .append("\"serverDatagramsOutPerSecond\":1,")
                    .append("\"staleDatagramsPerSecond\":0,")
                    .append("\"nackOutPerSecond\":0,")
                    .append("\"fairnessIndex\":1,")
                    .append("\"healthyFairnessIndex\":1,")
                    .append("\"affectedFairnessIndex\":1,")
                    .append("\"disconnects\":0,")
                    .append("\"unstable\":false,")
                    .append("\"unstableReasons\":[]")
                    .append(readinessRetryFieldsJson()).append(",")
                    .append("\"artifact\":\"").append(artifact).append("\"}\n");
        }
        Files.writeString(labRoot.resolve("suite-aggregate.jsonl"), aggregate.toString(), StandardCharsets.UTF_8);
        Files.writeString(labRoot.resolve("bandwidth-capacity.jsonl"),
                "{\"summaryKind\":\"bandwidth-capacity\",\"case\":\"curve-100_0mbps\",\"payloadSize\":512,"
                        + "\"selected\":true,\"selectedCandidate\":{\"benchmarkName\":\"curve-100_0mbps\","
                        + "\"deliveredGbps\":1.0,\"probeRttP99Millis\":1.0}}\n",
                StandardCharsets.UTF_8);
    }

    private static void writeComparableSuite(Path suiteRoot, boolean validationBypass) throws Exception {
        Files.createDirectories(suiteRoot);
        Files.writeString(suiteRoot.resolve("suite-aggregate.jsonl"),
                "{\"summaryKind\":\"aggregate\","
                        + "\"case\":\"fanout\","
                        + "\"benchmarkName\":\"multi-client-fanout\","
                        + "\"iteration\":\"aggregate\","
                        + "\"measuredIterations\":3,"
                        + "\"clients\":100,"
                        + "\"payloadSize\":512,"
                        + "\"reliability\":\"RELIABLE_ORDERED\","
                        + "\"batched\":false,"
                        + "\"targetMbps\":500,"
                        + "\"targetClientMbps\":5,"
                        + "\"impairmentProfile\":\"0ms/0ms/0%\","
                        + "\"deliveredGbps\":1,"
                        + "\"healthyDeliveredGbps\":1,"
                        + "\"affectedDeliveredGbps\":0,"
                        + "\"serverDatagramsOutPerSecond\":1,"
                        + "\"sentToDeliveredBytesRatio\":1,"
                        + "\"healthySentToDeliveredBytesRatio\":1,"
                        + "\"affectedSentToDeliveredBytesRatio\":0,"
                        + "\"clientMbpsP50\":5,"
                        + "\"clientMbpsP99\":5,"
                        + "\"healthyClientMbpsP50\":5,"
                        + "\"healthyClientMbpsP99\":5,"
                        + "\"affectedClientMbpsP50\":0,"
                        + "\"affectedClientMbpsP99\":0,"
                        + "\"deliveredGbpsSpreadPct\":0,"
                        + "\"probeRttP99Millis\":1,"
                        + "\"probeRttP99MillisSpreadPct\":0,"
                        + "\"fairnessIndex\":1,"
                        + "\"healthyFairnessIndex\":1,"
                        + "\"affectedFairnessIndex\":1,"
                        + "\"disconnects\":0,"
                        + "\"staleDatagrams\":0,"
                        + "\"staleDatagramsPerSecond\":0,"
                        + "\"nackIn\":0,"
                        + "\"nackOut\":0,"
                        + "\"nackOutPerSecond\":0,"
                        + "\"maxQueuedBytes\":0,"
                        + "\"unstable\":false,"
                        + "\"unstableReasons\":[]"
                        + readinessRetryFieldsJson() + ","
                        + "\"artifact\":\"" + suiteRoot.resolve("fanout") + "\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(suiteRoot.resolve("validation.json"),
                validationBypass
                        ? "{\"passed\":true,\"allowUnstable\":true,\"issues\":[]}\n"
                        : "{\"passed\":true,\"issues\":[]}\n",
                StandardCharsets.UTF_8);
    }

    private static void writeComparableBatchShapeSuite(Path suiteRoot,
                                                       int batchIntervalMillis,
                                                       int logicalPacketsPerBatch,
                                                       int batchGroups) throws Exception {
        Files.createDirectories(suiteRoot);
        Files.writeString(suiteRoot.resolve("suite-aggregate.jsonl"),
                "{\"summaryKind\":\"aggregate\","
                        + "\"case\":\"batch\","
                        + "\"benchmarkName\":\"batched-game-traffic\","
                        + "\"iteration\":\"aggregate\","
                        + "\"measuredIterations\":3,"
                        + "\"clients\":100,"
                        + "\"payloadSize\":512,"
                        + "\"reliability\":\"RELIABLE_ORDERED\","
                        + "\"batched\":true,"
                        + "\"batchIntervalMillis\":" + batchIntervalMillis + ","
                        + "\"logicalPacketsPerBatch\":" + logicalPacketsPerBatch + ","
                        + "\"batchGroups\":" + batchGroups + ","
                        + "\"targetMbps\":500,"
                        + "\"targetClientMbps\":5,"
                        + "\"impairmentProfile\":\"0ms/0ms/0%\","
                        + "\"deliveredGbps\":1,"
                        + "\"healthyDeliveredGbps\":1,"
                        + "\"affectedDeliveredGbps\":0,"
                        + "\"serverDatagramsOutPerSecond\":1,"
                        + "\"sentToDeliveredBytesRatio\":1,"
                        + "\"healthySentToDeliveredBytesRatio\":1,"
                        + "\"affectedSentToDeliveredBytesRatio\":0,"
                        + "\"clientMbpsP50\":5,"
                        + "\"clientMbpsP99\":5,"
                        + "\"healthyClientMbpsP50\":5,"
                        + "\"healthyClientMbpsP99\":5,"
                        + "\"affectedClientMbpsP50\":0,"
                        + "\"affectedClientMbpsP99\":0,"
                        + "\"deliveredGbpsSpreadPct\":0,"
                        + "\"probeRttP99Millis\":1,"
                        + "\"probeRttP99MillisSpreadPct\":0,"
                        + "\"fairnessIndex\":1,"
                        + "\"healthyFairnessIndex\":1,"
                        + "\"affectedFairnessIndex\":1,"
                        + "\"disconnects\":0,"
                        + "\"staleDatagrams\":0,"
                        + "\"staleDatagramsPerSecond\":0,"
                        + "\"nackIn\":0,"
                        + "\"nackOut\":0,"
                        + "\"nackOutPerSecond\":0,"
                        + "\"maxQueuedBytes\":0,"
                        + "\"unstable\":false,"
                        + "\"unstableReasons\":[]"
                        + readinessRetryFieldsJson() + ","
                        + "\"artifact\":\"" + suiteRoot.resolve("batch") + "\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(suiteRoot.resolve("validation.json"),
                "{\"passed\":true,\"issues\":[]}\n",
                StandardCharsets.UTF_8);
    }

    private static void writeComparableImpairmentSummary(Path summaryRoot, boolean validationBypass) throws Exception {
        Files.createDirectories(summaryRoot);
        Files.writeString(summaryRoot.resolve("impairment-summary.json"),
                "{\"summaryKind\":\"raknet-lab-impairment-campaign\","
                        + "\"passed\":true,"
                        + "\"requireNetemEvidence\":true,"
                        + "\"allowValidationBypasses\":" + validationBypass + ","
                        + "\"profileCount\":1,"
                        + "\"validationPassedCount\":1,"
                        + "\"aggregateRowCount\":1,"
                        + "\"capacityRowCount\":1,"
                        + "\"netemStatusEvidenceCount\":1,"
                        + "\"profiles\":[{\"profile\":\"perfect\","
                        + "\"latency\":\"0ms\","
                        + "\"jitter\":\"0ms\","
                        + "\"loss\":\"0%\","
                        + "\"validation\":{\"passed\":true,\"bypassFlags\":"
                        + (validationBypass ? "[\"allowUnstable\"]" : "[]")
                        + "},"
                        + "\"netem\":{\"statusEvidenceCount\":1},"
                        + "\"capacity\":{\"rowCount\":1,\"selectedCount\":1,\"rows\":[{"
                        + "\"case\":\"perfect-curve\","
                        + "\"payloadSize\":512,"
                        + "\"reliability\":\"RELIABLE_ORDERED\","
                        + "\"selected\":true,"
                        + "\"selectedBenchmarkName\":\"curve-100_0mbps\","
                        + "\"selectedDeliveredGbps\":1,"
                        + "\"selectedProbeRttP99Millis\":1"
                        + "}]},"
                        + "\"aggregate\":{\"rowCount\":1,\"contentionRows\":[{"
                        + "\"case\":\"fanout\","
                        + "\"benchmarkName\":\"multi-client-fanout\","
                        + "\"clients\":100,"
                        + "\"payloadSize\":512,"
                        + "\"reliability\":\"RELIABLE_ORDERED\","
                        + "\"targetClientMbps\":5,"
                        + "\"deliveredGbps\":1,"
                        + "\"probeRttP99Millis\":1,"
                        + "\"maxQueuedBytes\":0,"
                        + "\"healthyFairnessIndex\":1,"
                        + "\"healthySentToDeliveredBytesRatio\":1,"
                        + "\"affectedSentToDeliveredBytesRatio\":0,"
                        + "\"unstable\":false,"
                        + "\"unstableReasons\":[]"
                        + readinessRetryFieldsJson()
                        + "}]}}]}\n",
                StandardCharsets.UTF_8);
    }

    private static void writeComparableImpairmentBatchShapeSummary(Path summaryRoot,
                                                                   int... batchIntervalsMillis) throws Exception {
        Files.createDirectories(summaryRoot);
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < batchIntervalsMillis.length; i++) {
            if (i > 0) {
                rows.append(',');
            }
            int interval = batchIntervalsMillis[i];
            rows.append("{\"case\":\"batch-")
                    .append(interval)
                    .append("ms\",")
                    .append("\"benchmarkName\":\"batched-game-traffic\",")
                    .append("\"clients\":100,")
                    .append("\"payloadSize\":512,")
                    .append("\"reliability\":\"RELIABLE_ORDERED\",")
                    .append("\"targetClientMbps\":5,")
                    .append("\"batchIntervalMillis\":")
                    .append(interval)
                    .append(",\"logicalPacketsPerBatch\":8,\"batchGroups\":4,")
                    .append("\"deliveredGbps\":1,")
                    .append("\"probeRttP99Millis\":1,")
                    .append("\"maxQueuedBytes\":0,")
                    .append("\"healthyFairnessIndex\":1,")
                    .append("\"healthySentToDeliveredBytesRatio\":1,")
                    .append("\"affectedSentToDeliveredBytesRatio\":0,")
                    .append("\"unstable\":false,")
                    .append("\"unstableReasons\":[]}");
        }
        Files.writeString(summaryRoot.resolve("impairment-summary.json"),
                "{\"summaryKind\":\"raknet-lab-impairment-campaign\","
                        + "\"passed\":true,"
                        + "\"requireNetemEvidence\":true,"
                        + "\"allowValidationBypasses\":false,"
                        + "\"profileCount\":1,"
                        + "\"validationPassedCount\":1,"
                        + "\"aggregateRowCount\":" + batchIntervalsMillis.length + ","
                        + "\"capacityRowCount\":1,"
                        + "\"netemStatusEvidenceCount\":1,"
                        + "\"profiles\":[{\"profile\":\"perfect\","
                        + "\"latency\":\"0ms\","
                        + "\"jitter\":\"0ms\","
                        + "\"loss\":\"0%\","
                        + "\"validation\":{\"passed\":true,\"bypassFlags\":[]},"
                        + "\"netem\":{\"statusEvidenceCount\":1},"
                        + "\"capacity\":{\"rowCount\":1,\"selectedCount\":1,\"rows\":[{"
                        + "\"case\":\"perfect-curve\","
                        + "\"payloadSize\":512,"
                        + "\"reliability\":\"RELIABLE_ORDERED\","
                        + "\"selected\":true,"
                        + "\"selectedBenchmarkName\":\"curve-100_0mbps\","
                        + "\"selectedDeliveredGbps\":1,"
                        + "\"selectedProbeRttP99Millis\":1"
                        + "}]},"
                        + "\"aggregate\":{\"rowCount\":" + batchIntervalsMillis.length
                        + ",\"contentionRows\":[" + rows + "]}}]}\n",
                StandardCharsets.UTF_8);
    }

    private static void writeReadinessImpairmentBaseline(Path impairmentBaseline) throws Exception {
        writeReadinessImpairmentBaseline(impairmentBaseline, true);
    }

    private static void writeReadinessImpairmentBaseline(Path impairmentBaseline,
                                                        boolean includeSplitPayload) throws Exception {
        writeReadinessImpairmentBaseline(impairmentBaseline, includeSplitPayload, true);
    }

    private static void writeReadinessImpairmentBaseline(Path impairmentBaseline,
                                                        boolean includeSplitPayload,
                                                        boolean includeDisappearingContention) throws Exception {
        Files.createDirectories(impairmentBaseline);
        Files.writeString(impairmentBaseline.resolve("impairment-baseline-manifest.json"),
                "{\"baselineKind\":\"raknet-lab-impairment-campaign\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(impairmentBaseline.resolve("campaign-manifest.jsonl"),
                "{\"profile\":\"perfect\"}\n{\"profile\":\"near-loss\"}\n"
                        + "{\"profile\":\"regional-loss\"}\n{\"profile\":\"poor\"}\n{\"profile\":\"severe\"}\n",
                StandardCharsets.UTF_8);

        int[] payloadSizes = includeSplitPayload
                ? new int[]{64, 256, 512, 1200, 1340, 1400, 262144}
                : new int[]{64, 256, 512, 1200, 1340, 1400};
        StringBuilder profiles = new StringBuilder();
        String[] profileNames = {"perfect", "near-loss", "regional-loss", "poor", "severe"};
        Path profileRoot = impairmentBaseline.resolve("profiles");
        clearDirectory(profileRoot);
        Files.createDirectories(profileRoot);
        for (int profileIndex = 0; profileIndex < profileNames.length; profileIndex++) {
            if (profileIndex > 0) {
                profiles.append(',');
            }
            profiles.append("{\"profile\":\"").append(profileNames[profileIndex]).append("\",")
                    .append("\"aggregate\":{\"contentionRows\":[")
                    .append("{\"benchmarkName\":\"multi-client-fanout\"")
                    .append(readinessRetryFieldsJson()).append("},")
                    .append("{\"case\":\"immediate-100x1-p256\",\"benchmarkName\":\"multi-client-fanout\",")
                    .append("\"payloadSize\":256,\"targetClientMbps\":1,\"affectedKind\":\"immediate\"")
                    .append(readinessRetryFieldsJson()).append("},")
                    .append("{\"benchmarkName\":\"fairness\"")
                    .append(readinessRetryFieldsJson()).append("}");
            if (includeDisappearingContention) {
                profiles.append(",{\"benchmarkName\":\"disappearing-clients\",")
                        .append("\"case\":\"disappear-blackhole\",")
                        .append("\"disappearanceMode\":\"blackhole\"")
                        .append(readinessRetryFieldsJson()).append("}");
            }
            for (int batchIntervalMillis : new int[]{10, 20, 50}) {
                profiles.append(",{\"benchmarkName\":\"batched-game-traffic\",")
                        .append("\"batchIntervalMillis\":")
                        .append(batchIntervalMillis)
                        .append(",\"logicalPacketsPerBatch\":8,\"batchGroups\":4")
                        .append(readinessRetryFieldsJson()).append("}");
            }
            for (int chunkSize : new int[]{8192, 262144}) {
                profiles.append(",{\"benchmarkName\":\"resource-pack-transfer\",")
                        .append("\"payloadSize\":")
                        .append(chunkSize)
                        .append(",\"batchIntervalMillis\":200")
                        .append(readinessRetryFieldsJson()).append("}");
            }
            profiles.append("]},\"capacity\":{\"rowCount\":").append(payloadSizes.length)
                    .append(",\"selectedCount\":").append(payloadSizes.length)
                    .append(",\"rows\":[");
            for (int payloadIndex = 0; payloadIndex < payloadSizes.length; payloadIndex++) {
                if (payloadIndex > 0) {
                    profiles.append(',');
                }
                profiles.append("{\"case\":\"")
                        .append(profileNames[profileIndex])
                        .append("-curve-p")
                        .append(payloadSizes[payloadIndex])
                        .append("\",\"payloadSize\":")
                        .append(payloadSizes[payloadIndex])
                        .append(",\"selected\":true,\"selectedBenchmarkName\":\"curve-100_0mbps\",")
                        .append("\"selectedDeliveredGbps\":1,\"selectedProbeRttP99Millis\":1}");
            }
            profiles.append("]}}");

            Path profileDir = profileRoot.resolve(profileNames[profileIndex]);
            Files.createDirectories(profileDir.resolve("netem"));
            Files.writeString(profileDir.resolve("validation.json"),
                    "{\"passed\":true,\"issues\":[]}\n",
                    StandardCharsets.UTF_8);
            Files.writeString(profileDir.resolve("suite-aggregate.jsonl"),
                    "{\"case\":\"fanout\",\"benchmarkName\":\"multi-client-fanout\""
                            + readinessRetryFieldsJson() + "}\n",
                    StandardCharsets.UTF_8);
            Files.writeString(profileDir.resolve("bandwidth-capacity.jsonl"),
                    "{\"summaryKind\":\"bandwidth-capacity\",\"case\":\""
                            + profileNames[profileIndex]
                            + "-curve-p512\",\"payloadSize\":512,\"selected\":true,"
                            + "\"selectedCandidate\":{\"benchmarkName\":\"curve-100_0mbps\","
                            + "\"deliveredGbps\":1,\"probeRttP99Millis\":1}}\n",
                    StandardCharsets.UTF_8);
            Files.writeString(profileDir.resolve("netem/" + profileNames[profileIndex] + "-status-before.txt"),
                    "qdisc noqueue 0: root refcnt 2\n",
                    StandardCharsets.UTF_8);
        }

        Files.writeString(impairmentBaseline.resolve("impairment-summary.json"),
                "{\"passed\":true,\"requireNetemEvidence\":true,\"validationPassedCount\":5,\"profileCount\":5,"
                        + "\"netemStatusEvidenceCount\":5,\"aggregateRowCount\":1,\"capacityRowCount\":1,"
                        + "\"profiles\":[" + profiles + "]}\n",
                StandardCharsets.UTF_8);
    }

    private static BenchmarkIterationResult iteration(String name, int iteration, int receivedBytes, double p99Millis) {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int i = 0; i < 10; i++) {
            histogram.record((long) (p99Millis * 1_000_000.0D));
        }
        PeerStats peer = new PeerStats(0, false);
        peer.addBulkReceived(receivedBytes);
        for (int i = 0; i < 10; i++) {
            peer.addProbeSent();
            peer.addProbeAcked();
        }
        return new BenchmarkIterationResult(
                name,
                iteration,
                1,
                64,
                RakReliability.RELIABLE_ORDERED,
                0.0D,
                0.0D,
                DisappearanceMode.CLOSE,
                false,
                20,
                1,
                1,
                1000,
                histogram.snapshot(),
                Arrays.asList(peer.snapshot())
        );
    }

    private static List<JsonNode> readJsonLines(Path path) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                rows.add(JSON.readTree(line));
            }
        }
        return rows;
    }

    private static JsonNode candidate(JsonNode rows, String benchmarkName) {
        for (JsonNode row : rows) {
            if (benchmarkName.equals(row.path("benchmarkName").asText())) {
                return row;
            }
        }
        throw new AssertionError("Missing candidate " + benchmarkName + " in " + rows);
    }

    private static void addCapacityIteration(BenchmarkRunResult run, String name, int iteration,
                                             double targetMbps, int deliveredBytes) {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int i = 0; i < 10; i++) {
            histogram.record(10_000_000L);
        }
        PeerStats peer = new PeerStats(0, false);
        peer.addBulkSent(deliveredBytes);
        peer.addBulkReceived(deliveredBytes);
        peer.addServerBytesOut(deliveredBytes);
        peer.addServerDatagramsOut(1);
        for (int i = 0; i < 10; i++) {
            peer.addProbeSent();
            peer.addProbeAcked();
        }
        run.add(new BenchmarkIterationResult(
                name,
                iteration,
                1,
                1200,
                RakReliability.RELIABLE_ORDERED,
                targetMbps,
                targetMbps,
                DisappearanceMode.CLOSE,
                false,
                20,
                8,
                1,
                1000,
                histogram.snapshot(),
                Arrays.asList(peer.snapshot(true, true))
        ));
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static JsonNode findSource(JsonNode auditJson, String sourceId) {
        for (JsonNode source : auditJson.path("sources")) {
            if (sourceId.equals(source.path("id").asText())) {
                return source;
            }
        }
        throw new AssertionError("Missing source audit row for " + sourceId + ": " + auditJson);
    }

    private static JsonNode findAction(JsonNode readinessJson, String actionCode) {
        for (JsonNode action : readinessJson.path("nextActions")) {
            if (actionCode.equals(action.path("code").asText())) {
                return action;
            }
        }
        throw new AssertionError("Missing next action for " + actionCode + ": " + readinessJson);
    }

    private static List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(value.asText());
        }
        return result;
    }

    private static Path initGitRepo(Path path) throws Exception {
        Files.createDirectories(path);
        runProcess(path, Duration.ofSeconds(10), "git", "init");
        runProcess(path, Duration.ofSeconds(10), "git", "config", "user.email", "benchmark@example.invalid");
        runProcess(path, Duration.ofSeconds(10), "git", "config", "user.name", "Benchmark Test");
        Files.writeString(path.resolve("README.md"), "benchmark source fixture\n", StandardCharsets.UTF_8);
        runProcess(path, Duration.ofSeconds(10), "git", "add", "README.md");
        ProcessResult commit = runProcess(path, Duration.ofSeconds(10), "git", "commit", "-m", "initial");
        Assertions.assertEquals(0, commit.exitCode, commit.output);
        return path;
    }

    private static String mockGradleScript() {
        return """
                #!/usr/bin/env bash
                set -euo pipefail

                benchmark_args=""
                for arg in "$@"; do
                  case "$arg" in
                    -PbenchmarkArgs=*)
                      benchmark_args="${arg#-PbenchmarkArgs=}"
                      ;;
                  esac
                done
                if [[ -z "$benchmark_args" ]]; then
                  echo "missing -PbenchmarkArgs" >&2
                  exit 2
                fi

                arg_value() {
                  local flag="$1"
                  local fallback="$2"
                  if [[ "$benchmark_args" =~ (^|[[:space:]])${flag}[[:space:]]([^[:space:]]+) ]]; then
                    printf '%s' "${BASH_REMATCH[2]}"
                  else
                    printf '%s' "$fallback"
                  fi
                }

                scenario="${benchmark_args%% *}"
                output_root="$(arg_value --out "")"
                run_id="$(arg_value --run-id "")"
                clients="$(arg_value --clients 1)"
                payload_size="$(arg_value --payload-size "$(arg_value --chunk-size 512)")"
	                target_client_mbps="$(arg_value --per-client-mbps "$(arg_value --rate-mbps 1)")"
	                disappearance_mode="$(arg_value --disappear-mode close)"
	                impairment_latency="$(arg_value --impairment-latency 0ms)"
                impairment_jitter="$(arg_value --impairment-jitter 0ms)"
                impairment_loss="$(arg_value --impairment-loss 0)"
                if [[ -z "$output_root" || -z "$run_id" ]]; then
                  echo "mock benchmark requires --out and --run-id in benchmark args: $benchmark_args" >&2
                  exit 2
                fi

                artifact="$output_root/$run_id"
                mkdir -p "$artifact"
                impairment_latency="${impairment_latency%ms}"
                impairment_jitter="${impairment_jitter%ms}"
                batched=false
                batch_interval=0
                logical_packets=1
                batch_groups=1
                if [[ "$scenario" == "batched-game-traffic" ]]; then
                  batched=true
                  batch_interval="$(arg_value --batch-interval 20ms)"
                  batch_interval="${batch_interval%ms}"
                  logical_packets="$(arg_value --logical-packets-per-batch 4)"
                  batch_groups="$(arg_value --batch-groups 2)"
                fi

                iteration_json() {
                  local name="$1"
                  local delivered_gbps="$2"
                  local p99="$3"
                  local affected_clients="${4:-0}"
                  local stale="${5:-0}"
                  local nack_out="${6:-0}"
                  cat <<JSON
	                {"name":"$name","iteration":1,"clients":$clients,"payloadSize":$payload_size,"reliability":"RELIABLE_ORDERED","targetMbps":1.0,"targetClientMbps":$target_client_mbps,"disappearanceMode":"$disappearance_mode","batched":$batched,"batchIntervalMillis":$batch_interval,"logicalPacketsPerBatch":$logical_packets,"batchGroups":$batch_groups,"elapsedMillis":1000,"offeredGbps":$delivered_gbps,"deliveredGbps":$delivered_gbps,"healthyDeliveredGbps":$delivered_gbps,"affectedDeliveredGbps":0.0,"serverBytesOut":1024,"serverDatagramsOut":10,"serverDatagramsOutPerSecond":10.0,"sentToDeliveredBytesRatio":1.0,"healthySentToDeliveredBytesRatio":1.0,"affectedSentToDeliveredBytesRatio":1.0,"perClientThroughput":{"minMbps":1.0,"p50Mbps":1.0,"p95Mbps":1.0,"p99Mbps":1.0,"maxMbps":1.0},"healthyClientThroughput":{"minMbps":1.0,"p50Mbps":1.0,"p95Mbps":1.0,"p99Mbps":1.0,"maxMbps":1.0},"affectedClientThroughput":{"minMbps":0.5,"p50Mbps":0.5,"p95Mbps":0.5,"p99Mbps":0.5,"maxMbps":0.5},"deliveredMessagesPerSecond":1000.0,"deliveredLogicalPacketsPerSecond":1000.0,"probesSent":10,"probesAcked":10,"probeAckSpillover":0,"probeResponseRate":1.0,"probeRttCount":10,"probeRttP95Millis":$p99,"probeRttP99Millis":$p99,"fairnessIndex":1.0,"healthyFairnessIndex":1.0,"affectedFairnessIndex":1.0,"affectedClients":$affected_clients,"disconnects":0,"blackholedDatagramsIn":0,"blackholedDatagramsOut":0,"staleDatagrams":$stale,"staleDatagramsPerSecond":$stale,"nackIn":0,"nackInPerSecond":0.0,"nackOut":$nack_out,"nackOutPerSecond":$nack_out,"maxQueuedBytes":1024}
                JSON
                }

                case "$scenario" in
                  bandwidth-latency-curve)
                    iterations="$(iteration_json curve-50_0mbps 0.05 10.0),$(iteration_json curve-100_0mbps 0.10 11.0)"
                    ;;
                  fairness)
                    iterations="$(iteration_json fairness 0.002 25.0 2 1 1)"
                    ;;
                  disappearing-clients)
                    iterations="$(iteration_json disappearing-clients 0.002 20.0 1 2 0)"
                    ;;
                  multi-client-fanout)
                    iterations="$(iteration_json multi-client-fanout 0.002 12.0)"
                    ;;
                  batched-game-traffic)
                    iterations="$(iteration_json batched-game-traffic 0.002 13.0)"
                    ;;
                  resource-pack-transfer)
                    iterations="$(iteration_json resource-pack-transfer 0.003 14.0)"
                    ;;
                  *)
                    iterations="$(iteration_json "$scenario" 0.05 10.0)"
                    ;;
                esac

                cat >"$artifact/summary.json" <<JSON
                {"runId":"$run_id","scenario":"$scenario","probeReliability":"UNRELIABLE","probePriority":"HIGH","probeSemantics":"UNRELIABLE/HIGH best-effort non-ordering through the weighted scheduler; lost probes are omitted from RTT samples","impairmentLatencyMillis":$impairment_latency,"impairmentJitterMillis":$impairment_jitter,"impairmentLossPercent":$impairment_loss,"iterations":[$iterations]}
                JSON
                printf 'mock benchmark wrote %s\\n' "$artifact"
                """;
    }

    private static void assumeShellTooling() throws Exception {
        Assumptions.assumeTrue(commandAvailable("bash"), "bash is required for benchmark script tests");
        Assumptions.assumeTrue(commandAvailable("jq"), "jq is required for benchmark script tests");
    }

    private static void clearDirectory(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    clearDirectory(child);
                }
                Files.deleteIfExists(child);
            }
        }
    }

    private static void assumeGit() throws Exception {
        Assumptions.assumeTrue(commandAvailable("git"), "git is required for source audit script tests");
    }

    private static boolean commandAvailable(String command) throws Exception {
        Process process = new ProcessBuilder("bash", "-lc", "command -v " + command)
                .redirectErrorStream(true)
                .start();
        return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
    }

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        while (path != null) {
            if (Files.exists(path.resolve("benchmark/scripts/prepare-lab-baseline-handoff.sh"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new AssertionError("Unable to locate repository root from " + Paths.get("").toAbsolutePath());
    }

    private static ProcessResult runProcess(Path workingDirectory, Duration timeout, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copyOutput(process.getInputStream(), output), "benchmark-test-output-reader");
        reader.start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            reader.join(TimeUnit.SECONDS.toMillis(5));
            Assertions.fail("Timed out running " + Arrays.toString(command) + "\n" + output.toString(StandardCharsets.UTF_8));
        }
        reader.join(TimeUnit.SECONDS.toMillis(5));
        return new ProcessResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private static void copyOutput(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            input.transferTo(output);
        } catch (Exception ignored) {
        }
    }

    private static final class ProcessResult {
        private final int exitCode;
        private final String output;

        private ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
