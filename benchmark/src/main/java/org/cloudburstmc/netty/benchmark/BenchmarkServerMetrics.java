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

import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakState;
import org.cloudburstmc.netty.channel.raknet.config.RakServerMetrics;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

public final class BenchmarkServerMetrics implements RakServerMetrics {
    private final ConcurrentMap<RakChildChannel, PeerStats> byChannel = new ConcurrentHashMap<>();
    private final ConcurrentMap<InetSocketAddress, PeerStats> byAddress = new ConcurrentHashMap<>();
    private final LongAdder channelOpen = new LongAdder();
    private final LongAdder channelClose = new LongAdder();
    private final LongAdder connectPackets = new LongAdder();
    private final LongAdder invalidCookies = new LongAdder();

    public void register(RakChildChannel channel, PeerStats peer) {
        this.byChannel.put(channel, peer);
        this.byAddress.put(channel.remoteAddress(), peer);
        peer.address(channel.remoteAddress());
    }

    public void resetMeasurement() {
        for (PeerStats peer : this.byChannel.values()) {
            peer.resetMeasurement();
        }
    }

    public List<PeerStats.Snapshot> peerSnapshots() {
        List<PeerStats.Snapshot> peers = new ArrayList<>();
        for (PeerStats peer : this.byChannel.values()) {
            peers.add(peer.snapshot());
        }
        Collections.sort(peers, (a, b) -> Integer.compare(a.id, b.id));
        return peers;
    }

    public long channelOpenCount() {
        return this.channelOpen.sum();
    }

    public long channelCloseCount() {
        return this.channelClose.sum();
    }

    public long connectPackets() {
        return this.connectPackets.sum();
    }

    public long invalidCookies() {
        return this.invalidCookies.sum();
    }

    @Override
    public void channelOpen(InetSocketAddress address) {
        this.channelOpen.increment();
    }

    @Override
    public void channelClose(InetSocketAddress address) {
        this.channelClose.increment();
        PeerStats peer = this.byAddress.get(address);
        if (peer != null) {
            peer.addDisconnect();
        }
    }

    @Override
    public void connectionInitPacket(InetSocketAddress address, int packetId) {
        this.connectPackets.increment();
    }

    @Override
    public void invalidCookie(InetSocketAddress address) {
        this.invalidCookies.increment();
    }

    @Override
    public void bytesIn(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addServerBytesIn(count);
        }
    }

    @Override
    public void bytesOut(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addServerBytesOut(count);
        }
    }

    @Override
    public void rakDatagramsIn(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addServerDatagramsIn(count);
        }
    }

    @Override
    public void rakDatagramsOut(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addServerDatagramsOut(count);
        }
    }

    @Override
    public void encapsulatedIn(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addEncapsulatedIn(count);
        }
    }

    @Override
    public void encapsulatedOut(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addEncapsulatedOut(count);
        }
    }

    @Override
    public void rakStaleDatagrams(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addStaleDatagrams(count);
        }
    }

    @Override
    public void ackIn(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addAckIn(count);
        }
    }

    @Override
    public void ackOut(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addAckOut(count);
        }
    }

    @Override
    public void nackOut(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addNackOut(count);
        }
    }

    @Override
    public void nackIn(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.addNackIn(count);
        }
    }

    @Override
    public void stateChange(RakChildChannel channel, RakState state) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.state(state);
        }
    }

    @Override
    public void queuedPacketBytes(RakChildChannel channel, int count) {
        PeerStats peer = this.byChannel.get(channel);
        if (peer != null) {
            peer.queuedBytes(count);
        }
    }
}
