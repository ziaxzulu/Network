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

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.channel.raknet.RakConstants;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class RakNetBenchmarkRunner {
    public BenchmarkRunResult run(BenchmarkConfig config) throws Exception {
        BenchmarkRunResult result = new BenchmarkRunResult(config, EnvironmentInfo.capture());
        if (config.role() == BenchmarkRole.CLIENT || config.scenario() == BenchmarkScenario.RECEIVER_WORKER) {
            runClientWorker(config, result);
        } else if (config.role() == BenchmarkRole.SERVER || config.scenario() == BenchmarkScenario.SERVER_WORKER) {
            runServerWorker(config, result);
        } else {
            for (BenchmarkCase benchmarkCase : cases(config)) {
                runLocal(config, benchmarkCase, result);
            }
        }
        return result;
    }

    private static List<BenchmarkCase> cases(BenchmarkConfig config) {
        List<BenchmarkCase> cases = new ArrayList<>();
        if (config.scenario() == BenchmarkScenario.BANDWIDTH_LATENCY_CURVE) {
            for (Double rate : config.ratesMbps()) {
                cases.add(singleCase(config, "curve-" + rateName(rate), config.payloadSize(), config.reliability(), rate));
            }
        } else if (config.scenario() == BenchmarkScenario.MATRIX) {
            for (Integer payload : config.payloadSizes()) {
                for (org.cloudburstmc.netty.channel.raknet.RakReliability reliability : config.reliabilities()) {
                    for (Double rate : config.ratesMbps()) {
                        cases.add(singleCase(config, "matrix-p" + payload + '-' + reliability.name().toLowerCase() + '-' + rateName(rate), payload, reliability, rate));
                    }
                }
            }
        } else if (config.scenario() == BenchmarkScenario.MULTI_CLIENT_FANOUT) {
            cases.add(singleCase(config, "multi-client-fanout", config.payloadSize(), config.reliability(), config.rateMbps()));
        } else if (config.scenario() == BenchmarkScenario.FAIRNESS) {
            cases.add(singleCase(config, "fairness", config.payloadSize(), config.reliability(), config.rateMbps()));
        } else if (config.scenario() == BenchmarkScenario.DISAPPEARING_CLIENTS) {
            cases.add(singleCase(config, "disappearing-clients", config.payloadSize(), config.reliability(), config.rateMbps()));
        } else {
            cases.add(singleCase(config, "baseline-bandwidth", config.payloadSize(), config.reliability(), config.rateMbps()));
        }
        return cases;
    }

    private static BenchmarkCase singleCase(BenchmarkConfig config, String name, int payloadSize,
                                            org.cloudburstmc.netty.channel.raknet.RakReliability reliability, double rateMbps) {
        double targetMbps = config.effectiveTargetMbps(rateMbps, config.clients());
        double targetClientMbps = config.effectiveTargetClientMbps(targetMbps, config.clients());
        int affectedClients = Math.max(config.impairedClients(), config.disappearingClients());
        return new BenchmarkCase(name, config.clients(), affectedClients, config.disappearingClients(), payloadSize,
                reliability, targetMbps, targetClientMbps, config.disappearAfterMillis());
    }

    private void runLocal(BenchmarkConfig config, BenchmarkCase benchmarkCase, BenchmarkRunResult result) throws Exception {
        if (benchmarkCase.disappearingClients() > 0) {
            for (int iteration = 1; iteration <= config.iterations(); iteration++) {
                try (LocalSession session = openLocalSession(config, benchmarkCase)) {
                    runLocalIteration(config, benchmarkCase, result, session, iteration);
                }
            }
            return;
        }

        try (LocalSession session = openLocalSession(config, benchmarkCase)) {
            for (int iteration = 1; iteration <= config.iterations(); iteration++) {
                runLocalIteration(config, benchmarkCase, result, session, iteration);
            }
        }
    }

    private LocalSession openLocalSession(BenchmarkConfig config, BenchmarkCase benchmarkCase) throws Exception {
        EventLoopGroup serverGroup = new NioEventLoopGroup(config.workers());
        EventLoopGroup clientGroup = new NioEventLoopGroup(config.workers());
        Channel serverChannel = null;
        List<Channel> clientChannels = new ArrayList<>();
        boolean success = false;
        try {
            LatencyHistogram probeRtt = new LatencyHistogram();
            BenchmarkServerMetrics metrics = new BenchmarkServerMetrics();
            Queue<PeerStats> pendingPeers = new ConcurrentLinkedQueue<>();
            List<ServerPeer> serverPeers = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch connectedLatch = new CountDownLatch(benchmarkCase.clients());
            AtomicInteger accepted = new AtomicInteger();

            serverChannel = startServer(config, benchmarkCase, serverGroup, metrics, pendingPeers, serverPeers, accepted, probeRtt);
            InetSocketAddress boundAddress = (InetSocketAddress) serverChannel.localAddress();
            InetSocketAddress serverAddress = new InetSocketAddress(config.host(), boundAddress.getPort());
            clientChannels.addAll(startClients(config, benchmarkCase, clientGroup, serverAddress, pendingPeers, connectedLatch));

            if (!connectedLatch.await(Math.max(30_000L, benchmarkCase.clients() * 100L), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + benchmarkCase.clients() + " established clients");
            }

            waitForServerPeers(serverPeers, benchmarkCase.clients());
            success = true;
            return new LocalSession(serverGroup, clientGroup, serverChannel, clientChannels, probeRtt, metrics, serverPeers);
        } finally {
            if (!success) {
                closeChannels(clientChannels);
                if (serverChannel != null) {
                    serverChannel.close().awaitUninterruptibly();
                }
                clientGroup.shutdownGracefully().awaitUninterruptibly();
                serverGroup.shutdownGracefully().awaitUninterruptibly();
            }
        }
    }

    private void runLocalIteration(BenchmarkConfig config, BenchmarkCase benchmarkCase, BenchmarkRunResult result,
                                   LocalSession session, int iteration) {
        runTraffic(config, benchmarkCase, session.serverPeers, session.probeRtt, session.metrics, config.warmupMillis());
        drainWarmup(config);
        session.metrics.resetMeasurement();
        session.probeRtt.clear();
        long started = System.nanoTime();
        runTraffic(config, benchmarkCase, session.serverPeers, session.probeRtt, session.metrics,
                config.durationMillis(), session.clientChannels, true);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        result.add(new BenchmarkIterationResult(
                benchmarkCase.name(),
                iteration,
                benchmarkCase.clients(),
                benchmarkCase.payloadSize(),
                benchmarkCase.reliability(),
                benchmarkCase.targetMbps(),
                benchmarkCase.targetClientMbps(),
                elapsedMillis,
                session.probeRtt.snapshot(),
                session.metrics.peerSnapshots()
        ));
    }

    private void runServerWorker(BenchmarkConfig config, BenchmarkRunResult result) throws Exception {
        BenchmarkCase benchmarkCase = singleCase(config, config.scenario().cliName(), config.payloadSize(), config.reliability(), config.rateMbps());
        EventLoopGroup serverGroup = new NioEventLoopGroup(config.workers());
        Channel serverChannel = null;
        try {
            LatencyHistogram probeRtt = new LatencyHistogram();
            BenchmarkServerMetrics metrics = new BenchmarkServerMetrics();
            List<ServerPeer> serverPeers = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger accepted = new AtomicInteger();
            serverChannel = startServer(config, benchmarkCase, serverGroup, metrics, new ConcurrentLinkedQueue<>(), serverPeers, accepted, probeRtt);
            System.out.println("Server worker listening on " + serverChannel.localAddress());
            long waitDeadline = System.currentTimeMillis() + config.startDelayMillis();
            while (serverPeers.size() < config.clients() && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(50L);
            }
            waitForServerPeers(serverPeers, Math.min(config.clients(), Math.max(1, serverPeers.size())));
            for (int iteration = 1; iteration <= config.iterations(); iteration++) {
                runTraffic(config, benchmarkCase, serverPeers, probeRtt, metrics, config.warmupMillis());
                drainWarmup(config);
                metrics.resetMeasurement();
                probeRtt.clear();
                long started = System.nanoTime();
                runTraffic(config, benchmarkCase, serverPeers, probeRtt, metrics, config.durationMillis());
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                result.add(new BenchmarkIterationResult(
                        benchmarkCase.name(),
                        iteration,
                        serverPeers.size(),
                        benchmarkCase.payloadSize(),
                        benchmarkCase.reliability(),
                        benchmarkCase.targetMbps(),
                        benchmarkCase.targetClientMbps(),
                        elapsedMillis,
                        probeRtt.snapshot(),
                        metrics.peerSnapshots()
                ));
            }
        } finally {
            if (serverChannel != null) {
                serverChannel.close().awaitUninterruptibly();
            }
            serverGroup.shutdownGracefully().awaitUninterruptibly();
        }
    }

    private static void drainWarmup(BenchmarkConfig config) {
        long drainMillis = Math.min(1000L, Math.max(100L, config.probeIntervalMillis() * 2L));
        try {
            Thread.sleep(drainMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runClientWorker(BenchmarkConfig config, BenchmarkRunResult result) throws Exception {
        BenchmarkCase benchmarkCase = singleCase(config, config.scenario().cliName(), config.payloadSize(), config.reliability(), config.rateMbps());
        EventLoopGroup group = new NioEventLoopGroup(config.workers());
        List<Channel> channels = new ArrayList<>();
        List<PeerStats> peers = new ArrayList<>();
        try {
            CountDownLatch connected = new CountDownLatch(config.clients());
            InetSocketAddress address = new InetSocketAddress(config.host(), config.port());
            for (int i = 0; i < config.clients(); i++) {
                PeerStats peer = new PeerStats(i, i < benchmarkCase.impairedClients());
                peers.add(peer);
                channels.add(startClient(group, address, peer, connected, benchmarkCase));
            }
            if (!connected.await(Math.max(30_000L, config.clients() * 100L), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for client worker connections");
            }
            Thread.sleep(config.warmupMillis());
            for (PeerStats peer : peers) {
                peer.resetMeasurement();
            }
            long started = System.nanoTime();
            Thread.sleep(config.durationMillis());
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            List<PeerStats.Snapshot> snapshots = new ArrayList<>();
            for (PeerStats peer : peers) {
                snapshots.add(peer.snapshot());
            }
            result.add(new BenchmarkIterationResult(
                    benchmarkCase.name(),
                    1,
                    config.clients(),
                    benchmarkCase.payloadSize(),
                    benchmarkCase.reliability(),
                    benchmarkCase.targetMbps(),
                    benchmarkCase.targetClientMbps(),
                    elapsedMillis,
                    new LatencyHistogram().snapshot(),
                    snapshots
            ));
        } finally {
            for (Channel channel : channels) {
                channel.close().awaitUninterruptibly();
            }
            group.shutdownGracefully().awaitUninterruptibly();
        }
    }

    private Channel startServer(BenchmarkConfig config, BenchmarkCase benchmarkCase, EventLoopGroup group,
                                BenchmarkServerMetrics metrics, Queue<PeerStats> pendingPeers,
                                List<ServerPeer> serverPeers, AtomicInteger accepted,
                                LatencyHistogram probeRtt) {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, new int[]{RakConstants.RAKNET_PROTOCOL_VERSION})
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, benchmarkCase.clients() + 16)
                .option(RakChannelOption.RAK_SERVER_METRICS, metrics)
                .childOption(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
                .handler(new ChannelInitializer<RakServerChannel>() {
                    @Override
                    protected void initChannel(RakServerChannel ch) {
                    }
                })
                .childHandler(new ChannelInitializer<RakChildChannel>() {
                    @Override
                    protected void initChannel(RakChildChannel ch) {
                        PeerStats peer = pendingPeers.poll();
                        if (peer == null) {
                            int id = accepted.get();
                            peer = new PeerStats(id, id < benchmarkCase.impairedClients());
                        }
                        final PeerStats assignedPeer = peer;
                        accepted.incrementAndGet();
                        metrics.register(ch, assignedPeer);
                        serverPeers.add(new ServerPeer(ch, assignedPeer));
                        ch.pipeline().addLast(new ServerProbeAckHandler(assignedPeer, probeRtt));
                    }
                });
        return bootstrap.bind(new InetSocketAddress(config.bindHost(), config.port())).awaitUninterruptibly().channel();
    }

    private List<Channel> startClients(BenchmarkConfig config, BenchmarkCase benchmarkCase, EventLoopGroup group,
                                       InetSocketAddress serverAddress, Queue<PeerStats> pendingPeers,
                                       CountDownLatch connectedLatch) {
        List<Channel> channels = new ArrayList<>(benchmarkCase.clients());
        for (int i = 0; i < benchmarkCase.clients(); i++) {
            PeerStats peer = new PeerStats(i, i < benchmarkCase.impairedClients());
            pendingPeers.add(peer);
            channels.add(startClient(group, serverAddress, peer, connectedLatch, benchmarkCase));
        }
        return channels;
    }

    private Channel startClient(EventLoopGroup group, InetSocketAddress serverAddress, PeerStats peer,
                                CountDownLatch connectedLatch, BenchmarkCase benchmarkCase) {
        Bootstrap bootstrap = new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, Integer.valueOf(RakConstants.RAKNET_PROTOCOL_VERSION))
                .option(RakChannelOption.RAK_MTU, RakConstants.MAXIMUM_MTU_SIZE)
                .option(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) {
                        ch.pipeline().addLast(new ClientReceiverHandler(peer, connectedLatch, benchmarkCase.reliability()));
                    }
                });
        return bootstrap.connect(serverAddress).awaitUninterruptibly().channel();
    }

    private static void waitForServerPeers(List<ServerPeer> peers, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(30_000L, expected * 100L);
        while (peers.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        if (peers.size() < expected) {
            throw new IllegalStateException("Timed out waiting for server peers. expected=" + expected + " actual=" + peers.size());
        }
    }

    private void runTraffic(BenchmarkConfig config, BenchmarkCase benchmarkCase, List<ServerPeer> peers,
                            LatencyHistogram probeRtt, BenchmarkServerMetrics metrics, long durationMillis) {
        runTraffic(config, benchmarkCase, peers, probeRtt, metrics, durationMillis, Collections.emptyList(), false);
    }

    private void runTraffic(BenchmarkConfig config, BenchmarkCase benchmarkCase, List<ServerPeer> peers,
                            LatencyHistogram probeRtt, BenchmarkServerMetrics metrics, long durationMillis,
                            List<Channel> clientChannels, boolean allowDisappearance) {
        if (durationMillis <= 0L || peers.isEmpty()) {
            return;
        }
        final long endNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
        final long probeIntervalNanos = TimeUnit.MILLISECONDS.toNanos(config.probeIntervalMillis());
        final long aggregateMessageRate = config.effectiveMessageRate(benchmarkCase.payloadSize(), benchmarkCase.targetMbps());
        final long bulkIntervalNanos = aggregateMessageRate > 0L ? Math.max(1L, 1_000_000_000L / aggregateMessageRate) : 0L;
        final long disappearAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(benchmarkCase.disappearAfterMillis());
        long nextBulkNanos = System.nanoTime();
        long nextProbeNanos = nextBulkNanos;
        long bulkSequence = 0L;
        long probeSequence = 0L;
        int peerIndex = 0;
        boolean disappeared = benchmarkCase.disappearingClients() == 0 || !allowDisappearance;

        while (System.nanoTime() < endNanos) {
            long now = System.nanoTime();
            if (!disappeared && now >= disappearAtNanos) {
                closeDisappearingClients(clientChannels, peers, benchmarkCase.disappearingClients());
                disappeared = true;
            }

            if (now >= nextProbeNanos) {
                probeSequence = sendProbes(peers, benchmarkCase, probeSequence);
                nextProbeNanos += probeIntervalNanos;
            }

            if (aggregateMessageRate == 0L) {
                for (ServerPeer peer : peers) {
                    bulkSequence = sendBulk(peer, benchmarkCase, bulkSequence);
                }
                Thread.yield();
                continue;
            }

            if (now >= nextBulkNanos) {
                ServerPeer peer = peers.get(peerIndex++ % peers.size());
                bulkSequence = sendBulk(peer, benchmarkCase, bulkSequence);
                nextBulkNanos += bulkIntervalNanos;
                continue;
            }

            long sleepNanos = Math.min(Math.min(nextBulkNanos, nextProbeNanos) - now, TimeUnit.MILLISECONDS.toNanos(1L));
            if (sleepNanos > 0L) {
                LockSupport.parkNanos(sleepNanos);
            }
        }
    }

    private static void closeDisappearingClients(List<Channel> clientChannels, List<ServerPeer> peers, int count) {
        int limit = Math.min(count, clientChannels.size());
        for (int i = 0; i < limit; i++) {
            Channel channel = clientChannels.get(i);
            if (channel.isOpen()) {
                channel.close();
            }
        }

        int closedPeers = 0;
        synchronized (peers) {
            for (ServerPeer peer : peers) {
                if (!peer.stats().impaired()) {
                    continue;
                }
                if (peer.channel().isOpen()) {
                    peer.channel().close();
                }
                closedPeers++;
                if (closedPeers >= count) {
                    break;
                }
            }
        }
    }

    private static void closeChannels(List<Channel> channels) {
        for (Channel channel : channels) {
            channel.close().awaitUninterruptibly();
        }
    }

    private long sendBulk(ServerPeer peer, BenchmarkCase benchmarkCase, long sequence) {
        if (!peer.channel().isActive()) {
            return sequence;
        }
        ByteBuf payload = BenchmarkPayload.bulk(peer.channel().alloc(), benchmarkCase.payloadSize(), sequence);
        peer.channel().writeAndFlush(new RakMessage(payload, benchmarkCase.reliability(), RakPriority.NORMAL));
        peer.stats().addBulkSent(benchmarkCase.payloadSize());
        return sequence + 1L;
    }

    private long sendProbes(List<ServerPeer> peers, BenchmarkCase benchmarkCase, long sequence) {
        long current = sequence;
        for (ServerPeer peer : peers) {
            if (!peer.channel().isActive()) {
                continue;
            }
            long sentNanos = System.nanoTime();
            peer.channel().writeAndFlush(new RakMessage(
                    BenchmarkPayload.probe(peer.channel().alloc(), current, sentNanos),
                    benchmarkCase.reliability(),
                    RakPriority.HIGH
            ));
            peer.stats().addProbeSent();
            current++;
        }
        return current;
    }

    private static final class LocalSession implements AutoCloseable {
        private final EventLoopGroup serverGroup;
        private final EventLoopGroup clientGroup;
        private final Channel serverChannel;
        private final List<Channel> clientChannels;
        private final LatencyHistogram probeRtt;
        private final BenchmarkServerMetrics metrics;
        private final List<ServerPeer> serverPeers;

        private LocalSession(EventLoopGroup serverGroup, EventLoopGroup clientGroup, Channel serverChannel,
                             List<Channel> clientChannels, LatencyHistogram probeRtt, BenchmarkServerMetrics metrics,
                             List<ServerPeer> serverPeers) {
            this.serverGroup = serverGroup;
            this.clientGroup = clientGroup;
            this.serverChannel = serverChannel;
            this.clientChannels = clientChannels;
            this.probeRtt = probeRtt;
            this.metrics = metrics;
            this.serverPeers = serverPeers;
        }

        @Override
        public void close() {
            closeChannels(this.clientChannels);
            this.serverChannel.close().awaitUninterruptibly();
            this.clientGroup.shutdownGracefully().awaitUninterruptibly();
            this.serverGroup.shutdownGracefully().awaitUninterruptibly();
        }
    }

    private static String rateName(Double rate) {
        if (rate == null || rate.doubleValue() == 0.0D) {
            return "unlimited";
        }
        return String.valueOf(rate).replace('.', '_') + "mbps";
    }
}
