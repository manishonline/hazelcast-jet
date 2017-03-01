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

package com.hazelcast.jet.impl.execution;

import com.hazelcast.jet.JetException;
import com.hazelcast.jet.Watermark;
import com.hazelcast.jet.impl.util.ProgressState;
import com.hazelcast.jet.impl.util.ProgressTracker;

import java.util.Arrays;
import java.util.Collection;

/**
 * {@code InboundEdgeStream} implemented in terms of a {@code ConcurrentConveyor}. The conveyor has as many
 * 1-to-1 concurrent queues as there are upstream tasklets contributing to it.
 */
public class ConcurrentInboundEdgeStream implements InboundEdgeStream {

    private final int ordinal;
    private final int priority;
    private final InboundEmitter[] producers;
    private final ProgressTracker tracker;
    private Watermark lastWatermark;
    private final boolean[] watermarkFound;
    private int watermarkFoundCount;
    private final CollectionWithWatermarkDetector watermarkDetector = new CollectionWithWatermarkDetector();

    public ConcurrentInboundEdgeStream(InboundEmitter[] producers, int ordinal, int priority) {
        this.producers = producers.clone();
        this.ordinal = ordinal;
        this.priority = priority;
        this.tracker = new ProgressTracker();
        watermarkFound = new boolean[producers.length];
    }

    @Override
    public ProgressState drainTo(Collection<Object> dest) {
        tracker.reset();
        watermarkDetector.wrapped = dest;
        for (int i = 0; i < producers.length; i++) {
            InboundEmitter producer = producers[i];
            if (producer != null && (lastWatermark == null || ! watermarkFound[i])) {
                watermarkDetector.watermark = null;
                ProgressState result = producer.drainTo(watermarkDetector::add);
                if (watermarkDetector.watermark != null) {
                    if (lastWatermark != null && ! watermarkDetector.watermark.equals(lastWatermark))
                        throw new JetException("Watermark emitted by one producer not in order to watermark produced by "
                                + "another producer, wm1=" + lastWatermark + ", wm2=" + watermarkDetector.watermark
                                + ", all producers must produce equal watermarks in the same order");
                    watermarkFound[i] = true;
                    watermarkFoundCount++;
                    lastWatermark = watermarkDetector.watermark;

                    if (watermarkFoundCount == producers.length) {
                        // we encountered equal watermark from all producers. Now add it to dest and get ready for next one.
                        dest.add(lastWatermark);
                        lastWatermark = null;
                        watermarkFoundCount = 0;
                        Arrays.fill(watermarkFound, false);

                        // restart the for loop - drain the producers again, possibly make them done
                        tracker.markDone();
                        i = -1;
                        continue;
                    }
                }
                if (result.isDone()) {
                    producers[i] = null;
                }
                tracker.mergeWith(result);
            }
        }
        return tracker.toProgressState();
    }

    @Override
    public int ordinal() {
        return ordinal;
    }

    @Override
    public int priority() {
        return priority;
    }
}

