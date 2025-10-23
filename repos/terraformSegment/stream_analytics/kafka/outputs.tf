output "kafka_api_key" {
  value     = confluent_api_key.kafka_api.id
  sensitive = true
}

output "kafka_api_secret" {
  value     = confluent_api_key.kafka_api.secret
  sensitive = true
}

output "kafka_rest_endpoint" {
  value = confluent_kafka_cluster.basic.rest_endpoint
}

output "iceberg_rest_uri" {
  value = confluent_kafka_cluster.basic.rest_endpoint
}

output "iceberg_gcp_project" {
  value = var.project_id
}

output "action_topic_name" {
  value = "game-bot-actions"
}