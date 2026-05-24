package dev.realfarlands.generator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe LRU cache keyed on chunk coordinates.
 * Stores a boolean[16][16][HEIGHT] density grid for each chunk.
 */
public final class ChunkCache {

    private final int maxSize;
    // LinkedHashMap with access-order=true gives us O(1) LRU eviction.
    private final Map<Long, boolean[][][]> cache;

    public ChunkCache(int maxSize) {
        this.maxSize = maxSize;
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, boolean[][][]> eldest) {
                return size() > ChunkCache.this.maxSize;
            }
        };
    }

    /** Pack two ints into a single long key. */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public synchronized boolean[][][] get(int chunkX, int chunkZ) {
        return cache.get(key(chunkX, chunkZ));
    }

    public synchronized void put(int chunkX, int chunkZ, boolean[][][] data) {
        cache.put(key(chunkX, chunkZ), data);
    }

    public synchronized int size() {
        return cache.size();
    }

    public synchronized void clear() {
        cache.clear();
    }
}
