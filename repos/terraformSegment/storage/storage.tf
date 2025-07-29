terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
}

data "google_project" "current" {
  project_id = var.project_id
}

data "google_compute_subnetwork" "default" {
  name    = "default"
  project = var.project_id
  region  = var.region
}

resource "google_storage_bucket" "flink_iceberg_data_bucket" {
  name          = var.flink_iceberg_data_bucket_name
  location      = var.region
  force_destroy = true
}

resource "google_storage_bucket_iam_member" "terraform_sa_admin" {
  bucket = google_storage_bucket.flink_iceberg_data_bucket.name
  role   = "roles/storage.admin"
  member = "serviceAccount:terraform-sa@gamebot-460320.iam.gserviceaccount.com"
}

resource "google_compute_router" "dataproc_router" {
  name    = "dataproc-router"
  project = var.project_id
  region  = var.region
  network = data.google_compute_subnetwork.default.network
}

resource "google_compute_router_nat" "dataproc_nat" {
  name    = "dataproc-nat-gateway"
  project = google_compute_router.dataproc_router.project
  region  = google_compute_router.dataproc_router.region
  router  = google_compute_router.dataproc_router.name

  source_subnetwork_ip_ranges_to_nat = "LIST_OF_SUBNETWORKS"
  subnetwork {
    name                    = data.google_compute_subnetwork.default.self_link
    source_ip_ranges_to_nat = ["ALL_IP_RANGES"]
  }

  nat_ip_allocate_option = "AUTO_ONLY"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }

  depends_on = [google_compute_router.dataproc_router]
}

resource "google_storage_bucket_iam_member" "iceberg_writer" {
  bucket = google_storage_bucket.flink_iceberg_data_bucket.name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

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
  location   = var.region
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

resource "google_bigquery_dataset" "transformed_dataset" {
  dataset_id = "gamebot_transformed"
  location   = var.region
}

resource "google_storage_bucket" "flink_bigquery_temp" {
  name          = "flink-iceberg-temp"
  location      = var.region
  force_destroy = true
}

resource "google_storage_bucket_iam_member" "flink_bigquery_temp_access" {
  bucket = google_storage_bucket.flink_bigquery_temp.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}
