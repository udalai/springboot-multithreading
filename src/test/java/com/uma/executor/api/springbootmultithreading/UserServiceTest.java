package com.uma.executor.api.springbootmultithreading;

import com.uma.executor.api.springbootmultithreading.entity.User;
import com.uma.executor.api.springbootmultithreading.repository.UserRepository;
import com.uma.executor.api.springbootmultithreading.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        // Direct executor runs supplyAsync tasks immediately on the calling thread for deterministic unit tests
        Executor directExecutor = Runnable::run;
        userService = new UserService(userRepository, directExecutor);
    }

    @Test
    @DisplayName("saveUsers() - Should skip CSV header row and save parsed users")
    void shouldParseCsvAndSkipHeader() throws Exception {
        String csvContent = """
                name,email,gender
                Alice,alice@example.com,Female
                Bob,bob@example.com,Male
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "users.csv",
                "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8)
        );

        when(userRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        CompletableFuture<List<User>> future = userService.saveUsers(file);
        List<User> savedUsers = future.join();

        assertThat(savedUsers).hasSize(2);
        assertThat(savedUsers.get(0).getName()).isEqualTo("Alice");
        assertThat(savedUsers.get(0).getEmail()).isEqualTo("alice@example.com");
        assertThat(savedUsers.get(0).getGender()).isEqualTo("Female");
        assertThat(savedUsers.get(1).getName()).isEqualTo("Bob");

        verify(userRepository, times(1)).saveAll(savedUsers);
    }

    @Test
    @DisplayName("getAllUsersInParallel() - Should return empty list immediately when table has 0 records")
    void shouldReturnEmptyListWhenNoRecordsExist() {
        when(userRepository.count()).thenReturn(0L);

        List<User> result = userService.getAllUsersInParallel(50).join();

        assertThat(result).isEmpty();
        verify(userRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("getAllUsersInParallel() - Should partition 120 records into 3 sorted pages and merge results")
    void shouldPartitionAndMergeAllPagesInParallel() {
        when(userRepository.count()).thenReturn(120L);

        when(userRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), 120);

            List<User> chunk = IntStream.range(start, end)
                    .mapToObj(i -> {
                        User u = new User();
                        u.setName("User " + (i + 1));
                        u.setEmail("user" + (i + 1) + "@example.com");
                        u.setGender(i % 2 == 0 ? "Male" : "Female");
                        return u;
                    })
                    .toList();

            return new PageImpl<>(chunk, pageable, 120L);
        });

        List<User> combined = userService.getAllUsersInParallel(50).join();

        // 1. Verify all 120 records were merged in order
        assertThat(combined).hasSize(120);
        assertThat(combined.get(0).getName()).isEqualTo("User 1");
        assertThat(combined.get(119).getName()).isEqualTo("User 120");

        // 2. Verify repository was queried exactly 3 times (pages 0, 1, 2) with ascending ID sort
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository, times(3)).findAll(pageableCaptor.capture());

        List<Pageable> capturedPages = pageableCaptor.getAllValues();
        assertThat(capturedPages).extracting(Pageable::getPageNumber).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(capturedPages).allSatisfy(p -> {
            assertThat(p.getPageSize()).isEqualTo(50);
            assertThat(p.getSort().getOrderFor("id")).isNotNull();
            assertThat(p.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
        });
    }

    @Test
    @DisplayName("getAllUser() - Should return all users from repository")
    void shouldReturnAllUsers() {
        User user = new User();
        user.setName("Charlie");
        when(userRepository.findAll()).thenReturn(List.of(user));

        List<User> result = userService.getAllUser().join();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Charlie");
        verify(userRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("saveUsers() - Should wrap IOException in RuntimeException when stream fails")
    void shouldThrowRuntimeExceptionOnCorruptFileStream() throws Exception {
        MockMultipartFile brokenFile = mock(MockMultipartFile.class);
        when(brokenFile.getInputStream()).thenThrow(new java.io.IOException("Stream closed"));

        assertThatThrownBy(() -> userService.saveUsers(brokenFile))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse CSV file");

        verify(userRepository, never()).saveAll(anyList());
    }
}
