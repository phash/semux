/**
 * Copyright (c) 2017 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.collections4.map.LRUMap;
import org.apache.commons.lang3.tuple.Pair;
import org.semux.Kernel;
import org.semux.config.Config;
import org.semux.core.state.AccountState;
import org.semux.core.state.DelegateState;
import org.semux.net.Channel;
import org.semux.net.ChannelManager;
import org.semux.net.msg.p2p.TransactionMessage;
import org.semux.util.ArrayUtil;
import org.semux.util.ByteArray;
import org.semux.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pending manager maintains all unconfirmed transactions, either from kernel or
 * network. All transactions are evaluated and propagated to peers if success.
 * 
 * TODO: sort transaction queue by fee, and other metrics
 *
 */
public class PendingManager implements Runnable, BlockchainListener {

    private static final Logger logger = LoggerFactory.getLogger(PendingManager.class);

    private static final ThreadFactory factory = new ThreadFactory() {

        private AtomicInteger cnt = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "pending-" + cnt.getAndIncrement());
        }
    };

    private static final int QUEUE_MAX_SIZE = 64 * 1024;
    private static final int POOL_MAX_SIZE = 8 * 1024;
    private static final int DELAYED_MAX_SIZE = 16 * 1024;
    private static final int PROCESSED_MAX_SIZE = 16 * 1024;

    private Config config;

    private Blockchain chain;
    private ChannelManager channelMgr;
    private AccountState pendingAS;
    private DelegateState pendingDS;

    /**
     * Transaction queue.
     */
    private LinkedList<Transaction> queue = new LinkedList<>();

    /**
     * Transaction pool.
     */
    private Map<ByteArray, Transaction> pool = new HashMap<>();
    private List<Transaction> transactions = new ArrayList<>();
    private List<TransactionResult> results = new ArrayList<>();

    // NOTE: make sure access to the LRUMap<> are synchronized.
    private Map<ByteArray, Transaction> delayed = new LRUMap<>(DELAYED_MAX_SIZE);
    private Map<ByteArray, ?> processed = new LRUMap<>(PROCESSED_MAX_SIZE);

    private ScheduledExecutorService exec;
    private ScheduledFuture<?> validateFuture;

    private volatile boolean isRunning;

    /**
     * Creates a pending manager.
     */
    public PendingManager(Kernel kernel) {
        this.config = kernel.getConfig();

        this.chain = kernel.getBlockchain();
        this.channelMgr = kernel.getChannelManager();

        this.pendingAS = chain.getAccountState().track();
        this.pendingDS = chain.getDelegateState().track();

        this.exec = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /**
     * Starts this pending manager.
     */
    public synchronized void start() {
        if (!isRunning) {
            /*
             * NOTE: a rate smaller than the message queue sending rate should be used to
             * prevent message queues from hitting the NET_MAX_QUEUE_SIZE, especially when
             * the network load is heavy.
             */
            this.validateFuture = exec.scheduleAtFixedRate(this, 2, 2, TimeUnit.MILLISECONDS);

            this.chain.addListener(this);

            logger.debug("Pending manager started");
            this.isRunning = true;
        }
    }

    /**
     * Shuts down this pending manager.
     */
    public synchronized void stop() {
        if (isRunning) {
            validateFuture.cancel(true);

            logger.debug("Pending manager stopped");
            isRunning = false;
        }
    }

    /**
     * Returns whether the pending manager is running or not.
     * 
     * @return
     */
    public synchronized boolean isRunning() {
        return isRunning;
    }

    /**
     * Returns a copy of the queue, for test purpose only.
     * 
     * @return
     */
    public synchronized List<Transaction> getQueue() {
        return new ArrayList<>(queue);
    }

    /**
     * Adds a transaction to the queue, which will be validated later by the
     * background worker.
     * 
     * @param tx
     */
    public synchronized void addTransaction(Transaction tx) {
        if (queue.size() < QUEUE_MAX_SIZE) {
            queue.add(tx);
        }
    }

    /**
     * Adds a transaction to the pool.
     * 
     * @param tx
     * @return true if the transaction is successfully added to the pool, otherwise
     *         false
     */
    public synchronized boolean addTransactionSync(Transaction tx) {
        return tx.validate() && processTransaction(tx, true) >= 1;
    }

    /**
     * Returns the nonce of an account based on the pending state.
     * 
     * @param address
     * @return
     */
    public synchronized long getNonce(byte[] address) {
        return pendingAS.getAccount(address).getNonce();
    }

    /**
     * Returns pending transactions and corresponding results.
     * 
     * @param limit
     * @return
     */
    public synchronized Pair<List<Transaction>, List<TransactionResult>> getTransactionsAndResults(int limit) {
        List<Transaction> txs = new ArrayList<>();
        List<TransactionResult> res = new ArrayList<>();

        if (limit == -1) {
            // returns all transactions if there is no limit
            txs.addAll(transactions);
            res.addAll(results);
        } else {
            Iterator<Transaction> itTransaction = transactions.iterator();
            Iterator<TransactionResult> itResult = results.iterator();

            int size = 0;
            while (itTransaction.hasNext() && itResult.hasNext()) {
                Transaction tx = itTransaction.next();
                TransactionResult txResult = itResult.next();

                if (size + tx.size() > limit) {
                    break;
                } else {
                    txs.add(tx);
                    res.add(txResult);
                }
            }
        }

        return Pair.of(txs, res);
    }

    /**
     * Returns a limited number of transactions in the pool.
     * 
     * @param limit
     * @return
     */
    public synchronized List<Transaction> getTransactions(int limit) {
        return getTransactionsAndResults(limit).getLeft();
    }

    /**
     * Returns all transactions in the pool.
     * 
     * @return
     */
    public synchronized List<Transaction> getTransactions() {
        return getTransactionsAndResults(-1).getLeft();
    }

    /**
     * Clear all pending transactions
     * 
     * @return
     */
    public synchronized List<Transaction> clear() {
        // reset state
        pendingAS = chain.getAccountState().track();
        pendingDS = chain.getDelegateState().track();

        // clear transaction pool
        List<Transaction> txs = new ArrayList<>(transactions);
        pool.clear();
        transactions.clear();
        results.clear();

        return txs;
    }

    @Override
    public synchronized void onBlockAdded(Block block) {
        if (isRunning) {
            long t1 = System.currentTimeMillis();

            // clear transaction pool
            List<Transaction> txs = clear();

            // update pending state
            long accepted = 0;
            for (Transaction tx : txs) {
                accepted += processTransaction(tx, false);
            }

            long t2 = System.currentTimeMillis();
            logger.debug("Pending tx evaluation: # txs = {} / {},  time =  {} ms", accepted, txs.size(), t2 - t1);
        }
    }

    @Override
    public synchronized void run() {
        Transaction tx;

        while (pool.size() < POOL_MAX_SIZE //
                && (tx = queue.poll()) != null //
                && tx.getFee() >= config.minTransactionFee()) {
            // filter by cache
            ByteArray key = ByteArray.of(tx.getHash());
            if (processed.containsKey(key)) {
                continue;
            }

            if (tx.validate() && processTransaction(tx, true) >= 1) {
                // exit after one success transaction
                return;
            }

            processed.put(key, null);
        }
    }

    /**
     * Validates the given transaction and add to pool if success.
     * 
     * @param tx
     *            transaction
     * @param relay
     *            whether to relay the transaction if success
     * @return the number of success transactions processed
     */
    protected int processTransaction(Transaction tx, boolean relay) {

        // NOTE: assume transaction format is valid

        int cnt = 0;
        while (tx != null && tx.getNonce() == getNonce(tx.getFrom())) {

            // check transaction timestamp
            long now = System.currentTimeMillis();
            long twoHours = TimeUnit.HOURS.toMillis(2);
            if (tx.getTimestamp() < now - twoHours || tx.getTimestamp() > now + twoHours) {
                return 0;
            }

            // execute transactions
            AccountState as = pendingAS.track();
            DelegateState ds = pendingDS.track();
            TransactionResult result = new TransactionExecutor(config).execute(tx, as, ds);

            if (result.isSuccess()) {
                // commit state updates
                as.commit();
                ds.commit();

                // add transaction to pool
                pool.put(createKey(tx), tx);
                transactions.add(tx);
                results.add(result);
                cnt++;

                // relay transaction
                if (relay) {
                    List<Channel> channels = channelMgr.getActiveChannels();
                    TransactionMessage msg = new TransactionMessage(tx);
                    int[] indices = ArrayUtil.permutation(channels.size());
                    for (int i = 0; i < indices.length && i < config.netRelayRedundancy(); i++) {
                        Channel c = channels.get(indices[i]);
                        if (c.isActive()) {
                            c.getMessageQueue().sendMessage(msg);
                        }
                    }
                }
            } else {
                // exit immediately if invalid
                return cnt;
            }

            tx = delayed.get(createKey(tx.getFrom(), getNonce(tx.getFrom())));
        }

        // add to cache
        if (tx != null && tx.getNonce() > getNonce(tx.getFrom())) {
            delayed.put(createKey(tx), tx);
        }

        return cnt;
    }

    private ByteArray createKey(Transaction tx) {
        return ByteArray.of(Bytes.merge(tx.getFrom(), Bytes.of(tx.getNonce())));
    }

    private ByteArray createKey(byte[] acc, long nonce) {
        return ByteArray.of(Bytes.merge(acc, Bytes.of(nonce)));
    }
}
