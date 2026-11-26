package com.uma.executor.api.springbootmultithreading.controller;

import com.uma.executor.api.springbootmultithreading.entity.User;
import com.uma.executor.api.springbootmultithreading.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
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
                });
    }




}
