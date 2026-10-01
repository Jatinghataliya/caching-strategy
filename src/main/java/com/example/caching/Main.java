package com.example.caching;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;
import com.example.caching.strategies.CacheAsideUserService;
import com.example.caching.strategies.ReadAndWriteThroughCache;
import com.example.caching.strategies.RefreshAheadCacheService;
import com.example.caching.strategies.WriteAroundUserService;
import com.example.caching.strategies.WriteBehindCache;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        // Setup Redis Pool (Default localhost:6379)
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);

        try (JedisPool jedisPool = new JedisPool(poolConfig, "localhost", 6379)) {
            RedisClient redisClient = new RedisClient(jedisPool);
            UserRepository userRepository = new UserRepository();

            // 1. CACHE-ASIDE
            System.out.println("==================================================");
            System.out.println("  1. CACHE-ASIDE (LAZY LOADING) DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            CacheAsideUserService cacheAsideService = new CacheAsideUserService(redisClient, userRepository);
            System.out.println("\n--- First read (Cache Miss, reads from DB) ---");
            cacheAsideService.getUser("u-101").ifPresent(System.out::println);
            System.out.println("\n--- Second read (Cache Hit from Redis) ---");
            cacheAsideService.getUser("u-101").ifPresent(System.out::println);
            System.out.println("\n--- Update User (Writes to DB, Evicts from Redis) ---");
            cacheAsideService.updateUser(new User("u-101", "Alice Williams", "alice.w@example.com"));
            System.out.println("\n--- Third read after update (Cache Miss, reloads updated value from DB) ---");
            cacheAsideService.getUser("u-101").ifPresent(System.out::println);


            // 2. READ-THROUGH & WRITE-THROUGH
            System.out.println("\n==================================================");
            System.out.println("  2. READ-THROUGH & WRITE-THROUGH DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            ReadAndWriteThroughCache writeThroughCache = new ReadAndWriteThroughCache(redisClient, userRepository);
            System.out.println("\n--- Write-Through: writes to DB & Cache synchronously ---");
            writeThroughCache.put(new User("u-201", "Charlie Brown", "charlie@example.com"));
            System.out.println("\n--- Immediate Read-Through: guaranteed cache hit ---");
            writeThroughCache.get("u-201").ifPresent(System.out::println);


            // 3. WRITE-BEHIND (WRITE-BACK)
            System.out.println("\n==================================================");
            System.out.println("  3. WRITE-BEHIND (WRITE-BACK) DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            try (WriteBehindCache writeBehindCache = new WriteBehindCache(redisClient, userRepository)) {
                System.out.println("\n--- Write-Behind: writes instantly to Redis, queues for DB ---");
                writeBehindCache.put(new User("u-301", "Diana Prince", "diana@example.com"));
                writeBehindCache.put(new User("u-302", "Evan Stone", "evan@example.com"));

                System.out.println("\n--- Instant read (from Redis directly) ---");
                writeBehindCache.get("u-301").ifPresent(System.out::println);

                System.out.println("\n--- Waiting 3 seconds for background flush to DB... ---");
                Thread.sleep(3000);
            }


            // 4. WRITE-AROUND
            System.out.println("\n==================================================");
            System.out.println("  4. WRITE-AROUND DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            WriteAroundUserService writeAroundService = new WriteAroundUserService(redisClient, userRepository);
            System.out.println("\n--- Write-Around: Saves to DB directly, leaves cache unpopulated ---");
            writeAroundService.saveUser(new User("u-401", "Frank Miller", "frank@example.com"));
            System.out.println("\n--- First read (Cache Miss, fetched on demand) ---");
            writeAroundService.getUser("u-401").ifPresent(System.out::println);
            System.out.println("\n--- Second read (Cache Hit) ---");
            writeAroundService.getUser("u-401").ifPresent(System.out::println);


            // 5. REFRESH-AHEAD (READ-AHEAD)
            System.out.println("\n==================================================");
            System.out.println("  5. REFRESH-AHEAD (READ-AHEAD) DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            // TTL 4 seconds, refresh threshold factor 0.4 (triggers refresh when >= 60% of TTL elapsed: ~2.4s)
            try (RefreshAheadCacheService refreshAheadService = new RefreshAheadCacheService(redisClient, userRepository, 4, 0.4)) {
                System.out.println("\n--- Initial load (Miss, stored in Redis with 4s TTL) ---");
                refreshAheadService.getUser("u-101").ifPresent(System.out::println);

                System.out.println("\n--- Read at 1s (Hit, before threshold, no refresh triggered) ---");
                Thread.sleep(1000);
                refreshAheadService.getUser("u-101").ifPresent(System.out::println);

                System.out.println("\n--- Read at 2.6s (Hit, passes refresh threshold -> triggers proactive async reload) ---");
                Thread.sleep(1600);
                refreshAheadService.getUser("u-101").ifPresent(System.out::println);

                // Give async refresh thread a moment to finish
                Thread.sleep(1000);
                System.out.println("\n--- Read at 4.2s (Still a Hit! Redis TTL was proactively extended without a cache miss) ---");
                refreshAheadService.getUser("u-101").ifPresent(System.out::println);
            }

            System.out.println("\nAll strategy demos completed successfully.");
        } catch (Exception e) {
            System.err.println("Redis connection error (Ensure Redis is running on localhost:6379): " + e.getMessage());
        }
    }
}
