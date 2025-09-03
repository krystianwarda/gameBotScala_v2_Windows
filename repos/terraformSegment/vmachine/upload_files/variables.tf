variable "project_id" {
  description = "GCP Project ID"
  type        = string
}

variable "bucket_name" {
  description = "Name of the GCS bucket"
  type        = string
}

variable "region" {
  description = "GCP Region"
  type        = string
  default     = "us-central1"
}

variable "launcher_package_path" {
  description = "Path to launcher package zip file"
  type        = string
  default     = "/opt/airflow/terraformSegment/vmachine/files/launcher_package.zip"
}

variable "appdata_package_path" {
  description = "Path to appdata package zip file"
  type        = string
  default     = "/opt/airflow/terraformSegment/vmachine/files/appdata_package.zip"
}

variable "jar_file_path" {
  description = "Path to game bot JAR file"
  type        = string
  default     = "/opt/airflow/scalaSegment/game-bot-assembly-1.0.0.jar"
}
