output "bucket_name" {
  description = "Name of the created GCS bucket"
  value = google_storage_bucket.game_bot_bucket.name
}

output "bucket_url" {
  description = "URL of the created GCS bucket"
  value = google_storage_bucket.game_bot_bucket.url
}

output "launcher_package_uploaded" {
  description = "Name of the uploaded launcher package"
  value = google_storage_bucket_object.launcher_package.name
  depends_on = [google_storage_bucket_object.launcher_package]
}

output "appdata_package_uploaded" {
  description = "Name of the uploaded appdata package"
  value = google_storage_bucket_object.appdata_package.name
  depends_on = [google_storage_bucket_object.appdata_package]
}

output "upload_complete" {
  description = "Indicates all files have been uploaded"
  value = "complete"
  depends_on = [
    google_storage_bucket_object.launcher_package,
    google_storage_bucket_object.appdata_package
  ]
}