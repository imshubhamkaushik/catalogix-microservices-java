package com.catalogix.seller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.catalogix.seller", "com.catalogix.security"})
public class SellerSvcApplication {
    public static void main(String[] args) { SpringApplication.run(SellerSvcApplication.class, args); }
}
