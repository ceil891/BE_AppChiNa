package com.chineselearning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChineseLearningApplication {

	public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("--migrate-only")) { com.chineselearning.common.config.DatabaseMigrations.run();return; }
		SpringApplication.run(ChineseLearningApplication.class, args);
	}

}
