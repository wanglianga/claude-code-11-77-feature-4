package com.example.clearing.repo;

import com.example.clearing.domain.TokenBucket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TokenBucketRepository extends JpaRepository<TokenBucket, String> {
}
