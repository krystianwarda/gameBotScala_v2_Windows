terraform {
  required_version = ">= 1.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
    confluent = {
      source  = "confluentinc/confluent"
      version = "~> 1.56"
    }
  }

  backend "gcs" {
    bucket = "gamebot-460320-terraform-state"
    prefix = "terraform/state"
  }
}

provider "google" {
  project     = var.project_id
  region      = var.region
  credentials = file("gcp-key.json")
}

provider "confluent" {
  alias            = "cloud"
  cloud_api_key    = var.confluent_cloud_api_key
  cloud_api_secret = var.confluent_cloud_api_secret
}

provider "confluent" {
  alias                = "kafka"
  kafka_api_key        = var.confluent_kafka_api_key
  kafka_api_secret     = var.confluent_kafka_api_secret
  kafka_rest_endpoint  = var.confluent_kafka_rest_endpoint
}

resource "confluent_environment" "dev" {
  provider     = confluent.cloud
  display_name = "Development"
}

resource "confluent_kafka_cluster" "basic" {
  provider     = confluent.cloud
  display_name = "basic_kafka_cluster"
  availability = "SINGLE_ZONE"
  cloud        = "GCP"
  region       = "europe-west1"

  basic {}

  environment {
    id = confluent_environment.dev.id
  }
}

resource "confluent_service_account" "app_manager" {
  provider     = confluent.cloud
  display_name = "app-manager"
  description  = "Service account to manage Kafka cluster"
}

resource "confluent_role_binding" "app_manager_binding" {
  provider     = confluent.cloud
  principal    = "User:${confluent_service_account.app_manager.id}"
  role_name    = "CloudClusterAdmin"
  crn_pattern  = confluent_kafka_cluster.basic.rbac_crn
}

resource "confluent_kafka_topic" "game-bot-events" {
  provider = confluent.kafka
  kafka_cluster {
    id = confluent_kafka_cluster.basic.id
  }
  topic_name       = "game-bot-events"
  partitions_count = 3
  config = {
    "cleanup.policy" = "delete"
  }
}

resource "google_storage_bucket" "flink_jar_bucket" {
  name          = var.flink_jar_bucket_name
  location      = var.region
  force_destroy = true
}

resource "google_storage_bucket" "iceberg_data_bucket" {
  name          = var.iceberg_data_bucket_name
  location      = var.region
  force_destroy = true
}

resource "google_dataproc_cluster" "flink_cluster" {
  name   = "flink-iceberg-cluster"
  region = var.region
  project = var.project_id

  cluster_config {


    master_config {
      num_instances = 1
      machine_type  = "n1-standard-2"
    }

    worker_config {
      num_instances = 2
      machine_type  = "n1-standard-2"
    }

    software_config {
      image_version       = "2.1-debian11"
      optional_components = ["FLINK"]
    }
  }
}
