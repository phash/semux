/**
 * Copyright (c) 2017 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.semux.config.Config;
import org.semux.config.Constants;
import org.semux.crypto.EdDSA;
import org.semux.util.SystemUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultMessageSizeEstimator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Represents a client which connects to the Semux network.
 */
public class PeerClient {

    private static final Logger logger = LoggerFactory.getLogger(PeerClient.class);

    private static final ThreadFactory factory = new ThreadFactory() {
        AtomicInteger cnt = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "client-" + cnt.getAndIncrement());
        }
    };

    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(factory);

    private ScheduledFuture<?> ipRefreshFuture = null;

    private String ip;
    private int port;
    private EdDSA coinbase;

    private EventLoopGroup workerGroup;

    /**
     * Create a new PeerClient instance.
     * 
     * @param config
     * @param coinbase
     */
    public PeerClient(Config config, EdDSA coinbase) {
        Optional<String> declaredIp = config.p2pDeclaredIp();
        if (declaredIp.isPresent()) {
            this.ip = declaredIp.get();
            logger.info("Use declared IP address: {}", ip);
        } else {
            this.ip = SystemUtil.getIp();
            logger.info("Use detected IP address: {}", ip);
            startIpRefresh();
        }
        this.port = config.p2pListenPort();
        this.coinbase = coinbase;

        this.workerGroup = new NioEventLoopGroup(0, factory);
    }

    /**
     * Create a new PeerClient with the given public IP address and coinbase.
     *
     * @param ip
     * @param port
     * @param coinbase
     */
    public PeerClient(String ip, int port, EdDSA coinbase) {
        this.ip = ip;
        this.port = port;
        this.coinbase = coinbase;

        this.workerGroup = new NioEventLoopGroup(0, factory);
    }

    /**
     * Keeps updating public IP address.
     */
    protected void startIpRefresh() {
        ipRefreshFuture = timer.scheduleAtFixedRate(() -> {
            String newIp = SystemUtil.getIp();
            try {
                if (!ip.equals(newIp) && !InetAddress.getByName(newIp).isSiteLocalAddress()) {
                    logger.info("Noticed IP change: {} => {}", ip, newIp);
                    ip = newIp;
                }
            } catch (UnknownHostException e) {
                logger.error("The fetched IP address is invalid: {}", newIp);
            }

        }, 15, 30, TimeUnit.SECONDS);
    }

    /**
     * Returns the listening IP address.
     * 
     * @return
     */
    public String getIp() {
        return ip;
    }

    /**
     * Returns the listening IP port.
     * 
     * @return
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the peerId of this client.
     * 
     * @return
     */
    public String getPeerId() {
        return coinbase.toAddressString();
    }

    /**
     * Returns the coinbase.
     * 
     * @return
     */
    public EdDSA getCoinbase() {
        return coinbase;
    }

    /**
     * Connects to a remote peer.
     * 
     * @param remoteAddress
     */
    public void connect(InetSocketAddress remoteAddress, SemuxChannelInitializer ci) {
        try {
            ChannelFuture f = connectAsync(remoteAddress, ci);

            f.sync();

            // Wait until the connection is closed.
            f.channel().closeFuture().sync();

            logger.debug("Connection is closed: {}", remoteAddress);
        } catch (Exception e) {
            logger.info("Unable to connect: {}", remoteAddress);
        }
    }

    /**
     * Connects to a remote peer asynchronously.
     * 
     * @param remoteAddress
     * @return
     */
    public ChannelFuture connectAsync(InetSocketAddress remoteAddress, SemuxChannelInitializer ci) {
        Bootstrap b = new Bootstrap();
        b.group(workerGroup);
        b.channel(NioSocketChannel.class);

        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Constants.DEFAULT_CONNECT_TIMEOUT);
        b.remoteAddress(remoteAddress);

        b.handler(ci);

        return b.connect();
    }

    /**
     * Closes this client.
     */
    public void close() {
        logger.info("Shutting down PeerClient");
        workerGroup.shutdownGracefully();
        workerGroup.terminationFuture().syncUninterruptibly();

        if (ipRefreshFuture != null) {
            ipRefreshFuture.cancel(true);
        }
    }
}