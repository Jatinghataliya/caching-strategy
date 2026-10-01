package com.example.caching.cache;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Redis Sliding Window Rate Limiter.
 *
 * Problem:
 *   Without rate limiting, a single client can flood an API with thousands of
 *   requests per second, starving legitimate users and overwhelming the backend.
 *   A local (in-JVM) counter breaks down in a horizontally-scaled environment
 *   because each pod maintains its own independent count.
 *
 * Solution: Distributed sliding window using Redis INCR + EXPIRE
 *   For each (clientId, window) pair:
 *     1. INCR  ratelimit:<clientId>:<windowKey>  -- atomically increment request count
 *     2. EXPIRE the key to windowSizeSeconds     -- auto-clean after the window closes
 *     3. Compare current count to maxRequests    -- allow or deny
 *
 *   Because Redis is single-threaded, INCR is atomic across all application
 *   instances - no race conditions, no double-counting across pods.
 *
 * Window strategies implemented:
 *   A) Fixed Window  - simple, minimal memory. Burst possible at window boundary.
 *   B) Sliding Window - uses current + previous window with a weighted blend,
 *      smoothing out boundary bursts with negligible extra memory cost.
 *
 * Typical use cases:
 *   - API rate limiting per user / API key (e.g. 100 req/min)
 *   - Login attempt throttling (e.g. 5 attempts per 15 min per IP)
 *   - SMS / email OTP send limits
 */
public class RateLimiterClient {

    private static final String RATE_PREFIX = "ratelimit:";

    private final JedisPool jedisPool;
    private final int maxRequests;
    private final long windowSizeSeconds;

    /**
     * @param jedisPool         shared Jedis connection pool
     * @param maxRequests       maximum allowed requests per window
     * @param windowSizeSeconds duration of each rate-limit window in seconds
     */
    public RateLimiterClient(JedisPool jedisPool, int maxRequests, long windowSizeSeconds) {
        this.jedisPool         = jedisPool;
        this.maxRequests       = maxRequests;
        this.windowSizeSeconds = windowSizeSeconds;
    }

    // -------------------------------------------------------------------------
    // Strategy A: Fixed Window
    // -------------------------------------------------------------------------

    /**
     * Checks and increments the request count for a fixed window.
     * Window key = floor(currentEpochSeconds / windowSize) so all requests
     * within the same window share the same Redis key.
     *
     * @param clientId unique identifier for the caller (userId, IP, API key)
     * @return true if the request is allowed, false if the limit is exceeded
     */
    public boolean allowFixedWindow(String clientId) {
        long windowKey = System.currentTimeMillis() / 1000 / windowSizeSeconds;
        String key     = RATE_PREFIX + clientId + ":" + windowKey;

        try (Jedis jedis = jedisPool.getResource()) {
            long count = jedis.incr(key);
            if (count == 1) {
                // First request in this window - set expiry
                jedis.expire(key, windowSizeSeconds * 2); // x2 so the key outlives the window slightly
            }
            boolean allowed = count <= maxRequests;
            System.out.printf("[RATE LIMIT fixed] client='%s' count=%d/%d window=%d -> %s%n",
                    clientId, count, maxRequests, windowKey, allowed ? "ALLOW" : "DENY");
            return allowed;
        }
    }

    // -------------------------------------------------------------------------
    // Strategy B: Sliding Window (weighted blend of current + previous window)
    // -------------------------------------------------------------------------

    /**
     * Sliding window rate limiter using a weighted combination of the current
     * and previous window counts.
     *
     * Formula:
     *   elapsed  = fraction of current window already consumed (0.0 - 1.0)
     *   estimate = prevCount * (1 - elapsed) + currCount
     *
     * If estimate <= maxRequests the request is allowed.
     * This smooths the burst that can occur at fixed window boundaries.
     *
     * @param clientId unique identifier for the caller
     * @return true if the request is allowed, false if the limit is exceeded
     */
    public boolean allowSlidingWindow(String clientId) {
        long nowSeconds   = System.currentTimeMillis() / 1000;
        long currWindow   = nowSeconds / windowSizeSeconds;
        long prevWindow   = currWindow - 1;
        double elapsed    = (double)(nowSeconds % windowSizeSeconds) / windowSizeSeconds;

        String currKey = RATE_PREFIX + clientId + ":sw:" + currWindow;
        String prevKey = RATE_PREFIX + clientId + ":sw:" + prevWindow;

        try (Jedis jedis = jedisPool.getResource()) {
            // Increment current window atomically
            long currCount = jedis.incr(currKey);
            if (currCount == 1) {
                jedis.expire(currKey, windowSizeSeconds * 2);
            }

            // Read previous window (may not exist)
            String prevRaw = jedis.get(prevKey);
            long prevCount = (prevRaw != null) ? Long.parseLong(prevRaw) : 0L;

            // Weighted estimate
            double estimate = prevCount * (1.0 - elapsed) + currCount;
            boolean allowed = estimate <= maxRequests;

            System.out.printf(
                    "[RATE LIMIT sliding] client='%s' prev=%d curr=%d elapsed=%.2f estimate=%.1f/%d -> %s%n",
                    clientId, prevCount, currCount, elapsed, estimate, maxRequests, allowed ? "ALLOW" : "DENY");
            return allowed;
        }
    }

    // -------------------------------------------------------------------------
    // Remaining quota helper
    // -------------------------------------------------------------------------

    /**
     * Returns how many requests the client can still make in the current fixed window.
     * Useful for returning X-RateLimit-Remaining response headers.
     */
    public long remainingFixedWindow(String clientId) {
        long windowKey = System.currentTimeMillis() / 1000 / windowSizeSeconds;
        String key     = RATE_PREFIX + clientId + ":" + windowKey;
        try (Jedis jedis = jedisPool.getResource()) {
            String raw = jedis.get(key);
            long used  = (raw != null) ? Long.parseLong(raw) : 0L;
            return Math.max(0, maxRequests - used);
        }
    }
}
