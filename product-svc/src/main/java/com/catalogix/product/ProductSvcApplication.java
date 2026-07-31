package com.catalogix.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class ProductSvcApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductSvcApplication.class, args);
    }
}
