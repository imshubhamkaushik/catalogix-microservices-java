package com.catalogix.fulfillment;
import org.springframework.boot.SpringApplication; import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"com.catalogix.fulfillment","com.catalogix.security"}) public class FulfillmentSvcApplication { public static void main(String[] args){SpringApplication.run(FulfillmentSvcApplication.class,args);} }
