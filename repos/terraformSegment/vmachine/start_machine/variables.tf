variable "vm_name" {
  description = "Name of the virtual machine"
  type        = string
}

variable "zone" {
  description = "GCP Zone"
  type        = string
}

variable "bucket_name" {
  description = "Name of the GCS bucket"
  type        = string
}

variable "files_uploaded_trigger" {
  description = "Dependency trigger to ensure files are uploaded first"
  type        = any
}