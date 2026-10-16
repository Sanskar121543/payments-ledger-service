package io.github.sanskar121543.payments.activity;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * The default error handler retries a record 10 times without pause and then skips it, which would
 * silently drop events. Instead: transient failures (database down, lock timeouts) are retried with
 * back-off until they succeed, and only records that can never succeed go to a dead-letter topic.
 */
@Configuration(proxyBeanMethods = false)
class ConsumerErrorHandling {

    static final String DEAD_LETTER_SUFFIX = ".DLT";

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafka,
                (record, error) -> new TopicPartition(record.topic() + DEAD_LETTER_SUFFIX, record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        // Capped well below max.poll.interval.ms, so a long outage doesn't get the consumer evicted.
        backOff.setMaxInterval(30_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetters, backOff);
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class,
                DataIntegrityViolationException.class);
        return handler;
    }
}
