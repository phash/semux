/**
 * Copyright (c) 2017 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.db;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.lang3.tuple.Pair;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.semux.config.Constants;
import org.semux.db.LevelDB.LevelDBFactory;
import org.semux.util.Bytes;
import org.semux.util.ClosableIterator;

public class LevelDBTest {

    private byte[] key = Bytes.of("key");
    private byte[] value = Bytes.of("value");

    private LevelDB db;

    @Before
    public void setup() {
        db = new LevelDB(new File(Constants.DEFAULT_DATA_DIR, Constants.DATABASE_DIR + File.separator + "test"));
    }

    @After
    public void teardown() {
        db.destroy();
    }

    @Test
    public void testRecover() {
        db.close();
        Options options = db.createOptions();
        db.recover(options);
    }

    @Test
    public void testGetAndPut() {
        try {
            assertNull(db.get(key));
            db.put(key, value);
            assertTrue(Arrays.equals(value, db.get(key)));
            db.delete(key);
        } finally {
            db.close();
        }
    }

    @Test
    public void testUpdateBatch() {
        db.put(Bytes.of("a"), Bytes.of("1"));

        List<Pair<byte[], byte[]>> update = new ArrayList<>();
        update.add(Pair.of(Bytes.of("a"), null));
        update.add(Pair.of(Bytes.of("b"), Bytes.of("2")));
        update.add(Pair.of(Bytes.of("c"), Bytes.of("3")));
        db.updateBatch(update);

        assertNull(db.get(Bytes.of("a")));
        assertArrayEquals(db.get(Bytes.of("b")), Bytes.of("2"));
        assertArrayEquals(db.get(Bytes.of("c")), Bytes.of("3"));
    }

    @Test
    public void testIterator() {
        db.put(Bytes.of("a"), Bytes.of("1"));
        db.put(Bytes.of("b"), Bytes.of("2"));
        db.put(Bytes.of("c"), Bytes.of("3"));

        ClosableIterator<Entry<byte[], byte[]>> itr = db.iterator(Bytes.of("a1"));
        assertTrue(itr.hasNext());
        assertArrayEquals(Bytes.of("b"), itr.next().getKey());
        assertTrue(itr.hasNext());
        assertArrayEquals(Bytes.of("c"), itr.next().getKey());
        itr.close();
    }

    @Test
    public void testLevelDBFactory() {
        LevelDBFactory factory = new LevelDBFactory(new File(Constants.DEFAULT_DATA_DIR));
        for (DBName name : DBName.values()) {
            assertNotNull(factory.getDB(name));
        }
        factory.close();
    }

    @Test(expected = DBException.class)
    public void testClose() {
        db.close();

        db.get(key);
    }

    @Test
    public void testDestroy() {
        db.destroy();

        File f = new File(Constants.DEFAULT_DATA_DIR, Constants.DATABASE_DIR + File.separator + "test");
        assertFalse(f.exists());
    }
}
