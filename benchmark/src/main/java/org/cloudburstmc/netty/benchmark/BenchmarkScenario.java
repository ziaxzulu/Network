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

public enum BenchmarkScenario {
    BASELINE_BANDWIDTH("baseline-bandwidth"),
    BANDWIDTH_LATENCY_CURVE("bandwidth-latency-curve"),
    MULTI_CLIENT_FANOUT("multi-client-fanout"),
    FAIRNESS("fairness"),
    DISAPPEARING_CLIENTS("disappearing-clients"),
    MATRIX("matrix"),
    RECEIVER_WORKER("receiver-worker"),
    SERVER_WORKER("server-worker");

    private final String cliName;

    BenchmarkScenario(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return this.cliName;
    }

    public static BenchmarkScenario parse(String value) {
        for (BenchmarkScenario scenario : values()) {
            if (scenario.cliName.equalsIgnoreCase(value) || scenario.name().equalsIgnoreCase(value.replace('-', '_'))) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("Unknown benchmark scenario: " + value);
    }
}
