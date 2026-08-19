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
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakRecoveryMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class BenchmarkRecoveryModeTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void testRecoveryModeParsingIsExplicitAndLegacyByDefault() {
        BenchmarkConfig defaults = BenchmarkConfig.parse(new String[]{"baseline-bandwidth"});
        Assertions.assertEquals(RakRecoveryMode.LEGACY, defaults.recoveryMode());
        Assertions.assertEquals("legacy", defaults.recoveryModeName());

        BenchmarkConfig bounded = BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth", "--recovery-mode", "bounded"
        });
        Assertions.assertEquals(RakRecoveryMode.BOUNDED, bounded.recoveryMode());
        Assertions.assertEquals("bounded", bounded.recoveryModeName());

        BenchmarkConfig uppercase = BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth", "--recovery-mode=LEGACY"
        });
        Assertions.assertEquals(RakRecoveryMode.LEGACY, uppercase.recoveryMode());

        IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
                () -> BenchmarkConfig.parse(new String[]{
                        "baseline-bandwidth", "--recovery-mode", "experimental"
                }));
        Assertions.assertEquals("--recovery-mode must be one of: legacy, bounded", error.getMessage());
    }

    @Test
    public void testRecoveryModeIsAppliedToServerParentAndClientBootstraps() {
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth", "--recovery-mode", "bounded"
        });
        ServerBootstrap server = new ServerBootstrap();
        Bootstrap client = new Bootstrap();

        RakNetBenchmarkRunner.configureServerRecoveryMode(server, config);
        RakNetBenchmarkRunner.configureClientRecoveryMode(client, config);

        Assertions.assertEquals(RakRecoveryMode.BOUNDED,
                server.config().options().get(RakChannelOption.RAK_RECOVERY_MODE));
        Assertions.assertFalse(server.config().childOptions().containsKey(RakChannelOption.RAK_RECOVERY_MODE),
                "server recovery mode must be inherited from the RakNet server parent option");
        Assertions.assertEquals(RakRecoveryMode.BOUNDED,
                client.config().options().get(RakChannelOption.RAK_RECOVERY_MODE));
    }

    @Test
    public void testRecoveryModeIsDurableAcrossBenchmarkArtifacts() throws Exception {
        Path output = Files.createTempDirectory("raknet-recovery-mode-output");
        BenchmarkConfig config = BenchmarkConfig.parse(new String[]{
                "baseline-bandwidth",
                "--recovery-mode", "bounded",
                "--out", output.toString(),
                "--run-id", "bounded-provenance",
                "--iterations", "1",
                "--duration", "1s",
                "--warmup", "0ms"
        });
        BenchmarkRunResult result = new BenchmarkRunResult(
                config, EnvironmentInfo.capture(), 1_000L, 1_000_000_000L);
        BenchmarkTimelineRecorder recorder = new BenchmarkTimelineRecorder(
                result,
                "bounded-provenance",
                0,
                0,
                Collections::emptyList,
                BenchmarkTimelineRecorder.Capabilities.SERVER,
                false
        );
        recorder.captureAt(1_250L, 1_250_000_000L);
        result.add(new BenchmarkIterationResult(
                "bounded-provenance",
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
        Assertions.assertEquals("bounded", summary.path("recoveryMode").asText());

        JsonNode timeline = JSON.readTree(Files.readAllLines(
                directory.resolve("timeline.jsonl"), StandardCharsets.UTF_8).get(0));
        Assertions.assertEquals("bounded", timeline.path("recoveryMode").asText());

        List<String> timeseries = Files.readAllLines(
                directory.resolve("timeseries.csv"), StandardCharsets.UTF_8);
        Assertions.assertTrue(timeseries.get(0).contains("recovery_mode"));
        Assertions.assertTrue(timeseries.get(1).contains("bounded"));

        JsonNode capacity = JSON.readTree(Files.readAllLines(
                directory.resolve("bandwidth-capacity.jsonl"), StandardCharsets.UTF_8).get(0));
        Assertions.assertEquals("bounded", capacity.path("recoveryMode").asText());
        List<String> capacityCsv = Files.readAllLines(
                directory.resolve("bandwidth-capacity.csv"), StandardCharsets.UTF_8);
        Assertions.assertTrue(capacityCsv.get(0).contains("recovery_mode"));
        Assertions.assertTrue(capacityCsv.get(1).contains("bounded"));

        Assertions.assertTrue(Files.readString(
                directory.resolve("report.md"), StandardCharsets.UTF_8)
                .contains("- Recovery mode: `bounded`"));
        Assertions.assertTrue(Files.readString(
                directory.resolve("bandwidth-capacity.md"), StandardCharsets.UTF_8)
                .contains("- Recovery mode: `bounded`"));
    }
}
