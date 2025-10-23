from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import json
import subprocess

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 1,
    'retry_delay': timedelta(minutes=2)
}

dag = DAG(
    'vmachine_bucket_upload_files',
    default_args=default_args,
    description='Upload files to GCS bucket using Terraform (upload_files module only)',
    schedule_interval=None,
    catchup=False,
    tags=['vmachine', 'bucket', 'terraform', 'upload_files']
)

terraform_dir = "/opt/airflow/terraformSegment/vmachine/upload_files"

terraform_init = BashOperator(
    task_id='terraform_init',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json
        cd {terraform_dir}
        terraform init -upgrade
    """,
    dag=dag
)

terraform_upload_files = BashOperator(
    task_id='terraform_upload_files',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json
        cd {terraform_dir}
        terraform apply -target=google_storage_bucket_iam_member.terraform_sa_object_viewer -auto-approve
        terraform apply -auto-approve
    """,
    dag=dag
)

# Step 3: Get and save upload results
def save_upload_results():
    terraform_dir = "/opt/airflow/terraformSegment/vmachine/upload_files"
    try:
        result = subprocess.run(
            ['terraform', 'output', '-json'],
            cwd=terraform_dir,
            capture_output=True,
            text=True,
            check=True
        )

        outputs = json.loads(result.stdout)

        upload_info = {
            "bucket_name": outputs.get("bucket_name", {}).get("value", "Unknown"),
            "bucket_url": outputs.get("bucket_url", {}).get("value", "Unknown"),
            "launcher_package": outputs.get("launcher_package_uploaded", {}).get("value", "Unknown"),
            "appdata_package": outputs.get("appdata_package_uploaded", {}).get("value", "Unknown"),
            "jar_file": outputs.get("jar_file_uploaded", {}).get("value", "Unknown"),
            "upload_status": outputs.get("upload_complete", {}).get("value", "Unknown"),
            "uploaded_at": datetime.now().isoformat()
        }

        # Save upload info to local file
        upload_info_path = f"{terraform_dir}/upload-results.json"
        with open(upload_info_path, 'w') as f:
            json.dump(upload_info, f, indent=2)

        print("✅ File upload completed successfully")
        print(f"🪣 Bucket: {upload_info['bucket_name']}")
        print(f"📦 Launcher package: {upload_info['launcher_package']}")
        print(f"📦 Appdata package: {upload_info['appdata_package']}")
        print(f"📦 JAR file: {upload_info['jar_file']}")
        print(f"✅ Upload status: {upload_info['upload_status']}")

    except Exception as e:
        print(f"❌ Error saving upload results: {e}")
        raise

save_results = PythonOperator(
    task_id='save_upload_results',
    python_callable=save_upload_results,
    dag=dag
)

# Define task dependencies
terraform_init >> terraform_upload_files >> save_results