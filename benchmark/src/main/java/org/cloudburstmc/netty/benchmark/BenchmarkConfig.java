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

import org.cloudburstmc.netty.channel.raknet.RakReliability;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BenchmarkConfig {
    public static final int DEFAULT_PORT = 19132;

    private BenchmarkScenario scenario = BenchmarkScenario.BASELINE_BANDWIDTH;
    private BenchmarkRole role = BenchmarkRole.LOCAL;
    private String host = "127.0.0.1";
    private String bindHost = "0.0.0.0";
    private int port = DEFAULT_PORT;
    private int clients = 1;
    private int impairedClients;
    private int disappearingClients;
    private int payloadSize = 512;
    private long warmupMillis = 5000;
    private long durationMillis = 10000;
    private long disappearAfterMillis = -1L;
    private DisappearanceMode disappearanceMode = DisappearanceMode.CLOSE;
    private long startDelayMillis = 3000;
    private int iterations = 3;
    private int workers = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private int packetLimit;
    private int globalPacketLimit;
    private long impairmentLatencyMillis;
    private long impairmentJitterMillis;
    private double impairmentLossPercent;
    private long messageRate;
    private double rateMbps;
    private double perClientRateMbps = -1.0D;
    private long probeIntervalMillis = 100;
    private RakReliability reliability = RakReliability.RELIABLE_ORDERED;
    private File outputRoot = new File("build/benchmark-results");
    private String runId;
    private List<Integer> payloadSizes = Arrays.asList(64, 512, 1200);
    private List<Integer> batchPayloadSizes = Arrays.asList(128, 512, 1200);
    private List<Double> ratesMbps = Arrays.asList(100.0D, 500.0D, 1000.0D, 0.0D);
    private List<RakReliability> reliabilities = Arrays.asList(RakReliability.RELIABLE_ORDERED);
    private long batchIntervalMillis = 20L;
    private int logicalPacketsPerBatch = 8;
    private int batchGroups = 1;

    private BenchmarkConfig() {
    }

    public static BenchmarkConfig parse(String[] args) {
        BenchmarkConfig config = new BenchmarkConfig();
        List<String> tokens = new ArrayList<>(Arrays.asList(args));
        if (!tokens.isEmpty() && !tokens.get(0).startsWith("--")) {
            config.scenario = BenchmarkScenario.parse(tokens.remove(0));
        }

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + token);
            }

            String key;
            String value;
            int equals = token.indexOf('=');
            if (equals > 0) {
                key = token.substring(2, equals);
                value = token.substring(equals + 1);
            } else {
                key = token.substring(2);
                if ("help".equals(key)) {
                    throw new HelpRequestedException();
                }
                if (i + 1 >= tokens.size() || tokens.get(i + 1).startsWith("--")) {
                    value = "true";
                } else {
                    value = tokens.get(++i);
                }
            }
            config.applyOption(key, value);
        }

        config.validate();
        return config;
    }

    private void applyOption(String key, String value) {
        if ("scenario".equals(key)) {
            this.scenario = BenchmarkScenario.parse(value);
        } else if ("role".equals(key)) {
            this.role = BenchmarkRole.parse(value);
        } else if ("host".equals(key)) {
            this.host = value;
        } else if ("bind-host".equals(key)) {
            this.bindHost = value;
        } else if ("port".equals(key)) {
            this.port = parsePositiveInt(key, value);
        } else if ("clients".equals(key)) {
            this.clients = parsePositiveInt(key, value);
        } else if ("impaired-clients".equals(key)) {
            this.impairedClients = parseNonNegativeInt(key, value);
        } else if ("disappearing-clients".equals(key) || "disconnect-clients".equals(key)) {
            this.disappearingClients = parseNonNegativeInt(key, value);
        } else if ("payload-size".equals(key)) {
            this.payloadSize = parsePositiveInt(key, value);
        } else if ("payload-sizes".equals(key)) {
            this.payloadSizes = parseIntegerList(key, value);
        } else if ("batch-payload-sizes".equals(key)) {
            this.batchPayloadSizes = parseIntegerList(key, value);
        } else if ("warmup".equals(key)) {
            this.warmupMillis = parseDurationMillis(value);
        } else if ("duration".equals(key)) {
            this.durationMillis = parseDurationMillis(value);
        } else if ("disappear-after".equals(key) || "disconnect-after".equals(key)) {
            this.disappearAfterMillis = parseDurationMillis(value);
        } else if ("disappear-mode".equals(key) || "disappearance-mode".equals(key) || "disconnect-mode".equals(key)) {
            this.disappearanceMode = DisappearanceMode.parse(value);
        } else if ("start-delay".equals(key)) {
            this.startDelayMillis = parseDurationMillis(value);
        } else if ("iterations".equals(key)) {
            this.iterations = parsePositiveInt(key, value);
        } else if ("workers".equals(key)) {
            this.workers = parsePositiveInt(key, value);
        } else if ("packet-limit".equals(key)) {
            this.packetLimit = parsePositiveInt(key, value);
        } else if ("global-packet-limit".equals(key)) {
            this.globalPacketLimit = parsePositiveInt(key, value);
        } else if ("impairment-latency".equals(key) || "impaired-latency".equals(key)) {
            this.impairmentLatencyMillis = parseDurationMillis(value);
        } else if ("impairment-jitter".equals(key) || "impaired-jitter".equals(key)) {
            this.impairmentJitterMillis = parseDurationMillis(value);
        } else if ("impairment-loss".equals(key) || "impaired-loss".equals(key)) {
            this.impairmentLossPercent = parsePercent(key, value);
        } else if ("message-rate".equals(key)) {
            this.messageRate = parseNonNegativeLong(key, value);
        } else if ("rate-mbps".equals(key) || "target-mbps".equals(key)) {
            this.rateMbps = parseRate(value);
        } else if ("per-client-mbps".equals(key) || "target-client-mbps".equals(key)) {
            this.perClientRateMbps = parseRate(value);
        } else if ("target-gbps".equals(key)) {
            this.rateMbps = parseRate(value) * 1000.0D;
        } else if ("rates-mbps".equals(key)) {
            this.ratesMbps = parseRateList(key, value);
        } else if ("probe-interval".equals(key)) {
            this.probeIntervalMillis = parseDurationMillis(value);
        } else if ("batch-interval".equals(key) || "flush-interval".equals(key)) {
            this.batchIntervalMillis = parseDurationMillis(value);
        } else if ("logical-packets-per-batch".equals(key) || "batch-logical-packets".equals(key)) {
            this.logicalPacketsPerBatch = parsePositiveInt(key, value);
        } else if ("batch-groups".equals(key) || "group-count".equals(key)) {
            this.batchGroups = parsePositiveInt(key, value);
        } else if ("reliability".equals(key)) {
            this.reliability = parseReliability(value);
        } else if ("reliabilities".equals(key)) {
            this.reliabilities = parseReliabilityList(value);
        } else if ("out".equals(key)) {
            this.outputRoot = new File(value);
        } else if ("run-id".equals(key)) {
            this.runId = value;
        } else {
            throw new IllegalArgumentException("Unknown option: --" + key);
        }
    }

    private void validate() {
        if (this.impairedClients > this.clients) {
            throw new IllegalArgumentException("--impaired-clients cannot exceed --clients");
        }
        if (this.disappearingClients > this.clients) {
            throw new IllegalArgumentException("--disappearing-clients cannot exceed --clients");
        }
        if (this.payloadSize < BenchmarkPayload.MIN_BULK_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("--payload-size must be at least " + BenchmarkPayload.MIN_BULK_PAYLOAD_SIZE + " bytes");
        }
        for (Integer size : this.payloadSizes) {
            if (size < BenchmarkPayload.MIN_BULK_PAYLOAD_SIZE) {
                throw new IllegalArgumentException("--payload-sizes values must be at least " + BenchmarkPayload.MIN_BULK_PAYLOAD_SIZE + " bytes");
            }
        }
        int minBatchPayloadSize = BenchmarkPayload.minBatchPayloadSize(this.logicalPacketsPerBatch);
        for (Integer size : this.batchPayloadSizes) {
            if (size < minBatchPayloadSize) {
                throw new IllegalArgumentException("--batch-payload-sizes values must be at least " + minBatchPayloadSize
                        + " bytes for " + this.logicalPacketsPerBatch + " logical packets");
            }
        }
        if (this.probeIntervalMillis <= 0) {
            throw new IllegalArgumentException("--probe-interval must be positive");
        }
        if (this.batchIntervalMillis <= 0) {
            throw new IllegalArgumentException("--batch-interval must be positive");
        }
        if (this.disappearingClients > 0 && this.disappearAfterMillis() >= this.durationMillis) {
            throw new IllegalArgumentException("--disappear-after must be less than --duration");
        }
        if (this.impairmentJitterMillis > 0L && this.impairmentLatencyMillis == 0L) {
            throw new IllegalArgumentException("--impairment-jitter requires --impairment-latency");
        }
    }

    public long effectiveMessageRate(int payloadBytes, double overrideRateMbps) {
        if (this.messageRate > 0) {
            return this.messageRate;
        }
        return messageRateFromMbps(payloadBytes, overrideRateMbps);
    }

    public long effectiveMessageRate(int payloadBytes, double overrideRateMbps, int clients) {
        if (this.messageRate > 0) {
            return this.messageRate;
        }
        return messageRateFromMbps(payloadBytes, effectiveTargetMbps(overrideRateMbps, clients));
    }

    private static long messageRateFromMbps(int payloadBytes, double mbps) {
        if (mbps <= 0.0D) {
            return 0;
        }
        double bytesPerSecond = (mbps * 1_000_000.0D) / 8.0D;
        return Math.max(1L, (long) (bytesPerSecond / Math.max(1, payloadBytes)));
    }

    public double effectiveTargetMbps(double overrideRateMbps, int clients) {
        if (this.perClientRateMbps >= 0.0D) {
            return this.perClientRateMbps * Math.max(1, clients);
        }
        return overrideRateMbps >= 0.0D ? overrideRateMbps : this.rateMbps;
    }

    public double effectiveTargetClientMbps(double aggregateMbps, int clients) {
        if (this.perClientRateMbps >= 0.0D) {
            return this.perClientRateMbps;
        }
        if (aggregateMbps <= 0.0D) {
            return 0.0D;
        }
        return aggregateMbps / Math.max(1, clients);
    }

    public BenchmarkScenario scenario() {
        return this.scenario;
    }

    public BenchmarkRole role() {
        return this.role;
    }

    public String host() {
        return this.host;
    }

    public String bindHost() {
        return this.bindHost;
    }

    public int port() {
        return this.port;
    }

    public int clients() {
        return this.clients;
    }

    public int impairedClients() {
        return this.impairedClients;
    }

    public int disappearingClients() {
        return this.disappearingClients;
    }

    public int payloadSize() {
        return this.payloadSize;
    }

    public long warmupMillis() {
        return this.warmupMillis;
    }

    public long durationMillis() {
        return this.durationMillis;
    }

    public long disappearAfterMillis() {
        if (this.disappearAfterMillis >= 0L) {
            return this.disappearAfterMillis;
        }
        return Math.max(1L, this.durationMillis / 2L);
    }

    public DisappearanceMode disappearanceMode() {
        return this.disappearanceMode;
    }

    public long startDelayMillis() {
        return this.startDelayMillis;
    }

    public int iterations() {
        return this.iterations;
    }

    public int workers() {
        return this.workers;
    }

    public int packetLimit() {
        return this.packetLimit;
    }

    public int globalPacketLimit() {
        return this.globalPacketLimit;
    }

    public long impairmentLatencyMillis() {
        return this.impairmentLatencyMillis;
    }

    public long impairmentJitterMillis() {
        return this.impairmentJitterMillis;
    }

    public double impairmentLossPercent() {
        return this.impairmentLossPercent;
    }

    public long messageRate() {
        return this.messageRate;
    }

    public double rateMbps() {
        return this.rateMbps;
    }

    public double perClientRateMbps() {
        return this.perClientRateMbps;
    }

    public long probeIntervalMillis() {
        return this.probeIntervalMillis;
    }

    public RakReliability reliability() {
        return this.reliability;
    }

    public File outputRoot() {
        return this.outputRoot;
    }

    public String runId() {
        return this.runId;
    }

    public List<Integer> payloadSizes() {
        return Collections.unmodifiableList(this.payloadSizes);
    }

    public List<Integer> batchPayloadSizes() {
        return Collections.unmodifiableList(this.batchPayloadSizes);
    }

    public List<Double> ratesMbps() {
        return Collections.unmodifiableList(this.ratesMbps);
    }

    public List<RakReliability> reliabilities() {
        return Collections.unmodifiableList(this.reliabilities);
    }

    public long batchIntervalMillis() {
        return this.batchIntervalMillis;
    }

    public int logicalPacketsPerBatch() {
        return this.logicalPacketsPerBatch;
    }

    public int batchGroups() {
        return this.batchGroups;
    }

    public static long parseDurationMillis(String value) {
        String trimmed = value.trim().toLowerCase();
        if (trimmed.endsWith("ms")) {
            return parseNonNegativeLong("duration", trimmed.substring(0, trimmed.length() - 2));
        }
        if (trimmed.endsWith("s")) {
            return parseNonNegativeLong("duration", trimmed.substring(0, trimmed.length() - 1)) * 1000L;
        }
        if (trimmed.endsWith("m")) {
            return parseNonNegativeLong("duration", trimmed.substring(0, trimmed.length() - 1)) * 60_000L;
        }
        return parseNonNegativeLong("duration", trimmed);
    }

    private static int parsePositiveInt(String key, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("--" + key + " must be positive");
        }
        return parsed;
    }

    private static int parseNonNegativeInt(String key, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("--" + key + " must be non-negative");
        }
        return parsed;
    }

    private static long parseNonNegativeLong(String key, String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("--" + key + " must be non-negative");
        }
        return parsed;
    }

    private static double parseRate(String value) {
        if ("unlimited".equalsIgnoreCase(value)) {
            return 0.0D;
        }
        double parsed = Double.parseDouble(value);
        if (parsed < 0.0D) {
            throw new IllegalArgumentException("rate must be non-negative");
        }
        return parsed;
    }

    private static double parsePercent(String key, String value) {
        String normalized = value.endsWith("%") ? value.substring(0, value.length() - 1) : value;
        double parsed = Double.parseDouble(normalized);
        if (parsed < 0.0D || parsed > 100.0D) {
            throw new IllegalArgumentException("--" + key + " must be between 0 and 100");
        }
        return parsed;
    }

    private static List<Integer> parseIntegerList(String key, String value) {
        String[] parts = value.split(",");
        List<Integer> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(parsePositiveInt(key, part.trim()));
        }
        return result;
    }

    private static List<Double> parseRateList(String key, String value) {
        String[] parts = value.split(",");
        List<Double> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(parseRate(part.trim()));
        }
        return result;
    }

    private static RakReliability parseReliability(String value) {
        return RakReliability.valueOf(value.trim().replace('-', '_').toUpperCase());
    }

    private static List<RakReliability> parseReliabilityList(String value) {
        String[] parts = value.split(",");
        List<RakReliability> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(parseReliability(part));
        }
        return result;
    }

    public static final class HelpRequestedException extends RuntimeException {
    }
}
