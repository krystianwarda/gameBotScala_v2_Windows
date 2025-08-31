terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.1"
    }
  }
}


provider "google" {
  credentials = file("/opt/airflow/gcp-key.json")
  project     = var.project_id
  region      = var.region
  zone        = var.zone
}