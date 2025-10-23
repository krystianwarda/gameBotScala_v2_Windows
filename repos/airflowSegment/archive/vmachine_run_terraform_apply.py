from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import os
import time
import json
import subprocess

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 0,
    'retry_delay': timedelta(minutes=5)
}

dag = DAG(
    'vmachine_run_terraform_apply',
    default_args=default_args,
    description='Run Terraform apply for VM machine infrastructure',
    schedule_interval=None,
    catchup=False
)

terraform_dir = "/opt/airflow/terraformSegment/vmachine"

# Step 1: Initialize and apply Terraform
terraform_apply = BashOperator(
    task_id='terraform_apply',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json
        cd {terraform_dir}
        terraform init
        terraform apply -auto-approve
    """,
    dag=dag
)

# Step 2: Wait for VM to be ready
def wait_for_vm_ready():
    max_wait_seconds = 300  # 5 minutes
    sleep_interval = 10

    print("Waiting for VM to be ready and credentials to be generated...")

    for attempt in range(max_wait_seconds // sleep_interval):
        print(f"Attempt {attempt + 1}: Checking if VM is ready...")
        time.sleep(sleep_interval)

        if attempt >= 2:  # Wait at least 30 seconds
            print("✅ VM should be ready now.")
            return

    print("⚠️ VM might still be starting up, but proceeding...")

wait_for_vm = PythonOperator(
    task_id='wait_for_vm_ready',
    python_callable=wait_for_vm_ready,
    dag=dag
)


def save_vm_credentials():
    try:
        result = subprocess.run(
            ['terraform', 'output', '-json'],
            cwd=terraform_dir,
            capture_output=True,
            text=True,
            check=True
        )

        outputs = json.loads(result.stdout)

        credentials = {
            "vm_ip": outputs["vm_external_ip"]["value"],
            "username": outputs["windows_username"]["value"],
            "password": outputs["windows_password"]["value"],
            "bucket_name": outputs.get("bucket_name", {}).get("value", "Unknown"),
            "created_at": datetime.now().isoformat()
        }

        # Save to Docker container
        with open('/opt/airflow/vm-credentials.json', 'w') as f:
            json.dump(credentials, f, indent=2)

        # Save to local terraformSegment directory (mounted volume)
        local_credentials_path = f"{terraform_dir}/vm-credentials.json"
        with open(local_credentials_path, 'w') as f:
            json.dump(credentials, f, indent=2)

        print("✅ VM credentials saved to both locations")
        print(f"🖥️ VM IP: {credentials['vm_ip']}")
        print(f"👤 Username: {credentials['username']}")
        print(f"🪣 Bucket: {credentials['bucket_name']}")

    except Exception as e:
        print(f"❌ Error saving credentials: {e}")
        raise

save_credentials = PythonOperator(
    task_id='save_vm_credentials',
    python_callable=save_vm_credentials,
    dag=dag
)

# DAG execution order
terraform_apply >> wait_for_vm >> save_credentials