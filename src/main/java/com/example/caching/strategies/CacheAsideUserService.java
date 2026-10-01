package com.example.caching.strategies;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;

import java.util.Optional;

/**
 * Strategy 1: Cache-Aside (Lazy Loading)
 * 
 * - Reads: Check Redis first -> On miss, load from DB -> Save to Redis.
 * - Writes: Write to DB -> Evict (or update) Redis key to prevent stale reads.
 */
public class CacheAsideUserService {
    private final RedisClient redisClient;
    private final UserRepository userRepository;
    private static final long DEFAULT_TTL = 300; // 5 minutes

    public CacheAsideUserService(RedisClient redisClient, UserRepository userRepository) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
    }

    public Optional<User> getUser(String id) {
        // 1. Try reading from cache
        Optional<User> cachedUser = redisClient.get(id);
        if (cachedUser.isPresent()) {
            return cachedUser;
        }

        // 2. Cache miss: Read from database
        Optional<User> dbUser = userRepository.findById(id);

        // 3. Populate cache if found in database
        dbUser.ifPresent(user -> redisClient.set(user, DEFAULT_TTL));

        return dbUser;
    }

    public void updateUser(User user) {
        // 1. Write update to primary DB
        userRepository.save(user);

        // 2. Invalidate (evict) cached copy
        redisClient.evict(user.getId());
    }
}
