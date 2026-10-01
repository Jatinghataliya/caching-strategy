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
class CacheAsideUserServiceTest {

    @Mock
    private RedisClient redisClient;

    @Mock
    private UserRepository userRepository;

    private CacheAsideUserService userService;

    @BeforeEach
    void setUp() {
        userService = new CacheAsideUserService(redisClient, userRepository);
    }

    @Test
    @DisplayName("Cache-Aside: Should return user from cache on Cache Hit without querying DB")
    void testGetUser_CacheHit() {
        User cachedUser = new User("u-1", "Alice", "alice@example.com");
        when(redisClient.get("u-1")).thenReturn(Optional.of(cachedUser));

        Optional<User> result = userService.getUser("u-1");

        assertTrue(result.isPresent());
        assertEquals("Alice", result.get().getName());
        verify(redisClient, times(1)).get("u-1");
        verifyNoInteractions(userRepository);
        verify(redisClient, never()).set(any(), anyLong());
    }

    @Test
    @DisplayName("Cache-Aside: Should query DB and populate Cache on Cache Miss")
    void testGetUser_CacheMiss() {
        User dbUser = new User("u-2", "Bob", "bob@example.com");
        when(redisClient.get("u-2")).thenReturn(Optional.empty());
        when(userRepository.findById("u-2")).thenReturn(Optional.of(dbUser));

        Optional<User> result = userService.getUser("u-2");

        assertTrue(result.isPresent());
        assertEquals("Bob", result.get().getName());
        verify(redisClient, times(1)).get("u-2");
        verify(userRepository, times(1)).findById("u-2");
        verify(redisClient, times(1)).set(dbUser, 300);
    }

    @Test
    @DisplayName("Cache-Aside: Should return empty when neither Cache nor DB has the user")
    void testGetUser_NotFound() {
        when(redisClient.get("u-99")).thenReturn(Optional.empty());
        when(userRepository.findById("u-99")).thenReturn(Optional.empty());

        Optional<User> result = userService.getUser("u-99");

        assertFalse(result.isPresent());
        verify(redisClient, times(1)).get("u-99");
        verify(userRepository, times(1)).findById("u-99");
        verify(redisClient, never()).set(any(), anyLong());
    }

    @Test
    @DisplayName("Cache-Aside: updateUser should save to DB and evict stale key from Cache")
    void testUpdateUser() {
        User updatedUser = new User("u-1", "Alice Updated", "alice.new@example.com");

        userService.updateUser(updatedUser);

        verify(userRepository, times(1)).save(updatedUser);
        verify(redisClient, times(1)).evict("u-1");
    }
}
