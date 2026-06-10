package me.projects.pushpage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PushPageApplication {

	public static void main(String[] args) {
		SpringApplication.run(PushPageApplication.class, args);
	}

}
