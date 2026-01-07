package fr.ses10doigts.tradeIO5;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TradeIO5 {

	public static void main(String[] args) {
		SpringApplication.run(TradeIO5.class, args);

	}

}
