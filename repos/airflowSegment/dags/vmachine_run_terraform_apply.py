from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import os
import time
import json

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

# Use Linux path that corresponds to your mounted volume
terraform_dir = "/opt/airflow/terraformSegment/vmachine"

# Step 1: Initialize and apply Terraform
terraform_apply = BashOperator(
    task_id='terraform_apply',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/gcp-key.json
        cd {terraform_dir}
        terraform init
        terraform apply -auto-approve
    """,
    dag=dag
)

# Step 2: Wait for VM to be ready and credentials file to be generated
def wait_for_vm_ready():
    max_wait_seconds = 300  # 5 minutes
    sleep_interval = 10

    print("Waiting for VM to be ready and credentials to be generated...")

    for attempt in range(max_wait_seconds // sleep_interval):
        print(f"Attempt {attempt + 1}: Checking if VM is ready...")
        time.sleep(sleep_interval)

        # In a real scenario, you might want to check VM status via GCP API
        # For now, just wait a reasonable amount of time
        if attempt >= 2:  # Wait at least 30 seconds
            print("✅ VM should be ready now.")
            return

    print("⚠️ VM might still be starting up, but proceeding...")


def save_vm_credentials():
    import subprocess
    import json

    try:
        # Get terraform output
        result = subprocess.run(
            ['terraform', 'output', '-json'],
            cwd='/opt/airflow/terraformSegment/vmachine',
            capture_output=True,
            text=True,
            check=True
        )

        outputs = json.loads(result.stdout)

        credentials = {
            "vm_ip": outputs["vm_external_ip"]["value"],
            "username": outputs["windows_username"]["value"],
            "password": outputs["windows_password"]["value"]
        }

        # Save to file
        with open('/opt/airflow/vm-credentials.json', 'w') as f:
            json.dump(credentials, f, indent=2)

        print("✅ VM credentials saved to /opt/airflow/vm-credentials.json")
        print(f"VM IP: {credentials['vm_ip']}")
        print(f"Username: {credentials['username']}")

    except Exception as e:
        print(f"❌ Error saving credentials: {e}")

# Add this task after wait_for_vm
save_credentials = PythonOperator(
    task_id='save_vm_credentials',
    python_callable=save_vm_credentials,
    dag=dag
)

wait_for_vm = PythonOperator(
    task_id='wait_for_vm_ready',
    python_callable=wait_for_vm_ready,
    dag=dag
)

# DAG execution order
terraform_apply >> wait_for_vm >> save_credentials