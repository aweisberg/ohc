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
package org.caffinitas.ohc.tables;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import org.caffinitas.ohc.OHCacheBuilder;
import org.caffinitas.ohc.histo.EstimatedHistogram;

final class OffHeapMap
{
    // maximum hash table size
    private static final int MAX_TABLE_SIZE = 1 << 30;

    private final int entriesPerBucket;
    private long size;
    private Table table;

    private long threshold;
    private final float loadFactor;

    private long lruCompactions;
    private long hitCount;
    private long missCount;
    private long putAddCount;
    private long putReplaceCount;
    private long removeCount;

    private long rehashes;
    private long evictedEntries;

    private final AtomicLong freeCapacity;

    private final ReentrantLock lock = new ReentrantLock();

    OffHeapMap(OHCacheBuilder builder, AtomicLong freeCapacity)
    {
        this.freeCapacity = freeCapacity;

        int hts = builder.getHashTableSize();
        if (hts <= 0)
            hts = 8192;
        if (hts < 256)
            hts = 256;
        int bl = builder.getBucketLength();
        if (bl <= 0)
            bl = 8;
        int buckets = (int) Util.roundUpToPowerOf2(hts, MAX_TABLE_SIZE);
        entriesPerBucket = (int) Util.roundUpToPowerOf2(bl, MAX_TABLE_SIZE);
        table = Table.create(buckets, entriesPerBucket);
        if (table == null)
            throw new RuntimeException("unable to allocate off-heap memory for segment");

        float lf = builder.getLoadFactor();
        if (lf <= .0d)
            lf = .75f;
        if (lf >= 1d)
            throw new IllegalArgumentException("load factor must not be greater that 1");
        this.loadFactor = lf;
        threshold = (long) ((double) table.size() * loadFactor);
    }

    void release()
    {
        lock.lock();
        try
        {
            table.release();
            table = null;
        }
        finally
        {
            lock.unlock();
        }
    }

    long size()
    {
        return size;
    }

    long hitCount()
    {
        return hitCount;
    }

    long missCount()
    {
        return missCount;
    }

    long putAddCount()
    {
        return putAddCount;
    }

    long putReplaceCount()
    {
        return putReplaceCount;
    }

    long removeCount()
    {
        return removeCount;
    }

    void resetStatistics()
    {
        rehashes = 0L;
        evictedEntries = 0L;
        hitCount = 0L;
        missCount = 0L;
        putAddCount = 0L;
        putReplaceCount = 0L;
        removeCount = 0L;
        lruCompactions = 0L;
    }

    long rehashes()
    {
        return rehashes;
    }

    long evictedEntries()
    {
        return evictedEntries;
    }

    long lruCompactions()
    {
        return lruCompactions;
    }

    long getEntry(KeyBuffer key, boolean reference)
    {
        lock.lock();
        try
        {
            long ptr = table.bucketOffset(key.hash());
            long hashEntryAdr;
            for (int idx = 0; idx < entriesPerBucket; idx++, ptr += Util.BUCKET_ENTRY_LEN)
            {
                if ((hashEntryAdr = table.getEntryAdr(ptr)) == 0L
                    || table.getHash(ptr) != key.hash()
                    || notSameKey(key, hashEntryAdr))
                    continue;

                // return existing entry

                touch(hashEntryAdr);

                if (reference)
                    HashEntries.reference(hashEntryAdr);

                hitCount++;
                return hashEntryAdr;
            }

            // not found
            missCount++;
            return 0L;
        }
        finally
        {
            lock.unlock();
        }
    }

    boolean putEntry(long newHashEntryAdr, long hash, long keyLen, long bytes, boolean ifAbsent, long oldValueAdr, long oldValueLen)
    {
        long removeHashEntryAdr = 0L;
        LongArrayList derefList = null;
        long fc = freeCapacity.get();
        long ofc = fc;
        lock.lock();
        try
        {
            long hashEntryAdr;
            long ptr = table.bucketOffset(hash);
            for (int idx = 0; idx < entriesPerBucket; idx++, ptr += Util.BUCKET_ENTRY_LEN)
            {
                if ((hashEntryAdr = table.getEntryAdr(ptr)) == 0L
                    || table.getHash(ptr) != hash
                    || notSameKey(newHashEntryAdr, keyLen, hashEntryAdr))
                    continue;

                // replace existing entry

                if (ifAbsent)
                    return false;

                if (oldValueAdr != 0L)
                {
                    // code for replace() operation
                    if (HashEntries.getValueLen(hashEntryAdr) != oldValueLen
                        || !HashEntries.compare(hashEntryAdr, Util.ENTRY_OFF_DATA + Util.roundUpTo8(keyLen), oldValueAdr, 0L, oldValueLen))
                        return false;
                }

                table.removeFromTableWithOff(hashEntryAdr, ptr);
                fc += HashEntries.getAllocLen(hashEntryAdr);

                removeHashEntryAdr = hashEntryAdr;

                break;
            }

            if (fc < bytes)
            {
                derefList = new LongArrayList();
                do
                {
                    long eldestEntryAdr = table.removeEldest();
                    if (eldestEntryAdr == 0L)
                    {
                        if (removeHashEntryAdr != 0L)
                            size--;
                        return false;
                    }

                    fc += HashEntries.getAllocLen(eldestEntryAdr);

                    size--;
                    evictedEntries++;
                    derefList.add(eldestEntryAdr);
                } while (fc < bytes);
            }

            if (removeHashEntryAdr == 0L)
            {
                if (size >= threshold)
                    rehash();

                size++;
            }

            if (!add(newHashEntryAdr, hash))
                return false;

            fc -= bytes;

            if (removeHashEntryAdr == 0L)
                putAddCount++;
            else
                putReplaceCount++;

            return true;
        }
        finally
        {
            lock.unlock();
            freeCapacity.addAndGet(fc - ofc);
            if (removeHashEntryAdr != 0L)
                HashEntries.dereference(removeHashEntryAdr);
            if (derefList != null)
                for (int i = 0; i < derefList.size(); i++)
                    HashEntries.dereference(derefList.getLong(i));
        }
    }

    void clear()
    {
        lock.lock();
        try
        {
            size = 0L;

            long hashEntryAdr;
            for (int p = 0; p < table.size(); p++)
            {
                long ptr = table.bucketOffset(p);
                for (int idx = 0; idx < entriesPerBucket; idx++, ptr += Util.BUCKET_ENTRY_LEN)
                {
                    if ((hashEntryAdr = table.getEntryAdr(ptr)) == 0L)
                        continue;

                    freeCapacity.addAndGet(HashEntries.getAllocLen(hashEntryAdr));
                    HashEntries.dereference(hashEntryAdr);
                }
            }

            table.clear();
        }
        finally
        {
            lock.unlock();
        }
    }

    void removeEntry(long removeHashEntryAdr)
    {
        lock.lock();
        try
        {
            long hash = HashEntries.getHash(removeHashEntryAdr);
            long hashEntryAdr;
            long ptr = table.bucketOffset(hash);
            for (int idx = 0; idx < entriesPerBucket; idx++, ptr += Util.BUCKET_ENTRY_LEN)
            {
                if ((hashEntryAdr = table.getEntryAdr(ptr)) != removeHashEntryAdr)
                    continue;

                // remove existing entry

                removeInternal(hashEntryAdr, ptr);

                return;
            }
            removeHashEntryAdr = 0L;
        }
        finally
        {
            lock.unlock();
            if (removeHashEntryAdr != 0L)
                HashEntries.dereference(removeHashEntryAdr);
        }
    }

    void removeEntry(KeyBuffer key)
    {
        long removeHashEntryAdr = 0L;
        lock.lock();
        try
        {
            long hashEntryAdr;
            long ptr = table.bucketOffset(key.hash());
            for (int idx = 0; idx < entriesPerBucket; idx++, ptr += Util.BUCKET_ENTRY_LEN)
            {
                if ((hashEntryAdr = table.getEntryAdr(ptr)) == 0L
                    || table.getHash(ptr) != key.hash()
                    || notSameKey(key, hashEntryAdr))
                    continue;

                // remove existing entry

                removeHashEntryAdr = hashEntryAdr;
                removeInternal(hashEntryAdr, ptr);

                return;
            }
        }
        finally
        {
            lock.unlock();
            if (removeHashEntryAdr != 0L)
                HashEntries.dereference(removeHashEntryAdr);
        }
    }

    private void removeInternal(long hashEntryAdr, long off)
    {
        table.removeFromTableWithOff(hashEntryAdr, off);

        freeCapacity.addAndGet(HashEntries.getAllocLen(hashEntryAdr));

        size--;
        removeCount++;
    }

    private static boolean notSameKey(KeyBuffer key, long hashEntryAdr)
    {
        long serKeyLen = HashEntries.getKeyLen(hashEntryAdr);
        return serKeyLen != key.size()
               || !HashEntries.compareKey(hashEntryAdr, key, serKeyLen);
    }

    private static boolean notSameKey(long newHashEntryAdr, long newKeyLen, long hashEntryAdr)
    {
        long serKeyLen = HashEntries.getKeyLen(hashEntryAdr);
        return serKeyLen != newKeyLen
               || !HashEntries.compare(hashEntryAdr, Util.ENTRY_OFF_DATA, newHashEntryAdr, Util.ENTRY_OFF_DATA, serKeyLen);
    }

    private void rehash()
    {
        Table tab = table;
        int tableSize = tab.size();
        if (tableSize > MAX_TABLE_SIZE)
        {
            // already at max hash table size
            return;
        }

        Table newTable = Table.create(tableSize * 2, entriesPerBucket);
        if (newTable == null)
            return;

        for (int part = 0; part < tableSize; part++)
        {
            long hashEntryAdr;
            long ptr = table.bucketOffset(part);
            for (int idx = 0; idx < entriesPerBucket; idx++, ptr += Util.BUCKET_ENTRY_LEN)
            {
                if ((hashEntryAdr = table.getEntryAdr(ptr)) == 0L)
                    continue;

                if (!newTable.addToTable(table.getHash(ptr), hashEntryAdr))
                    HashEntries.dereference(hashEntryAdr);
            }
        }
        newTable.copyLRU(table);

        threshold = (long) ((float) newTable.size() * loadFactor);
        table.release();
        table = newTable;
        rehashes++;
    }

    long[] hotN(int n)
    {
        lock.lock();
        try
        {
            long[] r = new long[n];
            table.fillHotN(r, n);
            for (long hashEntryAdr : r)
                if (hashEntryAdr != 0L)
                    HashEntries.reference(hashEntryAdr);
            return r;
        }
        finally
        {
            lock.unlock();
        }
    }

    float loadFactor()
    {
        return loadFactor;
    }

    int hashTableSize()
    {
        return table.size();
    }

    void updateBucketHistogram(EstimatedHistogram hist)
    {
        lock.lock();
        try
        {
            table.updateBucketHistogram(hist);
        }
        finally
        {
            lock.unlock();
        }
    }

    void getEntryAddresses(int mapSegmentIndex, int nSegments, List<Long> hashEntryAdrs)
    {
        lock.lock();
        try
        {
            for (; nSegments-- > 0 && mapSegmentIndex < table.size(); mapSegmentIndex++)
            {
                long hashEntryAdr;
                long ptr = table.bucketOffset(mapSegmentIndex);
                for (int idx = 0; idx < entriesPerBucket; idx++, ptr += Util.BUCKET_ENTRY_LEN)
                {
                    if ((hashEntryAdr = table.getEntryAdr(ptr)) == 0L)
                        continue;
                    hashEntryAdrs.add(hashEntryAdr);
                    HashEntries.reference(hashEntryAdr);
                }
            }
        }
        finally
        {
            lock.unlock();
        }
    }

    static final class Table
    {
        final int mask;
        final long address;
        private final int entriesPerBucket;
        private boolean released;

        private final long lruOffset;

        private int lruWriteTarget;
        private int lruEldestIndex;

        static Table create(int hashTableSize, int entriesPerBucket)
        {
            int msz = (int) Util.BUCKET_ENTRY_LEN * hashTableSize * entriesPerBucket;

            msz += hashTableSize * Util.POINTER_LEN;

            long address = Uns.allocate(msz);
            return address != 0L ? new Table(address, hashTableSize, entriesPerBucket) : null;
        }

        private Table(long address, int hashTableSize, int entriesPerBucket)
        {
            this.address = address;
            this.mask = hashTableSize - 1;
            this.entriesPerBucket = entriesPerBucket;

            this.lruOffset = Util.BUCKET_ENTRY_LEN * hashTableSize * entriesPerBucket;
            this.lruWriteTarget = 0;

            clear();
        }

        //

        long removeEldest()
        {
            int i = lruEldestIndex;
            long off = lruOffset(i);
            for (; i < lruWriteTarget; i++, off += Util.POINTER_LEN)
            {
                long hashEntryAdr = Uns.getAndPutLong(address, off, 0L);
                if (hashEntryAdr != 0L)
                {
                    lruEldestIndex = i + 1;

                    off = bucketOffset(HashEntries.getHash(hashEntryAdr));
                    for (i = 0; i < entriesPerBucket; i++, off += Util.BUCKET_ENTRY_LEN)
                        if (Uns.compareAndSwapLong(address, off, hashEntryAdr, 0L))
                        {
                            return hashEntryAdr;
                        }

                    assert false;
                }
            }
            return 0;
        }

        void fillHotN(long[] r, int n)
        {
            int c = 0;
            long hashEntryAdr;
            int i = lruWriteTarget - 1;
            long off = lruOffset(i);
            for (; i >= lruEldestIndex; i--, off -= Util.POINTER_LEN)
            {
                if ((hashEntryAdr = Uns.getLong(address, off)) != 0L)
                {
                    r[c++] = hashEntryAdr;
                    if (c == n)
                        return;
                }
            }
        }

        void copyLRU(Table srcTable)
        {
            lruEldestIndex = srcTable.lruEldestIndex;
            lruWriteTarget = srcTable.lruWriteTarget;
            Uns.copyMemory(srcTable.address, srcTable.lruOffset(0), address, lruOffset(0), lruWriteTarget * Util.POINTER_LEN);
        }

        boolean addToLRU(long hashEntryAdr)
        {
            if (lruWriteTarget < size())
            {
                // try to add to current-write-target
                entryToLRU(hashEntryAdr, lruWriteTarget++);
                return false;
            }

            // LRU table compaction needed

            int id = 0;
            long adr;
            int is = lruEldestIndex;
            long off = lruOffset(is);
            for (; is < size(); is++, off += Util.POINTER_LEN)
                if ((adr = Uns.getLong(address, off)) != 0L)
                {
                    if (is != id)
                        entryToLRU(adr, id);
                    id++;
                }

            // add hash-entry to LRU
            entryToLRU(hashEntryAdr, id++);

            lruWriteTarget = id;
            lruEldestIndex = 0;

            // clear remaining LRU table
            Uns.setMemory(address, lruOffset(id),
                          (size() - id) * Util.POINTER_LEN,
                          (byte) 0);

            return true;
        }

        private void entryToLRU(long hashEntryAdr, int id)
        {
            Uns.putLong(address, lruOffset(id), hashEntryAdr);
            HashEntries.setLRUIndex(hashEntryAdr, id);
        }

        void removeFromLRU(long hashEntryAdr)
        {
            int lruIndex = HashEntries.getLRUIndex(hashEntryAdr);
            if (lruEldestIndex == lruIndex)
                lruEldestIndex++;
            if (lruIndex == lruWriteTarget - 1)
                lruWriteTarget = lruIndex;
            if (!Uns.compareAndSwapLong(address, lruOffset(lruIndex), hashEntryAdr, 0L))
                assert false;
        }

        private long lruOffset(int i)
        {
            return lruOffset + i * Util.POINTER_LEN;
        }

        //

        void clear()
        {
            // It's important to initialize the hash table memory.
            // (uninitialized memory will cause problems - endless loops, JVM crashes, damaged data, etc)
            Uns.setMemory(address, 0L,
                          Util.BUCKET_ENTRY_LEN * entriesPerBucket * size() +
                          Util.POINTER_LEN * size(),
                          (byte) 0);
        }

        void release()
        {
            Uns.free(address);
            released = true;
        }

        protected void finalize() throws Throwable
        {
            if (!released)
                Uns.free(address);
            super.finalize();
        }

        boolean addToTable(long hash, long hashEntryAdr)
        {
            long off = bucketOffset(hash);
            for (int i = 0; i < entriesPerBucket; i++, off += Util.BUCKET_ENTRY_LEN)
                if (Uns.compareAndSwapLong(address, off, 0L, hashEntryAdr))
                {
                    Uns.putLong(address, off + Util.BUCKET_OFF_HASH, hash);
                    return true;
                }
            return false;
        }

        void removeFromTableWithOff(long hashEntryAdr, long off)
        {
            if (!Uns.compareAndSwapLong(address, off, hashEntryAdr, 0L))
                assert false;

            removeFromLRU(hashEntryAdr);
        }

        long bucketOffset(long hash)
        {
            return bucketIndexForHash(hash) * entriesPerBucket * Util.BUCKET_ENTRY_LEN;
        }

        private int bucketIndexForHash(long hash)
        {
            return (int) (hash & mask);
        }

        int size()
        {
            return mask + 1;
        }

        void updateBucketHistogram(EstimatedHistogram h)
        {
            long off = 0L;
            for (int p = 0; p < size(); p++)
            {
                int len = 0;
                for (int i = 0; i < entriesPerBucket; i++, off += Util.BUCKET_ENTRY_LEN)
                    if (getEntryAdr(off) != 0L)
                        len++;
                h.add(len + 1);
            }
        }

        long getEntryAdr(long entryOff)
        {
            return Uns.getLong(address, entryOff);
        }

        long getHash(long entryOff)
        {
            return Uns.getLong(address, entryOff + Util.BUCKET_OFF_HASH);
        }
    }

    private boolean add(long hashEntryAdr, long hash)
    {
        if (!table.addToTable(hash, hashEntryAdr))
            return false;

        addToLRU(hashEntryAdr);
        return true;
    }

    private void touch(long hashEntryAdr)
    {
        table.removeFromLRU(hashEntryAdr);
        addToLRU(hashEntryAdr);
    }

    private void addToLRU(long hashEntryAdr)
    {
        if (table.addToLRU(hashEntryAdr))
            lruCompactions++;
    }
}