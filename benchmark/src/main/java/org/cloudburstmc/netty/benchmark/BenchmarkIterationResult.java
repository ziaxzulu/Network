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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BenchmarkIterationResult {
    public final String name;
    public final int iteration;
    public final int clients;
    public final int payloadSize;
    public final RakReliability reliability;
    public final double targetMbps;
    public final double targetClientMbps;
    public final DisappearanceMode disappearanceMode;
    public final boolean batched;
    public final long batchIntervalMillis;
    public final int logicalPacketsPerBatch;
    public final int batchGroups;
    public final long elapsedMillis;
    public final long bulkSentMessages;
    public final long bulkSentBytes;
    public final long logicalPacketsSent;
    public final long bulkReceivedMessages;
    public final long bulkReceivedBytes;
    public final long logicalPacketsReceived;
    public final long probesSent;
    public final long probesAcked;
    public final long serverBytesOut;
    public final long serverDatagramsOut;
    public final long healthyServerBytesOut;
    public final long affectedServerBytesOut;
    public final long healthyServerDatagramsOut;
    public final long affectedServerDatagramsOut;
    public final long staleDatagrams;
    public final long nackIn;
    public final long nackOut;
    public final long maxQueuedBytes;
    public final long disconnects;
    public final long blackholedDatagramsIn;
    public final long blackholedDatagramsOut;
    public final double deliveredGbps;
    public final double healthyDeliveredGbps;
    public final double affectedDeliveredGbps;
    public final double deliveredMessagesPerSecond;
    public final double deliveredLogicalPacketsPerSecond;
    public final double offeredGbps;
    public final double serverDatagramsOutPerSecond;
    public final double staleDatagramsPerSecond;
    public final double nackInPerSecond;
    public final double nackOutPerSecond;
    public final double sentToDeliveredBytesRatio;
    public final double healthySentToDeliveredBytesRatio;
    public final double affectedSentToDeliveredBytesRatio;
    public final int affectedClients;
    public final double fairnessIndex;
    public final double healthyFairnessIndex;
    public final double affectedFairnessIndex;
    public final ThroughputDistribution perClientThroughput;
    public final ThroughputDistribution healthyClientThroughput;
    public final ThroughputDistribution affectedClientThroughput;
    public final LatencyHistogram.Snapshot probeRtt;
    public final List<PeerStats.Snapshot> peers;

    public BenchmarkIterationResult(String name, int iteration, int clients, int payloadSize, RakReliability reliability,
                                    double targetMbps, double targetClientMbps, DisappearanceMode disappearanceMode,
                                    boolean batched, long batchIntervalMillis, int logicalPacketsPerBatch,
                                    int batchGroups, long elapsedMillis, LatencyHistogram.Snapshot probeRtt,
                                    List<PeerStats.Snapshot> peers) {
        this.name = name;
        this.iteration = iteration;
        this.clients = clients;
        this.payloadSize = payloadSize;
        this.reliability = reliability;
        this.targetMbps = targetMbps;
        this.targetClientMbps = targetClientMbps;
        this.disappearanceMode = disappearanceMode;
        this.batched = batched;
        this.batchIntervalMillis = batchIntervalMillis;
        this.logicalPacketsPerBatch = logicalPacketsPerBatch;
        this.batchGroups = batchGroups;
        this.elapsedMillis = elapsedMillis;
        this.probeRtt = probeRtt;
        this.peers = Collections.unmodifiableList(new ArrayList<>(peers));

        long sentMessages = 0L;
        long sentBytes = 0L;
        long sentLogicalPackets = 0L;
        long receivedMessages = 0L;
        long receivedBytes = 0L;
        long receivedLogicalPackets = 0L;
        long sentProbes = 0L;
        long ackedProbes = 0L;
        long bytesOut = 0L;
        long datagramsOut = 0L;
        long healthyBytesOut = 0L;
        long affectedBytesOut = 0L;
        long healthyDatagramsOut = 0L;
        long affectedDatagramsOut = 0L;
        long stale = 0L;
        long nacksIn = 0L;
        long nacksOut = 0L;
        long queued = 0L;
        long disconnectCount = 0L;
        long blackholedIn = 0L;
        long blackholedOut = 0L;
        long healthyReceivedBytes = 0L;
        long affectedReceivedBytes = 0L;
        int affected = 0;
        List<Long> perClientReceived = new ArrayList<>();
        List<Long> healthyClientReceived = new ArrayList<>();
        List<Long> affectedClientReceived = new ArrayList<>();

        for (PeerStats.Snapshot peer : peers) {
            sentMessages += peer.bulkSentMessages;
            sentBytes += peer.bulkSentBytes;
            sentLogicalPackets += peer.logicalPacketsSent;
            receivedMessages += peer.bulkReceivedMessages;
            receivedBytes += peer.bulkReceivedBytes;
            receivedLogicalPackets += peer.logicalPacketsReceived;
            sentProbes += peer.probesSent;
            ackedProbes += peer.probesAcked;
            bytesOut += peer.serverBytesOut;
            datagramsOut += peer.serverDatagramsOut;
            stale += peer.staleDatagrams;
            nacksIn += peer.nackIn;
            nacksOut += peer.nackOut;
            queued = Math.max(queued, peer.maxQueuedBytes);
            disconnectCount += peer.disconnects;
            blackholedIn += peer.blackholedDatagramsIn;
            blackholedOut += peer.blackholedDatagramsOut;
            perClientReceived.add(peer.bulkReceivedBytes);
            if (peer.impaired) {
                affected++;
                affectedReceivedBytes += peer.bulkReceivedBytes;
                affectedBytesOut += peer.serverBytesOut;
                affectedDatagramsOut += peer.serverDatagramsOut;
                affectedClientReceived.add(peer.bulkReceivedBytes);
            } else {
                healthyReceivedBytes += peer.bulkReceivedBytes;
                healthyBytesOut += peer.serverBytesOut;
                healthyDatagramsOut += peer.serverDatagramsOut;
                healthyClientReceived.add(peer.bulkReceivedBytes);
            }
        }

        this.bulkSentMessages = sentMessages;
        this.bulkSentBytes = sentBytes;
        this.logicalPacketsSent = sentLogicalPackets;
        this.bulkReceivedMessages = receivedMessages;
        this.bulkReceivedBytes = receivedBytes;
        this.logicalPacketsReceived = receivedLogicalPackets;
        this.probesSent = sentProbes;
        this.probesAcked = ackedProbes;
        this.serverBytesOut = bytesOut;
        this.serverDatagramsOut = datagramsOut;
        this.healthyServerBytesOut = healthyBytesOut;
        this.affectedServerBytesOut = affectedBytesOut;
        this.healthyServerDatagramsOut = healthyDatagramsOut;
        this.affectedServerDatagramsOut = affectedDatagramsOut;
        this.staleDatagrams = stale;
        this.nackIn = nacksIn;
        this.nackOut = nacksOut;
        this.maxQueuedBytes = queued;
        this.disconnects = disconnectCount;
        this.blackholedDatagramsIn = blackholedIn;
        this.blackholedDatagramsOut = blackholedOut;
        this.deliveredGbps = BenchmarkMath.gigabitsPerSecond(receivedBytes, elapsedMillis);
        this.healthyDeliveredGbps = BenchmarkMath.gigabitsPerSecond(healthyReceivedBytes, elapsedMillis);
        this.affectedDeliveredGbps = BenchmarkMath.gigabitsPerSecond(affectedReceivedBytes, elapsedMillis);
        this.deliveredMessagesPerSecond = BenchmarkMath.messagesPerSecond(receivedMessages, elapsedMillis);
        this.deliveredLogicalPacketsPerSecond = BenchmarkMath.messagesPerSecond(receivedLogicalPackets, elapsedMillis);
        this.offeredGbps = BenchmarkMath.gigabitsPerSecond(sentBytes, elapsedMillis);
        this.serverDatagramsOutPerSecond = BenchmarkMath.messagesPerSecond(datagramsOut, elapsedMillis);
        this.staleDatagramsPerSecond = BenchmarkMath.messagesPerSecond(stale, elapsedMillis);
        this.nackInPerSecond = BenchmarkMath.messagesPerSecond(nacksIn, elapsedMillis);
        this.nackOutPerSecond = BenchmarkMath.messagesPerSecond(nacksOut, elapsedMillis);
        this.sentToDeliveredBytesRatio = sendToDeliveredRatio(bytesOut, receivedBytes);
        this.healthySentToDeliveredBytesRatio = sendToDeliveredRatio(healthyBytesOut, healthyReceivedBytes);
        this.affectedSentToDeliveredBytesRatio = sendToDeliveredRatio(affectedBytesOut, affectedReceivedBytes);
        this.affectedClients = affected;
        this.fairnessIndex = BenchmarkMath.jainFairness(perClientReceived);
        this.healthyFairnessIndex = BenchmarkMath.jainFairness(healthyClientReceived);
        this.affectedFairnessIndex = BenchmarkMath.jainFairness(affectedClientReceived);
        this.perClientThroughput = ThroughputDistribution.fromBytes(perClientReceived, elapsedMillis);
        this.healthyClientThroughput = ThroughputDistribution.fromBytes(healthyClientReceived, elapsedMillis);
        this.affectedClientThroughput = ThroughputDistribution.fromBytes(affectedClientReceived, elapsedMillis);
    }

    private static double sendToDeliveredRatio(long serverBytesOut, long deliveredBytes) {
        return serverBytesOut / (double) Math.max(1L, deliveredBytes);
    }
}
