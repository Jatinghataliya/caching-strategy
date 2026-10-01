package com.example.caching.strategies;

import com.example.caching.cache.RedisClient;
import com.example.caching.model.User;
import com.example.caching.repository.UserRepository;
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
class ReadAndWriteThroughCacheTest {

    @Mock
    private RedisClient redisClient;

    @Mock
    private UserRepository userRepository;

    private ReadAndWriteThroughCache cache;

    @BeforeEach
    void setUp() {
        cache = new ReadAndWriteThroughCache(redisClient, userRepository);
    }

    @Test
    @DisplayName("Read-Through: Returns data from Redis on Cache Hit without reaching DB")
    void testGet_CacheHit() {
        User user = new User("u-1", "Charlie", "charlie@example.com");
        when(redisClient.get("u-1")).thenReturn(Optional.of(user));

        Optional<User> result = cache.get("u-1");

        assertTrue(result.isPresent());
        assertEquals("Charlie", result.get().getName());
        verify(redisClient, times(1)).get("u-1");
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Read-Through: On Cache Miss, cache transparently loads from DB and updates itself")
    void testGet_CacheMiss() {
        User user = new User("u-1", "Charlie", "charlie@example.com");
        when(redisClient.get("u-1")).thenReturn(Optional.empty());
        when(userRepository.findById("u-1")).thenReturn(Optional.of(user));

        Optional<User> result = cache.get("u-1");

        assertTrue(result.isPresent());
        assertEquals("Charlie", result.get().getName());
        verify(redisClient, times(1)).get("u-1");
        verify(userRepository, times(1)).findById("u-1");
        verify(redisClient, times(1)).set(user, 600);
    }

    @Test
    @DisplayName("Write-Through: Synchronously writes to both DB and Cache before returning")
    void testPut_WriteThrough() {
        User user = new User("u-2", "Diana", "diana@example.com");

        cache.put(user);

        // Verify synchronous persistence to both DB and Redis
        verify(userRepository, times(1)).save(user);
        verify(redisClient, times(1)).set(user, 600);
    }
}
