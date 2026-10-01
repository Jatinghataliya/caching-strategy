package com.example.caching.strategies;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;

import java.util.Optional;

/**
 * Strategy 2: Read-Through & Write-Through
 * 
 * - Reads (Read-Through): Consumer talks to Cache facade; cache loads from DB transparently on miss.
 * - Writes (Write-Through): Consumer writes to Cache facade; cache synchronously persists to DB and itself.
 */
public class ReadAndWriteThroughCache {
    private final RedisClient redisClient;
    private final UserRepository userRepository;
    private static final long DEFAULT_TTL = 600;

    public ReadAndWriteThroughCache(RedisClient redisClient, UserRepository userRepository) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
    }

    /**
     * Read-Through implementation
     */
    public Optional<User> get(String id) {
        Optional<User> cached = redisClient.get(id);
        if (cached.isPresent()) {
            return cached;
        }

        // Cache transparently fetches from DB and stores into cache
        Optional<User> fromDb = userRepository.findById(id);
        fromDb.ifPresent(user -> redisClient.set(user, DEFAULT_TTL));
        return fromDb;
    }

    /**
     * Write-Through implementation
     */
    public void put(User user) {
        // 1. Synchronously persist to DB
        userRepository.save(user);

        // 2. Synchronously persist to Cache
        redisClient.set(user, DEFAULT_TTL);
    }
}
