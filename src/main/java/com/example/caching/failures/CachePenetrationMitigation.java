package com.example.caching.failures;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;

import java.util.BitSet;
import java.util.Optional;

/**
 * Failure Mode 3: Cache Penetration
 *
 * Problem:
 *   An attacker (or a bug) repeatedly requests keys that do NOT exist in either
 *   the cache or the database. Every such request bypasses the cache (miss) and
 *   hits the DB — a perfect vector for a DoS attack against the database layer.
 *
 * Mitigations implemented:
 *   A) Null-Value Caching — when the DB returns no result, store an explicit
 *      sentinel marker in Redis with a short TTL so subsequent requests for the
 *      same non-existent key are served from cache without touching the DB.
 *   B) Bloom Filter Guard — a probabilistic data structure that answers
 *      "definitely does NOT exist" or "probably exists" before the cache is
 *      consulted. Requests for keys not in the Bloom Filter are rejected
 *      immediately with zero DB calls.
 */
public class CachePenetrationMitigation {

    private final RedisClient redisClient;
    private final UserRepository userRepository;

    /** Sentinel value written to cache for confirmed-non-existent keys. */
    private static final String NULL_SENTINEL = "__NULL__";
    /** Short TTL for null sentinels so legitimate future inserts are not blocked long. */
    private static final long NULL_TTL_SECONDS = 60;
    /** Normal TTL for real entries. */
    private static final long TTL_SECONDS = 300;

    // -------------------------------------------------------------------------
    // Mitigation B: Simple in-memory Bloom Filter
    // -------------------------------------------------------------------------
    /**
     * Minimal Bloom Filter backed by a {@link BitSet}.
     * Uses two independent hash functions derived from the key's hashCode.
     * In production, use Guava's {@code BloomFilter} or Redisson's
     * {@code RBloomFilter} for a distributed implementation.
     */
    private static final class SimpleBloomFilter {
        private final BitSet bits;
        private final int size;

        SimpleBloomFilter(int expectedInsertions) {
            // Use ~10 bits per element for ~1% false-positive rate
            this.size = expectedInsertions * 10;
            this.bits = new BitSet(this.size);
        }

        private int hash1(String key) {
            return Math.abs(key.hashCode() % size);
        }

        private int hash2(String key) {
            // Mix the hash to get an independent second probe position
            int h = key.hashCode() ^ (key.hashCode() >>> 16);
            return Math.abs((h * 0x9e3779b9) % size);
        }

        public void add(String key) {
            bits.set(hash1(key));
            bits.set(hash2(key));
        }

        /** Returns {@code false} if the key is DEFINITELY not in the set. */
        public boolean mightContain(String key) {
            return bits.get(hash1(key)) && bits.get(hash2(key));
        }
    }

    private final SimpleBloomFilter bloomFilter;

    public CachePenetrationMitigation(RedisClient redisClient, UserRepository userRepository) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
        // Pre-populate the Bloom Filter with known valid IDs
        this.bloomFilter = new SimpleBloomFilter(10_000);
        bloomFilter.add("u-101");
        bloomFilter.add("u-102");
    }

    /** Register a newly created user ID in the Bloom Filter. */
    public void registerUser(String id) {
        bloomFilter.add(id);
    }

    // -------------------------------------------------------------------------
    // Mitigation A: Null-Value Caching
    // -------------------------------------------------------------------------

    /**
     * Reads a user and caches a null sentinel for non-existent IDs.
     * On repeat penetration attempts for the same ID, the sentinel is returned
     * from Redis without touching the database.
     */
    public Optional<User> getUserWithNullCache(String id) {
        // Check for cached null sentinel via raw Redis (RedisClient.get returns empty on sentinel)
        Optional<User> cached = redisClient.get(id);
        if (cached.isPresent()) {
            return cached;
        }

        // Check if sentinel is stored (a separate flag key approach)
        // We store sentinel as a special User with name=NULL_SENTINEL to reuse RedisClient
        // Instead, use a dedicated Redis key pattern: "null:user:<id>"
        // For simplicity, we store sentinel in the same key via a User placeholder.
        // Production: use jedis.exists("null:user:" + id) directly.
        Optional<User> dbUser = userRepository.findById(id);
        if (dbUser.isEmpty()) {
            // Store sentinel: a User with the null marker as name
            System.out.printf("[PENETRATION] No record found for id='%s' – caching null sentinel for %ds.%n", id, NULL_TTL_SECONDS);
            redisClient.set(new User(id, NULL_SENTINEL, NULL_SENTINEL), NULL_TTL_SECONDS);
            return Optional.empty();
        }

        redisClient.set(dbUser.get(), TTL_SECONDS);
        return dbUser;
    }

    // -------------------------------------------------------------------------
    // Mitigation B: Bloom Filter Guard
    // -------------------------------------------------------------------------

    /**
     * Reads a user but gates the request through a Bloom Filter.
     * If the filter says the key DEFINITELY does not exist, the request is
     * rejected immediately — no cache lookup, no DB call.
     */
    public Optional<User> getUserWithBloomFilter(String id) {
        if (!bloomFilter.mightContain(id)) {
            System.out.printf("[BLOOM FILTER] id='%s' is DEFINITELY NOT in the dataset – request blocked.%n", id);
            return Optional.empty();
        }

        System.out.printf("[BLOOM FILTER] id='%s' might exist – proceeding to cache/DB lookup.%n", id);
        Optional<User> cached = redisClient.get(id);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<User> dbUser = userRepository.findById(id);
        dbUser.ifPresent(user -> redisClient.set(user, TTL_SECONDS));
        return dbUser;
    }
}
