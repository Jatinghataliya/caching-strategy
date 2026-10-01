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
class WriteAroundUserServiceTest {

    @Mock
    private RedisClient redisClient;

    @Mock
    private UserRepository userRepository;

    private WriteAroundUserService userService;

    @BeforeEach
    void setUp() {
        userService = new WriteAroundUserService(redisClient, userRepository);
    }

    @Test
    @DisplayName("Write-Around: saveUser saves to DB directly, evicts stale key, but does NOT populate cache")
    void testSaveUser_BypassesCachePopulation() {
        User user = new User("u-5", "George", "george@example.com");

        userService.saveUser(user);

        verify(userRepository, times(1)).save(user);
        verify(redisClient, times(1)).evict("u-5");
        verify(redisClient, never()).set(any(), anyLong());
    }

    @Test
    @DisplayName("Write-Around: getUser follows Cache-Aside (reads Redis on hit)")
    void testGetUser_CacheHit() {
        User user = new User("u-5", "George", "george@example.com");
        when(redisClient.get("u-5")).thenReturn(Optional.of(user));

        Optional<User> result = userService.getUser("u-5");

        assertTrue(result.isPresent());
        assertEquals("George", result.get().getName());
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Write-Around: getUser queries DB on miss and caches on demand")
    void testGetUser_CacheMiss() {
        User user = new User("u-5", "George", "george@example.com");
        when(redisClient.get("u-5")).thenReturn(Optional.empty());
        when(userRepository.findById("u-5")).thenReturn(Optional.of(user));

        Optional<User> result = userService.getUser("u-5");

        assertTrue(result.isPresent());
        verify(redisClient, times(1)).set(user, 300);
    }
}
