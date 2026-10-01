package com.example.caching.strategies;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;

import java.util.Optional;

/**
 * Strategy: Write-Around
 * 
 * - Writes: Written directly to the Database, bypassing Redis entirely.
 *           If an existing cache entry exists, it is invalidated (evicted) to prevent stale reads.
 * - Reads: Follows Cache-Aside on subsequent requests.
 * 
 * Best Used For:
 * - Write-heavy data that is rarely read immediately (e.g., audit logs, historical archives,
 *   inactive account data), preventing cache pollution for active "hot" data.
 */
public class WriteAroundUserService {
    private final RedisClient redisClient;
    private final UserRepository userRepository;
    private static final long DEFAULT_TTL = 300; // 5 minutes

    public WriteAroundUserService(RedisClient redisClient, UserRepository userRepository) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
    }

    /**
     * Write-Around: Persists directly to DB.
     * Optionally evicts existing key from Redis if present, but does NOT populate the cache.
     */
    public void saveUser(User user) {
        // 1. Direct write to database (bypassing Redis population)
        userRepository.save(user);

        // 2. Invalidate cache if stale copy existed, but DO NOT re-cache yet
        redisClient.evict(user.getId());
        System.out.printf("[WRITE-AROUND] User '%s' persisted to DB directly (cache not pre-loaded).%n", user.getId());
    }

    /**
     * Reads load from Redis if available, or fall back to DB on miss.
     */
    public Optional<User> getUser(String id) {
        Optional<User> cached = redisClient.get(id);
        if (cached.isPresent()) {
            return cached;
        }

        // Cache miss: Load from DB and store in cache
        Optional<User> fromDb = userRepository.findById(id);
        fromDb.ifPresent(user -> redisClient.set(user, DEFAULT_TTL));
        return fromDb;
    }
}
