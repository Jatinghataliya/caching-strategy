package com.example.caching.patterns;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;

import java.util.List;
import java.util.Optional;

/**
 * Pattern: Cache Warmer (Pre-warming / Eager Loading)
 *
 * Problem:
 *   On a cold start (fresh deploy, Redis restart, or after a Cache Avalanche),
 *   the first wave of real user traffic suffers cache misses for every request.
 *   This "cold start penalty" can overwhelm the database and spike latency for
 *   real users during the ramp-up window.
 *
 * Solution:
 *   Before the application starts serving live traffic, a warm-up job proactively
 *   fetches the known hot keys from the DB and populates Redis. By the time the
 *   first real request arrives, the cache is already populated.
 *
 * How hot keys are identified (strategies):
 *   - Static list: hard-coded or config-driven list of known hot IDs.
 *   - Access log replay: read yesterday's top-N accessed keys from analytics.
 *   - DB query: SELECT id FROM users ORDER BY access_count DESC LIMIT 1000.
 *
 * This implementation uses a static list (simplest form) to demonstrate the
 * warm-up mechanics clearly.
 */
public class CacheWarmer {

    private final RedisClient redisClient;
    private final UserRepository userRepository;
    private static final long WARM_TTL_SECONDS = 600; // 10 minutes for warmed entries

    public CacheWarmer(RedisClient redisClient, UserRepository userRepository) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
    }

    // -------------------------------------------------------------------------
    // Warm-up from a known hot-key list
    // -------------------------------------------------------------------------

    /**
     * Pre-loads a list of known hot user IDs into Redis before live traffic starts.
     * Called once during application startup (e.g. from a Spring @EventListener
     * on ApplicationReadyEvent, or a ServletContextListener).
     *
     * @param hotUserIds list of IDs determined to be hot (from analytics, config, etc.)
     */
    public void warmUp(List<String> hotUserIds) {
        System.out.printf("[WARMER] Starting cache warm-up for %d hot keys...%n", hotUserIds.size());
        int loaded = 0;
        int skipped = 0;

        for (String id : hotUserIds) {
            // Skip keys that are already in Redis (e.g. partial restart)
            Optional<User> existing = redisClient.get(id);
            if (existing.isPresent()) {
                System.out.printf("[WARMER] id='%s' already warm - skipping.%n", id);
                skipped++;
                continue;
            }

            Optional<User> dbUser = userRepository.findById(id);
            if (dbUser.isPresent()) {
                redisClient.set(dbUser.get(), WARM_TTL_SECONDS);
                System.out.printf("[WARMER] id='%s' loaded into cache (TTL=%ds).%n", id, WARM_TTL_SECONDS);
                loaded++;
            } else {
                System.out.printf("[WARMER] id='%s' not found in DB - skipping.%n", id);
                skipped++;
            }
        }

        System.out.printf("[WARMER] Warm-up complete: %d loaded, %d skipped.%n", loaded, skipped);
    }

    // -------------------------------------------------------------------------
    // Verify warm-up result
    // -------------------------------------------------------------------------

    /**
     * Checks what fraction of the hot keys are currently cached.
     * Useful for health-check endpoints to confirm the cache is warm before
     * routing production traffic.
     *
     * @param hotUserIds the same list passed to warmUp()
     * @return ratio of cached keys (0.0 to 1.0)
     */
    public double warmRatio(List<String> hotUserIds) {
        if (hotUserIds.isEmpty()) return 1.0;
        long cached = hotUserIds.stream()
                .filter(id -> redisClient.get(id).isPresent())
                .count();
        double ratio = (double) cached / hotUserIds.size();
        System.out.printf("[WARMER] Warm ratio: %.0f%% (%d / %d keys cached).%n",
                ratio * 100, cached, hotUserIds.size());
        return ratio;
    }
}
