from datetime import datetime, timedelta
from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
import logging

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 1,
    'retry_delay': timedelta(minutes=5),
}

dag = DAG(
    'vmachine_template_destroy_instance',
    default_args=default_args,
    description='Destroy VM machine without touching bucket',
    schedule_interval=None,  # Manual trigger only
    catchup=False,
    tags=[ 'vmachine', 'template', 'terraform', 'destroy_instance']
)

def log_destruction_start():
    logging.info("Starting VM machine destruction process...")
    logging.info("This will destroy the VM instance and firewall rules only")
    logging.info("Bucket and its contents will remain untouched")

log_start_task = PythonOperator(
    task_id='log_destruction_start',
    python_callable=log_destruction_start,
    dag=dag,
)

# Change to the Terraform directory and destroy only VM resources
terraform_destroy = BashOperator(
    task_id='terraform_destroy_vm',
    bash_command='''
    cd /opt/airflow/terraformSegment/vmachine/start_machine

    # Initialize Terraform
    terraform init

    # Plan the destruction to see what will be destroyed
    terraform plan -destroy -target=google_compute_instance.vm_instance -target=google_compute_firewall.allow_rdp -target=google_compute_firewall.allow_winrm -target=random_password.windows_password -var="project_id=gamebot-469621" -var="region=us-central1" -var="zone=us-central1-a"

    # Destroy only the targeted resources
    terraform destroy -auto-approve -target=google_compute_instance.vm_instance -target=google_compute_firewall.allow_rdp -target=google_compute_firewall.allow_winrm -target=random_password.windows_password -var="project_id=gamebot-469621" -var="region=us-central1" -var="zone=us-central1-a"
    ''',
    dag=dag,
)

def log_destruction_complete():
    logging.info("VM machine destruction completed successfully!")
    logging.info("The following resources were destroyed:")
    logging.info("- google_compute_instance.vm_instance")
    logging.info("- google_compute_firewall.allow_rdp")
    logging.info("- google_compute_firewall.allow_winrm")
    logging.info("- random_password.windows_password")
    logging.info("Bucket gamebot-files-bucket and its contents remain intact")

log_complete_task = PythonOperator(
    task_id='log_destruction_complete',
    python_callable=log_destruction_complete,
    dag=dag,
)

# Set task dependencies
log_start_task >> terraform_destroy >> log_complete_task