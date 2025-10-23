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
    description='Destroy modular Terraform VM machine infrastructure',
    schedule_interval=None,
    catchup=False
)

terraform_dir = "/opt/airflow/terraformSegment/vmachine"

# Step 1: Initialize Terraform
terraform_init = BashOperator(
    task_id='terraform_init',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json
        cd {terraform_dir}
        echo "🔧 Initializing Terraform..."
        terraform init -upgrade
        echo "✅ Terraform initialized"
    """,
    dag=dag
)

# Step 2: Check current state
check_terraform_state = BashOperator(
    task_id='check_terraform_state',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json
        cd {terraform_dir}
        echo "📋 Current Terraform state:"
        terraform show
    """,
    dag=dag
)

# Step 3: Plan destroy for start_machine module first
terraform_plan_destroy_start_machine = BashOperator(
    task_id='terraform_plan_destroy_start_machine',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json
        cd {terraform_dir}
        echo "📋 Planning destroy for start_machine module..."
        terraform plan -destroy -target=module.start_machine
        echo "✅ Destroy plan for start_machine completed"
    """,
    dag=dag
)

# Step 4: Safety wait before destroying VM
def safety_wait_vm():
    print("⚠️ Safety pause before destroying VM...")
    print("This will destroy:")
    print("- Google Compute Instance (Windows VM with GPU)")
    print("- Firewall rules")
    print("- Random password resource")
    print("- VM credentials file")
    time.sleep(10)
    print("✅ Proceeding with VM destruction...")

wait_before_destroy_vm = PythonOperator(
    task_id='safety_wait_vm',
    python_callable=safety_wait_vm,
    dag=dag
)

# Step 5: Destroy start_machine module first
terraform_destroy_start_machine = BashOperator(
    task_id='terraform_destroy_start_machine',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json
        cd {terraform_dir}
        echo "🖥️ Destroying start_machine module..."
        terraform destroy -target=module.start_machine -auto-approve
        echo "✅ VM machine destroyed"
    """,
    dag=dag
)

# Step 6: Plan destroy for upload_files module
terraform_plan_destroy_upload_files = BashOperator(
    task_id='terraform_plan_destroy_upload_files',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json
        cd {terraform_dir}
        echo "📋 Planning destroy for upload_files module..."
        terraform plan -destroy -target=module.upload_files
        echo "✅ Destroy plan for upload_files completed"
    """,
    dag=dag
)

# Step 7: Safety wait before destroying bucket
def safety_wait_bucket():
    print("⚠️ Safety pause before destroying storage bucket...")
    print("This will destroy:")
    print("- GCS Storage bucket")
    print("- All uploaded files (launcher_package.zip, appdata_package.zip)")
    print("- Bucket IAM permissions")
    time.sleep(5)
    print("✅ Proceeding with bucket destruction...")

wait_before_destroy_bucket = PythonOperator(
    task_id='safety_wait_bucket',
    python_callable=safety_wait_bucket,
    dag=dag
)

# Step 8: Destroy upload_files module
terraform_destroy_upload_files = BashOperator(
    task_id='terraform_destroy_upload_files',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json
        cd {terraform_dir}
        echo "🪣 Destroying upload_files module..."
        terraform destroy -target=module.upload_files -auto-approve
        echo "✅ Storage bucket and files destroyed"
    """,
    dag=dag
)

# Step 9: Verify all resources destroyed
verify_destruction = BashOperator(
    task_id='verify_destruction',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json
        cd {terraform_dir}
        echo "🔍 Verifying all resources destroyed..."
        terraform show
        echo "✅ Destruction verification completed"
    """,
    dag=dag
)

# Step 10: Clean up local files
def cleanup_local_files():
    try:
        # Remove credentials file
        if os.path.exists('/opt/airflow/vm-credentials.json'):
            os.remove('/opt/airflow/vm-credentials.json')
            print("✅ VM credentials file removed")

        print("✅ Local cleanup completed")

    except Exception as e:
        print(f"⚠️ Cleanup warning: {e}")

cleanup_task = PythonOperator(
    task_id='cleanup_local_files',
    python_callable=cleanup_local_files,
    dag=dag
)

# Step 11: Final cleanup of terraform files
terraform_cleanup = BashOperator(
    task_id='terraform_cleanup',
    bash_command=f"""
        cd {terraform_dir}
        rm -f terraform.tfstate.backup
        rm -f .terraform.lock.hcl
        echo "✅ Terraform cleanup completed"
    """,
    dag=dag
)

# DAG execution order - destroy in reverse order: VM first, then bucket
terraform_init >> check_terraform_state >> terraform_plan_destroy_start_machine >> wait_before_destroy_vm >> terraform_destroy_start_machine >> terraform_plan_destroy_upload_files >> wait_before_destroy_bucket >> terraform_destroy_upload_files >> verify_destruction >> cleanup_task >> terraform_cleanup