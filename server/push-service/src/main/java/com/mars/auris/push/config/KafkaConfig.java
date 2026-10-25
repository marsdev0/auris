package com.mars.auris.push.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * @author geyan
 * @date 2026/9/13
 */
@Configuration
public class KafkaConfig {

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    /**
     * Manual-ack listener container factory (P5 §1.5), for @KafkaListeners that take an Acknowledgment parameter
     * (EventConsumer and the ChannelWorkers).
     * <p>
     * AckMode = MANUAL_IMMEDIATE: offset commit timing is decided by the listener's ack.acknowledge() —
     * commit only on success; on failure no commit, Kafka redelivers (the consumption-side foundation of at-least-once).
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> manualAckFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
