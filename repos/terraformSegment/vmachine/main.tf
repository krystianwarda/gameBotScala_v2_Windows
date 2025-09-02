
provider "google" {
  project     = var.project_id
  region      = var.region
  credentials = file("gcp-key.json")
}

# First upload files to bucket
module "upload_files" {
  source = "./upload_files"

  bucket_name           = var.bucket_name
  region               = var.region
  launcher_package_path = "/opt/airflow/terraformSegment/vmachine/files/launcher_package.zip"
  appdata_package_path  = "/opt/airflow/terraformSegment/vmachine/files/appdata_package.zip"
}

# Then start VM machine (after files are uploaded)
module "start_machine" {
  source = "./start_machine"

  vm_name               = var.vm_name
  zone                  = var.zone
  bucket_name           = module.upload_files.bucket_name
  files_uploaded_trigger = [
    module.upload_files.launcher_package_uploaded,
    module.upload_files.appdata_package_uploaded
  ]

  depends_on = [module.upload_files]
}