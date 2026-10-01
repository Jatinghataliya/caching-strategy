package com.example.caching.repository;

import com.example.caching.model.User;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates a primary persistent database (e.g. PostgreSQL, MySQL).
 */
public class UserRepository {
    private final Map<String, User> database = new ConcurrentHashMap<>();

    public UserRepository() {
        // Pre-populate some sample records
        database.put("u-101", new User("u-101", "Alice Smith", "alice@example.com"));
        database.put("u-102", new User("u-102", "Bob Johnson", "bob@example.com"));
    }

    public Optional<User> findById(String id) {
        // Simulate DB latency
        simulateLatency(50);
        System.out.printf("[DB] Reading user id '%s' from Database...%n", id);
        return Optional.ofNullable(database.get(id));
    }

    public void save(User user) {
        simulateLatency(80);
        System.out.printf("[DB] Writing user id '%s' to Database...%n", user.getId());
        database.put(user.getId(), user);
    }

    public void delete(String id) {
        simulateLatency(40);
        System.out.printf("[DB] Deleting user id '%s' from Database...%n", id);
        database.remove(id);
    }

    private void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
