terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.0"
    }
  }
}

provider "google" {
  credentials = file("/opt/airflow/terraformSegment/vmachine/gcp-key.json")
  project     = var.project_id
  region      = var.region
}

# Get the service account email from the key file
data "google_client_config" "default" {}

data "google_service_account" "terraform_sa" {
  account_id = "mainuser"
}

resource "google_storage_bucket" "game_bot_bucket" {
  name          = var.bucket_name
  location      = var.region
  force_destroy = true

  uniform_bucket_level_access = true

  lifecycle_rule {
    condition {
      age = 30
    }
    action {
      type = "Delete"
    }
  }
}

resource "google_storage_bucket_iam_member" "terraform_sa_object_viewer" {
  bucket = google_storage_bucket.game_bot_bucket.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${data.google_service_account.terraform_sa.email}"
}

# Grant necessary permissions to the service account
resource "google_storage_bucket_iam_member" "terraform_sa_object_admin" {
  bucket = google_storage_bucket.game_bot_bucket.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${data.google_service_account.terraform_sa.email}"
}

resource "google_storage_bucket_iam_member" "terraform_sa_bucket_reader" {
  bucket = google_storage_bucket.game_bot_bucket.name
  role   = "roles/storage.legacyBucketReader"
  member = "serviceAccount:${data.google_service_account.terraform_sa.email}"
}

resource "google_storage_bucket_object" "launcher_package" {
  name   = "launcher_package.zip"
  bucket = google_storage_bucket.game_bot_bucket.name
  source = var.launcher_package_path

  lifecycle {
    precondition {
      condition     = fileexists(var.launcher_package_path)
      error_message = "Launcher package file does not exist at path: ${var.launcher_package_path}"
    }
  }

  depends_on = [
    google_storage_bucket.game_bot_bucket,
    google_storage_bucket_iam_member.terraform_sa_object_admin,
    google_storage_bucket_iam_member.terraform_sa_object_viewer
  ]
}

resource "google_storage_bucket_object" "appdata_package" {
  name   = "appdata_package.zip"
  bucket = google_storage_bucket.game_bot_bucket.name
  source = var.appdata_package_path

  lifecycle {
    precondition {
      condition     = fileexists(var.appdata_package_path)
      error_message = "Appdata package file does not exist at path: ${var.appdata_package_path}"
    }
  }

  depends_on = [
    google_storage_bucket.game_bot_bucket,
    google_storage_bucket_iam_member.terraform_sa_object_admin,
    google_storage_bucket_iam_member.terraform_sa_object_viewer
  ]
}

resource "google_storage_bucket_object" "jar_file" {
  name   = "game-bot-assembly-1.0.0.jar"
  bucket = google_storage_bucket.game_bot_bucket.name
  source = var.jar_file_path

  lifecycle {
    precondition {
      condition     = fileexists(var.jar_file_path)
      error_message = "JAR file does not exist at path: ${var.jar_file_path}"
    }
  }

  depends_on = [
    google_storage_bucket.game_bot_bucket,
    google_storage_bucket_iam_member.terraform_sa_object_admin,
    google_storage_bucket_iam_member.terraform_sa_object_viewer
  ]
}

resource "local_file" "bucket_info" {
  content = jsonencode({
    bucket_name = google_storage_bucket.game_bot_bucket.name
    bucket_url  = google_storage_bucket.game_bot_bucket.url
    region      = var.region
    project_id  = var.project_id
    files = {
      launcher_package = "launcher_package.zip"
      appdata_package  = "appdata_package.zip"
      jar_file         = "game-bot-assembly-1.0.0.jar"
    }
  })
  filename = "/opt/airflow/terraformSegment/vmachine/bucket-info.json"

  depends_on = [
    google_storage_bucket_object.launcher_package,
    google_storage_bucket_object.appdata_package,
    google_storage_bucket_object.jar_file
  ]
}