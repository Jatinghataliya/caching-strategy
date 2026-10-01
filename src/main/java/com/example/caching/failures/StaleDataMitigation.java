package com.example.caching.failures;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;

import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Failure Mode 4: Data Inconsistency (Stale Cache)
 *
 * Problem:
 *   The primary database is updated but the cache is not invalidated (or the
 *   invalidation message is lost / arrives out-of-order). Subsequent reads
 *   serve stale data from the cache even though the DB has a newer version.
 *
 * Mitigations implemented:
 *   A) Change Data Capture (CDC) simulation — a background worker monitors
 *      a "change event queue" (simulating Debezium / DB WAL stream). For every
 *      committed DB change event, it proactively evicts (or re-warms) the
 *      corresponding cache key so the next read fetches fresh data.
 *   B) Transactional Outbox pattern simulation — the write path appends an
 *      invalidation task to an outbox table atomically with the DB write.
 *      A separate relay process drains the outbox and applies cache evictions,
 *      guaranteeing that no update is silently lost even if the application
 *      process crashes between the DB write and the cache eviction.
 */
public class StaleDataMitigation implements AutoCloseable {

    private final RedisClient redisClient;
    private final UserRepository userRepository;
    private final ExecutorService cdcWorker = Executors.newSingleThreadExecutor();

    /** Simulated outbox queue (would be a DB table in production). */
    private final Queue<String> outbox = new LinkedList<>();
    private final ExecutorService outboxRelay = Executors.newSingleThreadExecutor();

    private static final long TTL_SECONDS = 300;

    public StaleDataMitigation(RedisClient redisClient, UserRepository userRepository) {
        this.redisClient = redisClient;
        this.userRepository = userRepository;
        startOutboxRelay();
    }

    // -------------------------------------------------------------------------
    // Mitigation A: CDC-style Cache Invalidation
    // -------------------------------------------------------------------------

    /**
     * Simulates a CDC change event arriving for a mutated record.
     * In production, Debezium reads the DB transaction log (WAL/binlog) and
     * publishes change events to Kafka. A consumer calls this method.
     *
     * @param changedUserId the ID of the user whose record changed in the DB
     */
    public void onCdcChangeEvent(String changedUserId) {
        // CDC event received — evict asynchronously so the main write path is not blocked
        cdcWorker.submit(() -> {
            System.out.printf("[CDC] Change event received for id='%s' – evicting stale cache entry.%n", changedUserId);
            redisClient.evict(changedUserId);
            System.out.printf("[CDC] Cache invalidated for id='%s'. Next read will fetch fresh data from DB.%n", changedUserId);
        });
    }

    /**
     * Updates a user in the DB and fires a simulated CDC event.
     * The cache invalidation happens out-of-band via the CDC worker.
     */
    public void updateUserWithCdc(User user) {
        // 1. Write to DB
        userRepository.save(user);
        System.out.printf("[CDC] DB updated for id='%s' – emitting CDC change event.%n", user.getId());

        // 2. Publish CDC event (async — does NOT block the caller)
        onCdcChangeEvent(user.getId());
    }

    // -------------------------------------------------------------------------
    // Mitigation B: Transactional Outbox Pattern
    // -------------------------------------------------------------------------

    /**
     * Updates a user using the Transactional Outbox pattern.
     * The DB write and the outbox entry are appended "atomically" (in a real
     * system, both happen inside the same DB transaction so neither can be lost).
     * A separate relay drains the outbox and applies cache evictions.
     */
    public void updateUserWithOutbox(User user) {
        // 1. Write to DB (in production: same DB transaction as step 2)
        userRepository.save(user);

        // 2. Append invalidation task to outbox (atomically with DB write in production)
        synchronized (outbox) {
            outbox.add(user.getId());
            System.out.printf("[OUTBOX] Appended invalidation task for id='%s' to outbox.%n", user.getId());
        }
    }

    /**
     * Outbox relay: continuously drains pending invalidation tasks and
     * applies them to the cache. Runs in a background thread.
     */
    private void startOutboxRelay() {
        outboxRelay.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                String id;
                synchronized (outbox) {
                    id = outbox.poll();
                }
                if (id != null) {
                    System.out.printf("[OUTBOX RELAY] Processing invalidation for id='%s'.%n", id);
                    redisClient.evict(id);
                    System.out.printf("[OUTBOX RELAY] Cache evicted for id='%s'.%n", id);
                } else {
                    try {
                        TimeUnit.MILLISECONDS.sleep(200); // idle poll interval
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
    }

    /** Read helper: falls back to DB on cache miss and re-populates the cache. */
    public Optional<User> getUser(String id) {
        Optional<User> cached = redisClient.get(id);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<User> dbUser = userRepository.findById(id);
        dbUser.ifPresent(user -> redisClient.set(user, TTL_SECONDS));
        return dbUser;
    }

    @Override
    public void close() {
        cdcWorker.shutdownNow();
        outboxRelay.shutdownNow();
    }
}
