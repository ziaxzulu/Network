/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 */

package org.cloudburstmc.netty.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

public class ResilienceResultAnalyzerTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXTURE_SOURCE_REVISION = "0123456789ab";
    private static final String FIXTURE_BENCHMARK_JAR_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static Path root;
    private static Path fixture;
    private static Path analyzer;

    @BeforeAll
    public static void locateTooling() throws Exception {
        Assumptions.assumeTrue(commandAvailable("python3"), "python3 is required for resilience analysis tests");
        root = repoRoot();
        fixture = root.resolve("benchmark/src/test/resources/resilience-analysis/baseline");
        analyzer = root.resolve("benchmark/scripts/analyze-resilience-results.py");
    }

    @Test
    public void extractsEventAlignedMetricsAndAcceptsRawTimelineInput() throws Exception {
        Path denseFixture = Files.createTempDirectory("raknet-resilience-dense-fixture");
        copyDenseFixture(fixture, denseFixture);
        Path output = Files.createTempDirectory("raknet-resilience-single");
        Path timeline = denseFixture.resolve("cases/01-blackhole/server/netns-blackhole-10c/timeline.jsonl");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", timeline.toString(), "--out", output.toString());

        Assertions.assertEquals(0, result.exitCode, result.output);
        JsonNode report = readReport(output);
        Assertions.assertEquals("pass", report.path("status").asText());
        Assertions.assertEquals(1, report.path("cases").size());
        JsonNode caseResult = report.path("cases").get(0);
        Assertions.assertEquals("available",
                caseResult.path("dataAvailability").path("qdiscTimeseries").path("status").asText());
        Assertions.assertEquals("unavailable-on-server-worker",
                caseResult.path("dataAvailability").path("timeline").path("serverUsefulDelivery").asText());
        Assertions.assertEquals("available",
                caseResult.path("dataAvailability").path("timeline").path("sharedEventLoops").asText());
        Assertions.assertEquals(1.25D,
                caseResult.path("runtimeMaxima").path("sharedEventLoopSchedulingLagMillis").asDouble());
        JsonNode blackhole = event(caseResult, "external-blackhole");
        Assertions.assertEquals(110,
                blackhole.path("pressure").path("nackRetransmittedDatagramsDelta").asInt());
        Assertions.assertEquals(60,
                blackhole.path("pressure").path("timeoutRetransmittedDatagramsDelta").asInt());
        Assertions.assertEquals(5_400,
                blackhole.path("pressure").path("peakCurrentQueuedBytesAbovePreEvent").asInt());
        Assertions.assertEquals(200,
                blackhole.path("pressure").path("currentQueuedBytesAtTPlus10AbovePreEvent").asInt());
        JsonNode recovery = event(caseResult, "external-recovery");
        Assertions.assertEquals(1_180,
                recovery.path("timing").path("firstAcknowledgementProgressMillis").asInt());
        Assertions.assertEquals(9_980,
                recovery.path("timing").path("reclamation")
                        .path("queueAndInFlightReclaimedMillis").asInt());
        Assertions.assertTrue(Files.readString(output.resolve("resilience-analysis.md"), StandardCharsets.UTF_8)
                .contains("unavailable-on-server-worker"));
        Assertions.assertEquals(16_777_216L,
                findGate(report, "affected-cohort-queue-bytes").path("threshold").asLong());
        Assertions.assertEquals(8_388_608L,
                findGate(report, "healthy-collateral-queue-bytes").path("threshold").asLong());
        Assertions.assertEquals("pass", findGate(report, "resource-safety-direct-memory-bytes")
                .path("status").asText());
    }

    @Test
    public void blackholeOverImpairedBaseUsesZeroLatencyBlackholeTargetAndRestoresBase() throws Exception {
        Path impaired = Files.createTempDirectory("raknet-resilience-impaired-blackhole");
        copyDenseFixture(fixture, impaired);
        makeImpairedBlackhole(impaired);
        Path output = Files.createTempDirectory("raknet-resilience-impaired-blackhole-report");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10), "python3", analyzer.toString(),
                "--root", impaired.toString(), "--out", output.toString());

        Assertions.assertEquals(0, result.exitCode, result.output);
        JsonNode caseResult = readReport(output).path("cases").get(0);
        Assertions.assertEquals("pass", caseResult.path("status").asText());
        Assertions.assertFalse(event(caseResult, "initial-netem").isMissingNode());
        Assertions.assertFalse(event(caseResult, "external-blackhole").isMissingNode());
        Assertions.assertFalse(event(caseResult, "external-recovery").isMissingNode());
    }

    @Test
    public void recoveryActionMustClearZeroBaseAndReplaceNonzeroBase() throws Exception {
        Path impairedClear = Files.createTempDirectory("raknet-resilience-impaired-clear-recovery");
        copyDenseFixture(fixture, impairedClear);
        makeImpairedBlackhole(impairedClear);
        forceRecoveryAction(impairedClear, true);
        Path impairedOutput = Files.createTempDirectory("raknet-resilience-impaired-clear-report");
        ProcessResult impairedResult = runProcess(root, Duration.ofSeconds(10), "python3", analyzer.toString(),
                "--root", impairedClear.toString(), "--out", impairedOutput.toString());
        Assertions.assertEquals(1, impairedResult.exitCode, impairedResult.output);
        Assertions.assertTrue(readReport(impairedOutput).path("cases").toString()
                .contains("must restore netem when recovering to a nonzero impairment"));

        Path zeroReplace = Files.createTempDirectory("raknet-resilience-zero-replace-recovery");
        copyDenseFixture(fixture, zeroReplace);
        forceRecoveryAction(zeroReplace, false);
        Path zeroOutput = Files.createTempDirectory("raknet-resilience-zero-replace-report");
        ProcessResult zeroResult = runProcess(root, Duration.ofSeconds(10), "python3", analyzer.toString(),
                "--root", zeroReplace.toString(), "--out", zeroOutput.toString());
        Assertions.assertEquals(1, zeroResult.exitCode, zeroResult.output);
        Assertions.assertTrue(readReport(zeroOutput).path("cases").toString()
                .contains("must clear qdisc when recovering to zero impairment"));
    }

    @Test
    public void comparativeModeRequiresNinetyPercentForEveryPressureComponent() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-baseline");
        buildFullCampaignSet(baseline);
        Path candidate = Files.createTempDirectory("raknet-resilience-candidate");
        buildFullCampaignSet(candidate, "bounded");
        scaleCandidatePressure(candidate, 10);
        Path passingOutput = Files.createTempDirectory("raknet-resilience-comparison-pass");

        ProcessResult passing = compare(baseline, candidate, passingOutput);

        Assertions.assertEquals(0, passing.exitCode, passing.output);
        JsonNode passingReport = readReport(passingOutput);
        Assertions.assertEquals("pass", passingReport.path("comparison").path("status").asText());
        Assertions.assertEquals(2, passingReport.path("comparison").path("requiredCompleteCampaigns")
                .path("baseline").size());
        Assertions.assertEquals(2, passingReport.path("comparison").path("requiredCompleteCampaigns")
                .path("candidate").size());
        List<JsonNode> comparable = new ArrayList<>();
        passingReport.path("comparison").path("components").forEach(component -> {
            if (component.path("reductionPercent").isNumber()) {
                comparable.add(component);
            }
        });
        Assertions.assertFalse(comparable.isEmpty());
        Assertions.assertTrue(comparable.stream().allMatch(component ->
                component.path("reductionPercent").asDouble() >= 90.0D));
        Assertions.assertTrue(comparable.stream().map(component -> component.path("name").asText())
                .anyMatch(name -> name.contains("retransmittedDatagramsDelta")));
        Assertions.assertFalse(comparable.stream().map(component -> component.path("name").asText())
                .anyMatch(name -> name.contains("nackRetransmittedDatagramsDelta")));
        Assertions.assertTrue(passingReport.path("comparison").path("events").get(0)
                .path("sendTypeDiagnostics").path("status").asText()
                .equals("informational-send-type-classification"));
        Assertions.assertTrue(comparable.stream().map(component -> component.path("name").asText())
                .anyMatch(name -> name.startsWith("initial-netem.")));
        Assertions.assertTrue(comparable.stream().map(component -> component.path("name").asText())
                .anyMatch(name -> name.startsWith("external-blackhole.")));
        Assertions.assertFalse(passingReport.path("comparison").path("components")
                .findValuesAsText("name").stream()
                .anyMatch(name -> name.contains("unacknowledgedTransportBytesAtTPlus10")));
        Assertions.assertTrue(passingReport.path("gates").findValuesAsText("id")
                .contains("affected-bytes-in-flight-at-t-plus-10"));
        Assertions.assertTrue(passingReport.path("comparison").path("queueComponents")
                .findValuesAsText("name").contains("maximumCurrentBytes"));

        Path weakCandidate = Files.createTempDirectory("raknet-resilience-weak-candidate");
        buildFullCampaignSet(weakCandidate, "bounded");
        scaleCandidatePressure(weakCandidate, 2);
        Path failingOutput = Files.createTempDirectory("raknet-resilience-comparison-fail");
        ProcessResult failing = compare(baseline, weakCandidate, failingOutput);

        Assertions.assertEquals(1, failing.exitCode, failing.output);
        JsonNode failingReport = readReport(failingOutput);
        Assertions.assertEquals("fail", failingReport.path("comparison").path("status").asText());
        Assertions.assertTrue(failingReport.path("comparison").path("components").findValuesAsText("status")
                .contains("fail"));
    }

    @Test
    public void sendTypeReclassificationDoesNotOverrideTotalRetryReduction() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-subtype-baseline");
        buildFullCampaignSet(baseline);
        Path candidate = Files.createTempDirectory("raknet-resilience-subtype-candidate");
        buildFullCampaignSet(candidate, "bounded");
        scaleCandidatePressure(candidate, 20);
        forceTimeoutSubtype(baseline, false);
        forceTimeoutSubtype(candidate, true);
        Path output = Files.createTempDirectory("raknet-resilience-subtype-report");

        ProcessResult result = compare(baseline, candidate, output);

        Assertions.assertEquals(0, result.exitCode, result.output);
        JsonNode report = readReport(output);
        Assertions.assertEquals("pass", report.path("comparison").path("gateStatus")
                .path("pressureReduction").asText());
        JsonNode diagnostics = report.path("comparison").path("events").get(0)
                .path("sendTypeDiagnostics");
        Assertions.assertEquals(0,
                diagnostics.path("baseline").path("timeoutRetransmittedDatagramsDelta").asInt());
        Assertions.assertTrue(
                diagnostics.path("candidate").path("timeoutRetransmittedDatagramsDelta").asInt() > 0);
        Assertions.assertEquals("informational-send-type-classification",
                diagnostics.path("status").asText());
    }

    @Test
    public void missingOrInvalidTimelineFailsButStillWritesAvailabilityReport() throws Exception {
        Path broken = Files.createTempDirectory("raknet-resilience-broken");
        copyDenseFixture(fixture, broken);
        Files.delete(broken.resolve("cases/01-blackhole/server/netns-blackhole-10c/timeline.jsonl"));
        Path output = Files.createTempDirectory("raknet-resilience-broken-report");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", broken.toString(), "--out", output.toString());

        Assertions.assertEquals(1, result.exitCode, result.output);
        JsonNode report = readReport(output);
        Assertions.assertEquals("fail", report.path("status").asText());
        Assertions.assertEquals("fail", report.path("cases").get(0).path("status").asText());
        Assertions.assertEquals("unavailable", report.path("cases").get(0)
                .path("dataAvailability").path("analysis").path("status").asText());
        Assertions.assertTrue(report.path("cases").get(0).path("issues").get(0).asText()
                .contains("expected exactly one server timeline.jsonl"));
    }

    @Test
    public void resourceSafetyAbortFailsButPreservesPartialDiagnostics() throws Exception {
        Path aborted = Files.createTempDirectory("raknet-resilience-safety-abort");
        copyDenseFixture(fixture, aborted);
        Path timeline = aborted.resolve("cases/01-blackhole/server/netns-blackhole-10c/timeline.jsonl");
        List<String> rows = Files.readAllLines(timeline, StandardCharsets.UTF_8);
        ObjectNode event = (ObjectNode) JSON.readTree(rows.get(0));
        event.put("recordType", "event");
        event.put("eventName", "resource-safety-abort");
        event.put("eventSource", "benchmark-resource-watchdog");
        ObjectNode detail = event.putObject("resourceSafetyAbort");
        detail.putArray("reasons").add("aggregate-queued-bytes-exceeded");
        detail.put("maxAggregateQueuedBytes", 402_653_184L);
        detail.put("maxDirectMemoryUsedBytes", 805_306_368L);
        detail.put("observedAggregateQueuedBytes", 402_653_185L);
        detail.put("observedHealthyQueuedBytes", 400_000_000L);
        detail.put("observedAffectedQueuedBytes", 2_653_185L);
        detail.put("observedDirectMemoryUsedBytes", 700_000_000L);
        detail.put("observedDirectMemoryMetric", "runtime.directBufferPoolMemoryUsedBytes");
        detail.put("observedAtEpochMillis", event.path("epochMillis").asLong());
        detail.put("observedAtMonotonicElapsedMillis", event.path("monotonicElapsedMillis").asLong());
        rows.set(0, JSON.writeValueAsString(event));
        Files.writeString(timeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        Path output = Files.createTempDirectory("raknet-resilience-safety-abort-report");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", aborted.toString(), "--out", output.toString());

        Assertions.assertEquals(1, result.exitCode, result.output);
        JsonNode caseResult = readReport(output).path("cases").get(0);
        Assertions.assertEquals("fail", caseResult.path("status").asText());
        Assertions.assertEquals(402_653_185L,
                caseResult.path("resourceSafetyAbort").path("observedAggregateQueuedBytes").asLong());
        Assertions.assertTrue(caseResult.path("issues").toString()
                .contains("terminated by the resource safety watchdog"));
    }

    @Test
    public void resourceSafetyThresholdMismatchFailsClosed() throws Exception {
        Path mismatched = Files.createTempDirectory("raknet-resilience-safety-mismatch");
        copyDenseFixture(fixture, mismatched);
        Path manifestPath = mismatched.resolve("cases/01-blackhole/manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(manifestPath));
        manifest.put("resourceSafetyMaxAggregateQueuedBytes", 402_653_185L);
        Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        Path output = Files.createTempDirectory("raknet-resilience-safety-mismatch-report");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", mismatched.toString(), "--out", output.toString());

        Assertions.assertEquals(1, result.exitCode, result.output);
        Assertions.assertTrue(readReport(output).path("cases").toString()
                .contains("aggregate queue safety threshold disagrees with manifest"));
    }

    @Test
    public void permanentBlackholeRequiresObservedPeerReclamationWithinThirtySeconds() throws Exception {
        Path reclaimed = Files.createTempDirectory("raknet-resilience-reclaimed");
        copyDenseFixture(fixture, reclaimed);
        makePermanentBlackhole(reclaimed, true);
        Path reclaimedOutput = Files.createTempDirectory("raknet-resilience-reclaimed-report");

        ProcessResult passing = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", reclaimed.toString(),
                "--out", reclaimedOutput.toString());

        Assertions.assertEquals(0, passing.exitCode, passing.output);
        JsonNode passingReport = readReport(reclaimedOutput);
        JsonNode peerGate = findGate(passingReport, "irrecoverable-peer-reclamation-millis");
        Assertions.assertEquals("pass", peerGate.path("status").asText());
        Assertions.assertEquals(10_080, peerGate.path("actual").asInt());

        Path retained = Files.createTempDirectory("raknet-resilience-retained");
        copyDenseFixture(fixture, retained);
        makePermanentBlackhole(retained, false);
        Path retainedOutput = Files.createTempDirectory("raknet-resilience-retained-report");
        ProcessResult failing = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", retained.toString(),
                "--out", retainedOutput.toString());

        Assertions.assertEquals(1, failing.exitCode, failing.output);
        Assertions.assertEquals("fail", findGate(readReport(retainedOutput),
                "irrecoverable-peer-reclamation-millis").path("status").asText());

        Path late = Files.createTempDirectory("raknet-resilience-late-reclaim");
        copyDenseFixture(fixture, late);
        makePermanentBlackhole(late, 133_000L);
        Path lateOutput = Files.createTempDirectory("raknet-resilience-late-reclaim-report");
        ProcessResult lateResult = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", late.toString(), "--out", lateOutput.toString());
        Assertions.assertEquals(1, lateResult.exitCode, lateResult.output);
        JsonNode lateGate = findGate(readReport(lateOutput), "irrecoverable-peer-reclamation-millis");
        Assertions.assertEquals("fail", lateGate.path("status").asText());
        Assertions.assertTrue(lateGate.path("actual").asLong() > 30_000L);
    }

    @Test
    public void oneWayPermanentBlackholeIsNotDisappearanceEvidence() throws Exception {
        Path oneWay = Files.createTempDirectory("raknet-resilience-one-way-blackhole");
        copyDenseFixture(fixture, oneWay);
        makeOneWayPermanentBlackhole(oneWay);
        Path output = Files.createTempDirectory("raknet-resilience-one-way-blackhole-report");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", oneWay.toString(), "--out", output.toString());

        Assertions.assertEquals(0, result.exitCode, result.output);
        JsonNode report = readReport(output);
        JsonNode gate = findGate(report, "irrecoverable-peer-reclamation-millis");
        Assertions.assertEquals("not-applicable", gate.path("status").asText());
        Assertions.assertTrue(gate.path("reason").asText().contains("bidirectional"));
        JsonNode blackhole = event(report.path("cases").get(0), "external-blackhole");
        Assertions.assertTrue(blackhole.path("timing").path("reclamation").isNull());
    }

    @Test
    public void qdiscCoverageIsRequiredPerTargetAndNullableRuntimeSamplesAreSafe() throws Exception {
        Path nullable = Files.createTempDirectory("raknet-resilience-null-runtime");
        copyDenseFixture(fixture, nullable);
        Path nullableTimeline = nullable.resolve(
                "cases/01-blackhole/server/netns-blackhole-10c/timeline.jsonl");
        List<String> rows = Files.readAllLines(nullableTimeline, StandardCharsets.UTF_8);
        ObjectNode first = (ObjectNode) JSON.readTree(rows.get(0));
        ObjectNode runtime = (ObjectNode) first.path("runtime");
        runtime.putNull("residentSetSizeBytes");
        runtime.putNull("processCpuLoad");
        runtime.putNull("directBufferPoolMemoryUsedBytes");
        rows.set(0, JSON.writeValueAsString(first));
        Files.writeString(nullableTimeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        Path nullableOutput = Files.createTempDirectory("raknet-resilience-null-runtime-report");
        ProcessResult nullableResult = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", nullable.toString(),
                "--out", nullableOutput.toString());
        Assertions.assertEquals(0, nullableResult.exitCode, nullableResult.output);

        Path missingCoverage = Files.createTempDirectory("raknet-resilience-qdisc-gap");
        copyDenseFixture(fixture, missingCoverage);
        Path qdisc = missingCoverage.resolve("cases/01-blackhole/netem/qdisc-timeseries.jsonl");
        List<String> qdiscRows = new ArrayList<>();
        for (String line : Files.readAllLines(qdisc, StandardCharsets.UTF_8)) {
            JsonNode row = JSON.readTree(line);
            if (!"affected".equals(row.path("namespace").asText())
                    || row.path("epochMillis").asLong() < 105_000L) {
                qdiscRows.add(line);
            }
        }
        Files.writeString(qdisc, String.join("\n", qdiscRows) + "\n", StandardCharsets.UTF_8);
        Path missingOutput = Files.createTempDirectory("raknet-resilience-qdisc-gap-report");
        ProcessResult missing = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", missingCoverage.toString(),
                "--out", missingOutput.toString());

        Assertions.assertEquals(1, missing.exitCode, missing.output);
        Assertions.assertTrue(readReport(missingOutput).path("cases").get(0).path("issues").get(0)
                .asText().contains("does not span external-recovery for affected/rcvi"));
    }

    @Test
    public void qdiscStateMustBeNonemptyCorrectlyShapedAndUniquelyTargeted() throws Exception {
        for (String defect : List.of(
                "empty", "wrong-loss", "duplicate-target", "duplicate-command", "wrong-device")) {
            Path broken = Files.createTempDirectory("raknet-resilience-qdisc-" + defect);
            copyDenseFixture(fixture, broken);
            Path caseRoot = broken.resolve("cases/01-blackhole");
            if (defect.equals("duplicate-target")) {
                Path evidence = caseRoot.resolve("netem/external-blackhole-affected-rcvi.txt");
                String text = Files.readString(evidence, StandardCharsets.UTF_8)
                        .replace("namespace=affected", "namespace=server")
                        .replace("rcvi", "srvi");
                Files.writeString(evidence, text, StandardCharsets.UTF_8);
            } else if (defect.equals("duplicate-command") || defect.equals("wrong-device")) {
                Path evidence = caseRoot.resolve("netem/external-blackhole-server-srvi.txt");
                String command = "+ tc qdisc replace dev srvi root netem loss 100% limit 10000";
                String replacement = defect.equals("duplicate-command")
                        ? command + "\n" + command
                        : "+ tc qdisc replace dev unrelated0 root netem loss 100% limit 10000";
                Files.writeString(evidence, Files.readString(evidence, StandardCharsets.UTF_8)
                        .replace(command, replacement), StandardCharsets.UTF_8);
            } else {
                Path qdisc = caseRoot.resolve("netem/qdisc-timeseries.jsonl");
                List<String> rewritten = new ArrayList<>();
                for (String line : Files.readAllLines(qdisc, StandardCharsets.UTF_8)) {
                    ObjectNode row = (ObjectNode) JSON.readTree(line);
                    if (row.path("epochMillis").asLong() == 103_000L
                            && row.path("namespace").asText().equals("server")) {
                        if (defect.equals("empty")) {
                            row.putArray("qdisc");
                        } else {
                            ((ObjectNode) row.path("qdisc").get(0).path("options")
                                    .path("loss-random")).put("loss", 0.5D);
                        }
                    }
                    rewritten.add(JSON.writeValueAsString(row));
                }
                Files.writeString(qdisc, String.join("\n", rewritten) + "\n", StandardCharsets.UTF_8);
            }
            Path output = Files.createTempDirectory("raknet-resilience-qdisc-report-" + defect);
            ProcessResult result = runProcess(root, Duration.ofSeconds(10), "python3", analyzer.toString(),
                    "--root", broken.toString(), "--out", output.toString());
            Assertions.assertEquals(1, result.exitCode, defect + ": " + result.output);
            Assertions.assertEquals("fail", readReport(output).path("status").asText(), defect);
        }
    }

    @Test
    public void sparseEventWindowsAndMissingWindowRuntimeMetricsFailClosed() throws Exception {
        Path sparse = Files.createTempDirectory("raknet-resilience-sparse");
        copyTree(fixture, sparse);
        addResourceSafetyPolicies(sparse);
        Path sparseOutput = Files.createTempDirectory("raknet-resilience-sparse-report");
        ProcessResult sparseResult = runProcess(root, Duration.ofSeconds(10), "python3", analyzer.toString(),
                "--root", sparse.toString(), "--out", sparseOutput.toString());
        Assertions.assertEquals(1, sparseResult.exitCode, sparseResult.output);
        Assertions.assertTrue(readReport(sparseOutput).path("cases").get(0).path("issues").get(0)
                .asText().contains("timeline sample gap"));

        Path noRuntime = Files.createTempDirectory("raknet-resilience-window-runtime");
        copyDenseFixture(fixture, noRuntime);
        Path timeline = noRuntime.resolve("cases/01-blackhole/server/netns-blackhole-10c/timeline.jsonl");
        List<String> rewritten = new ArrayList<>();
        for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
            ObjectNode row = (ObjectNode) JSON.readTree(line);
            if (row.path("epochMillis").asLong() >= 101_900L) {
                ObjectNode runtime = (ObjectNode) row.path("runtime");
                runtime.putNull("residentSetSizeBytes");
                runtime.putNull("processCpuLoad");
                runtime.putNull("processCpuTimeNanos");
                runtime.putNull("directBufferPoolMemoryUsedBytes");
                runtime.putNull("nettyPooledDirectMemoryUsedBytes");
            }
            rewritten.add(JSON.writeValueAsString(row));
        }
        Files.writeString(timeline, String.join("\n", rewritten) + "\n", StandardCharsets.UTF_8);
        Path runtimeOutput = Files.createTempDirectory("raknet-resilience-window-runtime-report");
        ProcessResult runtimeResult = runProcess(root, Duration.ofSeconds(10), "python3", analyzer.toString(),
                "--root", noRuntime.toString(), "--out", runtimeOutput.toString());
        Assertions.assertEquals(1, runtimeResult.exitCode, runtimeResult.output);
        Assertions.assertTrue(readReport(runtimeOutput).path("cases").get(0).path("issues").get(0)
                .asText().contains("event window external-blackhole lacks runtime metrics"));
    }

    @Test
    public void malformedManifestFieldFailsClosedWithBothReports() throws Exception {
        Path malformed = Files.createTempDirectory("raknet-resilience-malformed");
        copyDenseFixture(fixture, malformed);
        Path manifestPath = malformed.resolve("cases/01-blackhole/manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        manifest.remove("clients");
        Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        Path output = Files.createTempDirectory("raknet-resilience-malformed-report");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", malformed.toString(), "--out", output.toString());

        Assertions.assertEquals(1, result.exitCode, result.output);
        Assertions.assertEquals("fail", readReport(output).path("status").asText());
        Assertions.assertTrue(Files.isRegularFile(output.resolve("resilience-analysis.json")));
        Assertions.assertTrue(Files.isRegularFile(output.resolve("resilience-analysis.md")));
    }

    @Test
    public void recoveryModeMustAgreeBetweenManifestAndEveryTimelineRecord() throws Exception {
        Path mismatched = Files.createTempDirectory("raknet-resilience-recovery-mode-mismatch");
        copyDenseFixture(fixture, mismatched);
        Path timeline = mismatched.resolve(
                "cases/01-blackhole/server/netns-blackhole-10c/timeline.jsonl");
        List<String> rows = Files.readAllLines(timeline, StandardCharsets.UTF_8);
        ObjectNode first = (ObjectNode) JSON.readTree(rows.get(0));
        first.put("recoveryMode", "bounded");
        rows.set(0, JSON.writeValueAsString(first));
        Files.writeString(timeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        Path output = Files.createTempDirectory("raknet-resilience-recovery-mode-mismatch-report");

        ProcessResult result = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", mismatched.toString(),
                "--out", output.toString());

        Assertions.assertEquals(1, result.exitCode, result.output);
        Assertions.assertTrue(readReport(output).path("cases").toString()
                .contains("timeline recoveryMode does not match manifest"));

        Path missing = Files.createTempDirectory("raknet-resilience-recovery-mode-missing");
        copyDenseFixture(fixture, missing);
        Path manifestPath = missing.resolve("cases/01-blackhole/manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(
                manifestPath, StandardCharsets.UTF_8));
        manifest.remove("recoveryMode");
        Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        Path missingOutput = Files.createTempDirectory("raknet-resilience-recovery-mode-missing-report");
        ProcessResult missingResult = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", missing.toString(),
                "--out", missingOutput.toString());
        Assertions.assertEquals(1, missingResult.exitCode, missingResult.output);
        Assertions.assertTrue(readReport(missingOutput).path("cases").toString()
                .contains("manifest.recoveryMode must be one of"));

        Path receiverMismatch = Files.createTempDirectory("raknet-resilience-receiver-mode-mismatch");
        copyDenseFixture(fixture, receiverMismatch);
        Path receiverTimeline = receiverMismatch.resolve(
                "cases/01-blackhole/receiver-healthy/netns-blackhole-10c-healthy/timeline.jsonl");
        List<String> receiverRows = Files.readAllLines(receiverTimeline, StandardCharsets.UTF_8);
        ObjectNode receiverFirst = (ObjectNode) JSON.readTree(receiverRows.get(0));
        receiverFirst.put("recoveryMode", "bounded");
        receiverRows.set(0, JSON.writeValueAsString(receiverFirst));
        Files.writeString(receiverTimeline, String.join("\n", receiverRows) + "\n", StandardCharsets.UTF_8);
        Path receiverOutput = Files.createTempDirectory("raknet-resilience-receiver-mode-report");
        ProcessResult receiverResult = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", receiverMismatch.toString(),
                "--out", receiverOutput.toString());
        Assertions.assertEquals(1, receiverResult.exitCode, receiverResult.output);
        Assertions.assertTrue(readReport(receiverOutput).path("cases").toString()
                .contains("receiver timeline recoveryMode does not match manifest"));

        Path missingReceiver = Files.createTempDirectory("raknet-resilience-receiver-missing");
        copyDenseFixture(fixture, missingReceiver);
        Files.delete(missingReceiver.resolve(
                "cases/01-blackhole/receiver-affected/netns-blackhole-10c-affected/timeline.jsonl"));
        Path missingReceiverOutput = Files.createTempDirectory("raknet-resilience-receiver-missing-report");
        ProcessResult missingReceiverResult = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", missingReceiver.toString(),
                "--out", missingReceiverOutput.toString());
        Assertions.assertEquals(1, missingReceiverResult.exitCode, missingReceiverResult.output);
        Assertions.assertTrue(readReport(missingReceiverOutput).path("cases").toString()
                .contains("expected exactly one affected receiver timeline.jsonl"));
    }

    @Test
    public void comparisonRequiresLegacyBaselineAndBoundedCandidate() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-mode-baseline");
        Path wrongCandidate = Files.createTempDirectory("raknet-resilience-mode-candidate");
        buildFullCampaignSet(baseline);
        buildFullCampaignSet(wrongCandidate);
        scaleCandidatePressure(wrongCandidate, 10);
        Path output = Files.createTempDirectory("raknet-resilience-mode-report");

        ProcessResult result = compare(baseline, wrongCandidate, output);

        Assertions.assertEquals(1, result.exitCode, result.output);
        JsonNode report = readReport(output);
        Assertions.assertEquals("fail", findGate(report, "comparison-recovery-mode-provenance")
                .path("status").asText());
        Assertions.assertTrue(report.path("comparison").path("issues").toString()
                .contains("every baseline campaign/case to be legacy"));

        Path boundedCandidate = Files.createTempDirectory("raknet-resilience-mode-plan-candidate");
        buildFullCampaignSet(boundedCandidate, "bounded");
        scaleCandidatePressure(boundedCandidate, 10);
        Path planPath = boundedCandidate.resolve("campaign-1/campaign-plan.json");
        ObjectNode plan = (ObjectNode) JSON.readTree(Files.readString(planPath, StandardCharsets.UTF_8));
        ((ObjectNode) plan.path("parameters")).put("recoveryMode", "legacy");
        Files.writeString(planPath, JSON.writeValueAsString(plan) + "\n", StandardCharsets.UTF_8);
        Path mismatchOutput = Files.createTempDirectory("raknet-resilience-mode-plan-mismatch-report");

        ProcessResult mismatch = compare(baseline, boundedCandidate, mismatchOutput);

        Assertions.assertEquals(1, mismatch.exitCode, mismatch.output);
        Assertions.assertTrue(readReport(mismatchOutput).path("campaigns").toString()
                .contains("recovery mode disagrees"));
    }

    @Test
    public void comparisonSupportsExplicitBoundedToModelBasedPairWithModelTelemetry() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-bounded-baseline");
        Path candidate = Files.createTempDirectory("raknet-resilience-model-candidate");
        buildFullCampaignSet(baseline, "bounded");
        buildFullCampaignSet(candidate, "model_based");
        scaleCandidatePressure(candidate, 10);
        Path output = Files.createTempDirectory("raknet-resilience-model-comparison");

        ProcessResult result = compare(
                baseline, candidate, output, "bounded", "model_based");

        Assertions.assertEquals(0, result.exitCode, result.output);
        JsonNode report = readReport(output);
        Assertions.assertEquals("pass", report.path("comparison").path("status").asText());
        Assertions.assertEquals("bounded", report.path("comparison").path("recoveryModeProvenance")
                .path("baseline").path("expected").asText());
        Assertions.assertEquals("model_based", report.path("comparison").path("recoveryModeProvenance")
                .path("candidate").path("expected").asText());
        boolean modelEventObserved = false;
        for (JsonNode caseResult : report.path("cases")) {
            if (!"candidate".equals(caseResult.path("side").asText())) {
                continue;
            }
            Assertions.assertEquals("available", caseResult.path("dataAvailability")
                    .path("timeline").path("congestionModelState").asText());
            for (JsonNode event : caseResult.path("externalEvents")) {
                modelEventObserved |= event.path("affectedCongestionModel").isObject();
            }
        }
        Assertions.assertTrue(modelEventObserved);

        Path modelTimeline;
        try (var paths = Files.walk(candidate.resolve("campaign-1/cases/02-near-loss/server"))) {
            modelTimeline = paths.filter(path -> path.getFileName().toString().equals("timeline.jsonl"))
                    .findFirst().orElseThrow();
        }
        List<String> rows = Files.readAllLines(modelTimeline, StandardCharsets.UTF_8);
        ObjectNode first = (ObjectNode) JSON.readTree(rows.get(0));
        first.put("schemaVersion", 1);
        rows.set(0, JSON.writeValueAsString(first));
        Files.writeString(modelTimeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        Path invalidOutput = Files.createTempDirectory("raknet-resilience-model-schema-invalid");
        ProcessResult invalid = compare(
                baseline, candidate, invalidOutput, "bounded", "model_based");
        Assertions.assertEquals(1, invalid.exitCode, invalid.output);
        Assertions.assertTrue(readReport(invalidOutput).path("cases").toString()
                .contains("model_based timeline requires schema 2"));
    }

    @Test
    public void modelBasedComparisonRequiresAffectedModelSamplesInEveryEventWindow() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-model-window-baseline");
        Path candidate = Files.createTempDirectory("raknet-resilience-model-window-candidate");
        buildFullCampaignSet(baseline, "bounded");
        buildFullCampaignSet(candidate, "model_based");
        scaleCandidatePressure(candidate, 10);
        Path modelTimeline;
        try (var paths = Files.walk(candidate.resolve("campaign-1/cases/02-near-loss/server"))) {
            modelTimeline = paths.filter(path -> path.getFileName().toString().equals("timeline.jsonl"))
                    .findFirst().orElseThrow();
        }
        List<String> rows = Files.readAllLines(modelTimeline, StandardCharsets.UTF_8);
        List<String> originalRows = List.copyOf(rows);
        for (int index = 0; index < rows.size(); index++) {
            ObjectNode row = (ObjectNode) JSON.readTree(rows.get(index));
            if (!"sample".equals(row.path("recordType").asText())) {
                continue;
            }
            ObjectNode model = (ObjectNode) row.path("affected").path("congestionModel");
            model.put("observedPeers", 0);
            model.put("estimatedDeliveryRateObservedPeers", 0);
            model.put("pacingRateObservedPeers", 0);
            model.put("minimumRttObservedPeers", 0);
            model.put("recentLossObservedPeers", 0);
            model.put("packetRoundObservedPeers", 0);
            model.put("startupPeers", 0);
            model.put("persistentCongestionPeers", 0);
            for (String field : List.of("oldestObservedAtEpochMillis", "latestObservedAtEpochMillis",
                    "totalEstimatedDeliveryRateBytesPerSecond", "maxEstimatedDeliveryRateBytesPerSecond",
                    "totalPacingRateBytesPerSecond", "maxPacingRateBytesPerSecond",
                    "minimumRttMillis", "maximumMinimumRttMillis", "maximumRecentLossRate",
                    "minimumPacketRound", "maximumPacketRound")) {
                model.putNull(field);
            }
            rows.set(index, JSON.writeValueAsString(row));
        }
        Files.writeString(modelTimeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        Path output = Files.createTempDirectory("raknet-resilience-model-window-report");

        ProcessResult result = compare(baseline, candidate, output, "bounded", "model_based");

        Assertions.assertEquals(1, result.exitCode, result.output);
        JsonNode cases = readReport(output).path("cases");
        Assertions.assertTrue(cases.toString().contains("unavailable-no-affected-model-samples"));
        Assertions.assertTrue(cases.toString()
                .contains("model_based event window lacks usable affected-cohort model samples"));

        rows = new ArrayList<>(originalRows);
        for (int index = 0; index < rows.size(); index++) {
            ObjectNode row = (ObjectNode) JSON.readTree(rows.get(index));
            if (!"sample".equals(row.path("recordType").asText())) {
                continue;
            }
            ObjectNode model = (ObjectNode) row.path("affected").path("congestionModel");
            int observedPeers = model.path("observedPeers").asInt();
            if (observedPeers > 0) {
                model.put("estimatedDeliveryRateObservedPeers", observedPeers - 1);
                model.put("pacingRateObservedPeers", observedPeers - 1);
                model.put("minimumRttObservedPeers", observedPeers - 1);
            }
            rows.set(index, JSON.writeValueAsString(row));
        }
        Files.writeString(modelTimeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        Path partialOutput = Files.createTempDirectory("raknet-resilience-model-partial-window-report");

        ProcessResult partial = compare(baseline, candidate, partialOutput, "bounded", "model_based");

        Assertions.assertEquals(1, partial.exitCode, partial.output);
        JsonNode partialCases = readReport(partialOutput).path("cases");
        Assertions.assertTrue(partialCases.toString().contains("unavailable-no-affected-model-samples"));
        Assertions.assertTrue(partialCases.toString()
                .contains("model_based event window lacks usable affected-cohort model samples"));
        JsonNode partialEvent = null;
        for (JsonNode caseResult : partialCases) {
            if (!"candidate".equals(caseResult.path("side").asText())
                    || !caseResult.path("caseRoot").asText()
                    .contains("campaign-1/cases/02-near-loss")) {
                continue;
            }
            partialEvent = caseResult.path("externalEvents").get(0);
        }
        Assertions.assertNotNull(partialEvent);
        Assertions.assertTrue(partialEvent.path("affectedCongestionModel").isObject());
        Assertions.assertTrue(partialEvent.path("affectedCongestionModel").path("window").isNull());
        Assertions.assertTrue(partialEvent.path("affectedCongestionModel").path("coverage")
                .path("sampleCount").asInt() > 0);

        rows = new ArrayList<>(originalRows);
        for (int index = 0; index < rows.size(); index++) {
            ObjectNode row = (ObjectNode) JSON.readTree(rows.get(index));
            if (!"sample".equals(row.path("recordType").asText())) {
                continue;
            }
            ObjectNode model = (ObjectNode) row.path("affected").path("congestionModel");
            int cohortPeers = row.path("affected").path("observedPeers").asInt();
            if (cohortPeers > 0) {
                int modelPeers = cohortPeers - 1;
                model.put("observedPeers", modelPeers);
                model.put("estimatedDeliveryRateObservedPeers", modelPeers);
                model.put("pacingRateObservedPeers", modelPeers);
                model.put("minimumRttObservedPeers", modelPeers);
                model.put("recentLossObservedPeers", modelPeers);
                model.put("packetRoundObservedPeers", modelPeers);
                model.put("startupPeers", 0);
                model.put("persistentCongestionPeers", modelPeers);
                if (modelPeers == 0) {
                    for (String field : List.of("oldestObservedAtEpochMillis", "latestObservedAtEpochMillis",
                            "totalEstimatedDeliveryRateBytesPerSecond", "maxEstimatedDeliveryRateBytesPerSecond",
                            "totalPacingRateBytesPerSecond", "maxPacingRateBytesPerSecond",
                            "minimumRttMillis", "maximumMinimumRttMillis", "maximumRecentLossRate",
                            "minimumPacketRound", "maximumPacketRound")) {
                        model.putNull(field);
                    }
                }
            }
            rows.set(index, JSON.writeValueAsString(row));
        }
        Files.writeString(modelTimeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        Path incompleteCohortOutput = Files.createTempDirectory(
                "raknet-resilience-model-incomplete-cohort-window-report");

        ProcessResult incompleteCohort = compare(
                baseline, candidate, incompleteCohortOutput, "bounded", "model_based");

        Assertions.assertEquals(1, incompleteCohort.exitCode, incompleteCohort.output);
        Assertions.assertTrue(readReport(incompleteCohortOutput).path("cases").toString()
                .contains("unavailable-no-affected-model-samples"));

        rows = new ArrayList<>(originalRows);
        for (int index = 0; index < rows.size(); index++) {
            ObjectNode row = (ObjectNode) JSON.readTree(rows.get(index));
            if (!"sample".equals(row.path("recordType").asText())) {
                continue;
            }
            ObjectNode model = (ObjectNode) row.path("affected").path("congestionModel");
            if (model.path("observedPeers").asInt() > 0) {
                long staleEpoch = Math.min(101_000L, row.path("epochMillis").asLong());
                model.put("oldestObservedAtEpochMillis", staleEpoch);
                model.put("latestObservedAtEpochMillis", staleEpoch);
            }
            rows.set(index, JSON.writeValueAsString(row));
        }
        Files.writeString(modelTimeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        Path staleOutput = Files.createTempDirectory("raknet-resilience-model-stale-window-report");

        ProcessResult stale = compare(baseline, candidate, staleOutput, "bounded", "model_based");

        Assertions.assertEquals(1, stale.exitCode, stale.output);
        JsonNode staleCases = readReport(staleOutput).path("cases");
        Assertions.assertTrue(staleCases.toString().contains("unavailable-no-affected-model-samples"));
        Assertions.assertTrue(staleCases.toString().contains("samplesWithFreshCompleteKeyCoverage\":0"));
    }

    @Test
    public void comparisonRequiresOneExactStagedDistribution() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-dist-baseline");
        Path candidate = Files.createTempDirectory("raknet-resilience-dist-candidate");
        buildFullCampaignSet(baseline);
        buildFullCampaignSet(candidate, "bounded");
        scaleCandidatePressure(candidate, 10);
        replaceGoalDistribution(candidate,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        Path output = Files.createTempDirectory("raknet-resilience-dist-report");

        ProcessResult result = compare(baseline, candidate, output);

        Assertions.assertEquals(1, result.exitCode, result.output);
        JsonNode report = readReport(output);
        Assertions.assertEquals("fail", findGate(report, "comparison-distribution-provenance")
                .path("status").asText());
        Assertions.assertTrue(report.path("comparison").path("issues").toString()
                .contains("one identical source revision and staged jar distribution"));
        Assertions.assertEquals(2, report.path("comparison").path("distributionProvenance")
                .path("distributionSha256").size());

        Files.writeString(candidate.resolve("campaign-1/distribution.sha256"),
                FIXTURE_BENCHMARK_JAR_SHA256 + "  benchmark-test.jar\n", StandardCharsets.UTF_8);
        Path staleOutput = Files.createTempDirectory("raknet-resilience-dist-stale-report");
        ProcessResult stale = compare(baseline, candidate, staleOutput);
        Assertions.assertEquals(1, stale.exitCode, stale.output);
        Assertions.assertTrue(readReport(staleOutput).path("campaigns").toString()
                .contains("fingerprint disagrees with distribution.sha256"));
    }

    @Test
    public void comparisonRequiresRepeatedPermanentDisappearanceEvidence() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-disappearance-baseline");
        Path candidate = Files.createTempDirectory("raknet-resilience-disappearance-candidate");
        buildFullCampaignSet(baseline);
        buildFullCampaignSet(candidate, "bounded");
        scaleCandidatePressure(candidate, 10);
        deleteTree(candidate.resolve("disappearance-1"));
        deleteTree(candidate.resolve("disappearance-2"));
        Path output = Files.createTempDirectory("raknet-resilience-disappearance-report");

        ProcessResult result = compare(baseline, candidate, output);

        Assertions.assertEquals(1, result.exitCode, result.output);
        JsonNode report = readReport(output);
        Assertions.assertEquals("fail", findGate(report,
                "comparison-permanent-disappearance-evidence").path("status").asText());
        Assertions.assertTrue(report.path("comparison").path("issues").toString()
                .contains("fewer than 2 distinct valid permanent bidirectional disappearance goal executions"));
    }

    @Test
    public void disappearanceEvidenceRequiresDistinctPassingGoalExecutions() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-disappearance-goal-baseline");
        buildFullCampaignSet(baseline);

        Path copiedGoal = Files.createTempDirectory("raknet-resilience-disappearance-copied-goal");
        buildFullCampaignSet(copiedGoal, "bounded");
        scaleCandidatePressure(copiedGoal, 10);
        Path firstGoal = copiedGoal.resolve("disappearance-1/goal-manifest.json");
        Path secondGoal = copiedGoal.resolve("disappearance-2/goal-manifest.json");
        ObjectNode first = (ObjectNode) JSON.readTree(Files.readString(firstGoal, StandardCharsets.UTF_8));
        ObjectNode second = (ObjectNode) JSON.readTree(Files.readString(secondGoal, StandardCharsets.UTF_8));
        second.put("generatedAt", first.path("generatedAt").asText());
        Files.writeString(secondGoal, JSON.writeValueAsString(second) + "\n", StandardCharsets.UTF_8);
        Path copiedOutput = Files.createTempDirectory("raknet-resilience-disappearance-copied-report");

        ProcessResult copied = compare(baseline, copiedGoal, copiedOutput);

        Assertions.assertEquals(1, copied.exitCode, copied.output);
        JsonNode copiedEvidence = readReport(copiedOutput).path("comparison")
                .path("permanentDisappearanceEvidence");
        Assertions.assertEquals(1, copiedEvidence.path("candidateGoalExecutionIdentities").size());

        Path late = Files.createTempDirectory("raknet-resilience-disappearance-late");
        buildFullCampaignSet(late, "bounded");
        scaleCandidatePressure(late, 10);
        makePermanentBlackhole(late.resolve("disappearance-2"), 133_000L);
        Path lateOutput = Files.createTempDirectory("raknet-resilience-disappearance-late-report");

        ProcessResult lateResult = compare(baseline, late, lateOutput);

        Assertions.assertEquals(1, lateResult.exitCode, lateResult.output);
        Assertions.assertEquals(1, readReport(lateOutput).path("comparison")
                .path("permanentDisappearanceEvidence").path("candidateGoalExecutionIdentities").size());
    }

    @Test
    public void abortedLegacyDisappearanceIsDiagnosticButAbortedCandidateEvidenceFails() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-aborted-disappearance-baseline");
        Path candidate = Files.createTempDirectory("raknet-resilience-aborted-disappearance-candidate");
        buildFullCampaignSet(baseline);
        buildFullCampaignSet(candidate, "bounded");
        scaleCandidatePressure(candidate, 10);
        appendResourceSafetyAbort(baseline.resolve("disappearance-1/cases/01-blackhole"));
        Path baselineAbortOutput = Files.createTempDirectory("raknet-resilience-baseline-abort-report");

        ProcessResult baselineAbort = compare(baseline, candidate, baselineAbortOutput);

        Assertions.assertEquals(0, baselineAbort.exitCode, baselineAbort.output);
        JsonNode baselineAbortReport = readReport(baselineAbortOutput);
        Assertions.assertEquals("pass", baselineAbortReport.path("comparison").path("status").asText());
        Assertions.assertTrue(baselineAbortReport.path("cases").findValuesAsText("comparisonRole")
                .contains("diagnostic-only"));

        appendResourceSafetyAbort(candidate.resolve("disappearance-1/cases/01-blackhole"));
        Path candidateAbortOutput = Files.createTempDirectory("raknet-resilience-candidate-abort-report");

        ProcessResult candidateAbort = compare(baseline, candidate, candidateAbortOutput);

        Assertions.assertEquals(1, candidateAbort.exitCode, candidateAbort.output);
        JsonNode report = readReport(candidateAbortOutput);
        Assertions.assertEquals("fail", findGate(report,
                "comparison-permanent-disappearance-evidence").path("status").asText());
        Assertions.assertFalse(report.path("comparison").path("invalidCandidateCases").isEmpty());
        Assertions.assertTrue(report.path("cases").findValuesAsText("comparisonRole")
                .contains("candidate-absolute-evidence"));
    }

    @Test
    public void malformedCandidateSupplementalFailsWhileMalformedLegacyAncillaryIsDiagnostic() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-malformed-ancillary-baseline");
        Path candidate = Files.createTempDirectory("raknet-resilience-malformed-ancillary-candidate");
        buildFullCampaignSet(baseline);
        buildFullCampaignSet(candidate, "bounded");
        scaleCandidatePressure(candidate, 10);
        Files.writeString(baseline.resolve("disappearance-1/cases/01-blackhole/manifest.json"), "{\n",
                StandardCharsets.UTF_8);
        Path diagnosticOutput = Files.createTempDirectory("raknet-resilience-malformed-legacy-report");

        ProcessResult diagnostic = compare(baseline, candidate, diagnosticOutput);

        Assertions.assertEquals(0, diagnostic.exitCode, diagnostic.output);
        Assertions.assertEquals("pass", readReport(diagnosticOutput).path("comparison").path("status").asText());

        Files.writeString(candidate.resolve("disappearance-1/cases/01-blackhole/manifest.json"), "{\n",
                StandardCharsets.UTF_8);
        Path candidateOutput = Files.createTempDirectory("raknet-resilience-malformed-candidate-report");

        ProcessResult malformedCandidate = compare(baseline, candidate, candidateOutput);

        Assertions.assertEquals(1, malformedCandidate.exitCode, malformedCandidate.output);
        JsonNode report = readReport(candidateOutput);
        Assertions.assertFalse(report.path("comparison").path("invalidCandidateCases").isEmpty());
        Assertions.assertEquals("fail", findGate(report, "comparison-evidence-integrity")
                .path("status").asText());
    }

    @Test
    public void candidateSupplementalCampaignsMustShareModeAndDistribution() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-supplemental-baseline");
        buildFullCampaignSet(baseline);

        Path wrongMode = Files.createTempDirectory("raknet-resilience-supplemental-mode-candidate");
        buildFullCampaignSet(wrongMode, "bounded");
        scaleCandidatePressure(wrongMode, 10);
        Path modeSupplemental = wrongMode.resolve("disappearance-3");
        buildDisappearanceCampaign(wrongMode, 3, "bounded");
        makeAncillaryOneWay(modeSupplemental);
        setCampaignRecoveryMode(modeSupplemental, "legacy");
        Path modeOutput = Files.createTempDirectory("raknet-resilience-supplemental-mode-report");

        ProcessResult modeResult = compare(baseline, wrongMode, modeOutput);

        Assertions.assertEquals(1, modeResult.exitCode, modeResult.output);
        Assertions.assertEquals("fail", findGate(readReport(modeOutput),
                "comparison-recovery-mode-provenance").path("status").asText());

        Path wrongDistribution = Files.createTempDirectory("raknet-resilience-supplemental-dist-candidate");
        buildFullCampaignSet(wrongDistribution, "bounded");
        scaleCandidatePressure(wrongDistribution, 10);
        Path distributionSupplemental = wrongDistribution.resolve("disappearance-3");
        buildDisappearanceCampaign(wrongDistribution, 3, "bounded");
        makeAncillaryOneWay(distributionSupplemental);
        replaceGoalDistribution(distributionSupplemental,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        Path distributionOutput = Files.createTempDirectory("raknet-resilience-supplemental-dist-report");

        ProcessResult distributionResult = compare(baseline, wrongDistribution, distributionOutput);

        Assertions.assertEquals(1, distributionResult.exitCode, distributionResult.output);
        Assertions.assertEquals("fail", findGate(readReport(distributionOutput),
                "comparison-distribution-provenance").path("status").asText());
    }

    @Test
    public void candidateCampaignWithNoDiscoverableCaseFailsClosed() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-missing-case-baseline");
        buildFullCampaignSet(baseline);
        Path candidate = Files.createTempDirectory("raknet-resilience-missing-case-candidate");
        buildFullCampaignSet(candidate, "bounded");
        scaleCandidatePressure(candidate, 10);
        Path missingCaseCampaign = candidate.resolve("disappearance-3");
        buildDisappearanceCampaign(candidate, 3, "bounded");
        makeAncillaryOneWay(missingCaseCampaign);
        Files.delete(missingCaseCampaign.resolve("cases/01-blackhole/manifest.json"));
        Path output = Files.createTempDirectory("raknet-resilience-missing-case-report");

        ProcessResult result = compare(baseline, candidate, output);

        Assertions.assertEquals(1, result.exitCode, result.output);
        JsonNode report = readReport(output);
        Assertions.assertTrue(report.path("comparison").path("invalidCandidateCampaigns")
                .toString().contains(missingCaseCampaign.toString()));
        Assertions.assertEquals("fail", findGate(report, "comparison-evidence-integrity")
                .path("status").asText());
        Assertions.assertTrue(report.path("campaigns").toString()
                .contains("discovered case artifacts do not match the campaign plan profiles"));
    }

    @Test
    public void mergedWorkerProvenanceMustBeCompleteForEveryRole() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-role-provenance-baseline");
        buildFullCampaignSet(baseline);

        Path missingServer = Files.createTempDirectory("raknet-resilience-missing-server-provenance");
        buildFullCampaignSet(missingServer, "bounded");
        scaleCandidatePressure(missingServer, 10);
        Path serverSummaryPath = missingServer.resolve("campaign-1/cases/02-near-loss/merged/lab-summary.json");
        ObjectNode serverSummary = (ObjectNode) JSON.readTree(Files.readString(
                serverSummaryPath, StandardCharsets.UTF_8));
        ((ObjectNode) serverSummary.path("server")).remove("gitRevision");
        Files.writeString(serverSummaryPath, JSON.writeValueAsString(serverSummary) + "\n",
                StandardCharsets.UTF_8);
        Path serverOutput = Files.createTempDirectory("raknet-resilience-missing-server-report");

        ProcessResult serverResult = compare(baseline, missingServer, serverOutput);

        Assertions.assertEquals(1, serverResult.exitCode, serverResult.output);
        Assertions.assertTrue(readReport(serverOutput).path("cases").toString()
                .contains("exact server run/revision provenance"));

        Path missingReceiver = Files.createTempDirectory("raknet-resilience-missing-role-provenance");
        buildFullCampaignSet(missingReceiver, "bounded");
        scaleCandidatePressure(missingReceiver, 10);
        Path receiverSummaryPath = missingReceiver.resolve(
                "campaign-1/cases/02-near-loss/merged/lab-summary.json");
        ObjectNode receiverSummary = (ObjectNode) JSON.readTree(Files.readString(
                receiverSummaryPath, StandardCharsets.UTF_8));
        ((ArrayNode) receiverSummary.path("receivers")).remove(0);
        Files.writeString(receiverSummaryPath, JSON.writeValueAsString(receiverSummary) + "\n",
                StandardCharsets.UTF_8);
        Path receiverOutput = Files.createTempDirectory("raknet-resilience-missing-role-report");

        ProcessResult receiverResult = compare(baseline, missingReceiver, receiverOutput);

        Assertions.assertEquals(1, receiverResult.exitCode, receiverResult.output);
        Assertions.assertTrue(readReport(receiverOutput).path("cases").toString()
                .contains("receiver provenance count is incomplete or duplicated"));
    }

    @Test
    public void campaignArtifactPointersMustMatchTheDeclaredOutputLayout() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-pointer-baseline");
        Path candidate = Files.createTempDirectory("raknet-resilience-pointer-candidate");
        buildFullCampaignSet(baseline);
        buildFullCampaignSet(candidate, "bounded");
        scaleCandidatePressure(candidate, 10);
        Path summaryPath = candidate.resolve("campaign-1/campaign-summary.json");
        ObjectNode summary = (ObjectNode) JSON.readTree(Files.readString(summaryPath, StandardCharsets.UTF_8));
        ((ObjectNode) summary.path("statuses").get(0)).put(
                "artifact", "/different-parent/cases/01-perfect");
        ((ObjectNode) summary.path("results").get(0)).put(
                "caseArtifact", "/different-parent/cases/01-perfect");
        Files.writeString(summaryPath, JSON.writeValueAsString(summary) + "\n", StandardCharsets.UTF_8);
        Path output = Files.createTempDirectory("raknet-resilience-pointer-report");

        ProcessResult result = compare(baseline, candidate, output);

        Assertions.assertEquals(1, result.exitCode, result.output);
        Assertions.assertTrue(readReport(output).path("campaigns").toString()
                .contains("artifact provenance disagrees with discovered case"));
    }

    @Test
    public void comparisonRejectsAnIncompleteSecondCampaign() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-campaign-baseline");
        buildFullCampaignSet(baseline);
        Path candidate = Files.createTempDirectory("raknet-resilience-campaign-candidate");
        buildFullCampaignSet(candidate, "bounded");
        scaleCandidatePressure(candidate, 10);
        Path summaryPath = candidate.resolve("campaign-2/campaign-summary.json");
        ObjectNode summary = (ObjectNode) JSON.readTree(Files.readString(summaryPath, StandardCharsets.UTF_8));
        summary.put("executionPassed", false);
        Files.writeString(summaryPath, JSON.writeValueAsString(summary) + "\n", StandardCharsets.UTF_8);
        Path output = Files.createTempDirectory("raknet-resilience-incomplete-campaign-report");

        ProcessResult result = compare(baseline, candidate, output);

        Assertions.assertEquals(1, result.exitCode, result.output);
        JsonNode report = readReport(output);
        Assertions.assertEquals("fail", findGate(report, "complete-six-profile-campaigns")
                .path("status").asText());
        Assertions.assertEquals(1, report.path("comparison").path("requiredCompleteCampaigns")
                .path("candidate").size());
        Assertions.assertFalse(report.path("comparison").path("missingCandidateCases").isEmpty());
    }

    @Test
    public void comparisonRejectsCopiedExecutionIdentityAndConfigurationDrift() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-identity-baseline");
        buildFullCampaignSet(baseline);
        Path copiedIdentity = Files.createTempDirectory("raknet-resilience-copied-identity");
        buildFullCampaignSet(copiedIdentity, "bounded");
        scaleCandidatePressure(copiedIdentity, 10);
        ObjectNode firstGoal = (ObjectNode) JSON.readTree(Files.readString(
                copiedIdentity.resolve("campaign-1/goal-manifest.json"), StandardCharsets.UTF_8));
        Path secondGoalPath = copiedIdentity.resolve("campaign-2/goal-manifest.json");
        ObjectNode secondGoal = (ObjectNode) JSON.readTree(Files.readString(
                secondGoalPath, StandardCharsets.UTF_8));
        secondGoal.put("generatedAt", firstGoal.path("generatedAt").asText());
        Files.writeString(secondGoalPath, JSON.writeValueAsString(secondGoal) + "\n", StandardCharsets.UTF_8);
        Path copiedOutput = Files.createTempDirectory("raknet-resilience-copied-identity-report");
        ProcessResult copied = compare(baseline, copiedIdentity, copiedOutput);
        Assertions.assertEquals(1, copied.exitCode, copied.output);
        Assertions.assertEquals("fail", findGate(readReport(copiedOutput), "complete-six-profile-campaigns")
                .path("status").asText());

        Path drifted = Files.createTempDirectory("raknet-resilience-config-drift");
        buildFullCampaignSet(drifted, "bounded");
        scaleCandidatePressure(drifted, 10);
        Path driftedPlanPath = drifted.resolve("campaign-2/campaign-plan.json");
        ObjectNode driftedPlan = (ObjectNode) JSON.readTree(Files.readString(
                driftedPlanPath, StandardCharsets.UTF_8));
        ((ObjectNode) driftedPlan.path("parameters")).put("workers", 2);
        Files.writeString(driftedPlanPath, JSON.writeValueAsString(driftedPlan) + "\n",
                StandardCharsets.UTF_8);
        try (var manifests = Files.walk(drifted.resolve("campaign-2/cases"))) {
            for (Path manifestPath : manifests.filter(path -> path.getFileName().toString()
                    .equals("manifest.json")).toList()) {
                ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(
                        manifestPath, StandardCharsets.UTF_8));
                manifest.put("workers", 2);
                Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n",
                        StandardCharsets.UTF_8);
            }
        }
        Path driftedOutput = Files.createTempDirectory("raknet-resilience-config-drift-report");
        ProcessResult drift = compare(baseline, drifted, driftedOutput);
        Assertions.assertEquals(1, drift.exitCode, drift.output);
        Assertions.assertTrue(readReport(driftedOutput).path("comparison").path("issues").toString()
                .contains("do not share one exact experiment configuration"));
    }

    @Test
    public void comparisonCannotOmitRequiredSevereInitialNetemFromBothSides() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-missing-severe-baseline");
        Path candidate = Files.createTempDirectory("raknet-resilience-missing-severe-candidate");
        buildFullCampaignSet(baseline);
        buildFullCampaignSet(candidate, "bounded");
        removeSevereInitialNetem(baseline);
        removeSevereInitialNetem(candidate);
        scaleCandidatePressure(candidate, 10);
        Path output = Files.createTempDirectory("raknet-resilience-missing-severe-report");

        ProcessResult result = compare(baseline, candidate, output);

        Assertions.assertEquals(1, result.exitCode, result.output);
        JsonNode report = readReport(output);
        Assertions.assertEquals("fail", report.path("comparison").path("status").asText());
        Assertions.assertFalse(report.path("comparison").path("invalidBaselineCases").isEmpty());
        Assertions.assertFalse(report.path("comparison").path("invalidCandidateCases").isEmpty());
        Assertions.assertTrue(report.path("cases").toString()
                .contains("configured impaired profile lacks required initial-netem event evidence"));

        Path missingBlackhole = Files.createTempDirectory("raknet-resilience-missing-blackhole");
        copyDenseFixture(fixture, missingBlackhole);
        Path blackholeCase = missingBlackhole.resolve("cases/01-blackhole");
        Path manifestPath = blackholeCase.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        manifest.putNull("blackholeAtEpochMillis");
        Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        Files.delete(blackholeCase.resolve("netem/external-blackhole-server-srvi.txt"));
        Files.delete(blackholeCase.resolve("netem/external-blackhole-affected-rcvi.txt"));
        Path timeline = blackholeCase.resolve("server/netns-blackhole-10c/timeline.jsonl");
        List<String> timelineRows = new ArrayList<>();
        for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
            ObjectNode row = (ObjectNode) JSON.readTree(line);
            row.putNull("externalBlackholeAtEpochMillis");
            timelineRows.add(JSON.writeValueAsString(row));
        }
        Files.writeString(timeline, String.join("\n", timelineRows) + "\n", StandardCharsets.UTF_8);
        Path missingBlackholeOutput = Files.createTempDirectory("raknet-resilience-missing-blackhole-report");
        ProcessResult missingBlackholeResult = runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(), "--root", missingBlackhole.toString(),
                "--out", missingBlackholeOutput.toString());
        Assertions.assertEquals(1, missingBlackholeResult.exitCode, missingBlackholeResult.output);
        Assertions.assertTrue(readReport(missingBlackholeOutput).path("cases").toString()
                .contains("blackhole case lacks required external-blackhole event evidence"));
    }

    private static ProcessResult compare(Path baseline, Path candidate, Path output) throws Exception {
        return compare(baseline, candidate, output, "legacy", "bounded");
    }

    private static ProcessResult compare(Path baseline, Path candidate, Path output,
                                         String baselineMode, String candidateMode) throws Exception {
        return runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(),
                "--baseline-root", baseline.toString(),
                "--candidate-root", candidate.toString(),
                "--baseline-recovery-mode", baselineMode,
                "--candidate-recovery-mode", candidateMode,
                "--out", output.toString());
    }

    private static JsonNode event(JsonNode caseResult, String label) {
        for (JsonNode event : caseResult.path("externalEvents")) {
            if (label.equals(event.path("label").asText())) {
                return event;
            }
        }
        throw new AssertionError("Missing event " + label);
    }

    private static JsonNode findGate(JsonNode report, String id) {
        for (JsonNode gate : report.path("gates")) {
            if (id.equals(gate.path("id").asText())) {
                return gate;
            }
        }
        throw new AssertionError("Missing gate " + id);
    }

    private static JsonNode readReport(Path output) throws Exception {
        return JSON.readTree(Files.readString(output.resolve("resilience-analysis.json"), StandardCharsets.UTF_8));
    }

    private static void scaleCandidatePressure(Path candidate, int divisor) throws Exception {
        try (var paths = Files.walk(candidate)) {
            for (Path timeline : paths.filter(path -> path.getFileName().toString().equals("timeline.jsonl"))
                    .toList()) {
                List<String> rewritten = new ArrayList<>();
                for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
                    ObjectNode row = (ObjectNode) JSON.readTree(line);
                    if (!row.path("affected").isObject()) {
                        rewritten.add(JSON.writeValueAsString(row));
                        continue;
                    }
                    ObjectNode cohort = (ObjectNode) row.path("affected");
                    if (cohort.path("configuredPeers").asInt() == 0) {
                        rewritten.add(JSON.writeValueAsString(row));
                        continue;
                    }
                    for (String counter : List.of(
                            "nackRetransmittedDatagrams", "nackRetransmittedBytes",
                            "timeoutRetransmittedDatagrams", "timeoutRetransmittedBytes")) {
                        cohort.put(counter, cohort.path(counter).asLong() / divisor);
                    }
                    scaleGauge(cohort, "currentQueuedBytes", 100, divisor);
                    scaleGauge(cohort, "sampledQueuedBytesHighWater", 100, divisor);
                    scaleGauge(cohort, "maxPeerQueuedBytes", 50, divisor);
                    scaleGauge(cohort, "currentBytesInFlight", 50, divisor);
                    scaleGauge(cohort, "sampledBytesInFlightHighWater", 50, divisor);
                    scaleGauge(cohort, "maxPeerBytesInFlight", 25, divisor);
                    rewritten.add(JSON.writeValueAsString(row));
                }
                Files.writeString(timeline, String.join("\n", rewritten) + "\n", StandardCharsets.UTF_8);
            }
        }
    }

    private static void forceTimeoutSubtype(Path campaigns, boolean positiveAfterEvent) throws Exception {
        try (var paths = Files.walk(campaigns)) {
            for (Path timeline : paths.filter(path -> path.getFileName().toString().equals("timeline.jsonl"))
                    .filter(path -> path.toString().contains("/server/"))
                    .toList()) {
                List<String> rewritten = new ArrayList<>();
                for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
                    ObjectNode row = (ObjectNode) JSON.readTree(line);
                    if (row.path("affected").isObject()) {
                        ObjectNode cohort = (ObjectNode) row.path("affected");
                        boolean afterEvent = row.path("epochMillis").asLong() > 102_000L;
                        long datagrams = positiveAfterEvent && afterEvent ? 1L : 0L;
                        cohort.put("timeoutRetransmittedDatagrams", datagrams);
                        cohort.put("timeoutRetransmittedBytes", datagrams * 64L);
                    }
                    rewritten.add(JSON.writeValueAsString(row));
                }
                Files.writeString(timeline, String.join("\n", rewritten) + "\n", StandardCharsets.UTF_8);
            }
        }
    }

    private static void removeSevereInitialNetem(Path campaigns) throws Exception {
        try (var paths = Files.walk(campaigns)) {
            for (Path caseRoot : paths.filter(path -> path.getFileName().toString().equals("05-severe"))
                    .toList()) {
                Path manifestPath = caseRoot.resolve("manifest.json");
                ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(
                        manifestPath, StandardCharsets.UTF_8));
                manifest.putNull("netemAtEpochMillis");
                Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n",
                        StandardCharsets.UTF_8);
                try (var timelinePaths = Files.walk(caseRoot.resolve("server"))) {
                    for (Path timeline : timelinePaths.filter(path -> path.getFileName().toString()
                            .equals("timeline.jsonl")).toList()) {
                        List<String> rewritten = new ArrayList<>();
                        for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
                            ObjectNode row = (ObjectNode) JSON.readTree(line);
                            row.putNull("externalImpairmentAtEpochMillis");
                            rewritten.add(JSON.writeValueAsString(row));
                        }
                        Files.writeString(timeline, String.join("\n", rewritten) + "\n",
                                StandardCharsets.UTF_8);
                    }
                }
                deleteTree(caseRoot.resolve("netem"));
            }
        }
    }

    private static void writeGoalProvenance(Path output, String recoveryMode, String benchmarkJarSha256)
            throws Exception {
        Files.createDirectories(output);
        String distribution = benchmarkJarSha256 + "  benchmark-test.jar\n";
        Files.writeString(output.resolve("distribution.sha256"), distribution, StandardCharsets.UTF_8);
        ObjectNode goal = JSON.createObjectNode();
        goal.put("kind", "raknet-netns-autonomous-goal");
        String directoryName = output.getFileName().toString();
        int suffix = Integer.parseInt(directoryName.substring(directoryName.lastIndexOf('-') + 1));
        int second = directoryName.startsWith("disappearance-") ? 10 + suffix : suffix;
        goal.put("generatedAt", String.format("2026-08-19T00:00:%02dZ", second));
        goal.put("mode", "pilot");
        goal.put("recoveryMode", recoveryMode);
        goal.put("candidateRevision", fixtureCandidateRevision(benchmarkJarSha256));
        Files.writeString(output.resolve("goal-manifest.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(goal) + "\n",
                StandardCharsets.UTF_8);
    }

    private static String fixtureCandidateRevision(String benchmarkJarSha256) throws Exception {
        String distribution = benchmarkJarSha256 + "  benchmark-test.jar\n";
        return FIXTURE_SOURCE_REVISION + "+dist-" + sha256Hex(distribution.getBytes(StandardCharsets.UTF_8))
                .substring(0, 12);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte element : digest) {
            value.append(String.format("%02x", element & 0xff));
        }
        return value.toString();
    }

    private static void setMergedProvenance(ObjectNode summary, String runId, int healthyClients,
                                            int affectedClients, String revision, String recoveryMode) {
        ObjectNode aggregate = (ObjectNode) summary.withObject("/aggregate");
        aggregate.put("recoveryModeProvenanceValid", true);
        aggregate.put("recoveryMode", recoveryMode);
        summary.putObject("server")
                .put("runId", runId)
                .put("role", "server")
                .put("recoveryMode", recoveryMode)
                .put("gitRevision", revision);
        ArrayNode receivers = summary.putArray("receivers");
        if (affectedClients > 0) {
            receivers.addObject()
                    .put("runId", runId + "-affected")
                    .put("role", "client")
                    .put("clients", affectedClients)
                    .put("recoveryMode", recoveryMode)
                    .put("gitRevision", revision);
            receivers.addObject()
                    .put("runId", runId + "-healthy")
                    .put("role", "client")
                    .put("clients", healthyClients)
                    .put("recoveryMode", recoveryMode)
                    .put("gitRevision", revision);
        } else {
            receivers.addObject()
                    .put("runId", runId + "-receiver")
                    .put("role", "client")
                    .put("clients", healthyClients)
                    .put("recoveryMode", recoveryMode)
                    .put("gitRevision", revision);
        }
    }

    private static void replaceGoalDistribution(Path output, String benchmarkJarSha256) throws Exception {
        String revision = fixtureCandidateRevision(benchmarkJarSha256);
        try (var manifests = Files.walk(output)) {
            for (Path manifest : manifests.filter(path -> path.getFileName().toString()
                    .equals("goal-manifest.json")).toList()) {
                ObjectNode goal = (ObjectNode) JSON.readTree(Files.readString(
                        manifest, StandardCharsets.UTF_8));
                writeGoalProvenance(manifest.getParent(), goal.path("recoveryMode").asText(),
                        benchmarkJarSha256);
            }
        }
        try (var summaries = Files.walk(output)) {
            for (Path summaryPath : summaries.filter(path -> path.getFileName().toString()
                    .equals("lab-summary.json")).toList()) {
                ObjectNode summary = (ObjectNode) JSON.readTree(Files.readString(
                        summaryPath, StandardCharsets.UTF_8));
                ((ObjectNode) summary.path("server")).put("gitRevision", revision);
                for (JsonNode receiver : summary.path("receivers")) {
                    ((ObjectNode) receiver).put("gitRevision", revision);
                }
                Files.writeString(summaryPath,
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                        StandardCharsets.UTF_8);
            }
        }
    }

    private static void buildFullCampaignSet(Path output) throws Exception {
        buildFullCampaignSet(output, "legacy");
    }

    private static void buildFullCampaignSet(Path output, String recoveryMode) throws Exception {
        for (int campaignIndex = 1; campaignIndex <= 2; campaignIndex++) {
            Path campaign = output.resolve("campaign-" + campaignIndex);
            writeGoalProvenance(campaign, recoveryMode, FIXTURE_BENCHMARK_JAR_SHA256);
            Files.createDirectories(campaign.resolve("cases"));
            ArrayNode profiles = JSON.createArrayNode();
            ArrayNode statuses = JSON.createArrayNode();
            ArrayNode results = JSON.createArrayNode();
            List<String> names = List.of("perfect", "near-loss", "regional-loss", "poor", "severe", "blackhole");
            for (int profileIndex = 0; profileIndex < names.size(); profileIndex++) {
                String profile = names.get(profileIndex);
                Path caseRoot = campaign.resolve("cases/%02d-%s".formatted(profileIndex + 1, profile));
                copyDenseFixture(fixture.resolve("cases/01-blackhole"), caseRoot);
                configureProfile(caseRoot, profile, campaignIndex, recoveryMode);
                String[] shape = profileShape(profile);
                String caseType = profile.equals("perfect") ? "fanout"
                        : profile.equals("blackhole") ? "blackhole" : "fairness";
                profiles.addObject().put("profile", profile).put("caseType", caseType)
                        .put("latency", shape[0]).put("jitter", shape[1]).put("loss", shape[2]);
                statuses.addObject().put("profile", profile).put("caseType", caseType)
                        .put("status", "completed").put("startedAt", "2026-08-19T00:00:00Z")
                        .put("completedAt", "2026-08-19T00:01:00Z").put("artifact", caseRoot.toString());
                results.addObject().put("profile", profile).put("caseType", caseType)
                        .put("caseArtifact", caseRoot.toString());
            }
            ObjectNode plan = JSON.createObjectNode();
            plan.put("kind", "raknet-netns-pilot-campaign");
            plan.put("generatedAt", "20260819T00000" + campaignIndex + "Z");
            plan.put("execute", true);
            plan.put("outputRoot", campaign.toString());
            plan.set("profiles", profiles);
            ObjectNode parameters = plan.putObject("parameters");
            parameters.put("clients", "10");
            parameters.put("affectedClients", "2");
            parameters.put("payloadSize", "512");
            parameters.put("perClientMbps", "5");
            parameters.put("warmup", "10s");
            parameters.put("duration", "20s");
            parameters.put("iterations", "1");
            parameters.put("probeInterval", "200ms");
            parameters.put("startDelay", "1s");
            parameters.put("startOffset", "1s");
            parameters.put("netemBeforeStart", "0s");
            parameters.put("netemLimitPackets", 10000);
            parameters.put("blackholeAfter", "2s");
            parameters.put("blackholeDuration", "3s");
            parameters.put("direction", "both");
            parameters.put("reliability", "RELIABLE_ORDERED");
            parameters.put("recoveryMode", recoveryMode);
            parameters.putNull("packetLimit");
            parameters.putNull("globalPacketLimit");
            parameters.putNull("maxQueuedBytes");
            parameters.putNull("workers");
            parameters.put("resourceSafetyMaxAggregateQueuedBytes", 402_653_184);
            parameters.put("resourceSafetyMaxDirectMemoryUsedBytes", 805_306_368);
            Files.writeString(campaign.resolve("campaign-plan.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(plan) + "\n", StandardCharsets.UTF_8);
            ObjectNode summary = JSON.createObjectNode();
            summary.put("kind", "raknet-netns-pilot-summary");
            summary.put("generatedAt", "2026-08-19T00:02:00Z");
            summary.put("executed", true);
            summary.put("executionPassed", true);
            summary.put("campaignPlan", campaign.resolve("campaign-plan.json").toString());
            summary.put("campaignStatus", campaign.resolve("campaign-status.jsonl").toString());
            summary.set("statuses", statuses);
            summary.set("results", results);
            Files.writeString(campaign.resolve("campaign-summary.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n", StandardCharsets.UTF_8);
        }
        for (int campaignIndex = 1; campaignIndex <= 2; campaignIndex++) {
            buildDisappearanceCampaign(output, campaignIndex, recoveryMode);
        }
    }

    private static void buildDisappearanceCampaign(Path output, int campaignIndex, String recoveryMode)
            throws Exception {
        Path campaign = output.resolve("disappearance-" + campaignIndex);
        writeGoalProvenance(campaign, recoveryMode, FIXTURE_BENCHMARK_JAR_SHA256);
        Path caseRoot = campaign.resolve("cases/01-blackhole");
        Files.createDirectories(campaign.resolve("cases"));
        copyDenseFixture(fixture.resolve("cases/01-blackhole"), caseRoot);
        configureProfile(caseRoot, "blackhole", 10 + campaignIndex, recoveryMode);
        makePermanentBlackhole(campaign, true);

        ObjectNode plan = JSON.createObjectNode();
        plan.put("kind", "raknet-netns-pilot-campaign");
        plan.put("generatedAt", "20260819T10000" + campaignIndex + "Z");
        plan.put("execute", true);
        plan.put("outputRoot", campaign.toString());
        plan.putArray("profiles").addObject().put("profile", "blackhole").put("caseType", "blackhole")
                .put("latency", "0ms").put("jitter", "0ms").put("loss", "0%");
        ObjectNode parameters = plan.putObject("parameters");
        parameters.put("clients", "10");
        parameters.put("affectedClients", "2");
        parameters.put("payloadSize", "512");
        parameters.put("perClientMbps", "5");
        parameters.put("warmup", "10s");
        parameters.put("duration", "20s");
        parameters.put("iterations", "1");
        parameters.put("probeInterval", "200ms");
        parameters.put("startDelay", "1s");
        parameters.put("startOffset", "1s");
        parameters.put("netemBeforeStart", "0s");
        parameters.put("netemLimitPackets", 10000);
        parameters.put("blackholeAfter", "2s");
        parameters.put("blackholeDuration", "0s");
        parameters.put("direction", "both");
        parameters.put("reliability", "RELIABLE_ORDERED");
        parameters.put("recoveryMode", recoveryMode);
        parameters.putNull("packetLimit");
        parameters.putNull("globalPacketLimit");
        parameters.putNull("maxQueuedBytes");
        parameters.putNull("workers");
        parameters.put("resourceSafetyMaxAggregateQueuedBytes", 402_653_184);
        parameters.put("resourceSafetyMaxDirectMemoryUsedBytes", 805_306_368);
        Files.writeString(campaign.resolve("campaign-plan.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(plan) + "\n", StandardCharsets.UTF_8);

        ObjectNode status = JSON.createObjectNode();
        status.put("profile", "blackhole");
        status.put("caseType", "blackhole");
        status.put("status", "completed");
        status.put("startedAt", "2026-08-19T00:00:00Z");
        status.put("completedAt", "2026-08-19T00:01:00Z");
        status.put("artifact", caseRoot.toString());
        ObjectNode result = JSON.createObjectNode();
        result.put("profile", "blackhole");
        result.put("caseType", "blackhole");
        result.put("caseArtifact", caseRoot.toString());
        ObjectNode summary = JSON.createObjectNode();
        summary.put("kind", "raknet-netns-pilot-summary");
        summary.put("generatedAt", "2026-08-19T00:02:00Z");
        summary.put("executed", true);
        summary.put("executionPassed", true);
        summary.put("campaignPlan", campaign.resolve("campaign-plan.json").toString());
        summary.put("campaignStatus", campaign.resolve("campaign-status.jsonl").toString());
        summary.putArray("statuses").add(status);
        summary.putArray("results").add(result);
        Files.writeString(campaign.resolve("campaign-summary.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n", StandardCharsets.UTF_8);
    }

    private static void configureProfile(Path caseRoot, String profile, int campaignIndex,
                                         String recoveryMode) throws Exception {
        Path manifestPath = caseRoot.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        String runId = "netns-" + profile + "-10c-" + campaignIndex;
        manifest.put("runId", runId);
        boolean perfect = profile.equals("perfect");
        boolean blackhole = profile.equals("blackhole");
        manifest.put("case", perfect ? "fanout" : blackhole ? "blackhole" : "fairness");
        manifest.put("benchmarkName", perfect ? "multi-client-fanout" : blackhole ? "disappearing-clients" : "fairness");
        manifest.put("affectedClients", perfect ? 0 : 2);
        manifest.put("healthyClients", perfect ? 10 : 8);
        manifest.put("direction", "both");
        manifest.put("recoveryMode", recoveryMode);
        String[] shape = profileShape(profile);
        manifest.put("latency", shape[0]);
        manifest.put("jitter", shape[1]);
        manifest.put("loss", shape[2]);
        if (blackhole) {
            manifest.putNull("netemAtEpochMillis");
            manifest.put("blackholeAtEpochMillis", 102000);
            manifest.put("recoveryAtEpochMillis", 105000);
        } else if (perfect) {
            manifest.putNull("netemAtEpochMillis");
            manifest.putNull("blackholeAtEpochMillis");
            manifest.putNull("recoveryAtEpochMillis");
        } else {
            manifest.put("netemAtEpochMillis", 102000);
            manifest.putNull("blackholeAtEpochMillis");
            manifest.putNull("recoveryAtEpochMillis");
        }
        Files.writeString(manifestPath,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);

        Path oldTimeline = caseRoot.resolve("server/netns-blackhole-10c/timeline.jsonl");
        Path timeline = caseRoot.resolve("server").resolve(runId).resolve("timeline.jsonl");
        Files.createDirectories(timeline.getParent());
        List<String> rows = new ArrayList<>();
        for (String line : Files.readAllLines(oldTimeline, StandardCharsets.UTF_8)) {
            ObjectNode row = (ObjectNode) JSON.readTree(line);
            row.put("runId", runId);
            row.put("recoveryMode", recoveryMode);
            if (blackhole) {
                row.putNull("externalImpairmentAtEpochMillis");
                row.put("externalBlackholeAtEpochMillis", 102000);
                row.put("externalRecoveryAtEpochMillis", 105000);
            } else if (perfect) {
                row.putNull("externalImpairmentAtEpochMillis");
                row.putNull("externalBlackholeAtEpochMillis");
                row.putNull("externalRecoveryAtEpochMillis");
                zeroAffected((ObjectNode) row.path("affected"));
            } else {
                row.put("externalImpairmentAtEpochMillis", 102000);
                row.putNull("externalBlackholeAtEpochMillis");
                row.putNull("externalRecoveryAtEpochMillis");
            }
            if ("model_based".equals(recoveryMode) && "sample".equals(row.path("recordType").asText())) {
                addModelTelemetry(row, false);
            }
            rows.add(JSON.writeValueAsString(row));
        }
        Files.writeString(timeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        deleteTree(oldTimeline.getParent());

        String healthyRunId = runId + (perfect ? "-receiver" : "-healthy");
        configureReceiverTimeline(caseRoot, "receiver-healthy", "netns-blackhole-10c-healthy",
                healthyRunId, recoveryMode);
        if (perfect) {
            deleteTree(caseRoot.resolve("receiver-affected"));
        } else {
            configureReceiverTimeline(caseRoot, "receiver-affected", "netns-blackhole-10c-affected",
                    runId + "-affected", recoveryMode);
        }

        ObjectNode summary = (ObjectNode) JSON.readTree(Files.readString(
                caseRoot.resolve("merged/lab-summary.json"), StandardCharsets.UTF_8));
        ObjectNode aggregate = (ObjectNode) summary.path("aggregate");
        aggregate.put("affectedClients", perfect ? 0 : 2);
        aggregate.put("affectedClientMbpsP50", perfect ? 0.0D : 4.0D);
        aggregate.put("affectedSentToDeliveredBytesRatio", perfect ? 0.0D : 1.5D);
        setMergedProvenance(summary, runId, perfect ? 10 : 8, perfect ? 0 : 2,
                fixtureCandidateRevision(FIXTURE_BENCHMARK_JAR_SHA256), recoveryMode);
        Files.writeString(caseRoot.resolve("merged/lab-summary.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n", StandardCharsets.UTF_8);

        if (!blackhole) {
            Path netem = caseRoot.resolve("netem");
            deleteTree(netem);
            if (!perfect) {
                Files.createDirectories(netem);
                Files.writeString(netem.resolve("initial-netem-server-srvi.txt"),
                        initialNetemEvidence("server", "srvi", 102_000, 102_010, shape),
                        StandardCharsets.UTF_8);
                Files.writeString(netem.resolve("initial-netem-affected-rcvi.txt"),
                        initialNetemEvidence("affected", "rcvi", 102_005, 102_020, shape),
                        StandardCharsets.UTF_8);
                String netemQdisc = netemQdisc(shape);
                Files.writeString(netem.resolve("qdisc-timeseries.jsonl"), String.join("\n",
                        qdiscRow(101_000, "server", "srvi", "[{\"kind\":\"noqueue\",\"options\":{}}]"),
                        qdiscRow(101_000, "affected", "rcvi", "[{\"kind\":\"noqueue\",\"options\":{}}]"),
                        qdiscRow(103_000, "server", "srvi", netemQdisc),
                        qdiscRow(103_000, "affected", "rcvi", netemQdisc),
                        qdiscRow(116_100, "server", "srvi", netemQdisc),
                        qdiscRow(116_100, "affected", "rcvi", netemQdisc)) + "\n", StandardCharsets.UTF_8);
            }
        }
    }

    private static void configureReceiverTimeline(Path caseRoot, String directory, String oldRunId,
                                                  String newRunId, String recoveryMode) throws Exception {
        Path oldTimeline = caseRoot.resolve(directory).resolve(oldRunId).resolve("timeline.jsonl");
        Path newTimeline = caseRoot.resolve(directory).resolve(newRunId).resolve("timeline.jsonl");
        List<String> rows = new ArrayList<>();
        for (String line : Files.readAllLines(oldTimeline, StandardCharsets.UTF_8)) {
            ObjectNode row = (ObjectNode) JSON.readTree(line);
            row.put("runId", newRunId);
            row.put("recoveryMode", recoveryMode);
            if ("model_based".equals(recoveryMode) && "sample".equals(row.path("recordType").asText())) {
                addModelTelemetry(row, true);
            }
            rows.add(JSON.writeValueAsString(row));
        }
        Files.createDirectories(newTimeline.getParent());
        Files.writeString(newTimeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        deleteTree(oldTimeline.getParent());
    }

    private static String[] profileShape(String profile) {
        return switch (profile) {
            case "near-loss" -> new String[]{"10ms", "2ms", "2%"};
            case "regional-loss" -> new String[]{"50ms", "5ms", "2%"};
            case "poor" -> new String[]{"100ms", "10ms", "5%"};
            case "severe" -> new String[]{"200ms", "20ms", "10%"};
            default -> new String[]{"0ms", "0ms", "0%"};
        };
    }

    private static void addModelTelemetry(ObjectNode row, boolean receiver) {
        row.put("schemaVersion", 2);
        ObjectNode availability = row.withObject("/metricAvailability");
        availability.put("congestionModelState",
                receiver ? "unavailable-on-receiver-worker" : "available");
        availability.put("nackValidationEvents",
                receiver ? "unavailable-on-receiver-worker" : "available");
        for (String cohortName : List.of("all", "healthy", "affected")) {
            ObjectNode cohort;
            if (row.path(cohortName).isObject()) {
                cohort = (ObjectNode) row.path(cohortName);
            } else {
                cohort = row.putObject(cohortName);
                cohort.put("name", cohortName);
                cohort.put("observedPeers", 0);
            }
            if (receiver) {
                cohort.putNull("congestionModel");
                continue;
            }
            int peers = cohort.path("observedPeers").asInt();
            ObjectNode model = cohort.putObject("congestionModel");
            model.put("observedPeers", peers);
            model.put("estimatedDeliveryRateObservedPeers", peers);
            model.put("pacingRateObservedPeers", peers);
            model.put("minimumRttObservedPeers", peers);
            model.put("recentLossObservedPeers", peers);
            model.put("packetRoundObservedPeers", peers);
            if (peers > 0) {
                model.put("oldestObservedAtEpochMillis", row.path("epochMillis").asLong());
                model.put("latestObservedAtEpochMillis", row.path("epochMillis").asLong());
                model.put("totalEstimatedDeliveryRateBytesPerSecond", peers * 500_000.0D);
                model.put("maxEstimatedDeliveryRateBytesPerSecond", 500_000.0D);
                model.put("totalPacingRateBytesPerSecond", peers * 600_000.0D);
                model.put("maxPacingRateBytesPerSecond", 600_000.0D);
                model.put("minimumRttMillis", 20L);
                model.put("maximumMinimumRttMillis", 25L);
                model.put("maximumRecentLossRate", 0.05D);
                model.put("minimumPacketRound", 2L);
                model.put("maximumPacketRound", 4L);
            } else {
                for (String field : List.of("oldestObservedAtEpochMillis", "latestObservedAtEpochMillis",
                        "totalEstimatedDeliveryRateBytesPerSecond", "maxEstimatedDeliveryRateBytesPerSecond",
                        "totalPacingRateBytesPerSecond", "maxPacingRateBytesPerSecond",
                        "minimumRttMillis", "maximumMinimumRttMillis", "maximumRecentLossRate",
                        "minimumPacketRound", "maximumPacketRound")) {
                    model.putNull(field);
                }
            }
            model.put("startupPeers", 0);
            model.put("persistentCongestionPeers", "affected".equals(cohortName) ? peers : 0);
            long counter = row.path("sequence").asLong();
            model.put("nackRecoveryHints", counter);
            model.put("nackReorderingResolved", counter / 2L);
            model.put("nackLossValidated", counter / 3L);
            if (counter > 0) {
                model.put("maxNackRecoveryHintDelayMillis", 30L);
            } else {
                model.putNull("maxNackRecoveryHintDelayMillis");
            }
            if (counter / 2L > 0) {
                model.put("maxNackReorderingResolvedDelayMillis", 20L);
            } else {
                model.putNull("maxNackReorderingResolvedDelayMillis");
            }
            if (counter / 3L > 0) {
                model.put("maxNackLossValidatedDelayMillis", 40L);
            } else {
                model.putNull("maxNackLossValidatedDelayMillis");
            }
        }
    }

    private static String initialNetemEvidence(String namespace, String iface, long started, long completed,
                                                String[] shape) {
        return """
                # Netem Apply
                namespace=%s
                interface=%s
                action=apply
                latency=%s
                jitter=%s
                loss=%s
                limit=10000
                applyStartedAtEpochMillis=%d
                + tc qdisc replace dev %s root netem delay %s %s loss %s limit 10000
                applyCompletedAtEpochMillis=%d
                + tc qdisc show dev %s
                qdisc netem 8001: root refcnt 2 limit 10000 delay %s %s loss %s
                """.formatted(namespace, iface, shape[0], shape[1], shape[2], started,
                iface, shape[0], shape[1], shape[2], completed, iface, shape[0], shape[1], shape[2]);
    }

    private static String qdiscRow(long epoch, String namespace, String iface, String qdisc) {
        return "{\"epochMillis\":" + epoch + ",\"namespace\":\"" + namespace
                + "\",\"interface\":\"" + iface + "\",\"qdisc\":" + qdisc + "}";
    }

    private static double durationSeconds(String value) {
        return Double.parseDouble(value.replace("ms", "")) / 1000.0D;
    }

    private static String netemQdisc(String[] shape) {
        double delay = durationSeconds(shape[0]);
        double jitter = durationSeconds(shape[1]);
        double loss = Double.parseDouble(shape[2].replace("%", "")) / 100.0D;
        return "[{\"kind\":\"netem\",\"options\":{\"limit\":10000,"
                + "\"delay\":{\"delay\":" + delay + ",\"jitter\":" + jitter + "},"
                + "\"loss-random\":{\"loss\":" + loss + "}}}]";
    }

    private static void zeroAffected(ObjectNode cohort) {
        for (String field : List.of("configuredPeers", "observedPeers", "openPeers", "activePeers",
                "disconnectedPeers", "disconnectEvents", "nackRetransmittedDatagrams",
                "nackRetransmittedBytes", "timeoutRetransmittedDatagrams", "timeoutRetransmittedBytes",
                "acknowledgementProgressEvents", "acknowledgementProgressBytes", "currentQueuedBytes",
                "sampledQueuedBytesHighWater", "maxPeerQueuedBytes", "currentBytesInFlight",
                "sampledBytesInFlightHighWater", "maxPeerBytesInFlight")) {
            cohort.put(field, 0);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void appendResourceSafetyAbort(Path caseRoot) throws Exception {
        Path timeline;
        try (var timelines = Files.walk(caseRoot.resolve("server"))) {
            List<Path> matches = timelines.filter(path -> path.getFileName().toString()
                    .equals("timeline.jsonl")).toList();
            Assertions.assertEquals(1, matches.size());
            timeline = matches.get(0);
        }
        List<String> rows = new ArrayList<>(Files.readAllLines(timeline, StandardCharsets.UTF_8));
        ObjectNode previous = (ObjectNode) JSON.readTree(rows.get(rows.size() - 1));
        ObjectNode abort = previous.deepCopy();
        abort.put("sequence", previous.path("sequence").asLong() + 1L);
        abort.put("epochMillis", previous.path("epochMillis").asLong() + 1L);
        abort.put("monotonicElapsedMillis", previous.path("monotonicElapsedMillis").asLong() + 1L);
        abort.put("recordType", "event");
        abort.put("eventName", "resource-safety-abort");
        abort.put("eventSource", "benchmark-resource-watchdog");
        ObjectNode detail = abort.putObject("resourceSafetyAbort");
        detail.put("reason", "aggregate-queued-bytes");
        detail.put("observedAggregateQueuedBytes", 500_000_000L);
        detail.put("observedDirectMemoryUsedBytes", 100_000_000L);
        detail.put("maxAggregateQueuedBytes", 402_653_184L);
        detail.put("maxDirectMemoryUsedBytes", 805_306_368L);
        rows.add(JSON.writeValueAsString(abort));
        Files.writeString(timeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
    }

    private static void scaleGauge(ObjectNode cohort, String field, long steady, int divisor) {
        long value = cohort.path(field).asLong();
        cohort.put(field, steady + Math.max(0L, value - steady) / divisor);
    }

    private static void makeImpairedBlackhole(Path rootPath) throws Exception {
        Path caseRoot = rootPath.resolve("cases/01-blackhole");
        String[] shape = new String[]{"100ms", "10ms", "5%"};
        Path manifestPath = caseRoot.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        manifest.put("latency", shape[0]);
        manifest.put("jitter", shape[1]);
        manifest.put("loss", shape[2]);
        manifest.put("netemAtEpochMillis", 101_200L);
        Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);

        Path timeline;
        try (var timelines = Files.walk(caseRoot.resolve("server"))) {
            List<Path> matches = timelines.filter(path -> path.getFileName().toString()
                    .equals("timeline.jsonl")).toList();
            Assertions.assertEquals(1, matches.size());
            timeline = matches.get(0);
        }
        List<String> timelineRows = new ArrayList<>();
        for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
            ObjectNode row = (ObjectNode) JSON.readTree(line);
            row.put("externalImpairmentAtEpochMillis", 101_200L);
            timelineRows.add(JSON.writeValueAsString(row));
        }
        Files.writeString(timeline, String.join("\n", timelineRows) + "\n", StandardCharsets.UTF_8);

        Path netem = caseRoot.resolve("netem");
        Files.writeString(netem.resolve("initial-netem-server-srvi.txt"),
                initialNetemEvidence("server", "srvi", 101_200L, 101_210L, shape), StandardCharsets.UTF_8);
        Files.writeString(netem.resolve("initial-netem-affected-rcvi.txt"),
                initialNetemEvidence("affected", "rcvi", 101_205L, 101_220L, shape), StandardCharsets.UTF_8);
        Files.writeString(netem.resolve("external-recovery-server-srvi.txt"),
                initialNetemEvidence("server", "srvi", 105_000L, 105_010L, shape)
                        .replace("action=apply", "action=restore"), StandardCharsets.UTF_8);
        Files.writeString(netem.resolve("external-recovery-affected-rcvi.txt"),
                initialNetemEvidence("affected", "rcvi", 105_005L, 105_020L, shape)
                        .replace("action=apply", "action=restore"), StandardCharsets.UTF_8);
        String noqueue = "[{\"kind\":\"noqueue\",\"options\":{}}]";
        String base = netemQdisc(shape);
        String blackhole = "[{\"kind\":\"netem\",\"options\":{\"limit\":10000,"
                + "\"loss-random\":{\"loss\":1.0}}}]";
        Files.writeString(netem.resolve("qdisc-timeseries.jsonl"), String.join("\n",
                qdiscRow(101_000L, "server", "srvi", noqueue),
                qdiscRow(101_000L, "affected", "rcvi", noqueue),
                qdiscRow(101_500L, "server", "srvi", base),
                qdiscRow(101_500L, "affected", "rcvi", base),
                qdiscRow(103_000L, "server", "srvi", blackhole),
                qdiscRow(103_000L, "affected", "rcvi", blackhole),
                qdiscRow(106_000L, "server", "srvi", base),
                qdiscRow(106_000L, "affected", "rcvi", base),
                qdiscRow(116_100L, "server", "srvi", base),
                qdiscRow(116_100L, "affected", "rcvi", base)) + "\n", StandardCharsets.UTF_8);
    }

    private static void forceRecoveryAction(Path rootPath, boolean clear) throws Exception {
        Path netem = rootPath.resolve("cases/01-blackhole/netem");
        for (String target : List.of("server-srvi", "affected-rcvi")) {
            Path evidence = netem.resolve("external-recovery-" + target + ".txt");
            String text = Files.readString(evidence, StandardCharsets.UTF_8);
            String iface = target.substring(target.indexOf('-') + 1);
            if (clear) {
                text = text.replaceAll("(?m)^\\+ tc qdisc replace dev \\S+ root netem.*$",
                                "+ tc qdisc del dev " + iface + " root")
                        .replaceAll("(?m)^qdisc netem .*$", "qdisc noqueue 0: root refcnt 2");
            } else {
                text = text.replace("+ tc qdisc del dev " + iface + " root",
                                "+ tc qdisc replace dev " + iface + " root netem limit 10000")
                        .replace("qdisc noqueue 0: root refcnt 2",
                                "qdisc netem 8001: root refcnt 2 limit 10000");
            }
            Files.writeString(evidence, text, StandardCharsets.UTF_8);
        }
        Path qdisc = netem.resolve("qdisc-timeseries.jsonl");
        String forcedState = clear
                ? "[{\"kind\":\"noqueue\",\"options\":{}}]"
                : "[{\"kind\":\"netem\",\"options\":{\"limit\":10000}}]";
        List<String> rewritten = new ArrayList<>();
        for (String line : Files.readAllLines(qdisc, StandardCharsets.UTF_8)) {
            ObjectNode row = (ObjectNode) JSON.readTree(line);
            if (row.path("epochMillis").asLong() >= 106_000L) {
                row.set("qdisc", JSON.readTree(forcedState));
            }
            rewritten.add(JSON.writeValueAsString(row));
        }
        Files.writeString(qdisc, String.join("\n", rewritten) + "\n", StandardCharsets.UTF_8);
    }

    private static void makePermanentBlackhole(Path campaign, boolean reclaimPeers) throws Exception {
        makePermanentBlackhole(campaign, reclaimPeers ? 112_100L : null);
    }

    private static void makeOneWayPermanentBlackhole(Path campaign) throws Exception {
        makePermanentBlackhole(campaign, false);
        Path caseRoot = campaign.resolve("cases/01-blackhole");
        Path manifestPath = caseRoot.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(
                manifestPath, StandardCharsets.UTF_8));
        manifest.put("direction", "server-to-client");
        Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        Files.delete(caseRoot.resolve("netem/external-blackhole-affected-rcvi.txt"));
        Path qdisc = caseRoot.resolve("netem/qdisc-timeseries.jsonl");
        List<String> serverRows = new ArrayList<>();
        for (String line : Files.readAllLines(qdisc, StandardCharsets.UTF_8)) {
            JsonNode row = JSON.readTree(line);
            if (row.path("namespace").asText().equals("server")) {
                serverRows.add(line);
            }
        }
        Files.writeString(qdisc, String.join("\n", serverRows) + "\n", StandardCharsets.UTF_8);
    }

    private static void makeAncillaryOneWay(Path campaign) throws Exception {
        makeOneWayPermanentBlackhole(campaign);
        Path planPath = campaign.resolve("campaign-plan.json");
        ObjectNode plan = (ObjectNode) JSON.readTree(Files.readString(planPath, StandardCharsets.UTF_8));
        ((ObjectNode) plan.path("parameters")).put("direction", "server-to-client");
        Files.writeString(planPath, JSON.writeValueAsString(plan) + "\n", StandardCharsets.UTF_8);
    }

    private static void setCampaignRecoveryMode(Path campaign, String recoveryMode) throws Exception {
        Path goalPath = campaign.resolve("goal-manifest.json");
        ObjectNode goal = (ObjectNode) JSON.readTree(Files.readString(goalPath, StandardCharsets.UTF_8));
        goal.put("recoveryMode", recoveryMode);
        Files.writeString(goalPath, JSON.writeValueAsString(goal) + "\n", StandardCharsets.UTF_8);

        Path planPath = campaign.resolve("campaign-plan.json");
        ObjectNode plan = (ObjectNode) JSON.readTree(Files.readString(planPath, StandardCharsets.UTF_8));
        ((ObjectNode) plan.path("parameters")).put("recoveryMode", recoveryMode);
        Files.writeString(planPath, JSON.writeValueAsString(plan) + "\n", StandardCharsets.UTF_8);

        try (var manifests = Files.walk(campaign.resolve("cases"))) {
            for (Path manifestPath : manifests.filter(path -> path.getFileName().toString()
                    .equals("manifest.json")).toList()) {
                ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(
                        manifestPath, StandardCharsets.UTF_8));
                manifest.put("recoveryMode", recoveryMode);
                Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n",
                        StandardCharsets.UTF_8);
            }
        }
        try (var timelines = Files.walk(campaign.resolve("cases"))) {
            for (Path timeline : timelines.filter(path -> path.getFileName().toString()
                    .equals("timeline.jsonl")).toList()) {
                List<String> rows = new ArrayList<>();
                for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
                    ObjectNode row = (ObjectNode) JSON.readTree(line);
                    row.put("recoveryMode", recoveryMode);
                    rows.add(JSON.writeValueAsString(row));
                }
                Files.writeString(timeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
            }
        }
        addMergedRecoveryProvenance(campaign);
    }

    private static void makePermanentBlackhole(Path campaign, Long reclaimAtEpochMillis) throws Exception {
        Path caseRoot = campaign.resolve("cases/01-blackhole");
        Path manifestPath = caseRoot.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        manifest.putNull("recoveryAtEpochMillis");
        Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        Files.deleteIfExists(caseRoot.resolve("netem/external-recovery-server-srvi.txt"));
        Files.deleteIfExists(caseRoot.resolve("netem/external-recovery-affected-rcvi.txt"));
        Path qdisc = caseRoot.resolve("netem/qdisc-timeseries.jsonl");
        List<ObjectNode> qdiscRows = new ArrayList<>();
        ArrayNode blackholeState = null;
        for (String line : Files.readAllLines(qdisc, StandardCharsets.UTF_8)) {
            ObjectNode row = (ObjectNode) JSON.readTree(line);
            if (row.path("epochMillis").asLong() == 103_000L && blackholeState == null) {
                blackholeState = ((ArrayNode) row.path("qdisc")).deepCopy();
            }
            qdiscRows.add(row);
        }
        for (ObjectNode row : qdiscRows) {
            if (row.path("epochMillis").asLong() >= 103_000L) {
                row.set("qdisc", blackholeState.deepCopy());
            }
        }
        List<String> qdiscJson = new ArrayList<>();
        for (ObjectNode row : qdiscRows) {
            qdiscJson.add(JSON.writeValueAsString(row));
        }
        Files.writeString(qdisc, String.join("\n", qdiscJson) + "\n", StandardCharsets.UTF_8);
        Path timeline;
        try (var timelines = Files.walk(caseRoot.resolve("server"))) {
            List<Path> matches = timelines.filter(path -> path.getFileName().toString()
                    .equals("timeline.jsonl")).toList();
            Assertions.assertEquals(1, matches.size());
            timeline = matches.get(0);
        }
        List<ObjectNode> source = new ArrayList<>();
        for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
            source.add((ObjectNode) JSON.readTree(line));
        }
        if (reclaimAtEpochMillis != null
                && source.get(source.size() - 1).path("epochMillis").asLong() < reclaimAtEpochMillis + 1_000L) {
            ObjectNode last = source.get(source.size() - 1);
            long lastEpoch = last.path("epochMillis").asLong();
            long lastMonotonic = last.path("monotonicElapsedMillis").asLong();
            for (long epoch = lastEpoch + 200L; epoch <= reclaimAtEpochMillis + 1_200L; epoch += 200L) {
                ObjectNode extension = last.deepCopy();
                extension.put("sequence", source.size());
                extension.put("epochMillis", epoch);
                extension.put("monotonicElapsedMillis", lastMonotonic + epoch - lastEpoch);
                source.add(extension);
            }
        }
        List<String> rewritten = new ArrayList<>();
        for (ObjectNode row : source) {
            row.putNull("externalRecoveryAtEpochMillis");
            if (reclaimAtEpochMillis != null) {
                ObjectNode cohort = (ObjectNode) row.path("affected");
                if (row.path("epochMillis").asLong() >= reclaimAtEpochMillis) {
                    cohort.put("openPeers", 0);
                    cohort.put("activePeers", 0);
                    cohort.put("disconnectedPeers", 2);
                    cohort.put("disconnectEvents", 2);
                    cohort.put("currentQueuedBytes", 0);
                    cohort.put("currentBytesInFlight", 0);
                } else {
                    cohort.put("openPeers", 2);
                    cohort.put("activePeers", 2);
                    cohort.put("disconnectedPeers", 0);
                    cohort.put("disconnectEvents", 0);
                    cohort.put("currentQueuedBytes", Math.max(100L,
                            cohort.path("currentQueuedBytes").asLong()));
                    cohort.put("currentBytesInFlight", Math.max(100L,
                            cohort.path("currentBytesInFlight").asLong()));
                }
            }
            rewritten.add(JSON.writeValueAsString(row));
        }
        Files.writeString(timeline, String.join("\n", rewritten) + "\n", StandardCharsets.UTF_8);
    }

    private static void copyDenseFixture(Path source, Path destination) throws Exception {
        copyTree(source, destination);
        try (var paths = Files.walk(destination)) {
            for (Path timeline : paths.filter(path -> path.getFileName().toString().equals("timeline.jsonl"))
                    .toList()) {
                densifyTimeline(timeline);
            }
        }
        addMergedRecoveryProvenance(destination);
    }

    private static void addMergedRecoveryProvenance(Path rootPath) throws Exception {
        try (var summaries = Files.walk(rootPath)) {
            for (Path summaryPath : summaries.filter(path -> path.getFileName().toString()
                    .equals("lab-summary.json")).toList()) {
                Path caseRoot = summaryPath.getParent().getParent();
                ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(
                        caseRoot.resolve("manifest.json"), StandardCharsets.UTF_8));
                String recoveryMode = manifest.path("recoveryMode").asText();
                ObjectNode summary = (ObjectNode) JSON.readTree(Files.readString(
                        summaryPath, StandardCharsets.UTF_8));
                ObjectNode aggregate = (ObjectNode) summary.path("aggregate");
                aggregate.put("recoveryModeProvenanceValid", true);
                aggregate.put("recoveryMode", recoveryMode);
                ((ObjectNode) summary.path("server")).put("recoveryMode", recoveryMode);
                for (JsonNode receiver : summary.path("receivers")) {
                    ((ObjectNode) receiver).put("recoveryMode", recoveryMode);
                }
                Files.writeString(summaryPath,
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n",
                        StandardCharsets.UTF_8);
            }
        }
    }

    private static void addResourceSafetyPolicies(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path timeline : paths.filter(path -> path.getFileName().toString().equals("timeline.jsonl"))
                    .toList()) {
                List<String> rewritten = new ArrayList<>();
                for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
                    ObjectNode row = (ObjectNode) JSON.readTree(line);
                    ObjectNode policy = row.putObject("resourceSafetyPolicy");
                    policy.put("maxAggregateQueuedBytes", 402_653_184);
                    policy.put("maxDirectMemoryUsedBytes", 805_306_368);
                    policy.put("enforcementStatus", "server".equals(row.path("role").asText())
                            ? "enforced" : "not-applicable-receiver-worker");
                    rewritten.add(JSON.writeValueAsString(row));
                }
                Files.writeString(timeline, String.join("\n", rewritten) + "\n", StandardCharsets.UTF_8);
            }
        }
    }

    private static void densifyTimeline(Path timeline) throws Exception {
        List<ObjectNode> originals = new ArrayList<>();
        for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
            ObjectNode row = (ObjectNode) JSON.readTree(line);
            ObjectNode policy = row.putObject("resourceSafetyPolicy");
            policy.put("maxAggregateQueuedBytes", 402_653_184);
            policy.put("maxDirectMemoryUsedBytes", 805_306_368);
            boolean server = "server".equals(row.path("role").asText());
            policy.put("enforcementStatus", server ? "enforced" : "not-applicable-receiver-worker");
            if (server && "sample".equals(row.path("recordType").asText())) {
                ObjectNode affected = (ObjectNode) row.path("affected");
                if (!row.has("healthy")) {
                    ObjectNode healthy = affected.deepCopy();
                    healthy.put("name", "healthy");
                    healthy.put("configuredPeers", 8);
                    healthy.put("observedPeers", 8);
                    healthy.put("openPeers", 8);
                    healthy.put("activePeers", 8);
                    healthy.put("disconnectedPeers", 0);
                    healthy.put("currentQueuedBytes", 0);
                    healthy.put("sampledQueuedBytesHighWater", 0);
                    healthy.put("maxPeerQueuedBytes", 0);
                    row.set("healthy", healthy);
                }
                if (!row.has("all")) {
                    ObjectNode all = affected.deepCopy();
                    all.put("name", "all");
                    all.put("configuredPeers", 10);
                    all.put("observedPeers", 10);
                    all.put("openPeers", 10);
                    all.put("activePeers", 10);
                    row.set("all", all);
                }
                ObjectNode eventLoops = ((ObjectNode) row.path("runtime")).putObject("sharedEventLoops");
                eventLoops.put("status", "available");
                eventLoops.put("eventLoopCount", 2);
                eventLoops.put("totalPendingTasks", 3);
                eventLoops.put("maxPendingTasks", 2);
                eventLoops.put("completedSchedulingProbes", 4);
                eventLoops.put("outstandingSchedulingProbes", 0);
                eventLoops.put("latestMaxSchedulingLagMillis", 0.5D);
                eventLoops.put("maxSchedulingLagMillis", 1.25D);
            }
            originals.add(row);
        }
        originals.sort(Comparator.comparingLong(row -> row.path("epochMillis").asLong()));
        long first = originals.get(0).path("epochMillis").asLong();
        long last = originals.get(originals.size() - 1).path("epochMillis").asLong();
        TreeSet<Long> epochs = new TreeSet<>();
        originals.forEach(row -> epochs.add(row.path("epochMillis").asLong()));
        for (long epoch = first; epoch <= last; epoch += 200L) {
            epochs.add(epoch);
        }
        List<String> rows = new ArrayList<>();
        int sourceIndex = 0;
        int sequence = 0;
        for (long epoch : epochs) {
            while (sourceIndex + 1 < originals.size()
                    && originals.get(sourceIndex + 1).path("epochMillis").asLong() <= epoch) {
                sourceIndex++;
            }
            ObjectNode source = originals.get(sourceIndex);
            ObjectNode row = source.deepCopy();
            long sourceEpoch = source.path("epochMillis").asLong();
            row.put("sequence", sequence++);
            row.put("epochMillis", epoch);
            row.put("monotonicElapsedMillis",
                    source.path("monotonicElapsedMillis").asLong() + epoch - sourceEpoch);
            rows.add(JSON.writeValueAsString(row));
        }
        Files.writeString(timeline, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
    }

    private static void copyTree(Path source, Path destination) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
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
            if (Files.exists(path.resolve("benchmark/scripts/analyze-resilience-results.py"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new AssertionError("Unable to locate repository root");
    }

    private static ProcessResult runProcess(Path workingDirectory, Duration timeout, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copyOutput(process.getInputStream(), output),
                "resilience-analysis-test-output-reader");
        reader.start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            reader.join(TimeUnit.SECONDS.toMillis(5));
            Assertions.fail("Timed out running resilience analyzer");
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

    private record ProcessResult(int exitCode, String output) {
    }
}
