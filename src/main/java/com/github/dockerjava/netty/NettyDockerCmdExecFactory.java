package com.github.dockerjava.netty;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.Security;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import com.github.dockerjava.core.AbstractDockerCmdExecFactory;
import com.github.dockerjava.core.WebTarget;
import org.apache.commons.lang.SystemUtils;

import com.github.dockerjava.api.command.DockerCmdExecFactory;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.SSLConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.kqueue.KQueueDomainSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.unix.UnixChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;


/**
 * Experimental implementation of {@link DockerCmdExecFactory} that supports http connection hijacking that is needed to pass STDIN to the
 * container.
 * <p>
 * To use it just pass an instance via {@link DockerClientImpl#withDockerCmdExecFactory(DockerCmdExecFactory)}
 *
 * @author Marcus Linke
 * @see https://docs.docker.com/engine/reference/api/docker_remote_api_v1.21/#attach-to-a-container
 * @see https://docs.docker.com/engine/reference/api/docker_remote_api_v1.21/#exec-start
 */
public class NettyDockerCmdExecFactory extends AbstractDockerCmdExecFactory implements DockerCmdExecFactory {

    private static String threadPrefix = "dockerjava-netty";

    /*
     * useful links:
     *
     * http://stackoverflow.com/questions/33296749/netty-connect-to-unix-domain-socket-failed
     * http://netty.io/wiki/native-transports.html
     * https://github.com/netty/netty/blob/master/example/src/main/java/io/netty/example/http/snoop/HttpSnoopClient.java
     * https://github.com/slandelle/netty-request-chunking/blob/master/src/test/java/slandelle/ChunkingTest.java
     */

    private Bootstrap bootstrap;

    private EventLoopGroup eventLoopGroup;

    private NettyInitializer nettyInitializer;

    private WebTarget baseResource;

    private ChannelProvider channelProvider = new ChannelProvider() {
        @Override
        public DuplexChannel getChannel() {
            DuplexChannel channel = connect();
            channel.pipeline().addLast(new LoggingHandler(getClass()));
            return channel;
        }
    };

    private Integer connectTimeout = null;

    @Override
    public void init(DockerClientConfig dockerClientConfig) {
        super.init(dockerClientConfig);

        bootstrap = new Bootstrap();

        String scheme = dockerClientConfig.getDockerHost().getScheme();

        if ("unix".equals(scheme)) {
            nettyInitializer = new UnixDomainSocketInitializer();
        } else if ("tcp".equals(scheme)) {
            nettyInitializer = new InetSocketInitializer();
        }

        eventLoopGroup = nettyInitializer.init(bootstrap, dockerClientConfig);

        baseResource = new NettyWebTarget(channelProvider).path(dockerClientConfig.getApiVersion().asWebPathPart());
    }

    private DuplexChannel connect() {
        try {
            return connect(bootstrap);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private DuplexChannel connect(final Bootstrap bootstrap) throws InterruptedException {
        return nettyInitializer.connect(bootstrap);
    }

    private interface NettyInitializer {
        EventLoopGroup init(final Bootstrap bootstrap, DockerClientConfig dockerClientConfig);

        DuplexChannel connect(final Bootstrap bootstrap) throws InterruptedException;
    }

    private class UnixDomainSocketInitializer implements NettyInitializer {
        @Override
        public EventLoopGroup init(Bootstrap bootstrap, DockerClientConfig dockerClientConfig) {
            if (SystemUtils.IS_OS_LINUX) {
                return epollGroup();
            } else if (SystemUtils.IS_OS_MAC_OSX) {
                return kqueueGroup();
            }
            throw new RuntimeException("Unspported OS");
        }

        public EventLoopGroup epollGroup() {
            EventLoopGroup epollEventLoopGroup = new EpollEventLoopGroup(0, new DefaultThreadFactory(threadPrefix));

            ChannelFactory<EpollDomainSocketChannel> factory = new ChannelFactory<EpollDomainSocketChannel>() {
                @Override
                public EpollDomainSocketChannel newChannel() {
                    return configure(new EpollDomainSocketChannel());
                }
            };

            bootstrap.group(epollEventLoopGroup).channelFactory(factory).handler(new ChannelInitializer<UnixChannel>() {
                @Override
                protected void initChannel(final UnixChannel channel) throws Exception {
                    channel.pipeline().addLast(new HttpClientCodec());
                }
            });
            return epollEventLoopGroup;
        }

        public EventLoopGroup kqueueGroup() {
            EventLoopGroup nioEventLoopGroup = new KQueueEventLoopGroup(0, new DefaultThreadFactory(threadPrefix));

            bootstrap.group(nioEventLoopGroup).channel(KQueueDomainSocketChannel.class)
                    .handler(new ChannelInitializer<KQueueDomainSocketChannel>() {
                        @Override
                        protected void initChannel(final KQueueDomainSocketChannel channel) throws Exception {
                            channel.pipeline().addLast(new LoggingHandler(getClass()));
                            channel.pipeline().addLast(new HttpClientCodec());
                        }
                    });

            return nioEventLoopGroup;
        }

        @Override
        public DuplexChannel connect(Bootstrap bootstrap) throws InterruptedException {
            return (DuplexChannel) bootstrap.connect(new DomainSocketAddress("/var/run/docker.sock")).sync().channel();
        }
    }

    private class InetSocketInitializer implements NettyInitializer {
        @Override
        public EventLoopGroup init(Bootstrap bootstrap, final DockerClientConfig dockerClientConfig) {
            EventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(0, new DefaultThreadFactory(threadPrefix));

            InetAddress addr = InetAddress.getLoopbackAddress();

            final SocketAddress proxyAddress = new InetSocketAddress(addr, 8008);

            Security.addProvider(new BouncyCastleProvider());

            ChannelFactory<NioSocketChannel> factory = new ChannelFactory<NioSocketChannel>() {
                @Override
                public NioSocketChannel newChannel() {
                    return configure(new NioSocketChannel());
                }
            };

            bootstrap.group(nioEventLoopGroup).channelFactory(factory)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel channel) throws Exception {
                            // channel.pipeline().addLast(new
                            // HttpProxyHandler(proxyAddress));
                            channel.pipeline().addLast(new HttpClientCodec());
                        }
                    });

            return nioEventLoopGroup;
        }

        @Override
        public DuplexChannel connect(Bootstrap bootstrap) throws InterruptedException {
            DockerClientConfig dockerClientConfig = getDockerClientConfig();
            String host = dockerClientConfig.getDockerHost().getHost();
            int port = dockerClientConfig.getDockerHost().getPort();

            if (port == -1) {
                throw new RuntimeException("no port configured for " + host);
            }

            DuplexChannel channel = (DuplexChannel) bootstrap.connect(host, port).sync().channel();

            final SslHandler ssl = initSsl(dockerClientConfig);

            if (ssl != null) {
                channel.pipeline().addFirst(ssl);
            }

            return channel;
        }

        private SslHandler initSsl(DockerClientConfig dockerClientConfig) {
            SslHandler ssl = null;

            try {
                String host = dockerClientConfig.getDockerHost().getHost();
                int port = dockerClientConfig.getDockerHost().getPort();

                final SSLConfig sslConfig = dockerClientConfig.getSSLConfig();

                if (sslConfig != null && sslConfig.getSSLContext() != null) {

                    SSLEngine engine = sslConfig.getSSLContext().createSSLEngine(host, port);
                    engine.setUseClientMode(true);
                    engine.setSSLParameters(enableHostNameVerification(engine.getSSLParameters()));

                    // in the future we may use HostnameVerifier like here:
                    // https://github.com/AsyncHttpClient/async-http-client/blob/1.8.x/src/main/java/com/ning/http/client/providers/netty/NettyConnectListener.java#L76

                    ssl = new SslHandler(engine);
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            return ssl;
        }
    }

    public SSLParameters enableHostNameVerification(SSLParameters sslParameters) {
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        return sslParameters;
    }

    @Override
    public void close() throws IOException {
        checkNotNull(eventLoopGroup, "Factory not initialized. You probably forgot to call init()!");

        eventLoopGroup.shutdownGracefully();
    }

    /**
     * Configure connection timeout in milliseconds
     */
    public NettyDockerCmdExecFactory withConnectTimeout(Integer connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    private <T extends Channel> T configure(T channel) {
        ChannelConfig channelConfig = channel.config();

        if (connectTimeout != null) {
            channelConfig.setConnectTimeoutMillis(connectTimeout);
        }

        return channel;
    }

    protected WebTarget getBaseResource() {
        checkNotNull(baseResource, "Factory not initialized, baseResource not set. You probably forgot to call init()!");
        return baseResource;
    }
}
