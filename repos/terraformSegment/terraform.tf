terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# ────────────────────────────────────────────────────────────────────────────────
# 0) Enable the APIs we need (IAM, Storage, KMS)
# ────────────────────────────────────────────────────────────────────────────────
resource "google_project_service" "iam" {
  project = var.project_id
  service = "iam.googleapis.com"
}
resource "google_project_service" "storage" {
  project = var.project_id
  service = "storage.googleapis.com"
}
resource "google_project_service" "kms" {
  project = var.project_id
  service = "cloudkms.googleapis.com"
}

# ────────────────────────────────────────────────────────────────────────────────
# 1) Create a KeyRing + CryptoKey for CMEK (wait for KMS API)
# ────────────────────────────────────────────────────────────────────────────────
resource "google_kms_key_ring" "iceberg_ring" {
  depends_on = [google_project_service.kms]
  name       = var.kms_key_ring_id   # e.g. "my-ring"
  location   = var.region
}

resource "google_kms_crypto_key" "iceberg_key" {
  depends_on     = [google_kms_key_ring.iceberg_ring]
  name           = var.kms_crypto_key_id   # e.g. "my-key"
  key_ring       = google_kms_key_ring.iceberg_ring.id
  rotation_period = "7776000s"               # 90 days, optional
}

# ────────────────────────────────────────────────────────────────────────────────
# 2) Lookup your project’s numeric ID (for the GCS system account)
# ────────────────────────────────────────────────────────────────────────────────
data "google_project" "project" {
  project_id = var.project_id
}

# ────────────────────────────────────────────────────────────────────────────────
# 3) Grant GCS service account permission to use that CMEK key
# ────────────────────────────────────────────────────────────────────────────────
resource "google_kms_crypto_key_iam_member" "gcs_cmek_use" {
  depends_on    = [google_project_service.kms]
  crypto_key_id = google_kms_crypto_key.iceberg_key.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:service-998018098924@gs-project-accounts.iam.gserviceaccount.com"
}

# ────────────────────────────────────────────────────────────────────────────────
# 4) Create the Iceberg bucket (wait for Storage API + CMEK binding)
# ────────────────────────────────────────────────────────────────────────────────
resource "google_storage_bucket" "iceberg_bucket" {
  depends_on = [
    google_project_service.storage,
    google_kms_crypto_key_iam_member.gcs_cmek_use,
  ]

  name                        = var.bucket_name
  location                    = var.region
  force_destroy               = false
  uniform_bucket_level_access = true

  versioning {
    enabled = true
  }

  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      age = 365
    }
  }

  logging {
    log_bucket        = var.audit_log_bucket
    log_object_prefix = "access-logs/"
  }

  encryption {
    default_kms_key_name = google_kms_crypto_key.iceberg_key.id
  }
}

# ────────────────────────────────────────────────────────────────────────────────
# 5) Create the Flink service account (after IAM API is enabled)
# ────────────────────────────────────────────────────────────────────────────────
resource "google_service_account" "flink_sa" {
  depends_on   = [google_project_service.iam]
  account_id   = "flink-job-runner"
  display_name = "Flink Job Runner"
}

# ────────────────────────────────────────────────────────────────────────────────
# 6) Grant that SA read & write on the bucket
# ────────────────────────────────────────────────────────────────────────────────
resource "google_storage_bucket_iam_member" "flink_writer" {
  bucket = google_storage_bucket.iceberg_bucket.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.flink_sa.email}"
}

resource "google_storage_bucket_iam_member" "flink_reader" {
  bucket = google_storage_bucket.iceberg_bucket.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.flink_sa.email}"
}

# ────────────────────────────────────────────────────────────────────────────────
# 7) Outputs
# ────────────────────────────────────────────────────────────────────────────────
output "flink_service_account_email" {
  value = google_service_account.flink_sa.email
}

output "bucket_self_link" {
  value = google_storage_bucket.iceberg_bucket.self_link
}
