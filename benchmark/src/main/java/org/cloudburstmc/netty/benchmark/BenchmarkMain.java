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

import java.io.File;

public final class BenchmarkMain {
    private BenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        BenchmarkConfig config;
        try {
            config = BenchmarkConfig.parse(args);
        } catch (BenchmarkConfig.HelpRequestedException ignored) {
            printUsage();
            return;
        }

        BenchmarkRunResult result = new RakNetBenchmarkRunner().run(config);
        File directory = new BenchmarkResultWriter().write(result);
        System.out.println("Benchmark artifacts written to " + directory.getAbsolutePath());
    }

    private static void printUsage() {
        System.out.println("Usage: raknetBenchmark <scenario> [options]");
        System.out.println();
        System.out.println("Scenarios:");
        for (BenchmarkScenario scenario : BenchmarkScenario.values()) {
            System.out.println("  " + scenario.cliName());
        }
        System.out.println();
        System.out.println("Common options:");
        System.out.println("  --role local|server|client");
        System.out.println("  --host 127.0.0.1 --bind-host 0.0.0.0 --port 19132");
        System.out.println("  --clients 1 --impaired-clients 0 --disappearing-clients 0 --workers <n>");
        System.out.println("  --payload-size 512 --payload-sizes 64,512,1200");
        System.out.println("  --reliability reliable_ordered|reliable|unreliable");
        System.out.println("  --rate-mbps 1000 --per-client-mbps 5 --target-gbps 1 --rates-mbps 100,500,1000,unlimited");
        System.out.println("  --warmup 5s --duration 10s --iterations 3 --probe-interval 100ms --disappear-after 5s");
        System.out.println("  --disappear-mode close|stop-reading");
        System.out.println("  --out build/benchmark-results --run-id my-run");
    }
}
