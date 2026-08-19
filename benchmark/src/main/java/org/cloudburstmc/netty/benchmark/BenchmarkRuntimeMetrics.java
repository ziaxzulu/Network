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

import io.netty.buffer.PooledByteBufAllocator;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BenchmarkRuntimeMetrics {
    private static final Path PROC_SELF_STATUS = Path.of("/proc/self/status");

    private BenchmarkRuntimeMetrics() {
    }

    static BenchmarkTimeline.RuntimeMetrics capture() {
        Runtime runtime = Runtime.getRuntime();
        long heapCommitted = runtime.totalMemory();
        long heapUsed = heapCommitted - runtime.freeMemory();
        long heapMax = runtime.maxMemory();

        Long directCount = null;
        Long directMemoryUsed = null;
        Long directCapacity = null;
        try {
            for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if ("direct".equalsIgnoreCase(pool.getName())) {
                    long count = pool.getCount();
                    long memoryUsed = pool.getMemoryUsed();
                    long totalCapacity = pool.getTotalCapacity();
                    directCount = count >= 0L ? count : null;
                    directMemoryUsed = memoryUsed >= 0L ? memoryUsed : null;
                    directCapacity = totalCapacity >= 0L ? totalCapacity : null;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // Reported as unavailable below.
        }

        Long nettyDirectMemory = null;
        try {
            long value = PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory();
            if (value >= 0L) {
                nettyDirectMemory = value;
            }
        } catch (RuntimeException ignored) {
            // Reported as unavailable below.
        }

        Long processCpuTimeNanos = null;
        Double processCpuLoad = null;
        java.lang.management.OperatingSystemMXBean operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended) {
            long cpuTime = extended.getProcessCpuTime();
            double cpuLoad = extended.getProcessCpuLoad();
            if (cpuTime >= 0L) {
                processCpuTimeNanos = cpuTime;
            }
            if (cpuLoad >= 0.0D) {
                processCpuLoad = cpuLoad;
            }
        }

        Long residentSetSize = residentSetSize();
        List<String> unavailable = new ArrayList<>();
        if (directCount == null) {
            unavailable.add("runtime.directBufferPoolCount");
        }
        if (directMemoryUsed == null) {
            unavailable.add("runtime.directBufferPoolMemoryUsedBytes");
        }
        if (directCapacity == null) {
            unavailable.add("runtime.directBufferPoolTotalCapacityBytes");
        }
        if (nettyDirectMemory == null) {
            unavailable.add("runtime.nettyPooledDirectMemoryUsedBytes");
        }
        if (residentSetSize == null) {
            unavailable.add("runtime.residentSetSizeBytes");
        }
        if (processCpuTimeNanos == null) {
            unavailable.add("runtime.processCpuTimeNanos");
        }
        if (processCpuLoad == null) {
            unavailable.add("runtime.processCpuLoad");
        }

        return new BenchmarkTimeline.RuntimeMetrics(
                heapUsed,
                heapCommitted,
                heapMax,
                directCount,
                directMemoryUsed,
                directCapacity,
                nettyDirectMemory,
                residentSetSize,
                processCpuTimeNanos,
                processCpuLoad,
                List.copyOf(unavailable)
        );
    }

    private static Long residentSetSize() {
        if (!Files.isReadable(PROC_SELF_STATUS)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(PROC_SELF_STATUS, StandardCharsets.US_ASCII)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.substring("VmRSS:".length()).trim().split("\\s+");
                    if (parts.length > 0) {
                        return Long.parseLong(parts[0]) * 1024L;
                    }
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            return null;
        }
        return null;
    }
}
