package org.cloudburstmc.netty.benchmark;

import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Matched workload driver; uses the pinned benchmark's payload encoding and statistics. */
public final class ComparisonMain {
    static final byte HELLO = (byte) 0x84;
    static final long APPLICATION_PENDING_LIMIT = 64L << 20;
    final Options options;
    final Peer[] peers;
    final List<String> errors = new CopyOnWriteArrayList<>();
    final List<Map<String, Object>> timeline = new ArrayList<>();
    final AtomicLong writeFailures = new AtomicLong();
    final LatencyHistogram latency = new LatencyHistogram();
    final LatencyHistogram settledLatency = new LatencyHistogram();
    volatile long measurementStart = Long.MAX_VALUE, measurementEnd = Long.MIN_VALUE;
    long maxRssBytes, maxPendingBytes;
    ComparisonTransport transport;

    ComparisonMain(Options options) {
        this.options = options;
        peers = new Peer[options.clients];
        for (int i = 0; i < peers.length; i++) peers[i] = new Peer(i, i < options.affected);
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Files.createDirectories(options.out);
        ComparisonMain run = new ComparisonMain(options);
        Map<String, Object> result = new LinkedHashMap<>();
        int exit = 0;
        try {
            result.putAll(run.run());
        } catch (Throwable failure) {
            exit = 1;
            result.put("status", "failed");
            result.put("failure", failure.toString());
            failure.printStackTrace();
        } finally {
            if (run.transport != null) {
                try { run.transport.close(); }
                catch (Throwable failure) { exit = 1; result.put("cleanupFailure", failure.toString()); }
            }
            result.put("options", options);
            result.put("recordedAt", Instant.now().toString());
            result.put("errors", run.errors);
            result.put("writeFailures", run.writeFailures.get());
            result.put("javaRuntime", System.getProperty("java.runtime.version"));
            result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
            result.put("topology", "one JVM, real UDP on one Linux host; setup uses local admission signalling");
            result.put("probeSemantics", "reliable ordered, normal priority, same application stream as bulk; RTT includes queueing");
            result.put("raknetPacketLimit", 0);
            result.put("raknetGlobalPacketLimit", 0);
            result.put("nativeLibrary", System.getProperty("libdatachannel.native.datachannel-java.path"));
            result.put("transportQueueBytes", null);
            result.put("sctpRetransmissions", null);
            result.put("raknetRetransmissions", null);
            result.put("serverOnlyCpu", null);
            var gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
            // Paths are rendered explicitly; Gson cannot reflect into JDK Path internals.
            result.put("options", options.asMap());
            Files.writeString(options.out.resolve("result.json"), gson.toJson(result) + "\n");
            Files.writeString(options.out.resolve("timeline.json"), gson.toJson(run.timeline) + "\n");
            System.out.println(gson.toJson(result));
        }
        // Bound native-library shutdown too; every campaign row runs in a fresh process.
        System.exit(exit);
    }

    Map<String, Object> run() throws Exception {
        transport = new ComparisonTransport(options.transport, options.port);
        long setupStart = System.nanoTime();
        transport.start(options.clients, options.identity, () -> new Receiver(null));
        for (Peer peer : peers) {
            peer.client = transport.connect(peer.id, peer.affected, new Receiver(peer));
            ByteBuf hello = peer.client.alloc().buffer(17, 17).writeByte(HELLO).writeLong(peer.id).writeLong(0);
            peer.client.writeAndFlush(transport.wrap(hello)).sync();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (peer.server == null && System.nanoTime() < deadline) Thread.sleep(2);
            if (peer.server == null) throw new IllegalStateException("Application HELLO missing for peer " + peer.id);
        }
        double setupMillis = (System.nanoTime() - setupStart) / 1e6;
        if (options.transport.equals("nethernet")) {
            String requested = System.getProperty("libdatachannel.native.datachannel-java.path");
            if (requested == null || Files.readAllLines(Path.of("/proc/self/maps")).stream().noneMatch(line -> line.contains(requested))) {
                throw new IllegalStateException("Pinned Release native library was not loaded");
            }
        }
        System.err.println("Established " + peers.length + " " + options.transport + " clients");
        drive(options.warmupMillis, false);
        flushSubmissions();
        Thread.sleep(500);
        for (Peer p : peers) { p.sequence = 0; p.credit = 0; }
        if (!options.profile.equals("clean") && !options.profile.equals("blackhole")) shape(options.profile);
        qdiscSnapshot("measurement-before");
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuBefore = os.getProcessCpuTime(), gcBefore = gcMillis();
        measurementStart = System.nanoTime();
        measurementEnd = measurementStart + options.durationMillis * 1_000_000L;
        Files.writeString(options.out.resolve("measurement-start.json"),
            "{\"epochMillis\":" + System.currentTimeMillis() + "}");
        drive(options.durationMillis, true);
        double actualMeasurementSeconds = (System.nanoTime() - measurementStart) / 1e9;
        double cpuSeconds = (os.getProcessCpuTime() - cpuBefore) / 1e9;
        long gcMillis = gcMillis() - gcBefore;
        qdiscSnapshot("measurement-after");
        flushSubmissions();
        long drainEnd = System.nanoTime() + options.drainMillis * 1_000_000L;
        while (System.nanoTime() < drainEnd && Arrays.stream(peers).anyMatch(p -> p.receivedMessages.get() < p.offeredMessages)) {
            Thread.sleep(10);
        }
        if (!options.profile.equals("clean")) shape("clean");
        sample("drained");
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Long> healthy = new ArrayList<>(), affected = new ArrayList<>();
        long offeredBytes = 0, receivedBytes = 0, receivedWindow = 0, deliveredMessages = 0, orderingErrors = 0;
        int disconnected = 0;
        for (Peer p : peers) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", p.id); row.put("affected", p.affected);
            row.put("offeredBytes", p.offeredBytes); row.put("offeredMessages", p.offeredMessages);
            row.put("deliveredBytesIncludingDrain", p.receivedBytes.get());
            row.put("deliveredBytesInWindow", p.windowBytes.get());
            row.put("deliveredMessagesIncludingDrain", p.receivedMessages.get());
            row.put("orderingErrors", p.orderingErrors.get());
            row.put("sequenceGaps", p.sequenceGaps.get());
            row.put("applicationPendingBytes", p.pendingBytes.get());
            row.put("open", p.client.isOpen() && p.server.isOpen());
            row.put("active", p.client.isActive() && p.server.isActive());
            row.put("probeCount", p.latency.snapshot().count());
            row.put("probeP99Ms", percentile(p.latency, 99));
            row.put("probesSent", p.probesSent);
            row.put("probesReceivedIncludingDrain", p.settledLatency.snapshot().count());
            row.put("probeP99IncludingDrainMs", percentile(p.settledLatency, 99));
            row.put("lastDeliveryMs", p.lastDeliveryNanos == 0 ? null : (p.lastDeliveryNanos - measurementStart) / 1e6);
            rows.add(row);
            (p.affected ? affected : healthy).add(p.windowBytes.get());
            offeredBytes += p.offeredBytes; receivedBytes += p.receivedBytes.get();
            receivedWindow += p.windowBytes.get(); deliveredMessages += p.receivedMessages.get();
            orderingErrors += p.orderingErrors.get();
            if (!p.client.isOpen() || !p.server.isOpen()) disconnected++;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("status", errors.isEmpty() && writeFailures.get() == 0 && orderingErrors == 0 ? "measured" : "measured-with-errors");
        result.put("setupMillis", setupMillis);
        result.put("raknetCodeSource", org.cloudburstmc.netty.channel.raknet.RakServerChannel.class.getProtectionDomain().getCodeSource().getLocation().toString());
        result.put("nativeReleaseLoaded", options.transport.equals("nethernet"));
        result.put("cpuAffinity", Files.readAllLines(Path.of("/proc/self/status")).stream().filter(line -> line.startsWith("Cpus_allowed_list:")).findFirst().orElse("unknown"));
        result.put("offeredMbps", BenchmarkMath.megabitsPerSecond(offeredBytes, options.durationMillis));
        result.put("deliveredMbps", BenchmarkMath.megabitsPerSecond(receivedWindow, options.durationMillis));
        result.put("deliveryRatioIncludingDrain", offeredBytes == 0 ? null : (double) receivedBytes / offeredBytes);
        result.put("deliveredMessagesIncludingDrain", deliveredMessages);
        result.put("healthyMbps", BenchmarkMath.megabitsPerSecond(healthy.stream().mapToLong(Long::longValue).sum(), options.durationMillis));
        result.put("affectedMbps", BenchmarkMath.megabitsPerSecond(affected.stream().mapToLong(Long::longValue).sum(), options.durationMillis));
        result.put("healthyFairness", healthy.isEmpty() ? null : BenchmarkMath.jainFairness(healthy));
        result.put("affectedFairness", affected.isEmpty() ? null : BenchmarkMath.jainFairness(affected));
        result.put("probeSamples", latency.snapshot().count());
        result.put("probeP50Ms", percentile(latency, 50)); result.put("probeP95Ms", percentile(latency, 95));
        result.put("probeP99Ms", percentile(latency, 99));
        result.put("probeP99IncludingDrainMs", percentile(settledLatency, 99));
        result.put("probesSent", Arrays.stream(peers).mapToLong(p -> p.probesSent).sum());
        result.put("probesReceivedIncludingDrain", settledLatency.snapshot().count());
        result.put("processCpuSeconds", cpuSeconds);
        result.put("cpuMeasurementSeconds", actualMeasurementSeconds);
        result.put("processCpuCores", cpuSeconds / actualMeasurementSeconds);
        result.put("generatorDeadlineMissedBytes", Arrays.stream(peers).mapToLong(p -> p.deadlineMissedBytes.get()).sum());
        result.put("gcMillis", gcMillis); result.put("maxRssBytes", maxRssBytes);
        result.put("maxApplicationPendingBytes", maxPendingBytes);
        result.put("disconnectedClients", disconnected); result.put("peers", rows);
        return result;
    }

    private void drive(long durationMillis, boolean measured) throws Exception {
        long start = measured ? measurementStart : System.nanoTime(), end = start + durationMillis * 1_000_000L;
        long nextSample = start, nextProbe = start, nextTick = start;
        long tick = 0;
        boolean blackholed = false, recovered = false, disappeared = false;
        long interval = options.scenario.equals("bulk") ? 1_000_000L : options.intervalMillis * 1_000_000L;
        while (System.nanoTime() < end) {
            long now = System.nanoTime();
            if (measured && options.profile.equals("blackhole")) {
                if (!blackholed && now - start >= 2_000_000_000L) { shape("blackhole"); blackholed = true; }
                if (!recovered && now - start >= 5_000_000_000L) { shape("clean"); recovered = true; }
            }
            if (measured && !options.disappear.equals("none") && !disappeared && now - start >= 2_000_000_000L) {
                for (Peer p : peers) if (p.affected) {
                    if (options.disappear.equals("close")) p.client.close();
                    else p.client.config().setAutoRead(false);
                }
                disappeared = true;
            }
            if (now >= nextTick) {
                for (Peer peer : peers) {
                    if (!peer.server.isActive()) continue;
                    final long sequenceTick = tick;
                    double bytesPerTick = options.clientMbps * 1_000_000 / 8 * interval / 1e9;
                    long bytesDue = options.scenario.equals("resource") ? options.payload
                        : (long)((tick + 1) * bytesPerTick) - (long)(tick * bytesPerTick);
                    // Preserve fractional packet budgets across ticks instead of over-offering small rows.
                    peer.credit += bytesDue;
                    long budget = peer.credit;
                    if (options.scenario.equals("bulk")) budget = (budget / options.payload) * options.payload;
                    if (budget > 0) {
                        peer.credit -= budget;
                        final long sendBudget = budget;
                        if (peer.pendingBytes.addAndGet(sendBudget) > APPLICATION_PENDING_LIMIT) {
                            throw new IllegalStateException("Harness send queue exceeded 64 MiB for peer " + peer.id);
                        }
                        peer.server.eventLoop().execute(() -> sendBudget(peer, sendBudget, measured, sequenceTick));
                    }
                }
                tick++;
                nextTick += interval;
            }
            if (now >= nextProbe) {
                if (measured) for (Peer peer : peers) if (peer.server.isActive()) {
                    long sent = System.nanoTime();
                    peer.probesSent++;
                    peer.server.writeAndFlush(transport.wrap(BenchmarkPayload.probe(peer.server.alloc(), tick, sent)));
                }
                nextProbe += 100_000_000L;
            }
            if (measured && now >= nextSample) { sample("measurement"); nextSample += 200_000_000L; }
            long sleep = Math.min(nextTick - System.nanoTime(), 1_000_000L);
            if (sleep > 0) LockSupport.parkNanos(sleep);
        }
    }

    private void flushSubmissions() throws Exception {
        // Outside the CPU window. These barriers also make offered counters visible.
        for (Peer p : peers) p.server.eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private void sendBudget(Peer peer, long budget, boolean measured, long tick) {
        try {
            long bytes = 0;
            int[] batchSizes = {128, 512, 1200};
            while (bytes < budget && peer.server.isActive()) {
                if (measured && System.nanoTime() >= measurementEnd) {
                    peer.deadlineMissedBytes.addAndGet(budget - bytes);
                    break;
                }
                int size = options.scenario.equals("batch") ? batchSizes[(int)((peer.sequence + tick) % 3)] : options.payload;
                long sequence = measured ? peer.sequence++ : -1;
                ByteBuf message = options.scenario.equals("batch")
                    ? BenchmarkPayload.batch(peer.server.alloc(), size, sequence, 8)
                    : BenchmarkPayload.bulk(peer.server.alloc(), size, sequence);
                if (measured) { peer.offeredBytes += size; peer.offeredMessages++; }
                peer.server.write(transport.wrap(message)).addListener(f -> {
                    if (!f.isSuccess()) { writeFailures.incrementAndGet(); recordError(f.cause()); }
                });
                bytes += size;
            }
            peer.server.flush();
        } catch (Throwable e) { recordError(e); }
        finally { peer.pendingBytes.addAndGet(-budget); }
    }

    private void sample(String phase) throws Exception {
        long rss = 0;
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS:")) rss = Long.parseLong(line.trim().split("\\s+")[1]) * 1024;
        }
        long pending = Arrays.stream(peers).mapToLong(p -> p.pendingBytes.get()).sum();
        maxRssBytes = Math.max(maxRssBytes, rss); maxPendingBytes = Math.max(maxPendingBytes, pending);
        if (rss > 4L * 1024 * 1024 * 1024 || pending > 384L * 1024 * 1024) {
            throw new IllegalStateException("Benchmark process resource cap exceeded: rss=" + rss + " pending=" + pending);
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("phase", phase); row.put("elapsedMs", (System.nanoTime() - measurementStart) / 1e6);
        row.put("rssBytes", rss); row.put("applicationPendingBytes", pending);
        row.put("healthyDeliveredBytes", Arrays.stream(peers).filter(p -> !p.affected).mapToLong(p -> p.receivedBytes.get()).sum());
        row.put("affectedDeliveredBytes", Arrays.stream(peers).filter(p -> p.affected).mapToLong(p -> p.receivedBytes.get()).sum());
        row.put("openClients", Arrays.stream(peers).filter(p -> p.client.isOpen()).count());
        row.put("processCpuNanos", ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime());
        timeline.add(row);
    }

    private void shape(String profile) throws Exception {
        qdiscSnapshot("before-" + profile);
        List<String> args = new ArrayList<>(List.of("tc", "qdisc", "replace", "dev", "lo", "parent", "1:3", "handle", "30:", "netem", "limit", "100000"));
        switch (profile) {
            case "clean" -> args.addAll(List.of("delay", "0ms"));
            case "near" -> args.addAll(List.of("delay", "10ms", "2ms", "loss", "2%", "seed", "42"));
            case "regional" -> args.addAll(List.of("delay", "50ms", "5ms", "loss", "2%", "seed", "42"));
            case "poor" -> args.addAll(List.of("delay", "100ms", "10ms", "loss", "5%", "seed", "42"));
            case "severe" -> args.addAll(List.of("delay", "200ms", "20ms", "loss", "10%", "seed", "42"));
            case "blackhole" -> args.addAll(List.of("loss", "100%"));
            default -> throw new IllegalArgumentException("Unknown impairment " + profile);
        }
        Files.writeString(options.out.resolve("qdisc-events.log"), Instant.now() + " " + String.join(" ", args) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Process p = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(options.out.resolve("qdisc-events.log").toFile())).start();
        if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IllegalStateException("External qdisc failed");
        qdiscSnapshot("after-" + profile);
    }

    private void qdiscSnapshot(String phase) throws Exception {
        Process status = new ProcessBuilder("tc", "-s", "-j", "qdisc", "show", "dev", "lo").start();
        String data = new String(status.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!status.waitFor(5, TimeUnit.SECONDS) || status.exitValue() != 0) throw new IllegalStateException("qdisc status failed");
        Files.writeString(options.out.resolve("qdisc-" + phase + "-" + System.currentTimeMillis() + ".json"), data);
    }

    final class Receiver extends ChannelInboundHandlerAdapter {
        Peer peer;
        final boolean client;
        Receiver(Peer peer) { this.peer = peer; this.client = peer != null; }
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            try {
                ByteBuf content = ComparisonTransport.content(message);
                byte type = BenchmarkPayload.type(content);
                if (type == HELLO && !client) {
                    int id = Math.toIntExact(BenchmarkPayload.sequence(content));
                    if (id < 0 || id >= peers.length || peers[id].server != null) throw new IllegalStateException("Invalid/duplicate HELLO");
                    peer = peers[id]; peer.server = ctx.channel();
                } else if ((type == BenchmarkPayload.BULK || type == BenchmarkPayload.BATCH) && client) {
                    long sequence = BenchmarkPayload.sequence(content);
                    validatePayload(content, sequence, options.payload);
                    if (sequence >= 0) {
                        if (sequence < peer.expectedSequence) peer.orderingErrors.incrementAndGet();
                        else {
                            peer.sequenceGaps.addAndGet(sequence - peer.expectedSequence);
                            peer.expectedSequence = sequence + 1;
                        }
                        long now = System.nanoTime();
                        peer.receivedBytes.addAndGet(content.readableBytes()); peer.receivedMessages.incrementAndGet();
                        peer.lastDeliveryNanos = now;
                        if (now >= measurementStart && now < measurementEnd) peer.windowBytes.addAndGet(content.readableBytes());
                    }
                } else if (type == BenchmarkPayload.PROBE && client) {
                    ctx.writeAndFlush(transport.wrap(BenchmarkPayload.probeAck(ctx.alloc(), BenchmarkPayload.sequence(content), BenchmarkPayload.timestampNanos(content))));
                } else if (type == BenchmarkPayload.PROBE_ACK && !client) {
                    long now = System.nanoTime(), sent = BenchmarkPayload.timestampNanos(content);
                    if (sent >= measurementStart && sent < measurementEnd) {
                        settledLatency.record(now - sent); peer.settledLatency.record(now - sent);
                        if (now < measurementEnd) { latency.record(now - sent); peer.latency.record(now - sent); }
                    }
                } else throw new IllegalStateException("Unexpected application message " + type);
            } catch (Throwable failure) { recordError(failure); ctx.close(); }
            finally { ReferenceCountUtil.release(message); }
        }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable failure) { recordError(failure); ctx.close(); }
    }

    void recordError(Throwable failure) {
        if (errors.size() < 20) errors.add(failure == null ? "unknown failure" : failure.toString());
    }
    static Double percentile(LatencyHistogram histogram, double percentile) {
        var snapshot = histogram.snapshot();
        return snapshot.count() == 0 ? null : snapshot.percentileMillis(percentile);
    }
    static void validatePayload(ByteBuf content, long sequence, int payloadSize) {
        byte type = BenchmarkPayload.type(content);
        if (type == BenchmarkPayload.BULK && content.readableBytes() != payloadSize) throw new IllegalStateException("Bulk payload length changed");
        if (type == BenchmarkPayload.BATCH) {
            int index = content.readerIndex() + BenchmarkPayload.MIN_BATCH_HEADER_SIZE;
            int count = BenchmarkPayload.logicalPackets(content);
            if (count != 8) throw new IllegalStateException("Batch logical packet count changed");
            for (int i = 0; i < count; i++) {
                if (index + 4 > content.writerIndex()) throw new IllegalStateException("Truncated batch length");
                int size = content.getInt(index);
                if (size < 0 || size > content.writerIndex() - index - 4) throw new IllegalStateException("Invalid batch length");
                if (sequence >= 0 && sequence < 16) for (int j = 0; j < size; j++) {
                    if (content.getByte(index + 4 + j) != 0) throw new IllegalStateException("Batch payload corruption");
                }
                index += 4 + size;
            }
            if (index != content.writerIndex()) throw new IllegalStateException("Trailing batch bytes");
        } else if (sequence >= 0 && sequence < 16) {
            for (int i = content.readerIndex() + BenchmarkPayload.MIN_BULK_PAYLOAD_SIZE; i < content.writerIndex(); i++) {
                if (content.getByte(i) != 0) throw new IllegalStateException("Bulk payload corruption");
            }
        }
    }
    static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum(); }
    static final class Peer {
        final int id; final boolean affected;
        final AtomicLong receivedBytes = new AtomicLong(), receivedMessages = new AtomicLong(), windowBytes = new AtomicLong();
        final AtomicLong orderingErrors = new AtomicLong(), sequenceGaps = new AtomicLong(), pendingBytes = new AtomicLong(), deadlineMissedBytes = new AtomicLong();
        final LatencyHistogram latency = new LatencyHistogram(), settledLatency = new LatencyHistogram();
        volatile Channel server, client;
        volatile long lastDeliveryNanos;
        long sequence, expectedSequence, offeredBytes, offeredMessages, credit, probesSent;
        Peer(int id, boolean affected) { this.id = id; this.affected = affected; }
    }

    record Options(String transport, String scenario, int clients, int affected, int payload, double clientMbps,
                   long intervalMillis, long warmupMillis, long durationMillis, long drainMillis, int port,
                   String profile, String disappear, Path out, Path identity) {
        static Options parse(String[] args) {
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("Expected --key value");
                map.put(args[i].substring(2), args[i + 1]);
            }
            Options o = new Options(map.getOrDefault("transport", "raknet"), map.getOrDefault("scenario", "bulk"),
                Integer.parseInt(map.getOrDefault("clients", "1")), Integer.parseInt(map.getOrDefault("affected", "0")),
                Integer.parseInt(map.getOrDefault("payload", "512")), Double.parseDouble(map.getOrDefault("client-mbps", "5")),
                Long.parseLong(map.getOrDefault("interval-ms", "20")), Long.parseLong(map.getOrDefault("warmup-ms", "3000")),
                Long.parseLong(map.getOrDefault("duration-ms", "10000")), Long.parseLong(map.getOrDefault("drain-ms", "3000")),
                Integer.parseInt(map.getOrDefault("port", "19132")), map.getOrDefault("profile", "clean"), map.getOrDefault("disappear", "none"),
                Path.of(map.getOrDefault("out", "artifacts/smoke")).toAbsolutePath(),
                Path.of(map.getOrDefault("identity", "artifacts/identity")).toAbsolutePath());
            if (o.clients < 1 || o.affected < 0 || o.affected > o.clients || o.payload < 17 || o.payload > 262144
                || !Double.isFinite(o.clientMbps) || o.clientMbps <= 0 || o.intervalMillis <= 0 || o.durationMillis < 1 || o.warmupMillis < 0 || o.drainMillis < 0
                || !Set.of("bulk", "batch", "resource").contains(o.scenario)
                || !Set.of("none", "close", "stop-reading").contains(o.disappear)) throw new IllegalArgumentException("Invalid workload options");
            return o;
        }
        Map<String, Object> asMap() {
            return Map.ofEntries(Map.entry("transport", transport), Map.entry("scenario", scenario), Map.entry("clients", clients),
                Map.entry("affected", affected), Map.entry("payload", payload), Map.entry("clientMbps", clientMbps),
                Map.entry("intervalMillis", intervalMillis), Map.entry("warmupMillis", warmupMillis), Map.entry("durationMillis", durationMillis),
                Map.entry("drainMillis", drainMillis), Map.entry("port", port), Map.entry("profile", profile), Map.entry("disappear", disappear),
                Map.entry("out", out.toString()));
        }
    }
}
