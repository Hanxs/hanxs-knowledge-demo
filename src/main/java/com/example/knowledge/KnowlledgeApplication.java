package com.example.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * @author hanxu
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowlledgeApplication {

	public static void main(String[] args) {
		SpringApplication.run(KnowlledgeApplication.class, args);
	}
}
