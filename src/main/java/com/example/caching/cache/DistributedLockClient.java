package com.example.caching.cache;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.UUID;

/**
 * Redis Distributed Lock using the SET NX PX pattern.
 *
 * Problem:
 *   In a distributed system multiple application instances can race to perform
 *   the same exclusive operation (e.g. cache refresh, cron job, payment processing).
 *   A local Java synchronized block only protects within one JVM - it offers
 *   zero protection across multiple pods/nodes.
 *
 * Solution: Redis SET NX PX
 *   SET lock:<resource> <token> NX PX <ttlMs>
 *     NX  - Only set if the key does NOT already exist (atomic compare-and-set)
 *     PX  - Expire automatically after ttlMs milliseconds (prevents deadlock
 *           if the lock holder crashes before releasing)
 *
 *   The unique token (UUID) ensures only the thread that acquired the lock
 *   can release it (prevents a slow thread from accidentally releasing a lock
 *   acquired by a different thread after expiry).
 *
 * Important properties:
 *   - Mutual exclusion: only one holder at a time across all JVM instances.
 *   - Deadlock-safe: TTL guarantees automatic expiry if holder crashes.
 *   - No false releases: token validation prevents releasing another holder's lock.
 *
 * Production note:
 *   For mission-critical distributed locking consider Redlock (multi-node quorum)
 *   or a purpose-built library such as Redisson RLock.
 */
public class DistributedLockClient {

    private static final String LOCK_PREFIX = "lock:";

    private final JedisPool jedisPool;

    public DistributedLockClient(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    // -------------------------------------------------------------------------
    // Acquire
    // -------------------------------------------------------------------------

    /**
     * Attempts to acquire a distributed lock for the given resource.
     *
     * @param resource  logical name of the resource to protect (e.g. "user:u-101:refresh")
     * @param ttlMillis how long the lock is held before auto-expiry (safety net)
     * @return a unique lock token if acquired, or null if the lock is already held
     */
    public String tryAcquire(String resource, long ttlMillis) {
        String key   = LOCK_PREFIX + resource;
        String token = UUID.randomUUID().toString();

        try (Jedis jedis = jedisPool.getResource()) {
            // SET key token NX PX ttlMillis  — atomic, returns "OK" or null
            String result = jedis.set(key, token, SetParams.setParams().nx().px(ttlMillis));
            if ("OK".equals(result)) {
                System.out.printf("[LOCK] Acquired lock '%s' (token=%s, TTL=%dms).%n", key, token, ttlMillis);
                return token;
            }
            System.out.printf("[LOCK] Could not acquire lock '%s' - already held by another instance.%n", key);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Release
    // -------------------------------------------------------------------------

    /**
     * Releases the lock only if the supplied token matches the stored token.
     * This compare-and-delete must be atomic — implemented via an inline Lua
     * script so no other thread can slip in between the GET and DEL.
     *
     * @param resource the resource name passed to tryAcquire()
     * @param token    the token returned by tryAcquire()
     * @return true if the lock was released, false if token mismatch (already expired or stolen)
     */
    public boolean release(String resource, String token) {
        if (token == null) return false;
        String key = LOCK_PREFIX + resource;

        // Lua script: atomically check token and delete only if it matches
        String luaScript =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "  return redis.call('del', KEYS[1]) " +
                "else " +
                "  return 0 " +
                "end";

        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(luaScript,
                    java.util.Collections.singletonList(key),
                    java.util.Collections.singletonList(token));
            boolean released = Long.valueOf(1).equals(result);
            if (released) {
                System.out.printf("[LOCK] Released lock '%s'.%n", key);
            } else {
                System.out.printf("[LOCK] Release failed for '%s' - token mismatch (lock may have expired).%n", key);
            }
            return released;
        }
    }

    // -------------------------------------------------------------------------
    // Execute with lock (convenience wrapper)
    // -------------------------------------------------------------------------

    /**
     * Acquires the lock, runs the critical section, then releases.
     * If the lock cannot be acquired, the task is skipped entirely.
     *
     * @param resource  the resource to protect
     * @param ttlMillis lock TTL (auto-expiry safety net)
     * @param task      the critical section to execute exclusively
     */
    public void withLock(String resource, long ttlMillis, Runnable task) {
        String token = tryAcquire(resource, ttlMillis);
        if (token == null) {
            System.out.printf("[LOCK] Skipping task for '%s' - lock not available.%n", resource);
            return;
        }
        try {
            task.run();
        } finally {
            release(resource, token);
        }
    }
}
