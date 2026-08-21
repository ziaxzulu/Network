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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ThroughputDistribution(
        double minMbps,
        double p50Mbps,
        double p95Mbps,
        double p99Mbps,
        double maxMbps
) {
    public static ThroughputDistribution fromBytes(List<Long> bytesPerPeer, long elapsedMillis) {
        if (bytesPerPeer.isEmpty()) {
            return empty();
        }

        List<Double> mbps = new ArrayList<>(bytesPerPeer.size());
        for (Long bytes : bytesPerPeer) {
            long value = bytes == null ? 0L : Math.max(0L, bytes);
            mbps.add(BenchmarkMath.megabitsPerSecond(value, elapsedMillis));
        }
        Collections.sort(mbps);
        return new ThroughputDistribution(
                percentile(mbps, 0.0D),
                percentile(mbps, 50.0D),
                percentile(mbps, 95.0D),
                percentile(mbps, 99.0D),
                percentile(mbps, 100.0D)
        );
    }

    public static ThroughputDistribution empty() {
        return new ThroughputDistribution(0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0.0D;
        }
        double bounded = Math.max(0.0D, Math.min(100.0D, percentile));
        int index = (int) Math.ceil((bounded / 100.0D) * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(sortedValues.size() - 1, index));
        return sortedValues.get(index);
    }
}
