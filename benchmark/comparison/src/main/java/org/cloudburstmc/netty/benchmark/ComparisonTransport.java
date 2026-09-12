package org.cloudburstmc.netty.benchmark;

import dev.kastle.netty.channel.nethernet.NetherNetClientChannel;
import dev.kastle.netty.channel.nethernet.NetherNetConstants;
import dev.kastle.netty.channel.nethernet.admission.AdmissionGate;
import dev.kastle.netty.channel.nethernet.config.NetherChannelOption;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.signalling.ProviderTransport;
import org.cloudburstmc.netty.signalling.admission.NativeProviderTransport;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Only setup and message wrapping differ. Neither library's runtime is edited. */
final class ComparisonTransport implements AutoCloseable {
    private final boolean raknet;
    private final EventLoopGroup serverGroup = new NioEventLoopGroup(2);
    private final EventLoopGroup clientGroup = new NioEventLoopGroup(2);
    private final List<Channel> clients = new ArrayList<>();
    private final List<Channel> children = new CopyOnWriteArrayList<>();
    private NativeProviderTransport provider;
    private Channel server;
    private String fingerprint, audience;
    private final int port;

    ComparisonTransport(String name, int port) {
        if (!Set.of("raknet", "nethernet").contains(name)) throw new IllegalArgumentException("Unknown transport " + name);
        this.raknet = name.equals("raknet");
        this.port = port;
    }

    void start(int count, Path identity, Supplier<ChannelHandler> handlers) throws Exception {
        ServerBootstrap bootstrap = new ServerBootstrap().group(serverGroup)
            .childHandler(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel ch) {
                    children.add(ch);
                    ch.pipeline().addLast(handlers.get());
                }
            });
        if (raknet) {
            server = bootstrap.channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, new int[]{RakConstants.RAKNET_PROTOCOL_VERSION})
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, count + 16)
                // Production proxy disables these limits. Keep this explicit in results.
                .option(RakChannelOption.RAK_PACKET_LIMIT, 0)
                .option(RakChannelOption.RAK_GLOBAL_PACKET_LIMIT, 0)
                .childOption(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
                .bind("127.0.0.1", port).sync().channel();
        } else {
            provider = NativeProviderTransport.open(bootstrap, new InetSocketAddress("127.0.0.1", port),
                identity.resolve("cert.pem"), identity.resolve("key.pem"),
                new AdmissionGate.Limits(count + 16, (count + 16) * 2, count + 16, 15_000))
                .toCompletableFuture().get(15, TimeUnit.SECONDS);
            provider.installTicketKeys(List.of(new ProviderTransport.TicketKey("K001", TestSignallingProvider.SECRET)))
                .toCompletableFuture().get();
            var profile = provider.hostProfile().toCompletableFuture().get();
            fingerprint = profile.get("dtlsFingerprint").getAsString();
            audience = NativeProviderTransport.audience(profile.getAsJsonObject("statelessAdmission").get("incarnation").getAsString());
            server = provider.channel();
        }
    }

    Channel connect(int id, boolean affected, ChannelHandler handler) throws Exception {
        String local = affected ? "127.0.0.2" : "127.0.0.3";
        Bootstrap bootstrap = new Bootstrap().group(clientGroup).handler(handler);
        if (raknet) {
            bootstrap.channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, (int) RakConstants.RAKNET_PROTOCOL_VERSION)
                .option(RakChannelOption.RAK_MTU, 1400)
                .option(RakChannelOption.RAK_ORDERING_CHANNELS, 1);
        } else {
            bootstrap.channelFactory(() -> new NetherNetClientChannel(new LocalAdmissionSignalling(id)))
                .option(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG,
                    PeerConnectionConfiguration.DEFAULT.withBindAddress(InetAddress.getByName(local))
                        .withMaxMessageSize(NetherNetConstants.MAX_ADVERTISED_MESSAGE_SIZE));
        }
        ChannelFuture future = raknet
            ? bootstrap.connect(new InetSocketAddress("127.0.0.1", port), new InetSocketAddress(local, 0))
            : bootstrap.connect(new InetSocketAddress("127.0.0.1", port));
        clients.add(future.channel());
        if (!future.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Client " + id + " connect timeout");
        if (!future.isSuccess()) throw new IllegalStateException("Client " + id + " connect failed", future.cause());
        return future.channel();
    }

    Object wrap(ByteBuf payload) {
        // The same reliable ordered stream and normal priority carry load and probes.
        return raknet ? new RakMessage(payload, RakReliability.RELIABLE_ORDERED, RakPriority.NORMAL, 0) : payload;
    }

    static ByteBuf content(Object message) {
        return message instanceof RakMessage m ? m.content() : (ByteBuf) message;
    }

    private final class LocalAdmissionSignalling implements NetherNetClientSignaling {
        private final int id;
        private final Map<Long, SignalHandler> handlers = new ConcurrentHashMap<>();
        LocalAdmissionSignalling(int id) { this.id = id; }
        @Override public CompletableFuture<List<IceServerInfo>> connect(SocketAddress remote) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public void setSignalHandler(long id, SignalHandler handler) { handlers.put(id, handler); }
        @Override public void removeSignalHandler(long id) { handlers.remove(id); }
        @Override public String getLocalNetworkId() { return Integer.toString(id + 1); }
        @Override public void close() { handlers.clear(); }
        @Override public void setNotFoundHandler(NotFoundHandler handler) {}
        @Override public void sendSignal(String target, String signal) {
            String[] parts = signal.split(" ", 3);
            if (!parts[0].equals(NetherNetConstants.RTC_NEGOTIATION_CONNECT_REQUEST)) return;
            try {
                var answer = TestSignallingProvider.answer(parts[2], fingerprint, port,
                    System.currentTimeMillis() + 30_000, audience, false);
                handlers.get(Long.parseUnsignedLong(parts[1])).onSignal(
                    NetherNetConstants.RTC_NEGOTIATION_CONNECT_RESPONSE + " " + parts[1] + " " + answer.sdp());
            } catch (Exception e) { throw new IllegalStateException("Local benchmark admission failed", e); }
        }
    }

    @Override public void close() throws Exception {
        for (Channel ch : clients) ch.close();
        for (Channel ch : clients) ch.closeFuture().await(10, TimeUnit.SECONDS);
        if (provider != null) provider.close().toCompletableFuture().get(15, TimeUnit.SECONDS);
        else {
            for (Channel ch : children) ch.close();
            if (server != null) server.close().await(10, TimeUnit.SECONDS);
        }
        clientGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
        serverGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
    }
}
