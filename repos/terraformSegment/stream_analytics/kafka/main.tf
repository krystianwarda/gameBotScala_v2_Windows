terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
    confluent = {
      source  = "confluentinc/confluent"
      version = ">= 2.0.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = "europe-west1"
}

provider "confluent" {
  cloud_api_key    = var.confluent_cloud_api_key
  cloud_api_secret = var.confluent_cloud_api_secret
}

resource "confluent_api_key" "kafka_api" {
  display_name = "Kafka API key"
  owner {
    id          = confluent_service_account.app_manager.id
    api_version = "iam/v2"
    kind        = "ServiceAccount"
  }
  managed_resource {
    id          = confluent_kafka_cluster.basic.id
    api_version = "cmk/v2"
    kind        = "Cluster"
    environment { id = confluent_environment.gamebot_env.id }
  }
}

resource "confluent_role_binding" "gamebot_app_manager_binding" {
  principal   = "User:${confluent_service_account.gamebot_app_manager.id}"
  role_name   = "CloudClusterAdmin"
  crn_pattern = confluent_kafka_cluster.basic.rbac_crn
}

provider "confluent" {
  alias             = "cloud"
  cloud_api_key     = var.confluent_cloud_api_key
  cloud_api_secret  = var.confluent_cloud_api_secret
}

resource "time_sleep" "wait_for_cluster" {
  create_duration = "120s"
  depends_on = [
    confluent_api_key.kafka_api,
    confluent_role_binding.app_manager_binding,
    confluent_kafka_cluster.basic
  ]
}

resource "confluent_kafka_topic" "game_bot_actions" {
  kafka_cluster { id = confluent_kafka_cluster.basic.id }
  topic_name       = "game-bot-actions"
  partitions_count = 4
  rest_endpoint    = confluent_kafka_cluster.basic.rest_endpoint

  credentials {
    key    = confluent_api_key.kafka_api.id
    secret = confluent_api_key.kafka_api.secret
  }

  depends_on = [time_sleep.wait_for_cluster]
}

resource "confluent_kafka_topic" "game_bot_events" {
  kafka_cluster { id = confluent_kafka_cluster.basic.id }
  topic_name       = "game-bot-events"
  partitions_count = 4
  rest_endpoint    = confluent_kafka_cluster.basic.rest_endpoint

  credentials {
    key    = confluent_api_key.kafka_api.id
    secret = confluent_api_key.kafka_api.secret
  }

  depends_on = [time_sleep.wait_for_cluster]
}


resource "time_sleep" "wait_for_rbac" {
  depends_on = [confluent_role_binding.app_manager_binding]
  create_duration = "60s"
}

resource "random_id" "sa_suffix" {
  byte_length = 2
}

resource "random_id" "env_suffix" {
  byte_length = 2
}

resource "confluent_service_account" "app_manager" {
  display_name = "app-manager"
  description  = "Service account for managing Kafka cluster"
}

resource "confluent_role_binding" "app_manager_binding" {
  principal   = "User:${confluent_service_account.app_manager.id}"
  role_name   = "CloudClusterAdmin"
  crn_pattern = confluent_kafka_cluster.basic.rbac_crn
}


resource "confluent_service_account" "gamebot_app_manager" {
  display_name = "app-manager-469621"
  description  = "Service account to manage Kafka cluster"
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
      id = confluent_environment.gamebot_env.id  # <-- use the same environment as the cluster
    }
  }

  depends_on = [
    confluent_kafka_cluster.basic,
    confluent_role_binding.gamebot_app_manager_binding
  ]
}

resource "local_file" "kafka_creds" {
  content = jsonencode({
    kafka_api_key      = confluent_api_key.kafka_api.id
    kafka_api_secret   = confluent_api_key.kafka_api.secret
    kafka_cluster_id   = confluent_kafka_cluster.basic.id
    rest_endpoint      = confluent_kafka_cluster.basic.rest_endpoint
    bootstrap_servers  = replace(confluent_kafka_cluster.basic.bootstrap_endpoint, "SASL_SSL://", "")
    topic              = confluent_kafka_topic.game_bot_events.topic_name
    actionTopic         = confluent_kafka_topic.game_bot_actions.topic_name
    group_id           = "flink-gcp-scala-group"
    iceberg_rest_uri   = confluent_kafka_cluster.basic.rest_endpoint
    iceberg_gcp_project = var.project_id
  })
  filename = "${path.module}/../secrets/kafka-creds.json"
}

resource "google_storage_bucket" "kafka_bucket" {
  name     = var.kafka_bucket_name
  location      = var.region
  force_destroy = true
}

resource "google_storage_bucket_object" "kafka_creds" {
  name   = "secrets/kafka-creds.json"
  bucket = var.kafka_bucket_name
  source = local_file.kafka_creds.filename

  depends_on = [google_storage_bucket.kafka_bucket]
}

resource "confluent_environment" "gamebot_env" {
  display_name = "gamebot-env"
}

resource "confluent_kafka_cluster" "basic" {
  display_name = "gamebot-cluster"
  availability = "SINGLE_ZONE"
  cloud        = "GCP"
  region       = "europe-west1"

  basic {}

  environment {
    id = confluent_environment.gamebot_env.id
  }
}

