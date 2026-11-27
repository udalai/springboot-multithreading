package com.uma.executor.api.springbootmultithreading.service;

import com.uma.executor.api.springbootmultithreading.entity.User;
import com.uma.executor.api.springbootmultithreading.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    /*
    warning: Lombok does not copy the annotation 'org.springframework.beans.factory.annotation.Qualifier' into the constructor
    by default Lombok does not copy @Qualifier onto generated constructor parameters unless you tell it to via a lombok.config file—or simply write the constructor yourself.
    Recommended Fix: Add lombok.config (Best for Multiple Services)
     Create lombok.config in your project's root directory:
        config.stopBubbling = true
        lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier

    Alternative 1: Match the Field Name to the Bean Name
        Spring automatically resolves beans by parameter name when multiple Executor beans exist, even without @Qualifier.
        Name your field taskExecutor: Because your @Bean(name = "taskExecutor") matches the field name taskExecutor, you can remove @Qualifier completely and keep @RequiredArgsConstructor.

    Alternative 2: Write an Explicit Constructor
        If you do not want to add a lombok.config file and want to keep @Qualifier, drop @RequiredArgsConstructor on that single class and write the constructor manually.

    */
//    @Qualifier("taskExecutor") //commented following 'Alternative 1' option. Spring matches bean name "taskExecutor" automatically.
    private final Executor taskExecutor;

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

    public CompletableFuture<List<User>> getAllUsersInParallel(int batchSize) {

        long startTime = System.nanoTime();

        long totalRecords = userRepository.count();
        int totalPages = (int)Math.ceil((double) totalRecords/batchSize);
        if (totalPages == 0) {
          return CompletableFuture.completedFuture(Collections.emptyList());
        }

        // Spawn parallel tasks directly on your custom taskExecutor (avoids @Async self-invocation trap)
        //mapToObj converts a primitive IntStream (stream of int numbers) into a standard object Stream<CompletableFuture<List<User>>> so you can collect them into a List.
        List<CompletableFuture<List<User>>> futures = IntStream.range(0, totalPages)
                .mapToObj(
                        page -> CompletableFuture.supplyAsync(
                                () -> fetchPage(page, batchSize), taskExecutor
                        )
                ).toList();

        //commented to add logger and to check execution time
//        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])) //CompletableFuture[] completableFuture = new CompletableFuture[0], this is an empty array
//                .thenApply(ignored -> futures.stream()
//                        .map(CompletableFuture::join)
//                        .flatMap(List::stream)
//                        .toList());

        /*
        To accurately measure the execution time of your asynchronous parallel tasks, capture the start timestamp (System.currentTimeMillis()
        or System.nanoTime()) before spawning the futures and log the elapsed time inside the final .thenApply(...) callback.
         */
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> {
                    List<User> combined = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .toList();

                    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
                    log.info("Completed parallel fetch of {} users across {} threads in {} ms",
                            combined.size(), totalPages, durationMs);

                    return combined;
                });

    }

    public List<User> fetchPage(int pageNumber, int pageSize) {
        log.info("Fetching page: {} on thread: {}", pageNumber, Thread.currentThread().getName());
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("id").ascending());
        //List<User> pageContent = userRepository.findAll(pageable).getContent();
        return userRepository.findAll(pageable).getContent();
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
