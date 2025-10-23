from airflow import DAG
from airflow.operators.bash import BashOperator
from datetime import datetime, timedelta

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 0,
    'retry_delay': timedelta(minutes=5)
}

dag = DAG(
    'stream_analytics_run_terraform_destroy',
    default_args=default_args,
    description='Destroys infrastructure in safe order (cluster before API key)',
    schedule_interval=None,
    catchup=False,
    tags=[ 'stream_analytics', 'terraform', 'destroy']
)

terraform_dir = "/opt/airflow/terraformSegment/stream_analytics"

# Step 1: Destroy everything except API key and credential file
destroy_infra_except_key = BashOperator(
    task_id='terraform_destroy_except_api_key',
    bash_command=f"""
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json &&
        cd {terraform_dir} &&
        terraform init &&
        terraform destroy -auto-approve \\
          -target=confluent_environment.dev \\
          -target=confluent_kafka_cluster.basic \\
          -target=confluent_role_binding.app_manager_binding \\
          -target=confluent_service_account.app_manager \\
          -target=confluent_kafka_topic.game_bot_events \\
          -target=module.kafka_topics
    """,
    dag=dag
)

# Step 2: Destroy API key and credentials file
destroy_api_key = BashOperator(
    task_id='terraform_destroy_api_key',
    bash_command=f"""
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/files/gcp-key.json &&
        cd {terraform_dir} &&
        terraform init &&
        terraform destroy -auto-approve \\
          -target=confluent_api_key.kafka_api_key \\
          -target=local_file.kafka_credentials
    """,
    dag=dag
)

# DAG flow
destroy_infra_except_key >> destroy_api_key
