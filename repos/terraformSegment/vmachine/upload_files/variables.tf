variable "bucket_name" {
  description = "Name of the GCS bucket"
  type        = string
}

variable "region" {
  description = "GCP Region"
  type        = string
}

variable "launcher_package_path" {
  description = "Path to launcher package zip file"
  type        = string
}

variable "appdata_package_path" {
  description = "Path to appdata package zip file"
  type        = string
}