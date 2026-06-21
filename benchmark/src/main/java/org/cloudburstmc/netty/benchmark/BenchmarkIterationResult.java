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
    public final long elapsedMillis;
    public final long bulkSentMessages;
    public final long bulkSentBytes;
    public final long bulkReceivedMessages;
    public final long bulkReceivedBytes;
    public final long probesSent;
    public final long probesAcked;
    public final long staleDatagrams;
    public final long nackIn;
    public final long nackOut;
    public final long maxQueuedBytes;
    public final long disconnects;
    public final double deliveredGbps;
    public final double healthyDeliveredGbps;
    public final double affectedDeliveredGbps;
    public final double deliveredMessagesPerSecond;
    public final double offeredGbps;
    public final int affectedClients;
    public final double fairnessIndex;
    public final double healthyFairnessIndex;
    public final double affectedFairnessIndex;
    public final LatencyHistogram.Snapshot probeRtt;
    public final List<PeerStats.Snapshot> peers;

    public BenchmarkIterationResult(String name, int iteration, int clients, int payloadSize, RakReliability reliability,
                                    double targetMbps, double targetClientMbps, DisappearanceMode disappearanceMode,
                                    long elapsedMillis, LatencyHistogram.Snapshot probeRtt,
                                    List<PeerStats.Snapshot> peers) {
        this.name = name;
        this.iteration = iteration;
        this.clients = clients;
        this.payloadSize = payloadSize;
        this.reliability = reliability;
        this.targetMbps = targetMbps;
        this.targetClientMbps = targetClientMbps;
        this.disappearanceMode = disappearanceMode;
        this.elapsedMillis = elapsedMillis;
        this.probeRtt = probeRtt;
        this.peers = Collections.unmodifiableList(new ArrayList<>(peers));

        long sentMessages = 0L;
        long sentBytes = 0L;
        long receivedMessages = 0L;
        long receivedBytes = 0L;
        long sentProbes = 0L;
        long ackedProbes = 0L;
        long stale = 0L;
        long nacksIn = 0L;
        long nacksOut = 0L;
        long queued = 0L;
        long disconnectCount = 0L;
        long healthyReceivedBytes = 0L;
        long affectedReceivedBytes = 0L;
        int affected = 0;
        List<Long> perClientReceived = new ArrayList<>();
        List<Long> healthyClientReceived = new ArrayList<>();
        List<Long> affectedClientReceived = new ArrayList<>();

        for (PeerStats.Snapshot peer : peers) {
            sentMessages += peer.bulkSentMessages;
            sentBytes += peer.bulkSentBytes;
            receivedMessages += peer.bulkReceivedMessages;
            receivedBytes += peer.bulkReceivedBytes;
            sentProbes += peer.probesSent;
            ackedProbes += peer.probesAcked;
            stale += peer.staleDatagrams;
            nacksIn += peer.nackIn;
            nacksOut += peer.nackOut;
            queued = Math.max(queued, peer.maxQueuedBytes);
            disconnectCount += peer.disconnects;
            perClientReceived.add(peer.bulkReceivedBytes);
            if (peer.impaired) {
                affected++;
                affectedReceivedBytes += peer.bulkReceivedBytes;
                affectedClientReceived.add(peer.bulkReceivedBytes);
            } else {
                healthyReceivedBytes += peer.bulkReceivedBytes;
                healthyClientReceived.add(peer.bulkReceivedBytes);
            }
        }

        this.bulkSentMessages = sentMessages;
        this.bulkSentBytes = sentBytes;
        this.bulkReceivedMessages = receivedMessages;
        this.bulkReceivedBytes = receivedBytes;
        this.probesSent = sentProbes;
        this.probesAcked = ackedProbes;
        this.staleDatagrams = stale;
        this.nackIn = nacksIn;
        this.nackOut = nacksOut;
        this.maxQueuedBytes = queued;
        this.disconnects = disconnectCount;
        this.deliveredGbps = BenchmarkMath.gigabitsPerSecond(receivedBytes, elapsedMillis);
        this.healthyDeliveredGbps = BenchmarkMath.gigabitsPerSecond(healthyReceivedBytes, elapsedMillis);
        this.affectedDeliveredGbps = BenchmarkMath.gigabitsPerSecond(affectedReceivedBytes, elapsedMillis);
        this.deliveredMessagesPerSecond = BenchmarkMath.messagesPerSecond(receivedMessages, elapsedMillis);
        this.offeredGbps = BenchmarkMath.gigabitsPerSecond(sentBytes, elapsedMillis);
        this.affectedClients = affected;
        this.fairnessIndex = BenchmarkMath.jainFairness(perClientReceived);
        this.healthyFairnessIndex = BenchmarkMath.jainFairness(healthyClientReceived);
        this.affectedFairnessIndex = BenchmarkMath.jainFairness(affectedClientReceived);
    }
}
