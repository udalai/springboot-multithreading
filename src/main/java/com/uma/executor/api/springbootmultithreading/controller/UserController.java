package com.uma.executor.api.springbootmultithreading.controller;

import com.uma.executor.api.springbootmultithreading.entity.User;
import com.uma.executor.api.springbootmultithreading.service.UserService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Validated
public class UserController {
    private final UserService userService;

    @PostMapping(value = "/users", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity saveUsers(@RequestParam(value = "files") MultipartFile[] files) throws Exception {
        for (MultipartFile file: files) {
            userService.saveUsers(file);
        }

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/users")
    public CompletableFuture<ResponseEntity> findAllUsers() {
        //this will be served by only one thread
        return userService.getAllUser().thenApply(ResponseEntity::ok);
    }

    /**
     * • Ignored results: CompletableFuture.allOf(users1, users2, users3).join() waits for all three futures to complete, but their return values are never extracted or combined.
     * • Extra database call: Calling userService.getAllUser().thenApply(ResponseEntity::ok) at the end triggers a brand-new query instead of using the data already fetched by the threads.
     * • Blocking the web thread: Using .join() blocks the main HTTP request thread until all async tasks finish, which defeats the purpose of returning a CompletableFuture from a Spring @GetMapping method
     */
    //This is just to show multi threading and restun status ok
    @GetMapping("/usersByMultiThread")
    public ResponseEntity getAllUsersReturnStatusOK() { //uncomment to return just ResponseEntity
//  public CompletableFuture<ResponseEntity> getAllUsers() { //commented bcz , returning only status here, uncomment to return CompletableFuture

        //this will be served by multiple thread parallelly
        CompletableFuture<List<User>> users1 = userService.getAllUser();
        CompletableFuture<List<User>> users2 = userService.getAllUser();
        CompletableFuture<List<User>> users3 = userService.getAllUser();
        CompletableFuture.allOf(users1, users2, users3).join();
//        return userService.getAllUser().thenApply(ResponseEntity::ok); //uncomment to return CompletableFuture
        return ResponseEntity.status(HttpStatus.OK).build(); //comment to return CompletableFuture instead of just ResponseEntity
    }

    /*
    Important Note on CompletableFuture vs. void Methods
    • When AsyncUncaughtExceptionHandler triggers: Spring only invokes AsyncUncaughtExceptionHandler for @Async methods that have a void return type.
    • How CompletableFuture handles errors: When an @Async method returns a CompletableFuture (like your saveUsers and getAllUser methods), Spring wraps any thrown exception inside that CompletableFuture instead of sending it to AsyncUncaughtExceptionHandler.
    • Handling errors on CompletableFuture: To catch exceptions from your CompletableFuture methods in your controller, attach .exceptionally() to your future chain.
    */
    @GetMapping("/usersDataByMultiThread")
    public CompletableFuture<ResponseEntity<List<User>>> getAllUsersReturnData() {

        // 1. Trigger the 3 async calls in parallel
        CompletableFuture<List<User>> users1 = userService.getAllUser();
        CompletableFuture<List<User>> users2 = userService.getAllUser();
        CompletableFuture<List<User>> users3 = userService.getAllUser();

        // 2. Wait for all 3 to complete without blocking the HTTP thread
        return CompletableFuture.allOf(users1, users2, users3)
                .thenApply(v -> {
                    List<User> combined = new ArrayList<>();
                    combined.addAll(users1.join());
                    combined.addAll(users2.join());
                    combined.addAll(users3.join());
                    return ResponseEntity.ok(combined);
                })
                .exceptionally(ex -> {
                    log.error("Error fetching users asynchronously", ex);
                    return ResponseEntity.internalServerError().build();

                    // Unwrap CompletionException to get the real root cause message in json format
                    /*
                    Two Key Details in This Update if want to use root cause message in json format
                        • Wildcard ResponseEntity<?> and .<ResponseEntity<?>>thenApply: Because .thenApply() returns ResponseEntity<List<User>> and .exceptionally() returns ResponseEntity<Map<String, Object>>, using ResponseEntity<?> lets Java's compiler accept both return types in the same chain.
                        • Unwrapping ex.getCause(): CompletableFuture wraps any exception thrown inside userService.getAllUser() inside a CompletionException. Checking ex.getCause() ensures your JSON "message" field displays the actual database or service error rather than a generic wrapper message.
                     */
                    /*Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

                    Map<String, Object> errorBody = Map.of(
                            "status", 500,
                            "error", "Internal Server Error",
                            "message", cause.getMessage() != null ? cause.getMessage() : "Failed to fetch users"
                    );

                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody);
                    */
                });
    }

    /*
     To prevent slow database queries from hanging your endpoint indefinitely, chain .orTimeout(5, TimeUnit.SECONDS) right after CompletableFuture.allOf(...)
     and check for TimeoutException inside .exceptionally(...) to return a 504 Gateway Timeout JSON response.
     */
    @GetMapping("/usersDataByMultiThreadWithTimeOut")
    public CompletableFuture<ResponseEntity<?>> getAllUsersReturnDataWithTimeOut() {

        // 1. Trigger the 3 async calls in parallel
        CompletableFuture<List<User>> users1 = userService.getAllUser();
        CompletableFuture<List<User>> users2 = userService.getAllUser();
        CompletableFuture<List<User>> users3 = userService.getAllUser();

        // 2. Wait for all 3 with a 5-second timeout
        return CompletableFuture.allOf(users1, users2, users3)
                .orTimeout(5, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(v -> {
                    List<User> combined = new ArrayList<>();
                    combined.addAll(users1.join());
                    combined.addAll(users2.join());
                    combined.addAll(users3.join());
                    return ResponseEntity.ok(combined);
                })
                .exceptionally(ex -> {
                    // Unwrap CompletionException to inspect the real root cause
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

                    if (cause instanceof TimeoutException) {
                        log.warn("Timed out waiting for async user queries");
                        Map<String, Object> timeoutBody = Map.of(
                                "status", 504,
                                "error", "Gateway Timeout",
                                "message", "Fetching users took longer than 5 seconds"
                        );
                        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(timeoutBody);
                    }

                    log.error("Error fetching users asynchronously", cause);
                    Map<String, Object> errorBody = Map.of(
                            "status", 500,
                            "error", "Internal Server Error",
                            "message", cause.getMessage() != null ? cause.getMessage() : "Failed to fetch users"
                    );
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody);
                });
    }

    @GetMapping("/usersDataByPages")
    public CompletableFuture<ResponseEntity<?>> getAllUsersReturnDataByBatch(
            @RequestParam(defaultValue = "500") @Min(10) @Max(5000) int batchSize) {

        return userService.getAllUsersInParallel(batchSize)
                .orTimeout(10, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {  // we can remove this part after global exception handling
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log.error("Error fetching users asynchronously", cause);

                    Map<String, Object> errorBody = Map.of(
                            "status", 500,
                            "error", "Internal Server Error",
                            "message", cause.getMessage() != null ? cause.getMessage() : "Failed to fetch users"
                    );
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody);
                });
    }


}
