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
     * 手动 ack 的监听器容器工厂(P5 §1.5),供带 Acknowledgment 参数的 @KafkaListener 挂载
     * (EventConsumer 与各 ChannelWorker)。
     * <p>
     * AckMode = MANUAL_IMMEDIATE:offset 提交时机由 listener 的 ack.acknowledge() 决定——
     * 处理成功才提交;失败不提交,消息由 Kafka 重新投递(at-least-once 的消费侧根基)。
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
