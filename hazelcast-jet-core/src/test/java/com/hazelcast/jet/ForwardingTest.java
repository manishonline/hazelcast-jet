/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet;

import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.hazelcast.jet.Edge.between;
import static com.hazelcast.jet.DistributedFunctions.wholeItem;
import static com.hazelcast.jet.TestUtil.executeAndPeel;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;

@Category(QuickTest.class)
@RunWith(HazelcastParallelClassRunner.class)
public class ForwardingTest extends JetTestSupport {

    private static final List<Integer> NUMBERS = IntStream.range(0, 10).
            boxed().collect(toList());

    private JetInstance instance;
    private JetTestInstanceFactory factory;

    @Before
    public void setupEngine() {
        factory = new JetTestInstanceFactory();
        instance = factory.newMember();
    }

    @After
    public void shutdown() {
        factory.shutdownAll();
    }

    @Test
    public void when_single() throws Throwable {
        DAG dag = new DAG();
        Vertex producer = new Vertex("producer", () -> new ListSource(NUMBERS)).localParallelism(1);

        int parallelism = 4;
        ProcSupplier supplier = new ProcSupplier();
        Vertex consumer = new Vertex("consumer", supplier).localParallelism(parallelism);

        dag.vertex(producer)
           .vertex(consumer)
           .edge(between(producer, consumer));

        execute(dag);

        Set<Object> combined = new HashSet<>();
        for (int i = 0; i < parallelism; i++) {
            combined.addAll(supplier.getListAt(i));
        }
        assertEquals(new HashSet<>(NUMBERS), combined);
    }

    @Test
    public void when_broadcast() throws Throwable {
        DAG dag = new DAG();
        Vertex producer = new Vertex("producer", () -> new ListSource(NUMBERS)).localParallelism(1);

        int parallelism = 4;
        ProcSupplier supplier = new ProcSupplier();
        Vertex consumer = new Vertex("consumer", supplier).localParallelism(parallelism);

        dag.vertex(producer)
           .vertex(consumer)
           .edge(between(producer, consumer).broadcast());

        execute(dag);

        for (int i = 0; i < parallelism; i++) {
            assertEquals(NUMBERS, supplier.getListAt(i));
        }
    }

    @Test
    public void when_partitioned() throws Throwable {
        DAG dag = new DAG();
        Vertex producer = new Vertex("producer", () -> new ListSource(NUMBERS)).localParallelism(1);

        int parallelism = 2;
        ProcSupplier supplier = new ProcSupplier();
        Vertex consumer = new Vertex("consumer", supplier).localParallelism(parallelism);

        dag.vertex(producer)
           .vertex(consumer)
           .edge(between(producer, consumer)
                   .partitioned(wholeItem(), (Integer item, int numPartitions) -> item % numPartitions));

        execute(dag);

        assertEquals(asList(0, 2, 4, 6, 8), supplier.getListAt(0));
        assertEquals(asList(1, 3, 5, 7, 9), supplier.getListAt(1));

    }

    private void execute(DAG dag) throws Throwable {
        executeAndPeel(instance.newJob(dag));
    }

    private static class ProcSupplier implements ProcessorSupplier {

        List<Processor> processors;

        @Override
        public void init(@Nonnull Context context) {
            processors = Stream.generate(ListSink::new).limit(context.localParallelism()).collect(toList());
        }

        @Override @Nonnull
        public List<Processor> get(int count) {
            assertEquals(processors.size(), count);
            return processors;
        }

        List<Object> getListAt(int i) {
            return ((ListSink) processors.get(i)).getList();
        }
    }
}
