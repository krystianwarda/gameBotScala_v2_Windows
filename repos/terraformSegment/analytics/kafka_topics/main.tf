terraform {
  required_providers {
    confluent = {
      source  = "confluentinc/confluent"
      version = ">= 2.0.0"
    }
  }
}

resource "confluent_kafka_topic" "game_bot_events" {
  kafka_cluster {
    id = var.kafka_cluster_id
  }

  topic_name       = "game-bot-events"
  partitions_count = 1

  credentials {
    key    = var.kafka_api_key
    secret = var.kafka_api_secret
  }
}
