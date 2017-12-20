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
import java.util.Collection;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.collections4.map.LRUMap;
import org.semux.Kernel;
import org.semux.config.Config;
import org.semux.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NodeManager {

    private static final Logger logger = LoggerFactory.getLogger(NodeManager.class);

    private static final ThreadFactory factory = new ThreadFactory() {
        private AtomicInteger cnt = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "node-" + cnt.getAndIncrement());
        }
    };

    private static final String DNS_SEED_MAINNET = "mainnet.semux.org";
    private static final String DNS_SEED_TESTNET = "testnet.semux.org";

    private static final int LRU_CACHE_SIZE = 1024;
    private static final long RECONNECT_WAIT = 2L * 60L * 1000L;

    private Kernel kernel;
    private Config config;

    private ChannelManager channelMgr;
    private PeerClient client;

    private Queue<InetSocketAddress> queue;
    private LRUMap<InetSocketAddress, Long> lastConnect; // not thread-safe

    private ScheduledExecutorService exec;
    private ScheduledFuture<?> connectFuture;
    private ScheduledFuture<?> seedingFuture;

    private volatile boolean isRunning;

    /**
     * Creates a node manager instance.
     * 
     * @param kernel
     */
    public NodeManager(Kernel kernel) {
        this.kernel = kernel;
        this.config = kernel.getConfig();

        this.channelMgr = kernel.getChannelManager();
        this.client = kernel.getClient();

        this.queue = new ConcurrentLinkedQueue<>();
        this.lastConnect = new LRUMap<>(LRU_CACHE_SIZE);

        this.exec = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /**
     * Starts the node manager
     */
    public synchronized void start() {
        if (!isRunning) {
            addNodes(config.p2pSeedNodes());

            connectFuture = exec.scheduleAtFixedRate(this::doConnect, 100, 500, TimeUnit.MILLISECONDS);
            seedingFuture = exec.scheduleAtFixedRate(this::doFetch, 0, 3, TimeUnit.MINUTES);

            isRunning = true;
            logger.info("Node manager started");
        }
    }

    /**
     * Stops this node manager.
     */
    public synchronized void stop() {
        if (isRunning) {
            connectFuture.cancel(true);
            seedingFuture.cancel(false);

            isRunning = false;
            logger.info("Node manager stopped");
        }
    }

    /**
     * Returns if the node manager is running or not.
     *
     * @return true if running, otherwise false
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Add a node to the connection queue.
     * 
     * @param node
     */
    public void addNode(InetSocketAddress node) {
        queue.add(node);
    }

    /**
     * Add a collection of nodes to the connection queue.
     * 
     * @param nodes
     */
    public void addNodes(Collection<InetSocketAddress> nodes) {
        queue.addAll(nodes);
    }

    /**
     * Get the connection queue size.
     * 
     * @return
     */
    public int queueSize() {
        return queue.size();
    }

    /**
     * Get seed nodes from DNS records.
     * 
     * @param networkId
     * @return
     */
    public static Set<InetSocketAddress> getSeedNodes(byte networkId) {
        Set<InetSocketAddress> nodes = new HashSet<>();

        try {
            String name;
            switch (networkId) {
            case Constants.MAIN_NET_ID:
                name = DNS_SEED_MAINNET;
                break;
            case Constants.TEST_NET_ID:
                name = DNS_SEED_TESTNET;
                break;
            default:
                return nodes;
            }

            for (InetAddress a : InetAddress.getAllByName(name)) {
                nodes.add(new InetSocketAddress(a, Constants.DEFAULT_P2P_PORT));
            }
        } catch (UnknownHostException e) {
            logger.info("Failed to get bootstrapping nodes by dns");
        }

        return nodes;
    }

    /**
     * Connect to a node in the queue.
     */
    protected void doConnect() {
        Set<InetSocketAddress> activeAddresses = channelMgr.getActiveAddresses();
        InetSocketAddress addr;

        while ((addr = queue.poll()) != null && channelMgr.size() < config.netMaxOutboundConnections()) {
            Long l = lastConnect.get(addr);
            long now = System.currentTimeMillis();

            if (!new InetSocketAddress(client.getIp(), client.getPort()).equals(addr)//
                    && !activeAddresses.contains(addr) //
                    && (l == null || l + RECONNECT_WAIT < now)) {

                SemuxChannelInitializer ci = new SemuxChannelInitializer(kernel, addr);
                client.connectAsync(addr, ci);
                lastConnect.put(addr, now);
                break;
            }
        }
    }

    /**
     * Fetches seed nodes from DNS records or configuration.
     */
    protected void doFetch() {
        addNodes(getSeedNodes(config.networkId()));
    }
}
