output "vm_external_ip" {
  value = google_compute_instance.vm_instance.network_interface[0].access_config[0].nat_ip
}

output "windows_username" {
  value = "gamebot"
}

output "windows_password" {
  value     = random_password.windows_password.result
  sensitive = true
}

output "bucket_name" {
  value = var.bucket_name
}