package io.github.sanskar121543.payments.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class KafkaTopicConfig {

    // 6 partitions split evenly across 1, 2 or 3 consumer instances, and still 3/3 if one of three dies.
    @Bean
    NewTopic transferCompletedTopic(@Value("${payments.kafka.topic}") String topic,
            @Value("${payments.kafka.partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    // Same partition count: a dead letter keeps the partition of the record that failed.
    @Bean
    NewTopic transferCompletedDeadLetterTopic(@Value("${payments.kafka.topic}") String topic,
            @Value("${payments.kafka.partitions}") int partitions) {
        return TopicBuilder.name(topic + ".DLT").partitions(partitions).replicas(1).build();
    }
}
