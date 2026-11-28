package com.uma.executor.api.springbootmultithreading.controller;

import com.uma.executor.api.springbootmultithreading.entity.User;
import com.uma.executor.api.springbootmultithreading.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        // Seed 120 users so batchSize=50 splits work across 3 parallel pages (50 + 50 + 20)
        List<User> testUsers = IntStream.rangeClosed(1, 120)
                .mapToObj(i -> {
                    User user = new User();
                    user.setName("User " + i);
                    user.setEmail("user" + i + "@example.com");
                    return user;
                })
                .toList();

        userRepository.saveAll(testUsers);
    }

    @Test
    @DisplayName("Should fetch all 120 users across multiple threads and return 200 OK")
    void shouldReturnAllUsersInParallel() throws Exception {
        // Step 1: Trigger the endpoint and verify async processing started
        MvcResult mvcResult = mockMvc.perform(get("/v1/usersDataByPages")
                        .param("batchSize", "50")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Step 2: Wait for CompletableFuture to finish and assert the final JSON response
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(120)))
                .andExpect(jsonPath("$[0].name").value("User 1"))
                .andExpect(jsonPath("$[119].name").value("User 120"));
    }

    @Test
    @DisplayName("Should return 400 Bad Request JSON when batchSize is below @Min(50)")
    void shouldReturn400WhenBatchSizeTooSmall() throws Exception {
        // Validation runs synchronously before async starts, so no asyncDispatch is needed
        mockMvc.perform(get("/v1/usersDataByPages")
                        .param("batchSize", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("batchSize")));
    }

    @Test
    @DisplayName("Should return 404 Not Found JSON from GlobalExceptionHandler for unknown path")
    void shouldReturn404ForInvalidUrl() throws Exception {
        mockMvc.perform(get("/non-existent-endpoint")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }
}

