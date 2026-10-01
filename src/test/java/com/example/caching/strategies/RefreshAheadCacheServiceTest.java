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
class RefreshAheadCacheServiceTest {

    @Mock
    private RedisClient redisClient;

    @Mock
    private UserRepository userRepository;

    private RefreshAheadCacheService cacheService;

    @BeforeEach
    void setUp() {
        // TTL 2s, threshold factor 0.5 (triggers refresh after >= 1s has elapsed)
        cacheService = new RefreshAheadCacheService(redisClient, userRepository, 2, 0.5);
    }

    @AfterEach
    void tearDown() {
        cacheService.close();
    }

    @Test
    @DisplayName("Refresh-Ahead: Initial read causes Cache Miss, queries DB and populates cache")
    void testInitialGet_CacheMiss() {
        User user = new User("u-6", "Hannah", "hannah@example.com");
        when(redisClient.get("u-6")).thenReturn(Optional.empty());
        when(userRepository.findById("u-6")).thenReturn(Optional.of(user));

        Optional<User> result = cacheService.getUser("u-6");

        assertTrue(result.isPresent());
        assertEquals("Hannah", result.get().getName());
        verify(redisClient, times(1)).set(user, 2);
    }

    @Test
    @DisplayName("Refresh-Ahead: Read before threshold is a standard Hit (no async reload triggered)")
    void testGet_BeforeThreshold_NoRefresh() throws InterruptedException {
        User user = new User("u-6", "Hannah", "hannah@example.com");
        when(redisClient.get("u-6")).thenReturn(Optional.empty());
        when(userRepository.findById("u-6")).thenReturn(Optional.of(user));

        // Initial populate
        cacheService.getUser("u-6");

        // Subsequent read right away (< 1s threshold)
        when(redisClient.get("u-6")).thenReturn(Optional.of(user));
        Optional<User> secondResult = cacheService.getUser("u-6");

        assertTrue(secondResult.isPresent());
        // findById was only called once for the initial miss
        verify(userRepository, times(1)).findById("u-6");
    }

    @Test
    @DisplayName("Refresh-Ahead: Read after threshold triggers proactive async background DB fetch & TTL extension")
    void testGet_AfterThreshold_TriggersProactiveRefresh() throws InterruptedException {
        User user = new User("u-6", "Hannah", "hannah@example.com");
        User updatedUser = new User("u-6", "Hannah Pro", "hannah.pro@example.com");

        when(redisClient.get("u-6")).thenReturn(Optional.empty());
        when(userRepository.findById("u-6")).thenReturn(Optional.of(user));

        // 1. Initial populate (inserted at T=0)
        cacheService.getUser("u-6");

        // 2. Wait 1.1s (exceeds 50% of 2s TTL threshold)
        Thread.sleep(1100);

        // 3. Setup mocks for subsequent read and async refresh
        when(redisClient.get("u-6")).thenReturn(Optional.of(user));
        when(userRepository.findById("u-6")).thenReturn(Optional.of(updatedUser));

        // 4. Access key: should return cached user immediately while scheduling background reload
        Optional<User> result = cacheService.getUser("u-6");
        assertTrue(result.isPresent());
        assertEquals("Hannah", result.get().getName());

        // 5. Allow background thread to complete
        Thread.sleep(500);

        // findById should have been invoked 2 times (1 initial miss + 1 background refresh)
        verify(userRepository, times(2)).findById("u-6");
        verify(redisClient, times(1)).set(updatedUser, 2);
    }
}
