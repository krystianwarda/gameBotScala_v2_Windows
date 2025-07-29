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

resource "google_storage_bucket" "flink_jar_bucket" {
  name          = var.flink_jar_bucket_name
  location      = var.region
  force_destroy = true
}

resource "google_storage_bucket_iam_member" "jar_reader" {
  bucket = google_storage_bucket.flink_jar_bucket.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
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
      num_instances = 3
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

