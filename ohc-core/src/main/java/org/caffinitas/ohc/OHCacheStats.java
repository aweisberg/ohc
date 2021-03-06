/*
 *      Copyright (C) 2014 Robert Stupp, Koeln, Germany, robert-stupp.de
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.caffinitas.ohc;

import com.google.common.base.Objects;
import com.google.common.cache.CacheStats;

public final class OHCacheStats
{
    private final CacheStats cacheStats;
    private final long[] hashPartitionLengths;
    private final long capacity;
    private final long free;
    private final long size;
    private final long cleanupCount;
    private final long rehashCount;
    private final long putAddCount;
    private final long putReplaceCount;
    private final long putFailCount;
    private final long unlinkCount;

    public OHCacheStats(CacheStats cacheStats, long[] hashPartitionLengths, long size, long capacity, long free,
                        long cleanupCount, long rehashCount,
                        long putAddCount, long putReplaceCount, long putFailCount, long unlinkCount)
    {
        this.cacheStats = cacheStats;
        this.hashPartitionLengths = hashPartitionLengths;
        this.size = size;
        this.capacity = capacity;
        this.free = free;
        this.cleanupCount = cleanupCount;
        this.rehashCount = rehashCount;
        this.putAddCount = putAddCount;
        this.putReplaceCount = putReplaceCount;
        this.putFailCount = putFailCount;
        this.unlinkCount = unlinkCount;
    }

    public long getCapacity()
    {
        return capacity;
    }

    public long getFree()
    {
        return free;
    }

    public long getCleanupCount()
    {
        return cleanupCount;
    }

    public long getRehashCount()
    {
        return rehashCount;
    }

    public CacheStats getCacheStats()
    {
        return cacheStats;
    }

    public long[] getHashPartitionLengths()
    {
        return hashPartitionLengths;
    }

    public long getSize()
    {
        return size;
    }

    public long getPutAddCount()
    {
        return putAddCount;
    }

    public long getPutReplaceCount()
    {
        return putReplaceCount;
    }

    public long getPutFailCount()
    {
        return putFailCount;
    }

    public long getUnlinkCount()
    {
        return unlinkCount;
    }

    public double averageHashPartitionLength()
    {
        return avgOf(hashPartitionLengths);
    }

    public long minHashPartitionLength()
    {
        return minOf(hashPartitionLengths);
    }

    public long maxHashPartitionLength()
    {
        return maxOf(hashPartitionLengths);
    }

    public String toString()
    {
        return Objects.toStringHelper(this)
                      .add("cacheStats", cacheStats)
                      .add("size", size)
                      .add("capacity", capacity)
                      .add("free", free)
                      .add("cleanupCount", cleanupCount)
                      .add("rehashCount", rehashCount)
                      .add("put(add/replace/fail)", Long.toString(putAddCount)+'/'+putReplaceCount+'/'+putFailCount)
                      .add("unlinkCount", unlinkCount)
                      .add("hashPartitionLengths(#/min/max/avg)", String.format("%d/%d/%d/%.2f", hashPartitionLengths.length, minHashPartitionLength(), maxHashPartitionLength(), averageHashPartitionLength()))
                      .toString();
    }

    private static long maxOf(long[] arr)
    {
        long r = 0;
        for (long l : arr)
            if (l > r)
                r = l;
        return r;
    }

    private static long minOf(long[] arr)
    {
        long r = Long.MAX_VALUE;
        for (long l : arr)
            if (l < r)
                r = l;
        return r;
    }

    private static double avgOf(long[] arr)
    {
        double r = 0d;
        for (long l : arr)
            r += l;
        return r / arr.length;
    }
}
