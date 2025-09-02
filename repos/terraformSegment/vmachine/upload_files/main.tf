resource "google_storage_bucket" "game_bot_bucket" {
  name          = var.bucket_name
  location      = var.region
  force_destroy = true

  # Remove uniform_bucket_level_access to allow object-level ACLs
  uniform_bucket_level_access = false

  lifecycle_rule {
    condition {
      age = 30
    }
    action {
      type = "Delete"
    }
  }
}

# Make the bucket publicly readable
resource "google_storage_bucket_iam_member" "public_read" {
  bucket = google_storage_bucket.game_bot_bucket.name
  role   = "roles/storage.objectViewer"
  member = "allUsers"
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

  depends_on = [google_storage_bucket.game_bot_bucket]
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

  depends_on = [google_storage_bucket.game_bot_bucket]
}