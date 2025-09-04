terraform {
  required_version = ">= 1.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
    confluent = {
      source  = "confluentinc/confluent"
      version = "~> 2.0"
    }
  }
  backend "gcs" {
    bucket = "flink-iceberg-terraform-state-bucket"
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
  alias               = "kafka"
  kafka_api_key       = confluent_api_key.kafka_api_key.id
  kafka_api_secret    = confluent_api_key.kafka_api_key.secret
  kafka_rest_endpoint = confluent_kafka_cluster.basic.rest_endpoint
}


module "storage" {
  source = "./storage"

  project_id                     = var.project_id
  region                         = var.region
  flink_iceberg_data_bucket_name = var.flink_iceberg_data_bucket_name
  flink_jar_bucket_name          = var.flink_jar_bucket_name
}

resource "random_id" "env_suffix" {
  byte_length = 2
}


resource "confluent_environment" "dev" {
  provider     = confluent.cloud
  display_name = "Development-${random_id.env_suffix.hex}"
}


resource "confluent_kafka_cluster" "basic" {
  provider     = confluent.cloud
  display_name = "basic_kafka_cluster"
  availability = "SINGLE_ZONE"
  cloud        = "GCP"
  region       = "us-east1"

  basic {}

  environment {
    id = confluent_environment.dev.id
  }
}

output "rest_endpoint" {
  value = confluent_kafka_cluster.basic.rest_endpoint
}

resource "random_id" "sa_suffix" {
  byte_length = 2
}

resource "confluent_service_account" "gamebot_app_manager" {
  provider     = confluent.cloud
  display_name = "app-manager-${random_id.sa_suffix.hex}"
  description  = "Service account to manage Kafka cluster"
}


resource "confluent_role_binding" "app_manager_binding" {
  provider     = confluent.cloud
  principal    = "User:${confluent_service_account.gamebot_app_manager.id}"
  role_name    = "CloudClusterAdmin"
  crn_pattern  = confluent_kafka_cluster.basic.rbac_crn
}



resource "confluent_api_key" "kafka_api_key" {
  provider     = confluent.cloud
  display_name = "Kafka API Key"
  description  = "Key to access Kafka cluster"

  owner {
    id           = confluent_service_account.gamebot_app_manager.id
    api_version  = "iam/v2"
    kind         = "ServiceAccount"
  }

  managed_resource {
    id           = confluent_kafka_cluster.basic.id
    api_version  = "cmk/v2"
    kind         = "Cluster"
    environment {
      id = confluent_environment.dev.id
    }
  }
}


resource "local_file" "kafka_credentials" {
  content = jsonencode({
    api_key       = confluent_api_key.kafka_api_key.id
    api_secret    = confluent_api_key.kafka_api_key.secret
    rest_endpoint = confluent_kafka_cluster.basic.rest_endpoint
  })
  filename = "${path.module}/kafka-key.json"
}




module "kafka_topics" {
  source = "./kafka_topics"

  kafka_cluster_id = confluent_kafka_cluster.basic.id
  kafka_api_key    = confluent_api_key.kafka_api_key.id        # ✅ From resource
  kafka_api_secret = confluent_api_key.kafka_api_key.secret
  confluent_kafka_rest_endpoint = confluent_kafka_cluster.basic.rest_endpoint

  providers = {
    confluent = confluent.kafka  # ✅ Will match the module-local provider
  }

}





module "processing" {
  source = "./processing"

  project_id                        = var.project_id
  region                            = var.region
  flink_jar_bucket_name             = var.flink_jar_bucket_name
  confluent_cloud_api_key           = var.confluent_cloud_api_key
  confluent_cloud_api_secret        = var.confluent_cloud_api_secret

  confluent_kafka_rest_endpoint     = confluent_kafka_cluster.basic.rest_endpoint
  confluent_kafka_api_key           = confluent_api_key.kafka_api_key.id
  confluent_kafka_api_secret        = confluent_api_key.kafka_api_key.secret
}

