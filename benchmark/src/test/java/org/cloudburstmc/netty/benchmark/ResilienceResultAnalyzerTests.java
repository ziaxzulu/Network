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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

public class ResilienceResultAnalyzerTests {
    private static final ObjectMapper JSON = new ObjectMapper();
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
                .anyMatch(name -> name.contains("nackRetransmittedDatagramsDelta")));
        Assertions.assertTrue(comparable.stream().map(component -> component.path("name").asText())
                .anyMatch(name -> name.contains("timeoutRetransmittedDatagramsDelta")));
        Assertions.assertTrue(comparable.stream().map(component -> component.path("name").asText())
                .anyMatch(name -> name.startsWith("initial-netem.")));
        Assertions.assertTrue(comparable.stream().map(component -> component.path("name").asText())
                .anyMatch(name -> name.startsWith("external-blackhole.")));
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
                .contains("campaign recovery mode disagrees with manifest"));
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
        Assertions.assertFalse(report.path("comparison").path("invalidCandidateCases").isEmpty());
    }

    @Test
    public void comparisonRejectsCopiedExecutionIdentityAndConfigurationDrift() throws Exception {
        Path baseline = Files.createTempDirectory("raknet-resilience-identity-baseline");
        buildFullCampaignSet(baseline);
        Path copiedIdentity = Files.createTempDirectory("raknet-resilience-copied-identity");
        buildFullCampaignSet(copiedIdentity, "bounded");
        scaleCandidatePressure(copiedIdentity, 10);
        ObjectNode firstPlan = (ObjectNode) JSON.readTree(Files.readString(
                copiedIdentity.resolve("campaign-1/campaign-plan.json"), StandardCharsets.UTF_8));
        Path secondPlanPath = copiedIdentity.resolve("campaign-2/campaign-plan.json");
        ObjectNode secondPlan = (ObjectNode) JSON.readTree(Files.readString(secondPlanPath, StandardCharsets.UTF_8));
        secondPlan.put("generatedAt", firstPlan.path("generatedAt").asText());
        secondPlan.put("outputRoot", firstPlan.path("outputRoot").asText());
        Files.writeString(secondPlanPath, JSON.writeValueAsString(secondPlan) + "\n", StandardCharsets.UTF_8);
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
        return runProcess(root, Duration.ofSeconds(10),
                "python3", analyzer.toString(),
                "--baseline-root", baseline.toString(),
                "--candidate-root", candidate.toString(),
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

    private static void buildFullCampaignSet(Path output) throws Exception {
        buildFullCampaignSet(output, "legacy");
    }

    private static void buildFullCampaignSet(Path output, String recoveryMode) throws Exception {
        for (int campaignIndex = 1; campaignIndex <= 2; campaignIndex++) {
            Path campaign = output.resolve("campaign-" + campaignIndex);
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

        Path timeline = caseRoot.resolve("server/netns-blackhole-10c/timeline.jsonl");
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

    private static void makePermanentBlackhole(Path campaign, Long reclaimAtEpochMillis) throws Exception {
        Path caseRoot = campaign.resolve("cases/01-blackhole");
        Path manifestPath = caseRoot.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) JSON.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        manifest.putNull("recoveryAtEpochMillis");
        Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        Files.delete(caseRoot.resolve("netem/external-recovery-server-srvi.txt"));
        Files.delete(caseRoot.resolve("netem/external-recovery-affected-rcvi.txt"));
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
        Path timeline = caseRoot.resolve("server/netns-blackhole-10c/timeline.jsonl");
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
            if (reclaimAtEpochMillis != null
                    && row.path("epochMillis").asLong() >= reclaimAtEpochMillis) {
                ObjectNode cohort = (ObjectNode) row.path("affected");
                cohort.put("openPeers", 0);
                cohort.put("activePeers", 0);
                cohort.put("disconnectedPeers", 2);
                cohort.put("disconnectEvents", 2);
                cohort.put("currentQueuedBytes", 0);
                cohort.put("currentBytesInFlight", 0);
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
    }

    private static void densifyTimeline(Path timeline) throws Exception {
        List<ObjectNode> originals = new ArrayList<>();
        for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
            originals.add((ObjectNode) JSON.readTree(line));
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
