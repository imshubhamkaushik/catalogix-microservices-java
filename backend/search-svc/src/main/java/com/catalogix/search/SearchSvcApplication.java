package com.catalogix.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = { "com.catalogix.search", "com.catalogix.security" })
@EnableScheduling
public class SearchSvcApplication {
  public static void main(String[] a) {
    SpringApplication.run(SearchSvcApplication.class, a);
  }
}
