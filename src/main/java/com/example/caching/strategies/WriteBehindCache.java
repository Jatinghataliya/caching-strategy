package com.example.caching.strategies;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;

import java.util.Optional;
import java.util.concurrent.*;

/**
 * Strategy 3: Write-Behind (Write-Back)
 * 
 * - Writes: Written instantly to Redis cache and queued into an async batch buffer.
 * - DB flush: Dedicated background thread drains the queue and persists to DB in batches.
 */
public class WriteBehindCache implements AutoCloseable {
    private final RedisClient redisClient;
    private final UserRepository userRepository;
    private final BlockingQueue<User> writeQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final long DEFAULT_TTL = 3600;

    public WriteBehindCache(RedisClient redisClient, UserRepository userRepository) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
        startAsyncDbFlusher();
    }

    public Optional<User> get(String id) {
        Optional<User> cached = redisClient.get(id);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<User> fromDb = userRepository.findById(id);
        fromDb.ifPresent(user -> redisClient.set(user, DEFAULT_TTL));
        return fromDb;
    }

    public void put(User user) {
        // 1. Instant update to Redis Cache
        redisClient.set(user, DEFAULT_TTL);

        // 2. Queue for asynchronous DB flush
        writeQueue.offer(user);
        System.out.printf("[WRITE-BEHIND] User '%s' queued for async DB persistence.%n", user.getId());
    }

    private void startAsyncDbFlusher() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                User user;
                int count = 0;
                while ((user = writeQueue.poll()) != null) {
                    userRepository.save(user);
                    count++;
                }
                if (count > 0) {
                    System.out.printf("[WRITE-BEHIND] Flushed %d records to DB.%n", count);
                }
            } catch (Exception e) {
                System.err.println("Error flushing to database: " + e.getMessage());
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
