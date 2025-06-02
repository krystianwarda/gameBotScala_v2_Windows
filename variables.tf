variable "confluent_cloud_api_key" {
  description = "API Key for Confluent Cloud"
  type        = string
  sensitive   = true
}

variable "confluent_cloud_api_secret" {
  description = "API Secret for Confluent Cloud"
  type        = string
  sensitive   = true
}

variable "confluent_kafka_api_key" {
  description = "Kafka API Key for Kafka-specific operations (e.g., topics)"
  type        = string
  sensitive   = true
}

variable "confluent_kafka_api_secret" {
  description = "Kafka API Secret for Kafka-specific operations"
  type        = string
  sensitive   = true
}

variable "confluent_kafka_rest_endpoint" {
  description = "Kafka REST endpoint for Confluent Cloud"
  type        = string
}

variable "confluent_cloud_bootstrap_server" {
  description = "Bootstrap server for Confluent Cloud"
  type        = string
  default     = "pkc-z1o60.europe-west1.gcp.confluent.cloud:9092"
}

variable "gcp_project" {
  description = "GCP Project for BigQuery sink"
  type        = string
}

variable "gcp_dataset" {
  description = "BigQuery dataset name"
  type        = string
}

variable "project_id" {
  description = "GCP Project ID"
  type        = string
}

variable "region" {
  description = "GCP Region"
  type        = string
  default     = "europe-west1"
}

variable "flink_jar_bucket_name" {
  description = "Bucket for storing Flink JAR files and init scripts"
  type        = string
  default     = "gamebot-460320-flink-jars"
}

variable "iceberg_data_bucket_name" {
  description = "Bucket for storing Iceberg data"
  type        = string
  default     = "gamebot-460320-iceberg"
}
