package com.fantasy.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.security.Security;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

	static final String DNS_CACHE_TTL_PROPERTY = "networkaddress.cache.ttl";
	static final String DNS_CACHE_TTL_SECONDS = "5";

	static void main(String[] args) {
		holdResolvedAddressesBriefly();
		SpringApplication.run(Application.class, args);
	}

	/**
	 * A redeployed downstream comes back as a new container on a new address under the same name,
	 * and the JVM's own default is to keep using a resolved address for 30 seconds. So after every
	 * downstream deploy the BFF kept dialling the container that had just been removed - refused,
	 * timed out, then "No route to host" - for half a minute after the new one was serving
	 * (JAVA-SPRING-BOOT-3E to 3M, after yahoo-service#99 on staging). Docker's resolver is local
	 * and answers in microseconds, so asking it every five seconds costs nothing. Set before
	 * anything resolves a name, since the JDK reads the policy once; an operator's own setting
	 * (a java.security override) is left alone.
	 */
	static void holdResolvedAddressesBriefly() {
		if (Security.getProperty(DNS_CACHE_TTL_PROPERTY) == null) {
			Security.setProperty(DNS_CACHE_TTL_PROPERTY, DNS_CACHE_TTL_SECONDS);
		}
	}
}
