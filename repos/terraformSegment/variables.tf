variable "bucket_name" {
  type        = string
  description = "Name of the GCS bucket to create"
}

variable "audit_log_bucket" {
  type        = string
  description = "Existing bucket for access logs"
}

variable "kms_key_ring_id" {
  type        = string
  description = "ID for the KMS key ring (e.g. my-ring)"
}

variable "kms_crypto_key_id" {
  type        = string
  description = "ID for the KMS crypto key (e.g. my-key)"
}
