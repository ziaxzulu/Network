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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BenchmarkKitTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CsvMapper CSV = new CsvMapper();

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
    public void testPeerStatsResetClearsMeasurementWindowCounters() {
        PeerStats peer = new PeerStats(0, false);
        peer.addBulkSent(64);
        peer.addBulkReceived(64);
        peer.addProbeSent();
        peer.addProbeAcked();
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
        Assertions.assertEquals(0, snapshot.disconnects);
        Assertions.assertEquals(0, snapshot.blackholedDatagramsIn);
        Assertions.assertEquals(0, snapshot.blackholedDatagramsOut);
        Assertions.assertEquals(0, snapshot.maxQueuedBytes);
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
        Assertions.assertTrue(summary.contains("| case-a | 2 | 0.990099% | 0.000000% | true | `insufficient-iterations` |"));
        Assertions.assertTrue(summary.contains("| case-b | 2 | 50.000000% | 50.000000% | true | `insufficient-iterations,throughput-spread,p99-spread` |"));
    }

    @Test
    public void testStabilitySummaryRejectsZeroDelivery() {
        String summary = BenchmarkResultWriter.stabilitySummary(Arrays.asList(
                iteration("case-zero", 1, 0, 0.0D),
                iteration("case-zero", 2, 0, 0.0D),
                iteration("case-zero", 3, 0, 0.0D)
        ));

        Assertions.assertTrue(summary.contains("| case-zero | 3 | 0.000000% | 0.000000% | true | `zero-delivery` |"));
    }

    @Test
    public void testNetemPlannerDryRunCommands() {
        List<String> apply = NetemCommandPlanner.apply("eth0", "50ms", "5ms", "2%");
        Assertions.assertEquals("tc qdisc replace dev eth0 root netem delay 50ms 5ms loss 2%", apply.get(0));

        Assertions.assertEquals("tc qdisc del dev eth0 root", NetemCommandPlanner.clear("eth0").get(0));
        Assertions.assertEquals("tc qdisc show dev eth0", NetemCommandPlanner.status("eth0").get(0));
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
        Assertions.assertEquals(6, manifest.size());
        Assertions.assertTrue(manifest.stream().allMatch(row -> row.contains("\"status\":\"dry-run\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-curve-1c-mtu\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-fanout-100x5\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-fairness-100-10poor\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-disappear-100-blackhole\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-batch-100-20ms\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"name\":\"pilot-resource-100-8k-200ms\"")));
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
                "--cases", "resource-pack,batched",
                "--resource-pack-chunk-sizes", "8192,262144",
                "--resource-pack-interval", "200ms",
                "--batch-intervals", "20ms",
                "--batch-payload-sizes", "128,512,1200",
                "--logical-packets-per-batch", "8",
                "--batch-groups", "4",
                "--warmup", "1s",
                "--duration", "1s",
                "--iterations", "1",
                "--start-delay", "1s",
                "--start-offset", "180s"
        );
        Assertions.assertEquals(0, result.exitCode, result.output);

        List<String> manifest = Files.readAllLines(plan.resolve("manifest.jsonl"), StandardCharsets.UTF_8);
        Assertions.assertEquals(3, manifest.size());
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"benchmarkName\":\"batched-game-traffic\"")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"payloadSize\":8192")
                && row.contains("\"perClientMbps\":0.327680000")));
        Assertions.assertTrue(manifest.stream().anyMatch(row -> row.contains("\"payloadSize\":262144")
                && row.contains("\"perClientMbps\":10.485760000")));

        String serverCommands = Files.readString(plan.resolve("server-commands.sh"), StandardCharsets.UTF_8);
        Assertions.assertTrue(serverCommands.contains("batched-game-traffic --role server"));
        Assertions.assertTrue(serverCommands.contains("--batch-interval 20ms --logical-packets-per-batch 8"));
        Assertions.assertTrue(serverCommands.contains("resource-pack-transfer --role server"));
        Assertions.assertTrue(serverCommands.contains("--chunk-size 8192 --chunk-interval 200ms"));
        Assertions.assertTrue(serverCommands.contains("--chunk-size 262144 --chunk-interval 200ms"));
    }

    @Test
    public void testLabHandoffGeneratorProducesBaselineAndImpairmentPlans() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-handoff-test");
        Path handoff = output.resolve("handoff");
        Path artifacts = output.resolve("artifacts");

        ProcessResult result = runProcess(root, Duration.ofSeconds(30),
                "bash",
                root.resolve("benchmark/scripts/prepare-lab-baseline-handoff.sh").toString(),
                "--out", handoff.toString(),
                "--artifact-root", artifacts.toString(),
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
        Assertions.assertTrue(Files.exists(handoff.resolve("impairment-plan/check-plan-freshness.sh")));
        Assertions.assertTrue(Files.exists(handoff.resolve("impairment-plan/validate-all.sh")));
        Assertions.assertTrue(Files.exists(handoff.resolve("impairment-plan/summarize-campaign.sh")));
        Assertions.assertTrue(Files.exists(handoff.resolve("impairment-plan/manifest.jsonl")));
        Assertions.assertTrue(Files.exists(handoff.resolve("handoff-manifest.json")));
        Assertions.assertTrue(result.output.contains("Handoff manifest:"));

        String readme = Files.readString(handoff.resolve("README.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(readme.contains("RakNet Lab Baseline Handoff"));
        Assertions.assertTrue(readme.contains("check-lab-handoff.sh"));
        Assertions.assertTrue(readme.contains("handoff-manifest.json"));
        Assertions.assertTrue(readme.contains("promote-lab-baseline.sh"));
        Assertions.assertTrue(readme.contains("--min-contention-clients \"2\""));
        Assertions.assertTrue(readme.contains("--min-contention-target-client-mbps \"1\""));
        Assertions.assertTrue(readme.contains("check-lab-host-prereqs.sh"));
        Assertions.assertTrue(readme.contains("--expect-mtu 1500"));
        Assertions.assertTrue(readme.contains("--expect-min-cpus 2"));
        Assertions.assertTrue(readme.contains("--require-clock-sync"));
        Assertions.assertTrue(readme.contains("--require-no-netem"));
        Assertions.assertTrue(readme.contains("check-baseline-readiness.sh"));
        Assertions.assertTrue(readme.contains("--required-min-contention-clients \"2\""));
        Assertions.assertTrue(readme.contains("--required-min-contention-target-client-mbps \"1\""));

        JsonNode handoffManifest = JSON.readTree(Files.readString(handoff.resolve("handoff-manifest.json"),
                StandardCharsets.UTF_8));
        Assertions.assertEquals("raknet-lab-handoff", handoffManifest.path("kind").asText());
        Assertions.assertEquals(root.toString(), handoffManifest.path("repoRoot").asText());
        Assertions.assertEquals(handoff.toString(), handoffManifest.path("outputRoot").asText());
        Assertions.assertEquals(artifacts.toString(), handoffManifest.path("artifactRoot").asText());
        Assertions.assertEquals(handoff.resolve("perfect-plan").toString(), handoffManifest.path("perfectPlan").asText());
        Assertions.assertEquals(handoff.resolve("impairment-plan").toString(),
                handoffManifest.path("impairmentPlan").asText());
        Assertions.assertEquals(artifacts.resolve("perfect").toString(),
                handoffManifest.path("perfectArtifacts").asText());
        Assertions.assertEquals(artifacts.resolve("impairment").toString(),
                handoffManifest.path("impairmentArtifacts").asText());
        Assertions.assertEquals("127.0.0.1", handoffManifest.path("serverHost").asText());
        Assertions.assertEquals("0.0.0.0", handoffManifest.path("bindHost").asText());
        Assertions.assertEquals(19132, handoffManifest.path("port").asInt());
        Assertions.assertEquals("lo", handoffManifest.path("interface").asText());
        Assertions.assertEquals(1500, handoffManifest.path("expectedMtu").asInt());
        Assertions.assertEquals(2, handoffManifest.path("expectedMinCpus").asInt());
        Assertions.assertFalse(handoffManifest.path("requireCpuPerformance").asBoolean());
        Assertions.assertEquals("receiver-a", handoffManifest.path("targetHostRole").asText());
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
        Assertions.assertEquals(1.0D, handoffManifest.path("perClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(1000, handoffManifest.path("raisedPacketLimit").asInt());
        Assertions.assertEquals(10000, handoffManifest.path("raisedGlobalPacketLimit").asInt());
        Assertions.assertEquals(1048576, handoffManifest.path("maxQueuedBytes").asInt());
        Assertions.assertEquals("1s", handoffManifest.path("warmup").asText());
        Assertions.assertEquals("1s", handoffManifest.path("duration").asText());
        Assertions.assertEquals(1, handoffManifest.path("iterations").asInt());
        Assertions.assertEquals("1s", handoffManifest.path("startDelay").asText());
        Assertions.assertEquals("180s", handoffManifest.path("startOffset").asText());
        Assertions.assertFalse(handoffManifest.path("sudoNetem").asBoolean());

        List<String> profiles = Files.readAllLines(handoff.resolve("impairment-plan/manifest.jsonl"), StandardCharsets.UTF_8);
        Assertions.assertEquals(2, profiles.size());
        Assertions.assertTrue(profiles.get(0).contains("\"profile\":\"perfect\""));
        Assertions.assertTrue(profiles.get(1).contains("\"profile\":\"near-loss\""));

        List<String> defaultCurveRows = Files.readAllLines(handoff.resolve("perfect-plan/curve-plan/manifest.jsonl"),
                StandardCharsets.UTF_8);
        Assertions.assertEquals(56, defaultCurveRows.size());
        Assertions.assertTrue(defaultCurveRows.stream().anyMatch(row -> row.contains("\"payloadSize\":64")));
        Assertions.assertTrue(defaultCurveRows.stream().anyMatch(row -> row.contains("\"payloadSize\":262144")));

        List<String> raisedCurveRows = Files.readAllLines(handoff.resolve("perfect-plan/curve-raised-plan/manifest.jsonl"),
                StandardCharsets.UTF_8);
        Assertions.assertEquals(56, raisedCurveRows.size());
        Assertions.assertTrue(raisedCurveRows.stream().anyMatch(row -> row.contains("\"payloadSize\":64")));
        Assertions.assertTrue(raisedCurveRows.stream().anyMatch(row -> row.contains("\"payloadSize\":262144")));

        Path defaultHandoffPreflight = output.resolve("handoff-preflight-default");
        ProcessResult defaultHandoffCheck = runProcess(root, Duration.ofSeconds(20),
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
        Assertions.assertTrue(defaultHandoffCheckJson.findValuesAsText("code")
                .contains("handoff-contention-clients-below-threshold"));
        Assertions.assertTrue(defaultHandoffCheckJson.findValuesAsText("code")
                .contains("handoff-contention-target-client-mbps-below-threshold"));

        Path handoffPreflight = output.resolve("handoff-preflight");
        ProcessResult handoffCheck = runProcess(root, Duration.ofSeconds(20),
                "bash",
                root.resolve("benchmark/scripts/check-lab-handoff.sh").toString(),
                "--handoff", handoff.toString(),
                "--out", handoffPreflight.toString(),
                "--required-min-contention-clients", "2",
                "--required-min-contention-target-client-mbps", "1"
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
        Assertions.assertEquals(1.0D, handoffCheckJson.path("expectedPerClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(1500, handoffCheckJson.path("expectedMtu").asInt());
        Assertions.assertEquals(2, handoffCheckJson.path("expectedMinCpus").asInt());
        Assertions.assertEquals(2, handoffCheckJson.path("actualImpairmentProfileRows").size());
        for (JsonNode profileRows : handoffCheckJson.path("actualImpairmentProfileRows")) {
            Assertions.assertEquals(56, profileRows.path("curveRows").asInt());
            Assertions.assertEquals(56, profileRows.path("raisedCurveRows").asInt());
            Assertions.assertEquals(1, profileRows.path("contentionRows").asInt());
        }

        Path handoffReadme = handoff.resolve("README.md");
        Files.writeString(handoffReadme,
                Files.readString(handoffReadme, StandardCharsets.UTF_8)
                        .replace("--required-min-contention-clients \"2\"",
                                "--required-min-contention-clients \"1\""),
                StandardCharsets.UTF_8);
        ProcessResult tamperedReadmeCheck = runProcess(root, Duration.ofSeconds(20),
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

        Path contentionManifest = handoff.resolve("perfect-plan/contention-plan/manifest.jsonl");
        Files.writeString(contentionManifest,
                Files.readString(contentionManifest, StandardCharsets.UTF_8)
                        .replaceFirst("\"clients\":2", "\"clients\":1"),
                StandardCharsets.UTF_8);
        ProcessResult tamperedHandoffCheck = runProcess(root, Duration.ofSeconds(20),
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

        ProcessResult freshness = runProcess(root, Duration.ofSeconds(10),
                "bash",
                handoff.resolve("perfect-plan/check-plan-freshness.sh").toString()
        );
        Assertions.assertEquals(0, freshness.exitCode, freshness.output);
        Assertions.assertTrue(freshness.output.contains("result=fresh"));

        ProcessResult impairmentFreshness = runProcess(root, Duration.ofSeconds(20),
                "bash",
                handoff.resolve("impairment-plan/check-plan-freshness.sh").toString()
        );
        Assertions.assertEquals(0, impairmentFreshness.exitCode, impairmentFreshness.output);
        Assertions.assertTrue(impairmentFreshness.output.contains("==> profile perfect"));
        Assertions.assertTrue(impairmentFreshness.output.contains("==> profile near-loss"));
        Assertions.assertTrue(impairmentFreshness.output.contains("result=fresh"));
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
                "--iterations", "1",
                "--start-delay", "1s",
                "--start-offset", "180s"
        );
        Assertions.assertEquals(0, result.exitCode, result.output);

        JsonNode handoffManifest = JSON.readTree(Files.readString(handoff.resolve("handoff-manifest.json"),
                StandardCharsets.UTF_8));
        Assertions.assertEquals(500, handoffManifest.path("contentionClientTotal").asInt());
        Assertions.assertEquals(5.0D, handoffManifest.path("perClientMbps").asDouble(), 0.001D);
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
        Assertions.assertEquals(5.0D, handoffCheckJson.path("expectedPerClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(500, handoffCheckJson.path("requiredMinContentionClients").asInt());
        Assertions.assertEquals(5.0D, handoffCheckJson.path("requiredMinContentionTargetClientMbps").asDouble(), 0.001D);
        Assertions.assertEquals(56, handoffCheckJson.path("actualPerfectCurveRows").asInt());
        Assertions.assertEquals(56, handoffCheckJson.path("actualPerfectRaisedCurveRows").asInt());
        Assertions.assertEquals(8, handoffCheckJson.path("actualPerfectContentionRows").asInt());
        Assertions.assertEquals(1, handoffCheckJson.path("actualImpairmentProfileRows").size());
        Assertions.assertEquals(8, handoffCheckJson.path("actualImpairmentProfileRows").get(0).path("contentionRows").asInt());
        Assertions.assertTrue(handoffCheckJson.path("expectedContentionScenarios").toString()
                .contains("\"batched-game-traffic\""));
        Assertions.assertTrue(handoffCheckJson.path("expectedContentionScenarios").toString()
                .contains("\"resource-pack-transfer\""));
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
    public void testPromotionRejectsValidationBypassesByDefault() throws Exception {
        assumeShellTooling();
        Path root = repoRoot();
        Path output = Files.createTempDirectory("raknet-lab-promotion-bypass-test");

        Path strictLab = output.resolve("strict-lab");
        writeValidationLabArtifacts(strictLab, 2, 2);
        ProcessResult strictPromotion = runProcess(root, Duration.ofSeconds(15),
                "bash",
                root.resolve("benchmark/scripts/promote-lab-baseline.sh").toString(),
                "--input", strictLab.toString(),
                "--out", output.resolve("baselines").toString(),
                "--name", "strict",
                "--no-latest"
        );
        Assertions.assertEquals(0, strictPromotion.exitCode, strictPromotion.output);
        JsonNode strictManifest = JSON.readTree(Files.readString(
                output.resolve("baselines/strict/baseline-manifest.json"), StandardCharsets.UTF_8));
        Assertions.assertFalse(strictManifest.path("allowValidationBypasses").asBoolean());

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
                        + "\"probeRttP99Millis\":1}\n",
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
        Assertions.assertTrue(manifest.path("serverArgs").asText().contains("--clients 10"));
        Assertions.assertTrue(manifest.path("healthyReceiverArgs").asText().contains("--clients 8"));
        Assertions.assertTrue(manifest.path("affectedReceiverArgs").asText().contains("--clients 2"));

        String readme = Files.readString(output.resolve("README.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(readme.contains("single-host smoke harness"));
        Assertions.assertTrue(readme.contains("does not prove NIC line-rate"));
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
        histogram.record(1_000_000L);
        histogram.record(2_000_000L);
        PeerStats peer = new PeerStats(0, false);
        peer.addBulkSent(64, 4);
        peer.addBulkReceived(64, 4);
        peer.addServerBytesOut(128);
        peer.addServerDatagramsOut(2);
        peer.addStaleDatagrams(3);
        peer.addNackIn(4);
        peer.addNackOut(5);
        peer.addProbeSent();
        peer.addProbeAcked();
        peer.addBlackholedDatagramIn();
        peer.addBlackholedDatagramOut();
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
                Arrays.asList(peer.snapshot())
        ));

        Path directory = new BenchmarkResultWriter().write(run).toPath();
        Assertions.assertTrue(Files.exists(directory.resolve("summary.json")));
        Assertions.assertTrue(Files.exists(directory.resolve("timeseries.csv")));
        Assertions.assertTrue(Files.exists(directory.resolve("latency.hdr")));
        Assertions.assertTrue(Files.exists(directory.resolve("report.md")));
        Assertions.assertTrue(Files.readString(directory.resolve("report.md"), StandardCharsets.UTF_8)
                .contains("| unit | 1 | 0.000000% | 0.000000% | true | `insufficient-iterations` |"));
        JsonNode summary = JSON.readTree(Files.readString(directory.resolve("summary.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals("baseline-bandwidth", summary.path("scenario").asText());
        Assertions.assertEquals("unit", summary.path("runId").asText());
        Assertions.assertTrue(summary.has("perClientTargetMbps"));
        Assertions.assertTrue(summary.has("packetLimit"));
        Assertions.assertTrue(summary.has("globalPacketLimit"));
        Assertions.assertEquals(1_048_576, summary.path("configuredMaxQueuedBytes").asInt());
        Assertions.assertTrue(summary.has("startAtEpochMillis"));
        Assertions.assertTrue(summary.has("impairmentLatencyMillis"));
        Assertions.assertTrue(summary.has("impairmentJitterMillis"));
        Assertions.assertTrue(summary.has("impairmentLossPercent"));
        Assertions.assertTrue(summary.has("stability"));
        Assertions.assertEquals("unit", summary.path("stability").get(0).path("name").asText());
        Assertions.assertTrue(summary.path("stability").get(0).path("unstable").asBoolean());
        Assertions.assertEquals("insufficient-iterations", summary.path("stability").get(0).path("unstableReasons").get(0).asText());
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
        Assertions.assertEquals(2.0D, summary.path("iterations").get(0).path("sentToDeliveredBytesRatio").asDouble(), 0.001D);
        Assertions.assertEquals(3.0D, summary.path("iterations").get(0).path("staleDatagramsPerSecond").asDouble(), 0.001D);
        Assertions.assertEquals(5.0D, summary.path("iterations").get(0).path("nackOutPerSecond").asDouble(), 0.001D);
        Assertions.assertEquals(0.000512D, summary.path("iterations").get(0).path("perClientThroughput").path("p50Mbps").asDouble(), 0.000001D);
        Assertions.assertTrue(summary.path("iterations").get(0).has("healthyClientThroughput"));
        Assertions.assertTrue(summary.path("iterations").get(0).has("affectedClientThroughput"));
        Assertions.assertTrue(summary.path("iterations").get(0).has("disconnects"));
        Assertions.assertEquals(1, summary.path("iterations").get(0).path("blackholedDatagramsIn").asLong());
        Assertions.assertEquals(1, summary.path("iterations").get(0).path("blackholedDatagramsOut").asLong());
        Assertions.assertEquals(4, summary.path("iterations").get(0).path("peers").get(0).path("logicalPacketsReceived").asLong());
        Assertions.assertEquals(1, summary.path("iterations").get(0).path("peers").get(0).path("blackholedDatagramsIn").asLong());
        Assertions.assertEquals(1, summary.path("iterations").get(0).path("peers").get(0).path("blackholedDatagramsOut").asLong());
        Assertions.assertTrue(summary.path("iterations").get(0).path("peers").get(0).has("serverBytesOut"));

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
        Assertions.assertTrue(rows.get(0).containsKey("healthy_fairness"));
        Assertions.assertTrue(rows.get(0).containsKey("disconnects"));
        Assertions.assertEquals("1", rows.get(0).get("blackholed_datagrams_in"));
        Assertions.assertEquals("1", rows.get(0).get("blackholed_datagrams_out"));
        Assertions.assertTrue(rows.get(0).containsKey("max_queued_bytes"));
    }

    private static void writeReadinessLabBaseline(Path labBaseline, int... payloadSizes) throws Exception {
        writeReadinessLabBaseline(labBaseline, 500, 5.0D, payloadSizes);
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
                "{\"baselineKind\":\"raknet-lab-baseline\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(labBaseline.resolve("validation.json"),
                "{\"passed\":true,\"distinctHostnameCount\":2,\"hostReportCount\":2,\"rowCount\":"
                        + (payloadSizes.length + 5)
                        + ",\"capacityRowCount\":" + payloadSizes.length
                        + ",\"prereqReportCount\":" + prereqReportCount
                        + ",\"readyPrereqReportCount\":" + readyPrereqReportCount
                        + ",\"notReadyPrereqReportCount\":" + Math.max(0, prereqReportCount - readyPrereqReportCount)
                        + ",\"strictPrereqReportCount\":" + readyPrereqReportCount
                        + ",\"prereqDistinctHostnameCount\":" + prereqDistinctHostnameCount
                        + ",\"strictPrereqDistinctHostnameCount\":" + Math.min(readyPrereqReportCount, prereqDistinctHostnameCount)
                        + ",\"minContentionClients\":" + minContentionClients
                        + ",\"minContentionTargetClientMbps\":" + minContentionTargetClientMbps
                        + ",\"scenarioCounts\":{\"curve\":" + payloadSizes.length
                        + ",\"multi-client-fanout\":1,\"fairness\":1,\"disappearing-clients\":1,"
                        + "\"batched-game-traffic\":1,\"resource-pack-transfer\":1}}\n",
                StandardCharsets.UTF_8);

        StringBuilder aggregate = new StringBuilder();
        StringBuilder capacity = new StringBuilder();
        for (int payloadSize : payloadSizes) {
            aggregate.append("{\"case\":\"lab-curve-p")
                    .append(payloadSize)
                    .append("\",\"benchmarkName\":\"curve-100_0mbps\",\"scenario\":\"curve\",\"payloadSize\":")
                    .append(payloadSize)
                    .append("}\n");
            capacity.append("{\"summaryKind\":\"bandwidth-capacity\",\"case\":\"lab-curve-p")
                    .append(payloadSize)
                    .append("\",\"payloadSize\":")
                    .append(payloadSize)
                    .append(",\"selected\":true}\n");
        }
        aggregate.append("{\"case\":\"fanout\",\"benchmarkName\":\"multi-client-fanout\",\"payloadSize\":512}\n");
        aggregate.append("{\"case\":\"fairness\",\"benchmarkName\":\"fairness\",\"payloadSize\":512}\n");
        aggregate.append("{\"case\":\"disappear\",\"benchmarkName\":\"disappearing-clients\",\"payloadSize\":512}\n");
        aggregate.append("{\"case\":\"batch\",\"benchmarkName\":\"batched-game-traffic\",\"payloadSize\":512}\n");
        aggregate.append("{\"case\":\"resource-pack\",\"benchmarkName\":\"resource-pack-transfer\",\"payloadSize\":8192}\n");
        Files.writeString(labBaseline.resolve("suite-aggregate.jsonl"), aggregate.toString(), StandardCharsets.UTF_8);
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
                    .append("\"unstableReasons\":[],")
                    .append("\"artifact\":\"").append(artifact).append("\"}\n");
        }
        Files.writeString(labRoot.resolve("suite-aggregate.jsonl"), aggregate.toString(), StandardCharsets.UTF_8);
        Files.writeString(labRoot.resolve("bandwidth-capacity.jsonl"),
                "{\"summaryKind\":\"bandwidth-capacity\",\"case\":\"curve-100_0mbps\",\"payloadSize\":512,"
                        + "\"selected\":true}\n",
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
                        + "\"unstableReasons\":[],"
                        + "\"artifact\":\"" + suiteRoot.resolve("fanout") + "\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(suiteRoot.resolve("validation.json"),
                validationBypass
                        ? "{\"passed\":true,\"allowUnstable\":true,\"issues\":[]}\n"
                        : "{\"passed\":true,\"issues\":[]}\n",
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
                        + "}]}}]}\n",
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

        int[] payloadSizes = includeSplitPayload
                ? new int[]{64, 256, 512, 1200, 1340, 1400, 262144}
                : new int[]{64, 256, 512, 1200, 1340, 1400};
        StringBuilder profiles = new StringBuilder();
        String[] profileNames = {"perfect", "near-loss", "regional-loss", "poor", "severe"};
        for (int profileIndex = 0; profileIndex < profileNames.length; profileIndex++) {
            if (profileIndex > 0) {
                profiles.append(',');
            }
            profiles.append("{\"profile\":\"").append(profileNames[profileIndex]).append("\",")
                    .append("\"aggregate\":{\"contentionRows\":[")
                    .append("{\"benchmarkName\":\"multi-client-fanout\"},")
                    .append("{\"benchmarkName\":\"fairness\"}");
            if (includeDisappearingContention) {
                profiles.append(",{\"benchmarkName\":\"disappearing-clients\"}");
            }
            profiles.append(",{\"benchmarkName\":\"batched-game-traffic\"}");
            profiles.append(",{\"benchmarkName\":\"resource-pack-transfer\"}");
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
                        .append(",\"selected\":true}");
            }
            profiles.append("]}}");
        }

        Files.writeString(impairmentBaseline.resolve("impairment-summary.json"),
                "{\"passed\":true,\"requireNetemEvidence\":true,\"validationPassedCount\":5,\"profileCount\":5,"
                        + "\"netemStatusEvidenceCount\":5,\"aggregateRowCount\":1,\"capacityRowCount\":1,"
                        + "\"profiles\":[" + profiles + "]}\n",
                StandardCharsets.UTF_8);
    }

    private static BenchmarkIterationResult iteration(String name, int iteration, int receivedBytes, double p99Millis) {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record((long) (p99Millis * 1_000_000.0D));
        PeerStats peer = new PeerStats(0, false);
        peer.addBulkReceived(receivedBytes);
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

    private static void assumeShellTooling() throws Exception {
        Assumptions.assumeTrue(commandAvailable("bash"), "bash is required for benchmark script tests");
        Assumptions.assumeTrue(commandAvailable("jq"), "jq is required for benchmark script tests");
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
