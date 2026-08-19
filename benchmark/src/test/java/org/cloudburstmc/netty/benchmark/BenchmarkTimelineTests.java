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
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.channel.raknet.config.RakDatagramSendType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BenchmarkTimelineTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void testLifetimeTelemetrySurvivesMeasurementResetAndCloseReclaimsGauges() {
        PeerStats peer = new PeerStats(1, true);
        peer.addBulkSent(512, 4);
        peer.addBulkReceived(256, 2);
        peer.addServerBytesOut(640);
        peer.addServerDatagramsOut(2);
        peer.addStaleDatagrams(1);
        peer.addNackIn(2);
        peer.addNackOut(3);
        peer.queuedBytes(4096);
        peer.datagramSent(RakDatagramSendType.ORIGINAL, 300, 0, 300);
        peer.datagramSent(RakDatagramSendType.NACK_RETRANSMISSION, 300, 1, 300);
        peer.datagramSent(RakDatagramSendType.TIMEOUT_RETRANSMISSION, 300, 2, 300);
        peer.acknowledgementProgress(300, 2, 1_000L, 900L, 800L);
        peer.recoveryState(1_000L, 300, 1200.0D, 2400.0D, 50.0D, 5.0D,
                250L, 1, 1_000L, 800L);
        peer.addDisconnect();
        // Parent close can be observed before the internal session tick is cancelled. Reproduce the
        // late queue callback seen in a real netns disappearance run, then apply the terminal callback.
        peer.queuedBytes(4096);
        peer.recoveryStateClosed(1_100L);

        peer.resetMeasurement();

        PeerStats.Snapshot window = peer.snapshot(false, false);
        Assertions.assertEquals(0L, window.bulkSentBytes);
        Assertions.assertEquals(0L, window.disconnects);
        Assertions.assertEquals(0L, window.maxQueuedBytes);

        PeerStats.TimelineSnapshot lifetime = peer.timelineSnapshot(false, false);
        Assertions.assertTrue(lifetime.disconnected());
        Assertions.assertEquals(1L, lifetime.disconnectEvents());
        Assertions.assertEquals(512L, lifetime.usefulSentBytes());
        Assertions.assertEquals(256L, lifetime.usefulReceivedBytes());
        Assertions.assertEquals(1L, lifetime.nackRetransmittedDatagrams());
        Assertions.assertEquals(1L, lifetime.timeoutRetransmittedDatagrams());
        Assertions.assertEquals(600L, lifetime.nackRetransmittedBytes() + lifetime.timeoutRetransmittedBytes());
        Assertions.assertEquals(4096L, lifetime.maxQueuedBytes());
        Assertions.assertEquals(0L, lifetime.currentQueuedBytes());
        Assertions.assertEquals(300L, lifetime.maxBytesInFlight());
        Assertions.assertEquals(0L, lifetime.bytesInFlight());
        Assertions.assertEquals(0, lifetime.retransmittedDatagramsInFlight());
        Assertions.assertEquals(-1L, lifetime.recoveryObservedAtMillis());
        Assertions.assertEquals(-1.0D, lifetime.congestionWindow());
        Assertions.assertEquals(-1L, lifetime.lastAckProgressAtMillis());
    }

    @Test
    public void testDeterministicEventAlignedCohortSample() {
        BenchmarkConfig config = timelineConfig(null);
        BenchmarkRunResult result = new BenchmarkRunResult(
                config, EnvironmentInfo.capture(), 1_000_000L, 1_000_000_000L);
        PeerStats healthy = peer(0, false, 100L);
        PeerStats affected = peer(1, true, 200L);
        PeerStats disconnected = peer(2, true, 50L);
        disconnected.addDisconnect();

        BenchmarkTimelineRecorder recorder = new BenchmarkTimelineRecorder(
                result,
                "timeline-case",
                3,
                2,
                () -> Arrays.asList(
                        healthy.timelineSnapshot(true, true),
                        affected.timelineSnapshot(true, true),
                        disconnected.timelineSnapshot(false, false)),
                BenchmarkTimelineRecorder.Capabilities.SERVER,
                false
        );

        BenchmarkTimeline.Sample sample = recorder.captureAt(1_000_500L, 1_250_000_000L);

        Assertions.assertEquals(250L, sample.monotonicElapsedMillis());
        Assertions.assertEquals(500L, sample.coordinatedStartRelativeMillis());
        Assertions.assertEquals(400L, sample.externalImpairmentRelativeMillis());
        Assertions.assertEquals(100L, sample.externalBlackholeRelativeMillis());
        Assertions.assertEquals(-100L, sample.externalRecoveryRelativeMillis());
        Assertions.assertEquals("longitudinal-shared-session-windows", sample.measurementWindowSemantics());
        Assertions.assertEquals(3, sample.all().observedPeers());
        Assertions.assertEquals(2, sample.all().openPeers());
        Assertions.assertEquals(1, sample.all().disconnectedPeers());
        Assertions.assertEquals(300L, sample.all().currentQueuedBytes());
        Assertions.assertEquals(100L, sample.healthy().currentQueuedBytes());
        Assertions.assertEquals(200L, sample.affected().currentQueuedBytes());
        Assertions.assertEquals(6L, sample.all().retransmittedDatagrams());
        Assertions.assertEquals(1800L, sample.all().retransmittedBytes());
        Assertions.assertNull(sample.all().usefulReceivedBytes());
        Assertions.assertEquals("unavailable-on-server-worker",
                sample.metricAvailability().usefulDeliveryCounters());
        Assertions.assertTrue(sample.metricAvailability().unavailableFields()
                .contains("cohort.usefulReceivedBytes"));
        Assertions.assertTrue(sample.runtime().heapUsedBytes() > 0L);
    }

    @Test
    public void testConfiguredEventsAndTimelineSummaryAreWritten() throws Exception {
        Path output = Files.createTempDirectory("raknet-timeline-test");
        BenchmarkConfig config = timelineConfig(output);
        BenchmarkRunResult result = new BenchmarkRunResult(config, EnvironmentInfo.capture());
        PeerStats healthy = peer(0, false, 100L);
        PeerStats affected = peer(1, true, 200L);
        PeerStats disconnected = peer(2, true, 50L);
        disconnected.addDisconnect();
        try (BenchmarkTimelineRecorder recorder = new BenchmarkTimelineRecorder(
                result,
                "timeline-case",
                3,
                2,
                () -> List.of(
                        healthy.timelineSnapshot(true, true),
                        affected.timelineSnapshot(true, true),
                        disconnected.timelineSnapshot(false, false)),
                BenchmarkTimelineRecorder.Capabilities.SERVER,
                false)) {
            recorder.start();
            recorder.markPhase("measurement", 1);
        }

        Path directory = new BenchmarkResultWriter().write(result).toPath();
        List<String> rows = Files.readAllLines(directory.resolve("timeline.jsonl"), StandardCharsets.UTF_8);
        Assertions.assertFalse(rows.isEmpty());
        boolean impairmentEvent = false;
        boolean blackholeEvent = false;
        boolean recoveryEvent = false;
        for (String row : rows) {
            JsonNode json = JSON.readTree(row);
            if ("external-impairment-scheduled".equals(json.path("eventName").asText())) {
                impairmentEvent = true;
                Assertions.assertEquals(1_000_100L, json.path("epochMillis").asLong());
                Assertions.assertTrue(json.path("monotonicElapsedMillis").isNull());
            } else if ("external-blackhole-scheduled".equals(json.path("eventName").asText())) {
                blackholeEvent = true;
                Assertions.assertEquals(1_000_400L, json.path("epochMillis").asLong());
            } else if ("external-recovery-scheduled".equals(json.path("eventName").asText())) {
                recoveryEvent = true;
                Assertions.assertEquals(1_000_600L, json.path("epochMillis").asLong());
            }
        }
        Assertions.assertTrue(impairmentEvent);
        Assertions.assertTrue(blackholeEvent);
        Assertions.assertTrue(recoveryEvent);

        JsonNode summary = JSON.readTree(Files.readString(directory.resolve("summary.json"), StandardCharsets.UTF_8));
        Assertions.assertEquals("timeline.jsonl", summary.path("timelineArtifact").asText());
        Assertions.assertEquals(200L, summary.path("timelineSampleIntervalMillis").asLong());
        Assertions.assertEquals("longitudinal-shared-session-windows",
                summary.path("measurementWindowSemantics").asText());
        Assertions.assertTrue(summary.path("timelineSummary").path("sampleCount").asInt() >= 3);
        Assertions.assertEquals(300L,
                summary.path("timelineSummary").path("maxSampledAllCurrentQueuedBytes").asLong());
        Assertions.assertEquals(100L,
                summary.path("timelineSummary").path("maxSampledHealthyCurrentQueuedBytes").asLong());
        Assertions.assertEquals(200L,
                summary.path("timelineSummary").path("maxSampledAffectedCurrentQueuedBytes").asLong());
        Assertions.assertEquals(1,
                summary.path("timelineSummary").path("finalAll").path("disconnectedPeers").asInt());
        Assertions.assertTrue(summary.path("timelineSummary").path("maxHeapUsedBytes").asLong() > 0L);
    }

    @Test
    public void testTimelineIsFlushedBeforeRunCompletes() throws Exception {
        Path output = Files.createTempDirectory("raknet-timeline-stream-test");
        BenchmarkConfig config = timelineConfig(output);
        BenchmarkRunResult result = new BenchmarkRunResult(config, EnvironmentInfo.capture());
        PeerStats peer = peer(0, false, 128L);
        result.openTimelineStream();
        try {
            BenchmarkTimelineRecorder recorder = new BenchmarkTimelineRecorder(
                    result,
                    "timeline-case",
                    1,
                    0,
                    () -> List.of(peer.timelineSnapshot(true, true)),
                    BenchmarkTimelineRecorder.Capabilities.SERVER,
                    false
            );
            recorder.captureAt(1_000_500L, result.timelineOriginNanos() + 500_000_000L);

            Path timeline = result.outputDirectory().toPath().resolve("timeline.jsonl");
            Assertions.assertTrue(Files.exists(timeline));
            List<String> rows = Files.readAllLines(timeline, StandardCharsets.UTF_8);
            Assertions.assertEquals(1, rows.size());
            Assertions.assertEquals("sample", JSON.readTree(rows.get(0)).path("recordType").asText());
            Assertions.assertTrue(result.timelineRecords().isEmpty(),
                    "streamed production timelines must not also accumulate in heap");
            Assertions.assertEquals(1, result.timelineSummary().sampleCount());
        } finally {
            result.closeTimelineStream();
        }
    }

    @Test
    public void testTimelineCadenceValidation() {
        Assertions.assertEquals(100L, BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth", "--timeline-sample-interval", "100ms"
        }).timelineSampleIntervalMillis());
        Assertions.assertEquals(250L, BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth", "--timeline-sample-interval", "250ms"
        }).timelineSampleIntervalMillis());
        Assertions.assertThrows(IllegalArgumentException.class, () -> BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth", "--timeline-sample-interval", "99ms"
        }));
        Assertions.assertThrows(IllegalArgumentException.class, () -> BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth", "--timeline-sample-interval", "251ms"
        }));
    }

    @Test
    public void testResourceSafetyAbortIsStructuredFlushedAndNonPassing() throws Exception {
        Path output = Files.createTempDirectory("raknet-resource-safety-test");
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "server-worker",
                "--role", "server",
                "--clients", "1",
                "--resource-safety-max-aggregate-queued-bytes", "100",
                "--resource-safety-max-direct-memory-used-bytes", "9223372036854775807",
                "--out", output.toString(),
                "--run-id", "resource-safety-unit"
        });
        BenchmarkRunResult result = new BenchmarkRunResult(config, EnvironmentInfo.capture());
        PeerStats peer = peer(0, false, 200L);
        result.openTimelineStream();
        try {
            BenchmarkTimelineRecorder recorder = new BenchmarkTimelineRecorder(
                    result,
                    "resource-safety-case",
                    1,
                    0,
                    () -> List.of(peer.timelineSnapshot(true, true)),
                    BenchmarkTimelineRecorder.Capabilities.SERVER,
                    false
            );

            recorder.captureAt(1_000_500L, result.timelineOriginNanos() + 500_000_000L);

            BenchmarkResourceSafetyException aborted = Assertions.assertThrows(
                    BenchmarkResourceSafetyException.class, recorder::throwIfResourceSafetyAborted);
            Assertions.assertSame(result, aborted.result());
            Path timeline = result.outputDirectory().toPath().resolve("timeline.jsonl");
            List<String> rows = Files.readAllLines(timeline, StandardCharsets.UTF_8);
            Assertions.assertEquals(2, rows.size());
            JsonNode event = JSON.readTree(rows.get(1));
            Assertions.assertEquals("resource-safety-abort", event.path("eventName").asText());
            Assertions.assertEquals("benchmark-resource-watchdog", event.path("eventSource").asText());
            Assertions.assertEquals(200L,
                    event.path("resourceSafetyAbort").path("observedAggregateQueuedBytes").asLong());
            Assertions.assertEquals("aggregate-queued-bytes-exceeded",
                    event.path("resourceSafetyAbort").path("reasons").get(0).asText());
        } finally {
            result.closeTimelineStream();
        }

        Path directory = new BenchmarkResultWriter().write(result).toPath();
        JsonNode summary = JSON.readTree(Files.readString(directory.resolve("summary.json")));
        Assertions.assertEquals("aborted", summary.path("resourceSafetyStatus").asText());
        Assertions.assertEquals(100L, summary.path("resourceSafetyMaxAggregateQueuedBytes").asLong());
        Assertions.assertEquals(200L,
                summary.path("resourceSafetyAbort").path("observedAggregateQueuedBytes").asLong());
        Assertions.assertTrue(summary.path("iterations").isEmpty());
    }

    @Test
    public void testFinalCloseSampleResourceAbortCannotReturnSuccessfulResult() {
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "server-worker",
                "--role", "server",
                "--clients", "1",
                "--resource-safety-max-aggregate-queued-bytes", "100",
                "--resource-safety-max-direct-memory-used-bytes", "9223372036854775807"
        });
        BenchmarkRunResult result = new BenchmarkRunResult(config, EnvironmentInfo.capture());
        PeerStats safe = peer(0, false, 0L);
        PeerStats unsafe = peer(0, false, 200L);
        AtomicInteger captures = new AtomicInteger();
        BenchmarkTimelineRecorder recorder = new BenchmarkTimelineRecorder(
                result,
                "final-sample-safety-case",
                1,
                0,
                () -> List.of((captures.getAndIncrement() == 0 ? safe : unsafe)
                        .timelineSnapshot(true, true)),
                BenchmarkTimelineRecorder.Capabilities.SERVER,
                false
        );

        recorder.start();
        Assertions.assertNull(result.resourceSafetyAbort());
        recorder.close();

        BenchmarkResourceSafetyException aborted = Assertions.assertThrows(
                BenchmarkResourceSafetyException.class,
                () -> RakNetBenchmarkRunner.requireSuccessfulResult(result));
        Assertions.assertSame(result, aborted.result());
        Assertions.assertEquals(200L, result.resourceSafetyAbort().observedAggregateQueuedBytes());
    }

    @Test
    public void testHotPathResourceSafetyReadDoesNotTakeTimelineWriterMonitor() throws Exception {
        Assertions.assertFalse(Modifier.isSynchronized(BenchmarkRunResult.class
                .getDeclaredMethod("throwIfResourceSafetyAborted").getModifiers()));
        Assertions.assertFalse(Modifier.isSynchronized(BenchmarkRunResult.class
                .getDeclaredMethod("resourceSafetyAbort").getModifiers()));
        Assertions.assertFalse(Modifier.isSynchronized(BenchmarkRunResult.class
                .getDeclaredMethod("recordResourceSafetyAbort", BenchmarkTimeline.ResourceSafetyAbort.class)
                .getModifiers()));
    }

    @Test
    public void testResourceSafetyDefaultsAndPositiveValidation() {
        BenchmarkConfig defaults = BenchmarkConfig.parse(new String[]{"baseline-bandwidth"});
        Assertions.assertEquals(402_653_184L, defaults.resourceSafetyMaxAggregateQueuedBytes());
        Assertions.assertEquals(805_306_368L, defaults.resourceSafetyMaxDirectMemoryUsedBytes());
        Assertions.assertThrows(IllegalArgumentException.class, () -> BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth", "--resource-safety-max-aggregate-queued-bytes", "0"
        }));
        Assertions.assertThrows(IllegalArgumentException.class, () -> BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth", "--resource-safety-max-direct-memory-used-bytes", "-1"
        }));
    }

    @Test
    public void testEventLoopDiagnosticsBoundOutstandingProbeAndMeasureLag() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            group.next().execute(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));
            BenchmarkEventLoopDiagnostics diagnostics = new BenchmarkEventLoopDiagnostics(group);

            BenchmarkTimeline.EventLoopMetrics first = diagnostics.capture();
            BenchmarkTimeline.EventLoopMetrics blocked = diagnostics.capture();

            Assertions.assertEquals("available", first.status());
            Assertions.assertEquals(1, first.eventLoopCount());
            Assertions.assertEquals(1, blocked.outstandingSchedulingProbes());
            Assertions.assertTrue(blocked.totalPendingTasks() >= 1L);
            release.countDown();
            group.next().submit(() -> { }).syncUninterruptibly();

            BenchmarkTimeline.EventLoopMetrics completed = diagnostics.capture();
            Assertions.assertEquals(1L, completed.completedSchedulingProbes());
            Assertions.assertEquals(0, completed.outstandingSchedulingProbes());
            Assertions.assertNotNull(completed.latestMaxSchedulingLagMillis());
            Assertions.assertTrue(completed.latestMaxSchedulingLagMillis() >= 0.0D);
        } finally {
            release.countDown();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    public void testServerWorkerDoesNotClaimClientSideBlackholeCounters() {
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "server-worker",
                "--role", "server",
                "--clients", "1",
                "--disappearing-clients", "1",
                "--duration", "2s",
                "--disappear-after", "1s"
        });
        BenchmarkRunResult result = new BenchmarkRunResult(config, EnvironmentInfo.capture());
        PeerStats peer = new PeerStats(0, true);
        BenchmarkTimelineRecorder recorder = new BenchmarkTimelineRecorder(
                result,
                "server-worker-blackhole",
                1,
                1,
                () -> List.of(peer.timelineSnapshot(true, true)),
                BenchmarkTimelineRecorder.Capabilities.SERVER,
                false
        );

        BenchmarkTimeline.Sample sample = recorder.captureAt(System.currentTimeMillis(), System.nanoTime());

        Assertions.assertNull(sample.all().blackholedDatagramsIn());
        Assertions.assertNull(sample.all().blackholedDatagramsOut());
        Assertions.assertEquals("unavailable-on-server-worker",
                sample.metricAvailability().benchmarkManagedBlackholeCounters());
        Assertions.assertTrue(sample.metricAvailability().unavailableFields()
                .contains("cohort.blackholedDatagramsOut"));
    }

    private static BenchmarkConfig timelineConfig(Path output) {
        List<String> arguments = new java.util.ArrayList<>(List.of(
                "baseline-bandwidth",
                "--role", "server",
                "--clients", "3",
                "--impaired-clients", "2",
                "--start-at-epoch-ms", "1000000",
                "--external-impairment-at-epoch-ms", "1000100",
                "--external-blackhole-at-epoch-ms", "1000400",
                "--external-recovery-at-epoch-ms", "1000600",
                "--timeline-sample-interval", "200ms",
                "--run-id", "timeline-unit"
        ));
        if (output != null) {
            arguments.add("--out");
            arguments.add(output.toString());
        }
        return BenchmarkConfig.parse(arguments.toArray(String[]::new));
    }

    private static PeerStats peer(int id, boolean impaired, long queuedBytes) {
        PeerStats peer = new PeerStats(id, impaired);
        peer.addBulkSent(512, 4);
        peer.addServerBytesOut(640);
        peer.addServerDatagramsOut(2);
        peer.addNackIn(1);
        peer.addNackOut(2);
        peer.queuedBytes((int) queuedBytes);
        peer.datagramSent(RakDatagramSendType.ORIGINAL, 300, 0, 300);
        peer.datagramSent(RakDatagramSendType.NACK_RETRANSMISSION, 300, 1, 300);
        peer.datagramSent(RakDatagramSendType.TIMEOUT_RETRANSMISSION, 300, 2, 300);
        peer.recoveryState(1_000L, 300, 1200.0D, 2400.0D, 50.0D, 5.0D,
                250L, 1, 1_000L, 800L);
        return peer;
    }
}
