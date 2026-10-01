package com.example.caching.failures;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Failure Mode 1: Cache Stampede (Thundering Herd)
 *
 * Problem:
 *   A hot key expires under heavy concurrent load. All threads simultaneously
 *   get a cache miss and hammer the database, causing a spike that can bring
 *   the DB down.
 *
 * Mitigations implemented:
 *   A) Mutex / Singleflight — only ONE thread fetches from DB on a miss;
 *      all other concurrent threads wait and re-use the result.
 *   B) Probabilistic Early Expiration (XFetch) — a single thread proactively
 *      re-fetches before the TTL expires, spreading the DB reload over time
 *      and avoiding the simultaneous expiry thundering herd entirely.
 */
public class CacheStampedeMitigation {

    // -------------------------------------------------------------------------
    // Shared state
    // -------------------------------------------------------------------------
    private final RedisClient redisClient;
    private final UserRepository userRepository;
    private final JedisPool jedisPool;

    /** Per-key locks used for the Mutex/Singleflight pattern. */
    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    /** Base TTL in seconds for cached entries. */
    private static final long TTL_SECONDS = 30;
    /** XFetch beta constant: higher = more aggressive early re-fetch. */
    private static final double XFETCH_BETA = 1.0;

    public CacheStampedeMitigation(RedisClient redisClient, UserRepository userRepository, JedisPool jedisPool) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
        this.jedisPool = jedisPool;
    }

    // -------------------------------------------------------------------------
    // Mitigation A: Mutex / Singleflight
    // -------------------------------------------------------------------------

    /**
     * Fetches a user with Mutex/Singleflight protection.
     * On a cache miss, only one thread queries the DB; all others wait and
     * read the result populated by the winner thread.
     */
    public Optional<User> getUserWithMutex(String id) {
        // 1. Fast path: try the cache first (no lock needed)
        Optional<User> cached = redisClient.get(id);
        if (cached.isPresent()) {
            return cached;
        }

        // 2. Acquire a per-key lock so only ONE thread proceeds to the DB
        ReentrantLock lock = keyLocks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            // 3. Double-check: another thread may have already populated the cache
            Optional<User> doubleCheck = redisClient.get(id);
            if (doubleCheck.isPresent()) {
                System.out.printf("[MUTEX] Double-check hit for id='%s' – reusing result populated by another thread.%n", id);
                return doubleCheck;
            }

            // 4. This thread is the winner — fetch from DB and populate cache
            System.out.printf("[MUTEX] Cache miss for id='%s' – this thread fetches from DB.%n", id);
            Optional<User> dbUser = userRepository.findById(id);
            dbUser.ifPresent(user -> redisClient.set(user, TTL_SECONDS));
            return dbUser;
        } finally {
            lock.unlock();
            keyLocks.remove(id); // clean up lock entry (harmless race, lock is re-created if needed)
        }
    }

    // -------------------------------------------------------------------------
    // Mitigation B: Probabilistic Early Expiration (XFetch / Optimal Probabilistic Cache Stampede Prevention)
    // -------------------------------------------------------------------------

    /**
     * Fetches a user using the XFetch algorithm.
     * A thread decides to re-fetch early based on:
     *   currentTime - beta * delta * ln(random) > expiryTime
     * where delta is the measured fetch duration (in seconds).
     * This ensures re-fetches happen *before* expiry, probabilistically distributed
     * across threads, so no stampede ever occurs at the exact expiry moment.
     */
    public Optional<User> getUserWithXFetch(String id) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = "user:" + id;
            long remainingTtl = jedis.ttl(key);            // seconds remaining; -2 = expired/missing
            long nowEpoch     = System.currentTimeMillis() / 1000;
            long expiryEpoch  = (remainingTtl > 0) ? (nowEpoch + remainingTtl) : 0;

            // Simulate estimated fetch duration (use 0.05s as a conservative baseline)
            double delta = 0.05;

            // XFetch decision: should this thread proactively re-fetch?
            boolean shouldRefresh = (remainingTtl <= 0)
                    || (nowEpoch - XFETCH_BETA * delta * Math.log(Math.random())) > expiryEpoch;

            if (!shouldRefresh) {
                System.out.printf("[XFETCH] Cache valid for id='%s' (TTL=%ds) – serving from cache.%n", id, remainingTtl);
                return redisClient.get(id);
            }

            System.out.printf("[XFETCH] Early re-fetch triggered for id='%s' (TTL=%ds remaining).%n", id, remainingTtl);
            Optional<User> dbUser = userRepository.findById(id);
            dbUser.ifPresent(user -> redisClient.set(user, TTL_SECONDS));
            return dbUser;
        }
    }
}
