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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class EnvironmentInfo {
    public final String javaVersion;
    public final String javaVm;
    public final String osName;
    public final String osVersion;
    public final String osArch;
    public final int processors;
    public final long maxHeapBytes;
    public final String gitRevision;
    public final List<String> garbageCollectors;

    private EnvironmentInfo(String javaVersion, String javaVm, String osName, String osVersion, String osArch,
                            int processors, long maxHeapBytes, String gitRevision, List<String> garbageCollectors) {
        this.javaVersion = javaVersion;
        this.javaVm = javaVm;
        this.osName = osName;
        this.osVersion = osVersion;
        this.osArch = osArch;
        this.processors = processors;
        this.maxHeapBytes = maxHeapBytes;
        this.gitRevision = gitRevision;
        this.garbageCollectors = garbageCollectors;
    }

    public static EnvironmentInfo capture() {
        List<String> collectors = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            collectors.add(bean.getName());
        }
        return new EnvironmentInfo(
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.vm.name", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"),
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory(),
                gitRevision(),
                collectors
        );
    }

    private static String gitRevision() {
        String configuredRevision = System.getProperty("benchmark.gitRevision", "").trim();
        if (!configuredRevision.isEmpty()) {
            return configuredRevision;
        }
        Process process = null;
        try {
            process = new ProcessBuilder("git", "rev-parse", "--short=12", "HEAD").redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unknown";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            return line == null || line.trim().isEmpty() ? "unknown" : line.trim();
        } catch (Exception ignored) {
            return "unknown";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
