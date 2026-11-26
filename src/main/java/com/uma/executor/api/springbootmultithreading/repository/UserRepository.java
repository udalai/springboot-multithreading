package com.uma.executor.api.springbootmultithreading.repository;

import com.uma.executor.api.springbootmultithreading.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {
}
