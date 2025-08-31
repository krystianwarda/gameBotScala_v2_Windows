from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import os
import time

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 0,
    'retry_delay': timedelta(minutes=5)
}

dag = DAG(
    'vmachine_run_terraform_destroy',
    default_args=default_args,
    description='Destroy Terraform VM machine infrastructure',
    schedule_interval=None,
    catchup=False
)

# Use Linux path that corresponds to your mounted volume
terraform_dir = "/opt/airflow/terraformSegment/vmachine"

# Step 1: Confirm resources exist before destroying
check_terraform_state = BashOperator(
    task_id='check_terraform_state',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/gcp-key.json
        cd {terraform_dir}
        terraform init -upgrade
        terraform show
    """,
    dag=dag
)

# Step 2: Plan the destroy operation
terraform_plan_destroy = BashOperator(
    task_id='terraform_plan_destroy',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/gcp-key.json
        cd {terraform_dir}
        terraform plan -destroy
    """,
    dag=dag
)

# Step 3: Wait before destruction (safety pause)
def safety_wait():
    print("⚠️ Safety pause before destroying VM infrastructure...")
    print("This will destroy:")
    print("- Google Compute Instance (Windows VM with GPU)")
    print("- Storage bucket and any contents")
    print("- Firewall rules")
    print("- Random password resource")
    time.sleep(10)
    print("✅ Proceeding with destruction...")

wait_before_destroy = PythonOperator(
    task_id='safety_wait',
    python_callable=safety_wait,
    dag=dag
)

# Step 4: Destroy all resources
terraform_destroy = BashOperator(
    task_id='terraform_destroy',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/gcp-key.json
        cd {terraform_dir}
        terraform destroy -auto-approve
    """,
    dag=dag
)

# Step 5: Clean up local files (updated for Linux container)
cleanup_local_files = BashOperator(
    task_id='cleanup_local_files',
    bash_command=f"""
        cd {terraform_dir}
        rm -f terraform.tfstate.backup
        rm -f .terraform.lock.hcl
        echo "✅ Local cleanup completed"
    """,
    dag=dag
)

# DAG execution order
check_terraform_state >> terraform_plan_destroy >> wait_before_destroy >> terraform_destroy >> cleanup_local_files