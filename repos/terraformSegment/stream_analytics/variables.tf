variable "project_id" {
  description = "Your Google Cloud project ID"
  type        = string
}

variable "region" {
  description = "The GCP region for resources"
  type        = string
  default     = "europe-west1"
}

variable "flink_jar_bucket_name" {
  description = "Unique name for the Flink JAR GCS bucket"
  type        = string
}

variable "flink_iceberg_data_bucket_name" {
  description = "Unique name for the Iceberg data GCS bucket"
  type        = string
}

variable "confluent_cloud_api_key" {
  description = "Confluent Cloud API Key"
  type        = string
  sensitive   = true
}

variable "confluent_cloud_api_secret" {
  description = "Confluent Cloud API Secret"
  type        = string
  sensitive   = true
}
