variable "project_id" {
  description = "GCP Project ID"
  type        = string
}

variable "region" {
  description = "GCP Region"
  type        = string
  default     = "us-central1"
}

variable "zone" {
  description = "GCP Zone"
  type        = string
  default     = "us-central1-c"
}

variable "vm_name" {
  description = "Name of the virtual machine"
  type        = string
  default     = "game-bot-vm"
}

variable "bucket_name" {
  description = "Name of the GCS bucket containing game files"
  type        = string
  default     = "gamebot-files-bucket"
}