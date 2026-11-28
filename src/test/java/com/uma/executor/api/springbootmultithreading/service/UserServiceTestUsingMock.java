package com.uma.executor.api.springbootmultithreading.service;

import com.uma.executor.api.springbootmultithreading.entity.User;
import com.uma.executor.api.springbootmultithreading.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTestUsingMock {

    @Mock
    private UserRepository userRepository;

    /*
    Error:
    Cannot mock/spy class com.uma.executor.api.springbootmultithreading.service.UserServiceTestUsingMock$$Lambda$551/0x000001d2da19b3d8
Mockito cannot mock/spy because :
 - VM does not support modification of given type
org.mockito.exceptions.base.MockitoException: Unable to initialize @Spy annotated field 'taskExecutor'.

Cannot mock/spy class com.uma.executor.api.springbootmultithreading.service.UserServiceTestUsingMock$$Lambda$551/0x000001d2da19b3d8
Mockito cannot mock/spy because :
 - VM does not support modification of given type
	at org.mockito.junit.jupiter.MockitoExtension.beforeEach(MockitoExtension.java:160)

    Reason:
    Mockito cannot @Spy on a Java lambda expression (Runnable::run) because the JVM generates hidden,
    unmodifiable synthetic classes ($$Lambda$...) at runtime. ByteBuddy (the bytecode engine behind Mockito) cannot subclass or instrument those synthetic JVM lambda classes.

    Solution: below
    @Spy
    private Executor taskExecutor = new org.springframework.core.task.SyncTaskExecutor();

    OR

    @Spy
    private Executor taskExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    OR

    @BeforeEach
    void setUp() {}

     */
//    @Spy
//    private Executor taskExecutor = Runnable::run; // @Spy uses the real Runnable::run implementation

    @Spy
    private Executor taskExecutor = new org.springframework.core.task.SyncTaskExecutor();

/*    @Spy
    private Executor taskExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };*/


    @InjectMocks
    private UserService userService; // Mockito injects both userRepository and taskExecutor automatically



//    @BeforeEach
//    void setUp() {
//        // Direct executor runs supplyAsync tasks immediately on the calling thread for deterministic unit tests
//        Executor directExecutor = Runnable::run;
//        userService = new UserService(userRepository, directExecutor);
//    }

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

    /*
    This test verifies that UserService.getAllUsersInParallel(50) truly splits work across multiple background threads simultaneously and merges all results cleanly without losing data.

Step 1: Set Up a Real Multi-Threaded Environment

• Create a 3-thread pool: Executors.newFixedThreadPool(3) creates three real background worker threads instead of the single-threaded Runnable::run executor used in your other unit tests.
• Instantiate UserService: Passing realThreadPool into new UserService(userRepository, realThreadPool) ensures CompletableFuture.supplyAsync(..., taskExecutor) runs its tasks on those three background threads.
• Create a thread-safe tracker: ConcurrentHashMap.newKeySet() creates a thread-safe Set<String> to safely collect thread names from multiple workers at the exact same time without throwing a ConcurrentModificationException.

Step 2: Simulate Concurrent Database Queries with Mockito

• Mock the record count: when(userRepository.count()).thenReturn(150L) tells UserService there are 150 rows in the table. With batchSize = 50, UserService calculates 3 pages (150 / 50 = 3).
• Capture each worker's name: Inside thenAnswer(...), workerThreadNames.add(Thread.currentThread().getName()) records the name of whichever background thread (such as pool-1-thread-1 or pool-1-thread-2) executes that page query.
• Add a 50ms delay: Thread.sleep(50) simulates real database latency. Without this pause, an in-memory mock finishes in microseconds, allowing the first thread to grab and complete all 3 pages before threads 2 and 3 even start.
• Build dynamic page data: Using pageable.getOffset() and pageable.getPageSize(), the stub generates the exact 50 users for that page (User 1 to User 50 for Page 0, User 51 to User 100 for Page 1, and User 101 to User 150 for Page 2) and wraps them in a PageImpl.

Step 3: Trigger Execution and Wait Safely

• Run the parallel method: concurrentUserService.getAllUsersInParallel(50) spawns all 3 CompletableFuture tasks at once.
• Wait with a safety timeout: .get(5, TimeUnit.SECONDS) blocks the test thread until all 3 workers finish and combine their lists. If a bug or deadlock ever stalls the threads, the test fails cleanly after 5 seconds instead of hanging forever.

Step 4: Assert Data Integrity and True Concurrency

• Check merged data: assertThat(combined).hasSize(150) along with checking index 0 (User 1) and index 149 (User 150) proves that all 3 chunks merged in the proper page order with zero lost records.
• Prove multiple threads ran: assertThat(workerThreadNames).doesNotContain(testThreadName).hasSizeGreaterThan(1) confirms two critical things:
	• None of the database queries ran on the main JUnit test thread.
	• More than one background worker thread participated concurrently.

Step 5: Clean Up Resources in finally

• Shut down the thread pool: realThreadPool.shutdown() inside the finally block guarantees that all 3 background threads terminate cleanly after the test finishes, even if an assertion fails.
If you would like to run a quick comparison benchmark test or check how UserService handles empty or malformed lines inside parseCsvFile, let me know and we can add tests for those edge cases!

     */
    @Test
    @DisplayName("getAllUsersInParallel() - Should execute across multiple threads concurrently and merge safely")
    void shouldExecuteConcurrentlyAcrossMultipleThreads() throws Exception {
        // 1. Create a real 3-thread pool for this concurrency test
        ExecutorService realThreadPool = Executors.newFixedThreadPool(3);
        UserService concurrentUserService = new UserService(userRepository, realThreadPool);

        // Thread-safe set to capture which threads actually executed the repository calls
        Set<String> workerThreadNames = ConcurrentHashMap.newKeySet();

        try {
            when(userRepository.count()).thenReturn(150L);

            when(userRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
                // Record the background worker thread name
                workerThreadNames.add(Thread.currentThread().getName());

                // Simulate a 50ms DB query so threads overlap instead of one thread finishing all 3
                Thread.sleep(50);

                Pageable pageable = invocation.getArgument(0);
                int start = (int) pageable.getOffset();
                int end = Math.min(start + pageable.getPageSize(), 150);

                List<User> chunk = IntStream.range(start, end)
                        .mapToObj(i -> {
                            User u = new User();
                            u.setName("User " + (i + 1));
                            return u;
                        })
                        .toList();

                return new PageImpl<>(chunk, pageable, 150L);
            });

            // 2. Execute 3 pages (150 records / batchSize 50) in parallel with a 5-second safety timeout
            List<User> combined = concurrentUserService.getAllUsersInParallel(50)
                    .get(5, TimeUnit.SECONDS);

            // 3. Verify all 150 records were merged intact with no lost updates
            assertThat(combined).hasSize(150);
            assertThat(combined.get(0).getName()).isEqualTo("User 1");
            assertThat(combined.get(149).getName()).isEqualTo("User 150");

            // 4. Verify multiple distinct worker threads were used (not the main test thread)
            String testThreadName = Thread.currentThread().getName();
            assertThat(workerThreadNames)
                    .doesNotContain(testThreadName)
                    .hasSizeGreaterThan(1);

        } finally {
            // 5. Always shut down the thread pool so background threads don't leak after the test
            realThreadPool.shutdown();
        }
    }

    /*
        What This Verifies
     • No silent partial data: Confirms UserService never returns incomplete data (only Pages 0 and 2) when Page 1 crashes—instead, the entire operation fails cleanly.
     • End-to-end error propagation: Confirms the exception unwraps into CompletionException with your original message intact, which is the exact exception your GlobalExceptionHandler intercepts in the controller layer.
     */
    @Test
    @DisplayName("getAllUsersInParallel() - Should fail and propagate exception if one parallel page fails")
    void shouldPropagateExceptionWhenOnePageFails() {
        when(userRepository.count()).thenReturn(150L);

        when(userRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);

            // Simulate Page 1 crashing while Pages 0 and 2 succeed
            if (pageable.getPageNumber() == 1) {
                throw new RuntimeException("DB connection lost on page 1");
            }

            User user = new User();
            user.setName("User from page " + pageable.getPageNumber());
            return new PageImpl<>(List.of(user), pageable, 150L);
        });

        CompletableFuture<List<User>> future = userService.getAllUsersInParallel(50);

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB connection lost on page 1");
    }


}
