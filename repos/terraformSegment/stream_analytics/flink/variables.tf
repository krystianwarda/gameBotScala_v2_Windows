variable "project_id" {
  type = string
}

variable "region" {
  type = string
}

variable "flink_jar_bucket_name" {
  type = string
}

variable "confluent_cloud_api_key" {
  type      = string
  sensitive = true
}

variable "confluent_cloud_api_secret" {
  type      = string
  sensitive = true
}

variable "confluent_creds_file" {
  type        = string
  description = "Path to the Kafka Confluent Cloud credentials JSON file"
}