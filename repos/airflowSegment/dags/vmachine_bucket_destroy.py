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
    'vmachine_template_destroy_bucket',
    default_args=default_args,
    description='Destroy GCS bucket and remove all files',
    schedule_interval=None,  # Manual trigger only
    catchup=False,
    tags=['vmachine', 'bucket', 'terraform', 'destroy_bucket']
)

def log_bucket_destruction_start():
    logging.info("Starting bucket destruction process...")
    logging.info("This will destroy the GCS bucket and all its contents")
    logging.info("All uploaded files (launcher package, appdata package, JAR file) will be permanently deleted")
    logging.warning("This action is irreversible!")

log_start_task = PythonOperator(
    task_id='log_bucket_destruction_start',
    python_callable=log_bucket_destruction_start,
    dag=dag,
)

# Change to the Terraform directory and destroy bucket resources
terraform_destroy_bucket = BashOperator(
    task_id='terraform_destroy_bucket',
    bash_command='''
    set -e
    export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/gcp-key.json
    cd /opt/airflow/terraformSegment/vmachine/upload_files

    # Initialize Terraform
    terraform init

    # Plan the destruction to see what will be destroyed
    terraform plan -destroy

    # Destroy all resources (bucket and contents)
    terraform destroy -auto-approve
    ''',
    dag=dag,
)

def log_bucket_destruction_complete():
    logging.info("Bucket destruction completed successfully!")
    logging.info("The following resources were destroyed:")
    logging.info("- GCS bucket (gamebot-files-bucket)")
    logging.info("- All uploaded files and objects")
    logging.info("- Bucket IAM permissions")
    logging.info("- Any associated bucket configurations")
    logging.info("All bucket contents have been permanently removed")

log_complete_task = PythonOperator(
    task_id='log_bucket_destruction_complete',
    python_callable=log_bucket_destruction_complete,
    dag=dag,
)

# Set task dependencies
log_start_task >> terraform_destroy_bucket >> log_complete_task