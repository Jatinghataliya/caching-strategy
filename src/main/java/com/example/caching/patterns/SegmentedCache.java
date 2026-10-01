package com.example.caching.patterns;

import com.example.caching.model.User;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;

/**
 * Pattern: Segmented Cache (Namespace-based Key Partitioning)
 *
 * Problem:
 *   In a real application many different entity types (users, products, sessions,
 *   rate-limit counters, leaderboard scores) share the same Redis instance.
 *   Without namespacing:
 *     - Key collisions are possible (e.g. id "101" for both a user and a product).
 *     - You cannot selectively flush or set TTL policies per entity type.
 *     - Operational visibility is poor (KEYS * returns a flat, unreadable dump).
 *
 * Solution:
 *   Prefix every key with a namespace segment, e.g.:
 *     user:101       -> user data
 *     product:101    -> product data
 *     session:abc123 -> session token
 *     ratelimit:101  -> request counter
 *
 *   Benefits:
 *     - Zero collision risk between entity types.
 *     - Segment-level flush: delete all keys matching "product:*" without
 *       touching user or session data.
 *     - Per-segment TTL policies (sessions expire in 30 min, users in 5 min).
 *     - Readable SCAN output for ops/debugging.
 *
 * This class demonstrates three independent segments operating on the same
 * Redis instance, each with its own TTL policy and flush capability.
 */
public class SegmentedCache {

    // Namespace prefixes — the single source of truth for key structure
    public static final String NS_USER      = "user:";
    public static final String NS_PRODUCT   = "product:";
    public static final String NS_SESSION   = "session:";

    // Per-segment TTL policies
    private static final long TTL_USER      = 300;   //  5 minutes
    private static final long TTL_PRODUCT   = 3600;  // 60 minutes (changes rarely)
    private static final long TTL_SESSION   = 1800;  // 30 minutes

    private final JedisPool jedisPool;

    public SegmentedCache(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    // -------------------------------------------------------------------------
    // User segment
    // -------------------------------------------------------------------------

    public void putUser(User user) {
        String key = NS_USER + user.getId();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(key, "name",  user.getName());
            jedis.hset(key, "email", user.getEmail());
            jedis.expire(key, TTL_USER);
            System.out.printf("[SEGMENT user] SET '%s' (TTL=%ds).%n", key, TTL_USER);
        }
    }

    public Optional<User> getUser(String id) {
        String key = NS_USER + id;
        try (Jedis jedis = jedisPool.getResource()) {
            String name  = jedis.hget(key, "name");
            String email = jedis.hget(key, "email");
            if (name != null) {
                System.out.printf("[SEGMENT user] HIT '%s'.%n", key);
                return Optional.of(new User(id, name, email));
            }
            System.out.printf("[SEGMENT user] MISS '%s'.%n", key);
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Product segment (simple String value for brevity)
    // -------------------------------------------------------------------------

    public void putProduct(String productId, String productJson) {
        String key = NS_PRODUCT + productId;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(key, productJson);
            jedis.expire(key, TTL_PRODUCT);
            System.out.printf("[SEGMENT product] SET '%s' (TTL=%ds).%n", key, TTL_PRODUCT);
        }
    }

    public Optional<String> getProduct(String productId) {
        String key = NS_PRODUCT + productId;
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.get(key);
            if (value != null) {
                System.out.printf("[SEGMENT product] HIT '%s'.%n", key);
                return Optional.of(value);
            }
            System.out.printf("[SEGMENT product] MISS '%s'.%n", key);
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Session segment
    // -------------------------------------------------------------------------

    public void putSession(String sessionToken, String userId) {
        String key = NS_SESSION + sessionToken;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(key, userId);
            jedis.expire(key, TTL_SESSION);
            System.out.printf("[SEGMENT session] SET '%s' -> userId='%s' (TTL=%ds).%n", key, userId, TTL_SESSION);
        }
    }

    public Optional<String> getSession(String sessionToken) {
        String key = NS_SESSION + sessionToken;
        try (Jedis jedis = jedisPool.getResource()) {
            String userId = jedis.get(key);
            if (userId != null) {
                System.out.printf("[SEGMENT session] HIT '%s' -> userId='%s'.%n", key, userId);
                return Optional.of(userId);
            }
            System.out.printf("[SEGMENT session] MISS '%s'.%n", key);
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Segment-level flush (uses SCAN to avoid blocking KEYS *)
    // -------------------------------------------------------------------------

    /**
     * Flushes all keys belonging to a single namespace segment.
     * Uses SCAN with a MATCH pattern — non-blocking and safe for production Redis.
     *
     * @param namespace one of NS_USER, NS_PRODUCT, NS_SESSION
     */
    public void flushSegment(String namespace) {
        try (Jedis jedis = jedisPool.getResource()) {
            String cursor = "0";
            int deleted = 0;
            do {
                redis.clients.jedis.ScanResult<String> result =
                        jedis.scan(cursor, new redis.clients.jedis.params.ScanParams()
                                .match(namespace + "*").count(100));
                cursor = result.getCursor();
                for (String key : result.getResult()) {
                    jedis.del(key);
                    deleted++;
                }
            } while (!cursor.equals("0"));
            System.out.printf("[SEGMENT FLUSH] Deleted %d keys matching '%s*'.%n", deleted, namespace);
        }
    }
}
