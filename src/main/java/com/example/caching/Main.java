package com.example.caching;

import com.example.caching.cache.DistributedLockClient;
import com.example.caching.cache.RateLimiterClient;
import com.example.caching.cache.RedisClient;
import com.example.caching.failures.CacheAvalancheMitigation;
import com.example.caching.failures.CachePenetrationMitigation;
import com.example.caching.failures.CacheStampedeMitigation;
import com.example.caching.failures.StaleDataMitigation;
import com.example.caching.model.User;
import com.example.caching.patterns.CacheWarmer;
import com.example.caching.patterns.MultiLevelCache;
import com.example.caching.patterns.SegmentedCache;
import com.example.caching.repository.UserRepository;
import com.example.caching.strategies.CacheAsideUserService;
import com.example.caching.strategies.ReadAndWriteThroughCache;
import com.example.caching.strategies.RefreshAheadCacheService;
import com.example.caching.strategies.WriteAroundUserService;
import com.example.caching.strategies.WriteBehindCache;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.List;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        // Setup Redis Pool (Default localhost:6379)
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);

        try (JedisPool jedisPool = new JedisPool(poolConfig, "localhost", 6379)) {
            RedisClient redisClient = new RedisClient(jedisPool);
            UserRepository userRepository = new UserRepository();

            // ================================================================
            // SECTION 1: CACHING STRATEGIES
            // ================================================================

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

            // ================================================================
            // SECTION 2: DISTRIBUTED FAILURE MODES & MITIGATION
            // ================================================================

            // 6. CACHE STAMPEDE (THUNDERING HERD)
            System.out.println("\n==================================================");
            System.out.println("  6. CACHE STAMPEDE — MITIGATION DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            CacheStampedeMitigation stampedeService =
                    new CacheStampedeMitigation(redisClient, userRepository, jedisPool);

            System.out.println("\n--- Mutex/Singleflight: first call misses, second reuses populated result ---");
            stampedeService.getUserWithMutex("u-101").ifPresent(System.out::println);
            stampedeService.getUserWithMutex("u-101").ifPresent(System.out::println);

            System.out.println("\n--- XFetch Probabilistic Early Expiration ---");
            redisClient.flushAll();
            // Pre-populate with a very short TTL to demonstrate early re-fetch
            userRepository.findById("u-102").ifPresent(u -> redisClient.set(u, 3));
            Thread.sleep(500);
            stampedeService.getUserWithXFetch("u-102").ifPresent(System.out::println);


            // 7. CACHE AVALANCHE
            System.out.println("\n==================================================");
            System.out.println("  7. CACHE AVALANCHE — MITIGATION DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            CacheAvalancheMitigation avalancheService =
                    new CacheAvalancheMitigation(redisClient, userRepository);

            System.out.println("\n--- TTL Jitter: each key gets a different expiry to avoid simultaneous mass expiry ---");
            avalancheService.getUser("u-101").ifPresent(System.out::println);
            avalancheService.getUser("u-102").ifPresent(System.out::println);

            System.out.println("\n--- Staggered Warm-Up: keys loaded with offset TTLs after a cold restart ---");
            redisClient.flushAll();
            avalancheService.staggeredWarmUp(new String[]{"u-101", "u-102"}, 10);


            // 8. CACHE PENETRATION
            System.out.println("\n==================================================");
            System.out.println("  8. CACHE PENETRATION — MITIGATION DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            CachePenetrationMitigation penetrationService =
                    new CachePenetrationMitigation(redisClient, userRepository);

            System.out.println("\n--- Null-Value Caching: non-existent key is cached as sentinel ---");
            penetrationService.getUserWithNullCache("u-999").ifPresent(System.out::println);
            System.out.println("(2nd request for same ghost key — served from null sentinel, DB not hit)");
            penetrationService.getUserWithNullCache("u-999").ifPresent(System.out::println);

            System.out.println("\n--- Bloom Filter Guard: unknown IDs are blocked before cache/DB ---");
            redisClient.flushAll();
            System.out.println("Known ID u-101:");
            penetrationService.getUserWithBloomFilter("u-101").ifPresent(System.out::println);
            System.out.println("Unknown attack ID u-evil-999:");
            penetrationService.getUserWithBloomFilter("u-evil-999").ifPresent(System.out::println);


            // 9. STALE CACHE / DATA INCONSISTENCY
            System.out.println("\n==================================================");
            System.out.println("  9. STALE CACHE (DATA INCONSISTENCY) — MITIGATION DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            try (StaleDataMitigation staleService = new StaleDataMitigation(redisClient, userRepository)) {
                System.out.println("\n--- CDC: read → update DB → CDC event evicts cache → re-read fetches fresh ---");
                staleService.getUser("u-101").ifPresent(u -> System.out.println("Before update: " + u));
                staleService.updateUserWithCdc(new User("u-101", "Alice CDC-Updated", "alice.cdc@example.com"));
                Thread.sleep(300); // allow async CDC worker to evict
                staleService.getUser("u-101").ifPresent(u -> System.out.println("After CDC eviction: " + u));

                System.out.println("\n--- Outbox: update written atomically with outbox task; relay evicts cache ---");
                redisClient.flushAll();
                staleService.getUser("u-102").ifPresent(u -> System.out.println("Before update: " + u));
                staleService.updateUserWithOutbox(new User("u-102", "Bob Outbox-Updated", "bob.outbox@example.com"));
                Thread.sleep(500); // allow outbox relay to drain
                staleService.getUser("u-102").ifPresent(u -> System.out.println("After Outbox eviction: " + u));
            }

            // ================================================================
            // SECTION 3: ADVANCED CACHING PATTERNS
            // ================================================================

            // 10. MULTI-LEVEL CACHE (L1 + L2)
            System.out.println("\n==================================================");
            System.out.println("  10. MULTI-LEVEL CACHE (L1 local + L2 Redis) DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            MultiLevelCache multiLevelCache = new MultiLevelCache(redisClient, userRepository);

            System.out.println("\n--- First read: L1 miss -> L2 miss -> DB fetch -> promoted to L1 and L2 ---");
            multiLevelCache.getUser("u-101").ifPresent(System.out::println);

            System.out.println("\n--- Second read: L1 HIT (no Redis round trip) ---");
            multiLevelCache.getUser("u-101").ifPresent(System.out::println);

            System.out.println("\n--- Update: evicts from both L1 and L2 ---");
            multiLevelCache.updateUser(new User("u-101", "Alice Multi-Updated", "alice.ml@example.com"));

            System.out.println("\n--- Third read after update: L1 miss -> L2 miss -> DB (fresh value) ---");
            multiLevelCache.getUser("u-101").ifPresent(System.out::println);
            System.out.printf("L1 cache size after demo: %d%n", multiLevelCache.l1Size());


            // 11. CACHE WARMER
            System.out.println("\n==================================================");
            System.out.println("  11. CACHE WARMER (Pre-warming) DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            CacheWarmer cacheWarmer = new CacheWarmer(redisClient, userRepository);
            List<String> hotKeys = List.of("u-101", "u-102", "u-999"); // u-999 doesn't exist

            System.out.println("\n--- Warm-up: pre-loading hot keys before traffic starts ---");
            cacheWarmer.warmUp(hotKeys);

            System.out.println("\n--- Warm ratio check (health check before routing traffic) ---");
            cacheWarmer.warmRatio(hotKeys);

            System.out.println("\n--- Second warm-up run: already-warm keys are skipped ---");
            cacheWarmer.warmUp(hotKeys);


            // 12. SEGMENTED CACHE
            System.out.println("\n==================================================");
            System.out.println("  12. SEGMENTED CACHE (Namespace Partitioning) DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            SegmentedCache segmentedCache = new SegmentedCache(jedisPool);

            System.out.println("\n--- Writing to different namespace segments ---");
            segmentedCache.putUser(new User("u-101", "Alice", "alice@example.com"));
            segmentedCache.putProduct("p-501", "{\"name\":\"Laptop\",\"price\":999}");
            segmentedCache.putSession("sess-abc123", "u-101");

            System.out.println("\n--- Reading from each segment independently ---");
            segmentedCache.getUser("u-101").ifPresent(System.out::println);
            segmentedCache.getProduct("p-501").ifPresent(System.out::println);
            segmentedCache.getSession("sess-abc123").ifPresent(s -> System.out.println("Session -> userId: " + s));

            System.out.println("\n--- Flush only the product segment (user and session unaffected) ---");
            segmentedCache.flushSegment(SegmentedCache.NS_PRODUCT);
            System.out.println("After product flush:");
            segmentedCache.getUser("u-101").ifPresent(u -> System.out.println("User still cached: " + u));
            segmentedCache.getProduct("p-501").ifPresent(System.out::println); // should miss
            segmentedCache.getSession("sess-abc123").ifPresent(s -> System.out.println("Session still cached: " + s));


            // ================================================================
            // SECTION 4: REDIS UTILITY CLIENTS
            // ================================================================

            // 13. DISTRIBUTED LOCK
            System.out.println("\n==================================================");
            System.out.println("  13. DISTRIBUTED LOCK (SET NX PX) DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            DistributedLockClient lockClient = new DistributedLockClient(jedisPool);

            System.out.println("\n--- Acquire lock, second acquire on same resource fails ---");
            String token1 = lockClient.tryAcquire("resource:report-gen", 5000);
            String token2 = lockClient.tryAcquire("resource:report-gen", 5000); // should fail
            System.out.println("Token2 (should be null): " + token2);
            lockClient.release("resource:report-gen", token1);

            System.out.println("\n--- withLock convenience: runs task exclusively, skips if lock held ---");
            lockClient.withLock("resource:email-send", 3000, () ->
                    System.out.println("[TASK] Sending email exclusively from this instance."));


            // 14. RATE LIMITER
            System.out.println("\n==================================================");
            System.out.println("  14. RATE LIMITER (Fixed + Sliding Window) DEMO");
            System.out.println("==================================================");
            redisClient.flushAll();
            // Allow 3 requests per 10-second window for demo visibility
            RateLimiterClient rateLimiter = new RateLimiterClient(jedisPool, 3, 10);

            System.out.println("\n--- Fixed Window: 5 requests, limit=3 ---");
            for (int i = 1; i <= 5; i++) {
                boolean allowed = rateLimiter.allowFixedWindow("client-A");
                System.out.printf("  Request #%d: %s%n", i, allowed ? "ALLOWED" : "DENIED");
            }
            System.out.printf("  Remaining quota: %d%n", rateLimiter.remainingFixedWindow("client-A"));

            System.out.println("\n--- Sliding Window: 5 requests, limit=3 ---");
            redisClient.flushAll();
            for (int i = 1; i <= 5; i++) {
                boolean allowed = rateLimiter.allowSlidingWindow("client-B");
                System.out.printf("  Request #%d: %s%n", i, allowed ? "ALLOWED" : "DENIED");
            }

            System.out.println("\nAll demos completed successfully.");
        } catch (Exception e) {
            System.err.println("Redis connection error (Ensure Redis is running on localhost:6379): " + e.getMessage());
        }
    }
}
