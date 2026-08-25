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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.RakState;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.netty.util.FastWeightedFairQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class BenchmarkProbeIsolationTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void probeAndAckShareReliableOrderedIndicesWithGameTraffic() throws Exception {
        RakSessionCodec codec = codec();
        List<RakMessage> messages = new ArrayList<>();
        List<EncapsulatedPacket> packets = new ArrayList<>();
        try {
            RakMessage firstBulk = BenchmarkMessages.bulk(
                    BenchmarkPayload.bulk(UnpooledByteBufAllocator.DEFAULT, 64, 1L),
                    RakReliability.RELIABLE_ORDERED);
            messages.add(firstBulk);
            packets.add(encode(codec, firstBulk));
            assertWriteIndices(codec, 1, 1);

            RakMessage probe = BenchmarkMessages.probe(
                    BenchmarkPayload.probe(UnpooledByteBufAllocator.DEFAULT, 2L, 3L));
            messages.add(probe);
            EncapsulatedPacket encodedProbe = encode(codec, probe);
            packets.add(encodedProbe);
            Assertions.assertEquals(RakReliability.RELIABLE_ORDERED, encodedProbe.getReliability());
            Assertions.assertEquals(1, encodedProbe.getReliabilityIndex());
            Assertions.assertEquals(1, encodedProbe.getOrderingIndex());
            assertWriteIndices(codec, 2, 2);

            RakMessage ack = BenchmarkMessages.probeAck(
                    BenchmarkPayload.probeAck(UnpooledByteBufAllocator.DEFAULT, 2L, 3L));
            messages.add(ack);
            EncapsulatedPacket encodedAck = encode(codec, ack);
            packets.add(encodedAck);
            Assertions.assertEquals(RakReliability.RELIABLE_ORDERED, encodedAck.getReliability());
            Assertions.assertEquals(2, encodedAck.getReliabilityIndex());
            Assertions.assertEquals(2, encodedAck.getOrderingIndex());
            assertWriteIndices(codec, 3, 3);

            RakMessage secondBulk = BenchmarkMessages.bulk(
                    BenchmarkPayload.bulk(UnpooledByteBufAllocator.DEFAULT, 64, 4L),
                    RakReliability.RELIABLE_ORDERED);
            messages.add(secondBulk);
            EncapsulatedPacket encodedSecondBulk = encode(codec, secondBulk);
            packets.add(encodedSecondBulk);
            Assertions.assertEquals(3, encodedSecondBulk.getReliabilityIndex());
            Assertions.assertEquals(3, encodedSecondBulk.getOrderingIndex());
            Assertions.assertEquals(0, encodedSecondBulk.getOrderingChannel());
            assertWriteIndices(codec, 4, 4);
        } finally {
            packets.forEach(EncapsulatedPacket::release);
            messages.forEach(RakMessage::release);
        }
    }

    @Test
    public void highPriorityTelemetryUsesWeightedQueueWithoutReorderingOrStarvingBulk() throws Exception {
        RakSessionCodec codec = codec();
        FastWeightedFairQueue<EncapsulatedPacket> outgoing = new FastWeightedFairQueue<>(4);
        set(codec, "outgoingPackets", outgoing);
        set(codec, "outgoingPacketNextWeights", new long[4]);
        invoke(codec, "initOutgoingPacketWeights");
        set(codec, "state", RakState.CONNECTED);

        int initialBulkPackets = 32;
        List<Long> emittedBulkSequences = new ArrayList<>();
        int emittedTelemetry = 0;
        try {
            for (int sequence = 0; sequence < initialBulkPackets; sequence++) {
                enqueue(codec, BenchmarkMessages.bulk(
                        BenchmarkPayload.bulk(UnpooledByteBufAllocator.DEFAULT, 64, sequence),
                        RakReliability.RELIABLE_ORDERED));
            }
            Assertions.assertEquals(initialBulkPackets, outgoing.size());

            // Maintain a permanent NORMAL backlog while periodic HIGH probes and more NORMAL bulk arrive. This is
            // the production scheduling shape that exposed stale priority weights and ordered-stream holes.
            for (int round = 0; round < initialBulkPackets + 8; round++) {
                enqueue(codec, BenchmarkMessages.probe(BenchmarkPayload.probe(
                        UnpooledByteBufAllocator.DEFAULT, 1_000L + round, 2_000L + round)));
                enqueue(codec, BenchmarkMessages.bulk(BenchmarkPayload.bulk(
                                UnpooledByteBufAllocator.DEFAULT, 64, initialBulkPackets + round),
                        RakReliability.RELIABLE_ORDERED));
                Assertions.assertTrue(outgoing.size() >= initialBulkPackets);

                for (int sent = 0; sent < 2; sent++) {
                    EncapsulatedPacket packet = outgoing.poll();
                    try {
                        if (BenchmarkPayload.type(packet.getBuffer()) == BenchmarkPayload.BULK) {
                            emittedBulkSequences.add(BenchmarkPayload.sequence(packet.getBuffer()));
                        } else {
                            emittedTelemetry++;
                        }
                    } finally {
                        packet.release();
                    }
                }
            }

            for (long sequence = 0; sequence < initialBulkPackets; sequence++) {
                Assertions.assertTrue(emittedBulkSequences.contains(sequence),
                        "earlier NORMAL bulk " + sequence + " was starved by periodic probe traffic");
            }
            Assertions.assertTrue(emittedTelemetry > 0, "HIGH probes retain timely weighted service");
            Assertions.assertFalse(outgoing.isEmpty(), "test must retain a persistent workload backlog");

            EncapsulatedPacket packet;
            while ((packet = outgoing.poll()) != null) {
                try {
                    if (BenchmarkPayload.type(packet.getBuffer()) == BenchmarkPayload.BULK) {
                        emittedBulkSequences.add(BenchmarkPayload.sequence(packet.getBuffer()));
                    }
                } finally {
                    packet.release();
                }
            }
            List<Long> sortedBulkSequences = new ArrayList<>(emittedBulkSequences);
            Collections.sort(sortedBulkSequences);
            Assertions.assertEquals(sortedBulkSequences, emittedBulkSequences,
                    "probe scheduling never changes relative bulk order");
        } finally {
            EncapsulatedPacket packet;
            while ((packet = outgoing.poll()) != null) {
                packet.release();
            }
            outgoing.release();
        }
    }

    @Test
    public void receiverUsesReliableOrderedHighPriorityProbeAcknowledgements() {
        PeerStats peer = new PeerStats(0, false);
        EmbeddedChannel channel = new EmbeddedChannel(new ClientReceiverHandler(peer, new CountDownLatch(1)));
        try {
            channel.writeInbound(new RakMessage(
                    BenchmarkPayload.probe(channel.alloc(), 42L, 123L),
                    RakReliability.RELIABLE_ORDERED,
                    RakPriority.NORMAL));
            RakMessage ack = channel.readOutbound();
            Assertions.assertNotNull(ack);
            try {
                Assertions.assertEquals(BenchmarkPayload.PROBE_ACK, BenchmarkPayload.type(ack.content()));
                Assertions.assertEquals(42L, BenchmarkPayload.sequence(ack.content()));
                Assertions.assertEquals(123L, BenchmarkPayload.timestampNanos(ack.content()));
                Assertions.assertEquals(RakReliability.RELIABLE_ORDERED, ack.reliability());
                Assertions.assertEquals(RakPriority.HIGH, ack.priority());
            } finally {
                ack.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void lateAndDuplicateProbeAcknowledgementsCannotContaminateANewerWindow() {
        PeerStats peer = new PeerStats(0, false);
        ProbeTracker tracker = new ProbeTracker();
        EmbeddedChannel channel = new EmbeddedChannel(new ServerProbeAckHandler(peer, tracker));
        try {
            long warmupSequence = tracker.nextSequence();
            tracker.registerSent(warmupSequence, peer, System.nanoTime());

            tracker.beginMeasurement();
            long firstSequence = tracker.nextSequence();
            tracker.registerSent(firstSequence, peer, System.nanoTime() - 1_000_000L);
            Assertions.assertFalse(channel.writeInbound(probeAck(channel, firstSequence)));
            LatencyHistogram.Snapshot first = tracker.closeAndSnapshot();
            Assertions.assertEquals(1, first.count());
            Assertions.assertEquals(1, peer.snapshot().probesSent);
            Assertions.assertEquals(1, peer.snapshot().probesAcked);
            Assertions.assertEquals(0, peer.snapshot().probeAckSpillover);

            peer.resetMeasurement();
            tracker.beginMeasurement();
            Assertions.assertFalse(channel.writeInbound(probeAck(channel, warmupSequence)));
            Assertions.assertFalse(channel.writeInbound(probeAck(channel, firstSequence)));
            Assertions.assertEquals(0, peer.snapshot().probesAcked,
                    "warmup and prior-window ACKs never enter the current acknowledgement count");
            Assertions.assertEquals(2, peer.snapshot().probeAckSpillover);

            long secondSequence = tracker.nextSequence();
            tracker.registerSent(secondSequence, peer, System.nanoTime() - 1_000_000L);
            PeerStats wrongPeer = new PeerStats(1, false);
            tracker.acknowledge(wrongPeer, secondSequence, System.nanoTime());
            Assertions.assertEquals(1, wrongPeer.snapshot().probeAckSpillover);
            Assertions.assertFalse(channel.writeInbound(probeAck(channel, secondSequence)));
            Assertions.assertFalse(channel.writeInbound(probeAck(channel, secondSequence)));
            LatencyHistogram.Snapshot second = tracker.closeAndSnapshot();
            Assertions.assertEquals(1, second.count(), "only the exact outstanding current probe contributes RTT");
            Assertions.assertEquals(1, peer.snapshot().probesSent);
            Assertions.assertEquals(1, peer.snapshot().probesAcked);
            Assertions.assertEquals(3, peer.snapshot().probeAckSpillover,
                    "duplicate current ACK is explicit spillover evidence");

            Assertions.assertFalse(channel.writeInbound(probeAck(channel, secondSequence)));
            Assertions.assertEquals(3, peer.snapshot().probeAckSpillover,
                    "ACK after atomic close cannot mutate the completed window");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void artifactsDescribeReliableOrderedProbeSemantics() throws Exception {
        Path output = Files.createTempDirectory("raknet-probe-isolation-output");
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth",
                "--out", output.toString(),
                "--run-id", "probe-isolation",
                "--reliability", "reliable_ordered",
                "--iterations", "1",
                "--duration", "1s",
                "--warmup", "0ms"
        });
        BenchmarkRunResult result = new BenchmarkRunResult(
                config, EnvironmentInfo.capture(), 1_000L, 1_000_000_000L);
        result.add(new BenchmarkIterationResult(
                "probe-isolation",
                1,
                0,
                512,
                RakReliability.RELIABLE_ORDERED,
                0.0D,
                0.0D,
                DisappearanceMode.CLOSE,
                false,
                20L,
                8,
                1,
                1_000L,
                new LatencyHistogram().snapshot(),
                List.of()
        ));

        Path directory = new BenchmarkResultWriter().write(result).toPath();
        JsonNode summary = JSON.readTree(Files.readString(
                directory.resolve("summary.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals("RELIABLE_ORDERED", summary.path("probeReliability").asText());
        Assertions.assertEquals("HIGH", summary.path("probePriority").asText());
        Assertions.assertTrue(summary.path("probeSemantics").asText().contains("loss recovery"));
        Assertions.assertEquals(10, summary.path("minimumProbeResponsesPerIteration").asInt());
        Assertions.assertEquals(0.5D, summary.path("minimumProbeResponseRate").asDouble(), 0.000001D);

        String report = Files.readString(directory.resolve("report.md"), StandardCharsets.UTF_8);
        Assertions.assertTrue(report.contains("applies to bulk/batch traffic only"));
        Assertions.assertTrue(report.contains("Probe transport: `RELIABLE_ORDERED/HIGH"));
        Assertions.assertTrue(Files.readString(directory.resolve("latency.hdr"), StandardCharsets.UTF_8)
                .contains("Probe transport: RELIABLE_ORDERED/HIGH through the weighted scheduler"));
    }

    @Test
    public void probeCoverageFailsClosedAndPreservesSpilloverProvenance() throws Exception {
        Path output = Files.createTempDirectory("raknet-probe-censoring-output");
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "bandwidth-latency-curve",
                "--out", output.toString(),
                "--run-id", "probe-censoring",
                "--iterations", "3",
                "--duration", "1s",
                "--warmup", "0ms",
                "--rates-mbps", "100,200,300,400"
        });
        BenchmarkRunResult result = new BenchmarkRunResult(config, EnvironmentInfo.capture());
        for (int iteration = 1; iteration <= 3; iteration++) {
            addProbeCandidateIteration(result, "missing-responses", iteration, 200.0D,
                    100_000, 10, 0, 0, 0);
            addProbeCandidateIteration(result, "valid", iteration, 100.0D,
                    100_000, 12, 10, 10, 0);
            addProbeCandidateIteration(result, "low-return", iteration, 300.0D,
                    110_000, 100, 10, 10, 0);
            addProbeCandidateIteration(result, "spillover", iteration, 400.0D,
                    120_000, 10, 10, 10, 1);
        }

        Path directory = new BenchmarkResultWriter().write(result).toPath();
        JsonNode capacity = JSON.readTree(Files.readString(
                directory.resolve("bandwidth-capacity.jsonl"), StandardCharsets.UTF_8).trim());
        Assertions.assertEquals("valid",
                capacity.path("selectedCandidate").path("benchmarkName").asText());
        Assertions.assertEquals("valid",
                capacity.path("bestObservedCandidate").path("benchmarkName").asText(),
                "a tied candidate with unavailable p99 sorts after valid latency evidence");
        Assertions.assertEquals(36, capacity.path("selectedCandidate").path("probesSent").asLong());
        Assertions.assertEquals(30, capacity.path("selectedCandidate").path("probesAcked").asLong());
        Assertions.assertEquals(0, capacity.path("selectedCandidate").path("probeAckSpillover").asLong());
        Assertions.assertEquals(10.0D / 12.0D,
                capacity.path("selectedCandidate").path("probeResponseRate").asDouble(), 0.000001D);

        JsonNode missing = candidate(capacity.path("rejectedCandidates"), "missing-responses");
        Assertions.assertTrue(missing.path("probeRttP99Millis").isNull());
        Assertions.assertTrue(missing.path("probeP99SpreadPct").isNull());
        Assertions.assertTrue(missing.path("rejectionReasons").toString()
                .contains("missing-probe-p99"));
        Assertions.assertTrue(missing.path("rejectionReasons").toString()
                .contains("insufficient-probe-responses"));
        Assertions.assertTrue(missing.path("rejectionReasons").toString()
                .contains("insufficient-probe-return-rate"));
        JsonNode lowReturn = candidate(capacity.path("rejectedCandidates"), "low-return");
        Assertions.assertEquals(10, lowReturn.path("minimumProbeResponses").asInt());
        Assertions.assertEquals(0.1D, lowReturn.path("minimumProbeResponseRate").asDouble(), 0.000001D);
        Assertions.assertFalse(lowReturn.path("rejectionReasons").toString()
                .contains("insufficient-probe-responses"));
        Assertions.assertTrue(lowReturn.path("rejectionReasons").toString()
                .contains("insufficient-probe-return-rate"));
        JsonNode spillover = candidate(capacity.path("rejectedCandidates"), "spillover");
        Assertions.assertEquals(3, spillover.path("probeAckSpillover").asInt());
        Assertions.assertTrue(spillover.path("rejectionReasons").toString()
                .contains("probe-ack-spillover"));

        JsonNode summary = JSON.readTree(Files.readString(directory.resolve("summary.json"), StandardCharsets.UTF_8));
        JsonNode spilloverStability = candidate(summary.path("stability"), "spillover");
        Assertions.assertTrue(spilloverStability.path("unstableReasons").toString()
                .contains("probe-ack-spillover"));
        JsonNode missingStability = candidate(summary.path("stability"), "missing-responses");
        Assertions.assertTrue(missingStability.path("probeP99RelativeSpreadPct").isNull());
        Assertions.assertTrue(missingStability.path("unstableReasons").toString()
                .contains("insufficient-probe-responses"));
        Assertions.assertTrue(missingStability.path("unstableReasons").toString()
                .contains("insufficient-probe-return-rate"));
        JsonNode lowReturnStability = candidate(summary.path("stability"), "low-return");
        Assertions.assertTrue(lowReturnStability.path("unstableReasons").toString()
                .contains("insufficient-probe-return-rate"));

        String timeseries = Files.readString(directory.resolve("timeseries.csv"), StandardCharsets.UTF_8);
        Assertions.assertTrue(timeseries.lines().findFirst().orElseThrow()
                .contains("probe_reliability,probe_priority,probe_semantics"));
        Assertions.assertTrue(timeseries.lines().findFirst().orElseThrow()
                .contains("probes_sent,probes_acked,probe_ack_spillover,probe_response_rate,probe_rtt_count"));
        String capacityCsv = Files.readString(directory.resolve("bandwidth-capacity.csv"), StandardCharsets.UTF_8);
        Assertions.assertTrue(capacityCsv.lines().findFirst().orElseThrow().contains("probe_semantics"));
        Assertions.assertTrue(capacityCsv.lines().findFirst().orElseThrow()
                .contains("selected_probe_ack_spillover"));
        Assertions.assertTrue(capacityCsv.lines().findFirst().orElseThrow()
                .contains("selected_probe_response_rate"));
        Assertions.assertTrue(Files.readString(directory.resolve("bandwidth-capacity.md"), StandardCharsets.UTF_8)
                .contains("Probe ACKed/Sent"));
    }

    private static RakSessionCodec codec() throws Exception {
        RakChannelConfig config = (RakChannelConfig) Proxy.newProxyInstance(
                RakChannelConfig.class.getClassLoader(), new Class<?>[]{RakChannelConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMtu" -> 1_200;
                    case "getMetrics" -> null;
                    default -> defaultValue(method.getReturnType());
                });
        RakChannel channel = (RakChannel) Proxy.newProxyInstance(
                RakChannel.class.getClassLoader(), new Class<?>[]{RakChannel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "config" -> config;
                    case "remoteAddress", "localAddress" -> new InetSocketAddress("127.0.0.1", 19132);
                    default -> defaultValue(method.getReturnType());
                });
        RakSessionCodec codec = new RakSessionCodec(channel);
        set(codec, "orderWriteIndex", new int[1]);
        return codec;
    }

    private static EncapsulatedPacket encode(RakSessionCodec codec, RakMessage message) throws Exception {
        Method method = RakSessionCodec.class.getDeclaredMethod("createEncapsulated", RakMessage.class);
        method.setAccessible(true);
        return ((EncapsulatedPacket[]) method.invoke(codec, message))[0];
    }

    private static void enqueue(RakSessionCodec codec, RakMessage message) throws Exception {
        try {
            invoke(codec, "send", new Class<?>[]{io.netty.channel.ChannelHandlerContext.class, RakMessage.class},
                    null, message);
        } finally {
            message.release();
        }
    }

    private static void addProbeCandidateIteration(BenchmarkRunResult result, String name, int iteration,
                                                   double targetMbps, int deliveredBytes, int probesSent,
                                                   int probesAcked, int rttSamples, int probeAckSpillover) {
        PeerStats peer = new PeerStats(0, false);
        peer.addBulkSent(deliveredBytes);
        peer.addBulkReceived(deliveredBytes);
        peer.addServerBytesOut(deliveredBytes);
        for (int i = 0; i < probesSent; i++) {
            peer.addProbeSent();
        }
        for (int i = 0; i < probesAcked; i++) {
            peer.addProbeAcked();
        }
        for (int i = 0; i < probeAckSpillover; i++) {
            peer.addProbeAckSpillover();
        }
        LatencyHistogram histogram = new LatencyHistogram();
        for (int i = 0; i < rttSamples; i++) {
            histogram.record(10_000_000L);
        }
        result.add(new BenchmarkIterationResult(
                name,
                iteration,
                1,
                512,
                RakReliability.RELIABLE_ORDERED,
                targetMbps,
                targetMbps,
                DisappearanceMode.CLOSE,
                false,
                20L,
                1,
                1,
                1_000L,
                histogram.snapshot(),
                List.of(peer.snapshot(true, true))
        ));
    }

    private static RakMessage probeAck(EmbeddedChannel channel, long sequence) {
        return BenchmarkMessages.probeAck(BenchmarkPayload.probeAck(channel.alloc(), sequence, 0L));
    }

    private static JsonNode candidate(JsonNode rows, String name) {
        for (JsonNode row : rows) {
            String candidateName = row.has("benchmarkName")
                    ? row.path("benchmarkName").asText() : row.path("name").asText();
            if (name.equals(candidateName)) {
                return row;
            }
        }
        throw new AssertionError("Missing candidate " + name + " in " + rows);
    }

    private static void assertWriteIndices(RakSessionCodec codec, int reliability, int ordering) throws Exception {
        Assertions.assertEquals(reliability, get(codec, "reliabilityWriteIndex"));
        Assertions.assertEquals(ordering, ((int[]) get(codec, "orderWriteIndex"))[0]);
    }

    private static Object invoke(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        return '\0';
    }
}
