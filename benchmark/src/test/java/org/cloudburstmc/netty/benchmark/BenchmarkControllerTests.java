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
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class BenchmarkControllerTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void controllerIsFixedAndRecoveryModeOptionIsRejected() {
        BenchmarkConfig defaults = BenchmarkConfig.parse(new String[]{"baseline-bandwidth"});
        Assertions.assertEquals("model_based", defaults.recoveryModeName());

        IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
                () -> BenchmarkConfig.parse(new String[]{
                        "baseline-bandwidth", "--recovery-mode", "legacy"
                }));
        Assertions.assertEquals("Unknown option: --recovery-mode", error.getMessage());
    }

    @Test
    public void fixedControllerAndReliableOrderedProbeAreDurableAcrossArtifacts() throws Exception {
        Path output = Files.createTempDirectory("raknet-controller-output");
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth",
                "--out", output.toString(),
                "--run-id", "fixed-controller-provenance",
                "--iterations", "1",
                "--duration", "1s",
                "--warmup", "0ms"
        });
        BenchmarkRunResult result = new BenchmarkRunResult(
                config, EnvironmentInfo.capture(), 1_000L, 1_000_000_000L);
        BenchmarkTimelineRecorder recorder = new BenchmarkTimelineRecorder(
                result,
                "fixed-controller-provenance",
                0,
                0,
                Collections::emptyList,
                BenchmarkTimelineRecorder.Capabilities.SERVER,
                false
        );
        recorder.captureAt(1_250L, 1_250_000_000L);
        result.add(new BenchmarkIterationResult(
                "fixed-controller-provenance",
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
        Assertions.assertEquals("model_based", summary.path("recoveryMode").asText());
        Assertions.assertEquals("RELIABLE_ORDERED", summary.path("probeReliability").asText());

        JsonNode timeline = JSON.readTree(Files.readAllLines(
                directory.resolve("timeline.jsonl"), StandardCharsets.UTF_8).get(0));
        Assertions.assertEquals("model_based", timeline.path("recoveryMode").asText());
        Assertions.assertEquals(2, timeline.path("schemaVersion").asInt());
        Assertions.assertEquals("available",
                timeline.path("metricAvailability").path("congestionModelState").asText());

        List<String> timeseries = Files.readAllLines(
                directory.resolve("timeseries.csv"), StandardCharsets.UTF_8);
        Assertions.assertTrue(timeseries.get(0).contains("recovery_mode"));
        Assertions.assertTrue(timeseries.get(1).contains("model_based"));

        JsonNode capacity = JSON.readTree(Files.readAllLines(
                directory.resolve("bandwidth-capacity.jsonl"), StandardCharsets.UTF_8).get(0));
        Assertions.assertEquals("model_based", capacity.path("recoveryMode").asText());
        Assertions.assertEquals("RELIABLE_ORDERED", capacity.path("probeReliability").asText());
        List<String> capacityCsv = Files.readAllLines(
                directory.resolve("bandwidth-capacity.csv"), StandardCharsets.UTF_8);
        Assertions.assertTrue(capacityCsv.get(0).contains("recovery_mode"));
        Assertions.assertTrue(capacityCsv.get(1).contains("model_based"));
    }
}
