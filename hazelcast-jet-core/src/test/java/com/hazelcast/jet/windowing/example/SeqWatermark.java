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

package com.hazelcast.jet.windowing.example;

import com.hazelcast.jet.Watermark;

public final class SeqWatermark implements Watermark {

    private final long seq;

    public SeqWatermark(long seq) {
        this.seq = seq;
    }

    public long seq() {
        return seq;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof SeqWatermark && this.seq == ((SeqWatermark) o).seq;
    }

    @Override
    public int hashCode() {
        return (int) (seq ^ (seq >>> 32));
    }

    @Override
    public String toString() {
        return "SeqWatermark{seq=" + seq + '}';
    }
}