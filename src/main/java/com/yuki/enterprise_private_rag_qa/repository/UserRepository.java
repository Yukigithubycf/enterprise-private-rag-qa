package com.yuki.enterprise_private_rag_qa.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.yuki.enterprise_private_rag_qa.model.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}
