package io.github.sanskar121543.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentsLedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsLedgerServiceApplication.class, args);
    }
}
