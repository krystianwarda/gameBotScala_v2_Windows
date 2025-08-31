variable "project_id" {
  description = "GCP Project ID"
  type        = string
}

variable "region" {
  description = "GCP Region"
  type        = string
  default     = "europe-west4"
}

variable "zone" {
  description = "GCP Zone"
  type        = string
  default     = "europe-west4-c"
}

variable "vm_name" {
  description = "Name of the virtual machine"
  type        = string
  default     = "game-bot-vm"
}

variable "bucket_name" {
  description = "Name of the GCS bucket"
  type        = string
  default     = "gamebotbucket-469621"
}