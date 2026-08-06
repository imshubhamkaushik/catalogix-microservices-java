package com.catalogix.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class CatalogSvcApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogSvcApplication.class, args);
    }
}
