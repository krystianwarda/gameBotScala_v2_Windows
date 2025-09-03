output "bucket_name" {
  description = "Name of the created GCS bucket"
  value       = google_storage_bucket.game_bot_bucket.name
}

output "bucket_url" {
  description = "URL of the created GCS bucket"
  value       = google_storage_bucket.game_bot_bucket.url
}

output "bucket_info_file" {
  description = "Path to the bucket info JSON file"
  value       = "/opt/airflow/terraformSegment/vmachine/bucket-info.json"
}

output "upload_complete" {
  description = "Indicates all files have been uploaded"
  value       = "complete"
  depends_on = [
    google_storage_bucket_object.launcher_package,
    google_storage_bucket_object.appdata_package,
    google_storage_bucket_object.jar_file
  ]
}