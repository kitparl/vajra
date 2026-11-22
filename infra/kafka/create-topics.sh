#!/bin/sh
# Creates the event topics (auto-create is off). Idempotent; exits non-zero on any failure.
# 1 partition / RF 1 (single broker); 7-day retention keeps VPS disk bounded.
set -eu

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"

for topic in signal.generated order.updated fill.created position.updated; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions 1 --replication-factor 1 --config retention.ms=604800000
done
