package com.example.caching.strategies;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Strategy: Refresh-Ahead (Read-Ahead)
 * 
 * - Concept: Cache predicts which hot items are about to expire based on access patterns
 *   or a refresh threshold (e.g. within 20% of remaining TTL) and proactively fetches updated
 *   data from the database in an asynchronous background thread before the TTL expires.
 * 
 * Best Used For:
 * - High-traffic hot keys (e.g., product pricing, trending feeds, active configuration)
 *   where eliminating cache-miss latency and avoiding Cache Stampedes / Thundering Herd is critical.
 */
public class RefreshAheadCacheService implements AutoCloseable {
    private final RedisClient redisClient;
    private final UserRepository userRepository;
    private final long ttlSeconds;
    private final double refreshThresholdFactor; // e.g. 0.3 means refresh when >= 70% of TTL elapsed

    // Tracks when each key was cached: key -> timestamp (epoch millis)
    private final Map<String, Long> keyInsertionTimes = new ConcurrentHashMap<>();
    private final Set<String> ongoingRefreshes = ConcurrentHashMap.newKeySet();
    private final ExecutorService asyncRefreshExecutor = Executors.newFixedThreadPool(4);

    public RefreshAheadCacheService(RedisClient redisClient, UserRepository userRepository, 
                                   long ttlSeconds, double refreshThresholdFactor) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
        this.ttlSeconds = ttlSeconds;
        this.refreshThresholdFactor = refreshThresholdFactor;
    }

    public Optional<User> getUser(String id) {
        // 1. Try reading from cache
        Optional<User> cached = redisClient.get(id);

        if (cached.isPresent()) {
            // Check if key is nearing expiration and needs proactive background reload
            checkAndScheduleRefreshAhead(id);
            return cached;
        }

        // 2. Cache miss: Synchronous fallback to Database
        Optional<User> fromDb = userRepository.findById(id);
        fromDb.ifPresent(user -> {
            redisClient.set(user, ttlSeconds);
            keyInsertionTimes.put(id, System.currentTimeMillis());
        });
        return fromDb;
    }

    /**
     * Determines whether the key is past the refresh threshold and triggers background fetch.
     */
    private void checkAndScheduleRefreshAhead(String id) {
        Long insertedAt = keyInsertionTimes.get(id);
        if (insertedAt == null) return;

        long elapsedMillis = System.currentTimeMillis() - insertedAt;
        long ttlMillis = ttlSeconds * 1000L;
        long refreshThresholdMillis = (long) (ttlMillis * (1.0 - refreshThresholdFactor));

        // If elapsed time exceeded threshold and not already being refreshed
        if (elapsedMillis >= refreshThresholdMillis && ongoingRefreshes.add(id)) {
            System.out.printf("[REFRESH-AHEAD TRIGGER] Key 'user:%s' elapsed %dms of %dms TTL. Triggering proactive refresh...%n", 
                    id, elapsedMillis, ttlMillis);

            asyncRefreshExecutor.submit(() -> {
                try {
                    System.out.printf("[REFRESH-AHEAD ASYNC] Fetching fresh data from DB for user '%s'...%n", id);
                    Optional<User> freshData = userRepository.findById(id);
                    freshData.ifPresent(user -> {
                        redisClient.set(user, ttlSeconds);
                        keyInsertionTimes.put(id, System.currentTimeMillis());
                        System.out.printf("[REFRESH-AHEAD ASYNC] Refreshed key 'user:%s' in Redis before expiration.%n", id);
                    });
                } catch (Exception e) {
                    System.err.printf("[REFRESH-AHEAD ERROR] Failed to refresh user '%s': %s%n", id, e.getMessage());
                } finally {
                    ongoingRefreshes.remove(id);
                }
            });
        }
    }

    @Override
    public void close() {
        asyncRefreshExecutor.shutdown();
        try {
            if (!asyncRefreshExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                asyncRefreshExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncRefreshExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
