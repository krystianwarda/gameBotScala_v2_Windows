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
  kafka_rest_endpoint = var.confluent_kafka_rest_endpoint
  kafka_api_key       = var.confluent_kafka_api_key
  kafka_api_secret    = var.confluent_kafka_api_secret
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
  region       = "us-east1"

  basic {}

  environment {
    id = confluent_environment.dev.id
  }
}


resource "confluent_api_key" "app_manager_kafka" {
  provider = confluent.cloud
  display_name = "app-manager-kafka"
  description  = "Kafka API key for the app-manager service account"

  # WHO owns this key
  owner {
    id          = confluent_service_account.app_manager.id
    api_version = confluent_service_account.app_manager.api_version
    kind        = confluent_service_account.app_manager.kind
  }

  # WHAT resource this key is scoped to (your Kafka cluster)
  managed_resource {
    id          = confluent_kafka_cluster.basic.id
    api_version = confluent_kafka_cluster.basic.api_version
    kind        = confluent_kafka_cluster.basic.kind

    # You *must* point at the Environment, too
    environment {
      id = confluent_environment.dev.id
    }
  }

  depends_on = [
    confluent_role_binding.app_manager_binding
  ]
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
  partitions_count = 1

  credentials {
    key    = var.confluent_kafka_api_key
    secret = var.confluent_kafka_api_secret
  }
}



resource "google_storage_bucket" "flink_jar_bucket" {
  name          = var.flink_jar_bucket_name
  location      = var.region
  force_destroy = true
}

resource "google_storage_bucket" "flink_iceberg_data_bucket" {
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
      disk_config {
        boot_disk_size_gb = 100
      }
    }

    worker_config {
      num_instances = 2
      machine_type  = "n1-standard-2"
      disk_config {
        boot_disk_size_gb = 100
      }
    }

    software_config {
      image_version       = "2.1-debian11"
      optional_components = ["FLINK"]
    }
  }
}

data "google_project" "current" {
  project_id = var.project_id
}

# Grant the Dataproc VMs (Compute Engine default SA) permission to write objects
resource "google_storage_bucket_iam_member" "iceberg_writer" {
  bucket = google_storage_bucket.flink_iceberg_data_bucket.name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

# Access to JAR bucket
resource "google_storage_bucket_iam_member" "jar_reader" {
  bucket = google_storage_bucket.flink_jar_bucket.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

# Full access to Iceberg bucket
resource "google_storage_bucket_iam_member" "iceberg_rw" {
  bucket = google_storage_bucket.flink_iceberg_data_bucket.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

resource "google_project_iam_member" "dataproc_logging" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

resource "google_bigquery_dataset" "raw_dataset" {
  dataset_id = "gamebot_raw_staging"
  location   = var.region  # likely "europe-west1"
}

resource "google_bigquery_table" "raw_game_snapshots" {
  dataset_id          = google_bigquery_dataset.raw_dataset.dataset_id
  table_id            = "raw_game_snapshots"
  deletion_protection = false

  schema = jsonencode([
    {
      name = "raw_json"
      type = "STRING"
      mode = "NULLABLE"
    }
  ])
}
