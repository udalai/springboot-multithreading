package com.uma.executor.api.springbootmultithreading;

import com.uma.executor.api.springbootmultithreading.entity.User;
import com.uma.executor.api.springbootmultithreading.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.async.timeout-seconds=1") // Overrides 10s -> 1s for tests
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("200 OK - Should fetch all 120 users across 3 parallel threads")
    void shouldReturnAllUsersInParallel() throws Exception {
        // 1. Stub total record count (120 rows / batchSize 50 = 3 parallel pages)
        when(userRepository.count()).thenReturn(120L);

        // 2. Stub paged queries dynamically in memory (Page 0: 50, Page 1: 50, Page 2: 20)
        when(userRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), 120);

            List<User> pageUsers = IntStream.range(start, end)
                    .mapToObj(i -> {
                        User user = new User();
                        user.setName("User " + (i + 1));
                        user.setEmail("user" + (i + 1) + "@example.com");
                        return user;
                    })
                    .toList();

            return new PageImpl<>(pageUsers, pageable, 120L);
        });

        // 3. Trigger async request and dispatch result once background threads finish
        MvcResult mvcResult = mockMvc.perform(get("/v1/usersDataByPages")
                        .param("batchSize", "50")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();
        // Tell MockMvc to wait up to 15 seconds so your 10-second .orTimeout() fires first (10s in controller)
        mvcResult.getAsyncResult(15_000);

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(120)))
                .andExpect(jsonPath("$[0].name").value("User 1"))
                .andExpect(jsonPath("$[119].name").value("User 120"));
    }

    @Test
    @DisplayName("400 Bad Request - Should return JSON error when batchSize is below @Min(50)")
    void shouldReturn400WhenBatchSizeTooSmall() throws Exception {
        mockMvc.perform(get("/v1/usersDataByPages")
                        .param("batchSize", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("batchSize")));
    }

    @Test
    @DisplayName("404 Not Found - Should return JSON error for unknown endpoint")
    void shouldReturn404ForInvalidUrl() throws Exception {
        mockMvc.perform(get("/unknown-path")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    @DisplayName("500 Internal Server Error - Should unwrap async DB failure into safe JSON")
    void shouldReturn500WhenDatabaseThrowsException() throws Exception {
        when(userRepository.count()).thenReturn(100L);
        when(userRepository.findAll(any(Pageable.class)))
                .thenThrow(new RuntimeException("Simulated database connection failure"));

        MvcResult mvcResult = mockMvc.perform(get("/v1/usersDataByPages")
                        .param("batchSize", "50")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();
        // Tell MockMvc to wait up to 15 seconds so your 10-second .orTimeout() fires first (10s in controller)
        mvcResult.getAsyncResult(15_000);

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value(containsString("Reference ID:")));
    }

    @Test
    @DisplayName("504 Gateway Timeout - Should return 504 JSON when background threads exceed timeout")
    void shouldReturn504WhenAsyncQueryTimesOut() throws Exception {
        when(userRepository.count()).thenReturn(100L);
        when(userRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            // Sleep longer than your controller's 10-second .orTimeout(10, TimeUnit.SECONDS)
            //  Thread.sleep(11_000);

            // Sleeps 1.5s, triggering the 1-second timeout almost immediately
            Thread.sleep(1_500);
            return new PageImpl<>(List.of());
        });

        MvcResult mvcResult = mockMvc.perform(get("/v1/usersDataByPages")
                        .param("batchSize", "50")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Tell MockMvc to wait up to 15 seconds so your 10-second .orTimeout() fires first (10s in controller)
        // mvcResult.getAsyncResult(15_000); // This is no more required Because @TestPropertySource(properties = "app.async.timeout-seconds=1") and in test above we set to 1.5 ms. so automatically will time out after 1 sec and throw error

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.status").value(504))
                .andExpect(jsonPath("$.error").value("Gateway Timeout"));
    }
}
