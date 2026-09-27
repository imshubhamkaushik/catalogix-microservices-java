package com.catalogix.recommendation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = { "com.catalogix.recommendation", "com.catalogix.security" })
public class RecommendationSvcApplication {
  public static void main(String[] a) {
    SpringApplication.run(RecommendationSvcApplication.class, a);
  }
}
