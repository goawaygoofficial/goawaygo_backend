package com.goaway.backend;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

	public static void main(String[] args) {
		// Run in UTC everywhere so timestamps behave the same on every machine, in CI and in production.
		// It also avoids sending legacy zone IDs (e.g. Windows' "Asia/Calcutta") that PostgreSQL rejects.
		// Tests set the same zone via the surefire argLine in pom.xml.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(BackendApplication.class, args);
	}

}
