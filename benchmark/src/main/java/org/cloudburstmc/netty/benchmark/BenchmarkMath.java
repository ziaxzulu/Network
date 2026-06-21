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

import java.util.List;

public final class BenchmarkMath {
    private BenchmarkMath() {
    }

    public static double bytesPerSecond(long bytes, long elapsedMillis) {
        if (elapsedMillis <= 0) {
            return 0.0D;
        }
        return bytes * 1000.0D / elapsedMillis;
    }

    public static double messagesPerSecond(long messages, long elapsedMillis) {
        if (elapsedMillis <= 0) {
            return 0.0D;
        }
        return messages * 1000.0D / elapsedMillis;
    }

    public static double gigabitsPerSecond(long bytes, long elapsedMillis) {
        return bytesPerSecond(bytes, elapsedMillis) * 8.0D / 1_000_000_000.0D;
    }

    public static double jainFairness(List<Long> values) {
        if (values.isEmpty()) {
            return 1.0D;
        }
        double sum = 0.0D;
        double sumSquares = 0.0D;
        int active = 0;
        for (Long value : values) {
            long current = value == null ? 0L : value;
            if (current < 0L) {
                current = 0L;
            }
            sum += current;
            sumSquares += (double) current * (double) current;
            active++;
        }
        if (sumSquares == 0.0D) {
            return 1.0D;
        }
        return (sum * sum) / (active * sumSquares);
    }
}
