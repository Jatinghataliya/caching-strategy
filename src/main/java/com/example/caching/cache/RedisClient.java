package com.example.caching.cache;

import com.example.caching.model.User;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;

/**
 * Low-level Redis helper client using Jedis.
 */
public class RedisClient {
    private final JedisPool jedisPool;
    private static final String USER_PREFIX = "user:";

    public RedisClient(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public Optional<User> get(String id) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = USER_PREFIX + id;
            String name = jedis.hget(key, "name");
            String email = jedis.hget(key, "email");
            if (name != null && email != null) {
                System.out.printf("[REDIS HIT] Key '%s' found in cache.%n", key);
                return Optional.of(new User(id, name, email));
            }
            System.out.printf("[REDIS MISS] Key '%s' not in cache.%n", key);
            return Optional.empty();
        }
    }

    public void set(User user, long ttlSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = USER_PREFIX + user.getId();
            jedis.hset(key, "name", user.getName());
            jedis.hset(key, "email", user.getEmail());
            if (ttlSeconds > 0) {
                jedis.expire(key, ttlSeconds);
            }
            System.out.printf("[REDIS WRITE] Saved key '%s' (TTL=%ds).%n", key, ttlSeconds);
        }
    }

    public void evict(String id) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = USER_PREFIX + id;
            jedis.del(key);
            System.out.printf("[REDIS EVICT] Removed key '%s'.%n", key);
        }
    }

    public void flushAll() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushAll();
        }
    }
}
