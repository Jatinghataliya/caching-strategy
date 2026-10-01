package com.example.caching.strategies;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WriteBehindCacheTest {

    @Mock
    private RedisClient redisClient;

    @Mock
    private UserRepository userRepository;

    private WriteBehindCache cache;

    @BeforeEach
    void setUp() {
        cache = new WriteBehindCache(redisClient, userRepository);
    }

    @AfterEach
    void tearDown() {
        cache.close();
    }

    @Test
    @DisplayName("Write-Behind: put() immediately updates Redis without blocking for DB write")
    void testPut_InstantRedisUpdate() {
        User user = new User("u-3", "Evan", "evan@example.com");

        cache.put(user);

        // Redis is updated immediately
        verify(redisClient, times(1)).set(user, 3600);
        // DB save is not called synchronously on the calling thread
        verify(userRepository, never()).save(user);
    }

    @Test
    @DisplayName("Write-Behind: Asynchronously drains queue and persists batched items to DB")
    void testAsyncDbFlush() throws InterruptedException {
        User user1 = new User("u-3", "Evan", "evan@example.com");
        User user2 = new User("u-4", "Fiona", "fiona@example.com");

        cache.put(user1);
        cache.put(user2);

        // Wait for background scheduled flush (runs every 1-2 seconds)
        Thread.sleep(2200);

        verify(userRepository, times(1)).save(user1);
        verify(userRepository, times(1)).save(user2);
    }

    @Test
    @DisplayName("Write-Behind: get() reads from Redis cache directly on hit")
    void testGet_CacheHit() {
        User user = new User("u-3", "Evan", "evan@example.com");
        when(redisClient.get("u-3")).thenReturn(Optional.of(user));

        Optional<User> result = cache.get("u-3");

        assertTrue(result.isPresent());
        assertEquals("Evan", result.get().getName());
        verifyNoInteractions(userRepository);
    }
}
