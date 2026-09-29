#!/bin/bash
# kafka-topics-push.sh — Kafka Topic 初始化脚本

KAFKA_CONTAINER="auris-kafka"
BOOTSTRAP="localhost:9092"

echo "Creating Kafka topics..."

# auris-event(业务事件,ai-service 发,push-service 消费)
docker exec $KAFKA_CONTAINER kafka-topics --create --if-not-exists \
    --bootstrap-server $BOOTSTRAP \
    --topic auris-event \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=259200000 \
    --config cleanup.policy=delete

# auris-notify-delivery-email(投递指令,Router 发,Worker 消费)
docker exec $KAFKA_CONTAINER kafka-topics --create --if-not-exists \
    --bootstrap-server $BOOTSTRAP \
    --topic auris-notify-delivery-email \
    --partitions 2 \
    --replication-factor 1 \
    --config retention.ms=259200000 \
    --config cleanup.policy=delete

# auris-notify-delivery-inapp(投递指令,Router 发,Worker 消费)
docker exec $KAFKA_CONTAINER kafka-topics --create --if-not-exists \
    --bootstrap-server $BOOTSTRAP \
    --topic auris-notify-delivery-inapp \
    --partitions 2 \
    --replication-factor 1 \
    --config retention.ms=259200000 \
    --config cleanup.policy=delete

# auris-notify-delivery-feishu(投递指令,Router 发,Worker 消费)
docker exec $KAFKA_CONTAINER kafka-topics --create --if-not-exists \
    --bootstrap-server $BOOTSTRAP \
    --topic auris-notify-delivery-feishu \
    --partitions 2 \
    --replication-factor 1 \
    --config retention.ms=259200000 \
    --config cleanup.policy=delete

# auris-notify-dlq(死信广播,Worker 第 5 次失败时发;查询走 DB,topic 供外部订阅告警)
docker exec $KAFKA_CONTAINER kafka-topics --create --if-not-exists \
    --bootstrap-server $BOOTSTRAP \
    --topic auris-notify-dlq \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --config cleanup.policy=delete

echo "Topics created."
docker exec $KAFKA_CONTAINER kafka-topics --list --bootstrap-server $BOOTSTRAP
