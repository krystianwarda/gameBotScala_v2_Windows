# Define Terraform settings, including required versions and providers.
terraform {
  # Specifies the minimum Terraform version required to apply this configuration.
  required_version = ">= 1.0"

  # Declares the providers required for this infrastructure.
  required_providers {
    # Google Cloud Platform provider.
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0" # Use a version compatible with 5.0.
    }
    # Confluent Cloud provider.
    confluent = {
      source  = "confluentinc/confluent"
      version = "~> 1.56" # Use a version compatible with 1.56.
    }
  }

  # Configures the backend to store Terraform state in a Google Cloud Storage (GCS) bucket.
  # This is crucial for collaboration and state management.
  backend "gcs" {
    bucket = "flink-iceberg-terraform-state-bucket"
    prefix = "terraform/state"
  }
}

# Configure the Google Cloud provider with project details and credentials.
provider "google" {
  project     = var.project_id
  region      = var.region
  credentials = file("gcp-key.json") # Path to the GCP service account key file.
}

# Configure the Confluent provider for managing Confluent Cloud resources.
# This provider instance uses an alias "cloud" and is authenticated via API keys.
provider "confluent" {
  alias            = "cloud"
  cloud_api_key    = var.confluent_cloud_api_key
  cloud_api_secret = var.confluent_cloud_api_secret
}

# Configure another instance of the Confluent provider for Kafka-specific operations.
# This provider instance uses an alias "kafka" and is authenticated with Kafka-specific API keys and REST endpoint.
provider "confluent" {
  alias                   = "kafka"
  kafka_api_key           = var.confluent_kafka_api_key
  kafka_api_secret        = var.confluent_kafka_api_secret
  kafka_rest_endpoint     = var.confluent_kafka_rest_endpoint
}

# Create a new environment in Confluent Cloud.
resource "confluent_environment" "dev" {
  provider     = confluent.cloud
  display_name = "Development"
}

# Create a basic Kafka cluster in the "dev" environment on GCP.
resource "confluent_kafka_cluster" "basic" {
  provider     = confluent.cloud
  display_name = "basic_kafka_cluster"
  availability = "SINGLE_ZONE" # Cost-effective option for development.
  cloud        = "GCP"
  region       = "us-east1"

  basic {} # Defines the cluster type as "Basic".

  environment {
    id = confluent_environment.dev.id
  }
}

# Create a service account for application management within Confluent Cloud.
resource "confluent_service_account" "app_manager" {
  provider     = confluent.cloud
  display_name = "app-manager"
  description  = "Service account to manage Kafka cluster"
}

# Bind the "CloudClusterAdmin" role to the service account.
# This grants the service account administrative permissions on the Kafka cluster.
resource "confluent_role_binding" "app_manager_binding" {
  provider     = confluent.cloud
  principal    = "User:${confluent_service_account.app_manager.id}"
  role_name    = "CloudClusterAdmin"
  crn_pattern  = confluent_kafka_cluster.basic.rbac_crn
}

# Create a Kafka topic named "game-bot-events".
resource "confluent_kafka_topic" "game-bot-events" {
  provider = confluent.kafka # Use the "kafka" aliased provider.
  kafka_cluster {
    id = confluent_kafka_cluster.basic.id
  }
  topic_name       = "game-bot-events"
  partitions_count = 2
  config = {
    "cleanup.policy" = "delete" # Messages will be deleted after the retention period.
  }
}

resource "google_storage_bucket_iam_member" "terraform_sa_admin" {
  bucket = google_storage_bucket.flink_iceberg_data_bucket.name
  role   = "roles/storage.admin"
  member = "serviceAccount:terraform-sa@gamebot-460320.iam.gserviceaccount.com"
}


# Create a GCS bucket to store Flink application JAR files.
resource "google_storage_bucket" "flink_jar_bucket" {
  name          = var.flink_jar_bucket_name
  location      = var.region
  force_destroy = true # Allows deletion of the bucket even if it contains objects.
}

# Create a GCS bucket to store data for the Flink Iceberg table.
resource "google_storage_bucket" "flink_iceberg_data_bucket" {
  name          = var.flink_iceberg_data_bucket_name
  location      = var.region
  force_destroy = true
}

# Create a Dataproc cluster to run Flink jobs.
resource "google_dataproc_cluster" "flink_cluster" {
  name    = "flink-iceberg-cluster"
  region  = var.region
  project = var.project_id

  cluster_config {
    master_config {
      num_instances = 3
      machine_type  = "n1-standard-2"
      disk_config {
        boot_disk_size_gb = 30
      }
    }

    worker_config {
      num_instances = 3
      machine_type  = "n1-standard-2"
      disk_config {
        boot_disk_size_gb = 30
      }
    }

    software_config {
      image_version       = "2.3-debian12" # Specifies the Dataproc image.
      optional_components = ["FLINK"]      # Installs Flink on the cluster.
    }
  }
}

# Data source to get details about the current GCP project.
data "google_project" "current" {
  project_id = var.project_id
}

# Data source to get details about the default subnetwork.
data "google_compute_subnetwork" "default" {
  name    = "default"
  project = var.project_id
  region  = var.region
}

# Create a Cloud Router for the Dataproc network.
resource "google_compute_router" "dataproc_router" {
  name    = "dataproc-router"
  project = var.project_id
  region  = var.region
  network = data.google_compute_subnetwork.default.network
}

# Create a Cloud NAT gateway to allow outbound internet access for the Dataproc cluster
# without assigning public IP addresses to the VMs.
resource "google_compute_router_nat" "dataproc_nat" {
  name    = "dataproc-nat-gateway"
  project = google_compute_router.dataproc_router.project
  region  = google_compute_router.dataproc_router.region
  router  = google_compute_router.dataproc_router.name

  source_subnetwork_ip_ranges_to_nat = "LIST_OF_SUBNETWORKS"
  subnetwork {
    name                    = data.google_compute_subnetwork.default.self_link
    source_ip_ranges_to_nat = ["ALL_IP_RANGES"]
  }

  nat_ip_allocate_option = "AUTO_ONLY" # Automatically allocate NAT IP addresses.

  log_config {
    enable = true
    filter = "ERRORS_ONLY" # Log only NAT errors.
  }

  depends_on = [google_compute_router.dataproc_router]
}

# Grant the Dataproc cluster's service account permission to create objects in the Iceberg data bucket.
resource "google_storage_bucket_iam_member" "iceberg_writer" {
  bucket = google_storage_bucket.flink_iceberg_data_bucket.name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

# Grant the Dataproc service account permission to read JAR files from the JAR bucket.
resource "google_storage_bucket_iam_member" "jar_reader" {
  bucket = google_storage_bucket.flink_jar_bucket.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

# Grant the Dataproc service account full administrative access to the Iceberg data bucket.
resource "google_storage_bucket_iam_member" "iceberg_rw" {
  bucket = google_storage_bucket.flink_iceberg_data_bucket.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

# Grant the Dataproc service account permission to write logs.
resource "google_project_iam_member" "dataproc_logging" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

# Create a BigQuery dataset for staging raw data.
resource "google_bigquery_dataset" "raw_dataset" {
  dataset_id = "gamebot_raw_staging"
  location   = var.region
}

# Create a BigQuery table to store raw game snapshot data as JSON strings.
resource "google_bigquery_table" "raw_game_snapshots" {
  dataset_id          = google_bigquery_dataset.raw_dataset.dataset_id
  table_id            = "raw_game_snapshots"
  deletion_protection = false # Allows the table to be deleted.

  # Defines the table schema with a single column for the raw JSON data.
  schema = jsonencode([
    {
      name = "raw_json"
      type = "STRING"
      mode = "NULLABLE"
    }
  ])
}

# Create a BigQuery dataset for transformed views
resource "google_bigquery_dataset" "transformed_dataset" {
  dataset_id = "gamebot_transformed"
  location   = var.region
}

resource "google_storage_bucket" "flink_bigquery_temp" {
  name          = "flink-iceberg-temp"
  location      = var.region
  force_destroy = true
}

resource "google_storage_bucket_iam_member" "flink_bigquery_temp_access" {
  bucket = google_storage_bucket.flink_bigquery_temp.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}
