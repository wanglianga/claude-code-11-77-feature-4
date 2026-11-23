package com.example.clearing.repo;

import com.example.clearing.domain.CatalogItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogItemRepository extends JpaRepository<CatalogItem, String> {
}
