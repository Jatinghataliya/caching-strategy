package com.example.caching.failures;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;

import java.util.Optional;
import java.util.Random;

/**
 * Failure Mode 2: Cache Avalanche
 *
 * Problem:
 *   A large number of keys are assigned the same TTL (e.g. all set to expire
 *   at 5 minutes). When the clock hits that mark, all of them expire
 *   simultaneously, producing a massive surge of cache misses that floods the
 *   database — the "avalanche".
 *
 * Mitigations implemented:
 *   A) TTL Jitter — each key receives a slightly randomised TTL
 *      (base ± jitter range) so expiries are spread over time instead of
 *      clustering at a single moment.
 *   B) Staggered Warm-Up — when re-populating after a full cache flush/restart,
 *      keys are loaded with an incremental offset so they don't all land at
 *      the same TTL boundary.
 */
public class CacheAvalancheMitigation {

    private final RedisClient redisClient;
    private final UserRepository userRepository;
    private final Random random = new Random();

    /** Base TTL in seconds (e.g. 5 minutes). */
    private static final long BASE_TTL = 300;
    /** Maximum random jitter added to TTL in seconds (±30 s around base). */
    private static final long JITTER_RANGE = 30;

    public CacheAvalancheMitigation(RedisClient redisClient, UserRepository userRepository) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
    }

    // -------------------------------------------------------------------------
    // Mitigation A: TTL Jitter
    // -------------------------------------------------------------------------

    /**
     * Reads a user with jittered TTL on cache population.
     * The actual TTL = BASE_TTL + random value in [-JITTER_RANGE, +JITTER_RANGE].
     * This distributes expiry times evenly across a 60-second window instead
     * of having all keys expire at exactly the same second.
     */
    public Optional<User> getUser(String id) {
        Optional<User> cached = redisClient.get(id);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<User> dbUser = userRepository.findById(id);
        dbUser.ifPresent(user -> {
            long jitteredTtl = BASE_TTL + (long)(random.nextDouble() * 2 * JITTER_RANGE) - JITTER_RANGE;
            System.out.printf("[AVALANCHE] Storing id='%s' with jittered TTL=%ds (base=%d, jitter range=±%d).%n",
                    id, jitteredTtl, BASE_TTL, JITTER_RANGE);
            redisClient.set(user, jitteredTtl);
        });
        return dbUser;
    }

    // -------------------------------------------------------------------------
    // Mitigation B: Staggered Warm-Up
    // -------------------------------------------------------------------------

    /**
     * Pre-warms a list of user IDs into the cache after a cold start or restart.
     * Each key is assigned an incrementally offset TTL so they do NOT all expire
     * at the same moment in the future.
     *
     * @param userIds    ordered list of user IDs to warm up
     * @param stepSeconds TTL offset between consecutive keys (e.g. 2 seconds)
     */
    public void staggeredWarmUp(String[] userIds, long stepSeconds) {
        System.out.println("[AVALANCHE] Starting staggered warm-up...");
        for (int i = 0; i < userIds.length; i++) {
            String id = userIds[i];
            Optional<User> dbUser = userRepository.findById(id);
            long ttl = BASE_TTL + (long) i * stepSeconds;
            dbUser.ifPresent(user -> {
                redisClient.set(user, ttl);
                System.out.printf("[AVALANCHE] Warmed up id='%s' with TTL=%ds.%n", id, ttl);
            });
        }
        System.out.println("[AVALANCHE] Staggered warm-up complete.");
    }
}
