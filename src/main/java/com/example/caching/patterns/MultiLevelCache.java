package com.example.caching.patterns;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pattern: Multi-Level Cache (L1 + L2)
 *
 * Architecture:
 *   L1 - Local in-process cache (bounded LinkedHashMap, LRU eviction).
 *        Ultra-fast: pure heap access, zero network latency.
 *        Small capacity - only the hottest keys live here.
 *
 *   L2 - Distributed Redis cache (shared across all app instances).
 *        Fast: ~1ms network round trip.
 *        Large capacity - all recently accessed keys live here.
 *
 *   DB - Primary database (fallback of last resort).
 *
 * Read path:  L1 hit -> return immediately
 *             L1 miss -> L2 hit -> promote to L1 -> return
 *             L2 miss -> DB fetch -> populate L2 -> promote to L1 -> return
 *
 * Write path: write to DB -> evict from L2 -> evict from L1
 *             (evict-on-write keeps both levels consistent)
 *
 * Why use it:
 *   Eliminates Redis round trips for the very hottest keys (top 1-5% of traffic).
 *   In a high-QPS service even a 1ms Redis call at 100k RPS = 100s of CPU-seconds
 *   per second wasted on network I/O. L1 removes that entirely for hot keys.
 */
public class MultiLevelCache {

    // -------------------------------------------------------------------------
    // L1: bounded in-process LRU cache
    // -------------------------------------------------------------------------
    private static final int L1_MAX_SIZE = 100;

    private final Map<String, User> l1Cache = new LinkedHashMap<>(L1_MAX_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, User> eldest) {
            boolean evict = size() > L1_MAX_SIZE;
            if (evict) {
                System.out.printf("[L1] Capacity reached - evicting eldest key '%s'.%n", eldest.getKey());
            }
            return evict;
        }
    };

    private final RedisClient redisClient; // L2
    private final UserRepository userRepository; // DB
    private static final long L2_TTL_SECONDS = 300;

    public MultiLevelCache(RedisClient redisClient, UserRepository userRepository) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    public Optional<User> getUser(String id) {
        // 1. Check L1 (local heap)
        User l1User = l1Cache.get(id);
        if (l1User != null) {
            System.out.printf("[L1 HIT] id='%s' served from local in-process cache.%n", id);
            return Optional.of(l1User);
        }
        System.out.printf("[L1 MISS] id='%s' not in local cache - checking L2 (Redis).%n", id);

        // 2. Check L2 (Redis)
        Optional<User> l2User = redisClient.get(id);
        if (l2User.isPresent()) {
            System.out.printf("[L2 HIT] id='%s' found in Redis - promoting to L1.%n", id);
            l1Cache.put(id, l2User.get());
            return l2User;
        }
        System.out.printf("[L2 MISS] id='%s' not in Redis - fetching from DB.%n", id);

        // 3. Fallback to DB
        Optional<User> dbUser = userRepository.findById(id);
        dbUser.ifPresent(user -> {
            redisClient.set(user, L2_TTL_SECONDS); // populate L2
            l1Cache.put(id, user);                  // promote to L1
            System.out.printf("[DB->L2->L1] id='%s' loaded from DB and promoted to both cache levels.%n", id);
        });
        return dbUser;
    }

    // -------------------------------------------------------------------------
    // Write (evict-on-write to keep both levels consistent)
    // -------------------------------------------------------------------------

    public void updateUser(User user) {
        userRepository.save(user);
        redisClient.evict(user.getId());       // invalidate L2
        l1Cache.remove(user.getId());          // invalidate L1
        System.out.printf("[MULTI-LEVEL EVICT] id='%s' evicted from L1 and L2 after DB write.%n", user.getId());
    }

    /** Expose L1 size for demo/diagnostic purposes. */
    public int l1Size() {
        return l1Cache.size();
    }
}
