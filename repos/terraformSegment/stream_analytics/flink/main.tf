terraform {
  required_providers {
    confluent = {
      source  = "confluentinc/confluent"
      version = "~> 2.0"
    }
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
}

data "google_project" "current" {
  project_id = var.project_id
}

# Load Kafka creds from JSON file
locals {
  kafka_creds = jsondecode(file(var.confluent_creds_file))
}

data "google_storage_bucket" "flink_jar_bucket" {
  name = var.flink_jar_bucket_name
}

resource "google_storage_bucket_iam_member" "jar_reader" {
  bucket = data.google_storage_bucket.flink_jar_bucket.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:mainuser@gamebot-469621.iam.gserviceaccount.com"
}

resource "google_storage_bucket_iam_member" "flink_storage_admin" {
  bucket = data.google_storage_bucket.flink_jar_bucket.name
  role   = "roles/storage.admin"
  member = "serviceAccount:mainuser@gamebot-469621.iam.gserviceaccount.com"
}

resource "google_dataproc_cluster" "flink_cluster" {
  name    = "flink-iceberg-cluster"
  region  = var.region
  project = var.project_id

  cluster_config {
    master_config {
      num_instances = 3
      machine_type  = "n1-standard-2"
      disk_config {
        boot_disk_size_gb = 30
      }
    }

    worker_config {
      num_instances = 4
      machine_type  = "n1-standard-2"
      disk_config {
        boot_disk_size_gb = 30
      }
    }

    software_config {
      image_version       = "2.3-debian12"
      optional_components = ["FLINK"]
    }
  }
}
