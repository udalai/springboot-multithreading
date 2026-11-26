package com.uma.executor.api.springbootmultithreading.service;

import com.uma.executor.api.springbootmultithreading.entity.User;
import com.uma.executor.api.springbootmultithreading.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    //use csvExecutor bean for csv parsing
    @Async("csvExecutor")
    public CompletableFuture<List<User>> saveUsers(MultipartFile file) throws Exception{
        long st = System.currentTimeMillis();
        List<User> userFromCsv = parseCsvFile(file);
        log.info("saving users list ...of size {}, current thread name: {} ", userFromCsv.size(), Thread.currentThread().getName());
        userRepository.saveAll(userFromCsv);
        long et = System.currentTimeMillis();
        log.info("Total time taken: {}", et -st);
        return CompletableFuture.completedFuture(userFromCsv);
    }

    //This will call default "taskExecutor". This is same as @Async("taskExecutor") // Explicitly uses "taskExecutor"
    @Async
    public CompletableFuture<List<User>> getAllUser() {
        log.info("fetch list of users from db by ... thread name: {}", Thread.currentThread().getName());
        List<User> users = userRepository.findAll();
        return CompletableFuture.completedFuture(users);
    }

    private List<User> parseCsvFile(final MultipartFile file) throws IOException {
        final List<User> users = new ArrayList<>();

        try {
            //try {
//            BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()));

            //This code uses a try-with-resources statement, which automatically generates a hidden finally block at compile time to close your BufferedReader.
            //So you don't need a catch or finally block in this case
             try(BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
//                 String line;
                 String line = br.readLine(); // Read and discard the first line (header) or remove this line and header row from csv file
                 while ((line = br.readLine()) != null) {
                     final String[] data = line.split(",");
                     final User user = new User();
                     user.setName(data[0]);
                     user.setEmail(data[1]);
                     user.setGender(data[2]);
                     users.add(user);
                 }

                 return users;

                 //OR replce below code using stream
                 /*try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
                     return bufferedReader.lines()
                             .skip(1) // Skips the CSV header row
                             .map(csvline -> {
                                 String[] data = csvline.split(",");
                                 User user = new User();
                                 // map fields
                                 return user;
                             })
                             .collect(Collectors.toList());
                 }*/

//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
             }
        } catch (final IOException e) {
            log.error("Failed to parse CSV file {}", e.getMessage());
            throw new RuntimeException("Failed to parse CSV file{}", e);
        }
    }

    //use notificationExecutor for email, which off-load the heavy load from
    @Async("notificationExecutor")
    public void sendWelcomeEmail(String email) {
        log.info("Sending email on thread: {}", Thread.currentThread().getName()); // Prints notifyThread-1
        // ...
    }
}
