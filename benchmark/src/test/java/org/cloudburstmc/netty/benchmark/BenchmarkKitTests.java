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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
        Assertions.assertTrue(summary.contains("| case-a | 2 | 0.990099% | 0.000000% | false | `` |"));
        Assertions.assertTrue(summary.contains("| case-b | 2 | 50.000000% | 50.000000% | true | `throughput-spread,p99-spread` |"));
    }

    @Test
    public void testNetemPlannerDryRunCommands() {
        List<String> apply = NetemCommandPlanner.apply("eth0", "50ms", "5ms", "2%");
        Assertions.assertEquals("tc qdisc replace dev eth0 root netem delay 50ms 5ms loss 2%", apply.get(0));

        Assertions.assertEquals("tc qdisc del dev eth0 root", NetemCommandPlanner.clear("eth0").get(0));
        Assertions.assertEquals("tc qdisc show dev eth0", NetemCommandPlanner.status("eth0").get(0));
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
                "--payload-size", "64"
        });
        BenchmarkRunResult run = new BenchmarkRunResult(config, EnvironmentInfo.capture());
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(1_000_000L);
        histogram.record(2_000_000L);
        PeerStats peer = new PeerStats(0, false);
        peer.addBulkSent(64, 4);
        peer.addBulkReceived(64, 4);
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
        JsonNode summary = JSON.readTree(Files.readString(directory.resolve("summary.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals("baseline-bandwidth", summary.path("scenario").asText());
        Assertions.assertEquals("unit", summary.path("runId").asText());
        Assertions.assertTrue(summary.has("perClientTargetMbps"));
        Assertions.assertTrue(summary.has("packetLimit"));
        Assertions.assertTrue(summary.has("globalPacketLimit"));
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
}
