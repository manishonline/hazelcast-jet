/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.memory.operation.aggregator;

import com.hazelcast.jet.io.SerializationOptimizer;
import com.hazelcast.jet.io.Pair;
import com.hazelcast.jet.memory.BaseMemoryTest;
import com.hazelcast.jet.memory.JetMemoryException;
import com.hazelcast.jet.memory.binarystorage.SortOrder;
import com.hazelcast.jet.memory.binarystorage.accumulator.Accumulator;
import com.hazelcast.jet.memory.binarystorage.accumulator.IntSumAccumulator;
import com.hazelcast.jet.memory.binarystorage.comparator.Comparator;
import com.hazelcast.jet.memory.binarystorage.comparator.StringComparator;
import com.hazelcast.jet.memory.memoryblock.MemoryChainingRule;
import com.hazelcast.jet.memory.memoryblock.MemoryContext;
import com.hazelcast.jet.memory.operation.OperationFactory;
import com.hazelcast.jet.memory.operation.aggregator.cursor.PairCursor;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;

@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class SortingAggregatorTest extends BaseMemoryTest {
    private SortedAggregator aggregator;
    private SerializationOptimizer optimizer = new SerializationOptimizer();

    @Override
    protected long heapSize() {
        return 200L * 1024L * 1024L;
    }

    @Override
    protected long blockSize() {
        return 128 * 1024;
    }

    @Before
    public void setUp() throws Exception {
        init();
    }

    private void initAggregator(Comparator comparator) {
        initAggregator(comparator, null);
    }

    private void initAggregator(Comparator comparator, Accumulator binaryAccumulator) {
        memoryContext = new MemoryContext(heapMemoryPool, nativeMemoryPool, blockSize(), useBigEndian());
        aggregator = OperationFactory.getSortedAggregator(memoryContext, optimizer, MemoryChainingRule.HEAP,
                2,//partitionCount
                1024,//spillingBufferSize
                comparator,
                new Pair(),
                binaryAccumulator,
                "",
                SortOrder.ASC,
                1024,//spillingChunkSize
                false,
                true
        );
    }

    private void insertElements(Pair<String, String> pair, int start, int end
    ) throws Exception {
        for (int i = end; i >= start; i--) {
            pair.setKey(String.valueOf(i));
            pair.setValue(String.valueOf(i));
            if (!aggregator.accept(pair)) {
                throw new JetMemoryException("Not enough memory (spilling is turned off)");
            }
        }
    }

    @Test
    public void testString2String() throws Exception {
        initAggregator(new StringComparator());
        Pair<String, String> pair = new Pair<>();
        int CNT = 1_000_000;
        long t = System.currentTimeMillis();
        insertElements(pair, 1, CNT);
        System.out.println("InsertionTime=" + (System.currentTimeMillis() - t));
        t = System.currentTimeMillis();
        final SortedAggregator aggregator = this.aggregator;
        aggregator.prepareToSort();
        while (!aggregator.sort()) {
        }
        System.out.println("SortingTime=" + (System.currentTimeMillis() - t));
        long time = System.currentTimeMillis();
        String previous = null;
        int iterations_count = 0;
        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            Pair<String, Integer> tt = (Pair<String, Integer>) cursor.asPair();
            assertTrue(previous == null || tt.getKey().compareTo(previous) > 0);
            previous = tt.getKey();
            iterations_count++;
        }
        Assert.assertEquals(CNT, iterations_count);
        System.out.println("SelectionTime=" + (System.currentTimeMillis() - time));
    }

    @Test
    public void testString2StringMultiValue() throws Exception {
        initAggregator(new StringComparator());
        Pair<String, String> pair = new Pair<>();
        int KEYS_CNT = 100_000;
        int VALUES_CNT = 10;
        byte[] markers = new byte[KEYS_CNT];
        Arrays.fill(markers, (byte) 0);
        long t = System.currentTimeMillis();
        for (int i = 1; i <= 100_000; i++) {
            pair.setKey(String.valueOf(i));
            for (int ii = 0; ii < 10; ii++) {
                pair.setValue(String.valueOf(ii));
                if (!aggregator.accept(pair)) {
                    throw new JetMemoryException("Not enough memory (spilling is turned off)");
                }
            }
        }
        System.out.println("InsertionTime=" + (System.currentTimeMillis() - t));
        t = System.currentTimeMillis();
        aggregator.prepareToSort();
        do {
        } while (!aggregator.sort());
        System.out.println("SortingTime=" + (System.currentTimeMillis() - t));
        int value_offset = 0;
        String previous = null;
        int iterations_count = 0;
        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            Pair<String, Integer> tt = (Pair<String, Integer>) cursor.asPair();
            String key = tt.getKey();
            if (value_offset == 0) {
                if (previous != null) {
                    assertTrue(tt.getKey().compareTo(previous) > 0);
                }
                previous = key;
                value_offset++;
            } else {
                Assert.assertEquals(key, previous);
                if (value_offset < VALUES_CNT - 1) {
                    value_offset++;
                } else if (value_offset == VALUES_CNT - 1) {
                    value_offset = 0;
                }

                previous = key;
            }

            iterations_count++;
        }

        Assert.assertEquals(KEYS_CNT * VALUES_CNT, iterations_count);
    }

    @Test
    public void testString2StringAssociativeAccumulator() throws Exception {
        initAggregator(new StringComparator(), new IntSumAccumulator());
        testAccumulator();
    }

    @Test
    public void testString2StringNonAssociativeAccumulator() throws Exception {
        initAggregator(new StringComparator(), new NonAssociativeSumAccumulator());
        testAccumulator();
    }

    private void testAccumulator() throws Exception {
        Pair<String, Integer> pair = new Pair<String, Integer>();

        int KEYS_CNT = 100_000;
        Integer VALUES_CNT = 10;

        long t = System.currentTimeMillis();
        for (int i = 1; i <= KEYS_CNT; i++) {
            pair.setKey(String.valueOf(i));
            for (int ii = 0; ii < VALUES_CNT; ii++) {
                pair.setValue(1);
                aggregator.accept(pair);
            }
        }

        System.out.println("InsertionTime=" + (System.currentTimeMillis() - t));

        long time = System.currentTimeMillis();

        aggregator.prepareToSort();
        do {
        } while (!aggregator.sort());
        System.out.println("SortingTime=" + (System.currentTimeMillis() - time));

        time = System.currentTimeMillis();
        String previous = null;
        int iterations_count = 0;
        for (PairCursor cursor = aggregator.cursor(); cursor.advance();) {
            Pair<String, Integer> tt = (Pair<String, Integer>) cursor.asPair();
            if (previous != null) {
                assertTrue(tt.getKey().compareTo(previous) > 0);
            }
            Assert.assertEquals("Iteration=" + iterations_count + " " + tt.getKey(), VALUES_CNT, tt.getValue());
            previous = tt.getKey();
            iterations_count++;
        }
        Assert.assertEquals(KEYS_CNT, iterations_count);
        System.out.println("SelectionTime=" + (System.currentTimeMillis() - time));
    }

    @After
    public void tearDown() throws Exception {
        aggregator.dispose();
        cleanUp();
    }
}