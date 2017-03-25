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

package com.hazelcast.jet.windowing;

import com.hazelcast.jet.Distributed.Function;
import com.hazelcast.jet.Distributed.Supplier;
import com.hazelcast.jet.Distributed.ToLongFunction;
import com.hazelcast.jet.Punctuation;
import com.hazelcast.jet.StreamingProcessorBase;
import com.hazelcast.jet.stream.DistributedCollector;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import static com.hazelcast.jet.Traversers.traverseIterable;
import static com.hazelcast.jet.Traversers.traverseWithRemoval;

/**
 * Aggregates events into session windows. Events under different grouping
 * keys are completely independent, so there is a separate window for each
 * key. A newly observed event will be placed into the existing session
 * window if:
 * <ol><li>
 *     it is not behind the punctuation (that is, it is not a late event)
 * </li><li>
 *     its {@code eventSeq} is less than {@code maxSeqGap} ahead of the top
 *     {@code eventSeq} in the currently maintained window.
 * </li></ol>
 * If the event satisfies 1. but fails 2., the current window will be closed
 * and emitted as a final result, and a new window will be opened with the
 * current event.
 *
 * @param <T> type of stream event
 * @param <K> type of event's grouping key
 * @param <A> type of the container of accumulated value
 * @param <R> type of the result value for a session window
 */
public class SessionWindowP<T, K, A, R> extends StreamingProcessorBase {

    private final long maxSeqGap;
    private final ToLongFunction<? super T> extractEventSeqF;
    private final Function<? super T, K> extractKeyF;
    private final Supplier<A> newAccumulatorF;
    private final BiConsumer<? super A, ? super T> accumulateF;
    private final Function<A, R> finishAccumulationF;
    private final Map<K, Long> keyToDeadline = new HashMap<>();
    private final SortedMap<Long, Map<K, A>> deadlineToKeyToAcc = new TreeMap<>();
    private final FlatMapper<Punctuation, Frame<K, R>> expiredSessFlatmapper;
    private long lastObservedPunc;

    public SessionWindowP(
            long maxSeqGap,
            ToLongFunction<? super T> extractEventSeqF,
            Function<? super T, K> extractKeyF,
            DistributedCollector<? super T, A, R> collector
    ) {
        this.extractEventSeqF = extractEventSeqF;
        this.extractKeyF = extractKeyF;
        this.newAccumulatorF = collector.supplier();
        this.accumulateF = collector.accumulator();
        this.finishAccumulationF = collector.finisher();
        this.maxSeqGap = maxSeqGap;

        expiredSessFlatmapper = flatMapper(punc ->
                traverseWithRemoval(deadlineToKeyToAcc.headMap(punc.seq() + 1).entrySet())
                        .flatMap(deadlineAndKeyToAcc -> traverseIterable(deadlineAndKeyToAcc.getValue().entrySet())
                                .peek(keyAndAcc -> keyToDeadline.remove(keyAndAcc.getKey()))
                                .map(keyAndAcc -> new Frame<>(
                                        deadlineAndKeyToAcc.getKey(), keyAndAcc.getKey(),
                                        finishAccumulationF.apply(keyAndAcc.getValue())))
                        ));
    }

    @Override
    protected boolean tryProcess0(@Nonnull Object item) {
        final T event = (T) item;
        final long eventSeq = extractEventSeqF.applyAsLong(event);
        // drop late events
        if (eventSeq <= lastObservedPunc) {
            return true;
        }
        final long pushDeadlineTo = eventSeq + maxSeqGap;
        final K key = extractKeyF.apply(event);
        final Long oldDeadline = keyToDeadline.get(key);
        final long newDeadline;
        final A acc;
        if (oldDeadline == null) {
            newDeadline = pushDeadlineTo;
            acc = newAccumulatorF.get();
            accumulateF.accept(acc, event);
        } else {
            newDeadline = Math.max(oldDeadline, pushDeadlineTo);
            Map<K, A> oldDeadlineMap = deadlineToKeyToAcc.get(oldDeadline);
            acc = oldDeadlineMap.get(key);
            accumulateF.accept(acc, event);
            if (newDeadline == oldDeadline) {
                return true;
            }
            oldDeadlineMap.remove(key);
            if (oldDeadlineMap.isEmpty()) {
                deadlineToKeyToAcc.remove(oldDeadline);
            }
        }
        deadlineToKeyToAcc.computeIfAbsent(newDeadline, x -> new HashMap<>())
                          .put(key, acc);
        keyToDeadline.put(key, newDeadline);
        return true;
    }

    @Override
    protected boolean tryProcessPunc0(@Nonnull Punctuation punc) {
        lastObservedPunc = punc.seq();
        return expiredSessFlatmapper.tryProcess(punc);
    }
}