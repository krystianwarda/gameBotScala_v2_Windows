# Add these outputs to vmachine/main.tf
output "vm_external_ip" {
  description = "External IP address of the VM"
  value       = module.start_machine.vm_external_ip
}

output "windows_username" {
  description = "Windows username for the VM"
  value       = module.start_machine.windows_username
}

output "windows_password" {
  description = "Windows password for the VM"
  value       = module.start_machine.windows_password
  sensitive   = true
}

output "bucket_name" {
  description = "Name of the GCS bucket"
  value       = module.start_machine.bucket_name
}