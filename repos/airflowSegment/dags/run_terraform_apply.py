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
    'run_terraform_apply',
    default_args=default_args,
    description='Run Terraform with simple safe apply flow',
    schedule_interval=None,
    catchup=False
)

terraform_dir = "/opt/airflow/terraformSegment"
kafka_key_path = f"{terraform_dir}/kafka-key.json"

# Step 1: Apply core infrastructure (env, cluster, service account, role binding)
apply_core_resources = BashOperator(
    task_id='terraform_apply_core_resources',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/gcp-key.json
        cd {terraform_dir}
        terraform init
        terraform apply -auto-approve
    """,
    dag=dag
)

# Step 2: Generate Kafka API key + credentials file
generate_api_key = BashOperator(
    task_id='terraform_generate_api_key',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/gcp-key.json
        cd {terraform_dir}
        terraform apply -auto-approve \\
          -target=confluent_api_key.kafka_api_key \\
          -target=local_file.kafka_credentials
    """,
    dag=dag
)

# Step 3: Wait for kafka-key.json to be fresh and valid
def wait_for_kafka_key():
    max_wait_seconds = 120
    sleep_interval = 3
    min_fresh_age_seconds = 10
    path = "/opt/airflow/terraformSegment/kafka-key.json"

    for attempt in range(max_wait_seconds // sleep_interval):
        if os.path.exists(path):
            file_size = os.path.getsize(path)
            age_seconds = time.time() - os.path.getmtime(path)

            if file_size > 100 and age_seconds <= min_fresh_age_seconds:
                try:
                    with open(path) as f:
                        data = json.load(f)
                        if all(len(data.get(k, "")) > 5 for k in ("api_key", "api_secret")):
                            print("✅ kafka-key.json is fresh and valid.")
                            return
                except Exception as e:
                    print(f"⚠️ JSON error: {e}")
        time.sleep(sleep_interval)

    raise FileNotFoundError("❌ kafka-key.json is not fresh or valid after waiting.")

wait_task = PythonOperator(
    task_id='wait_for_kafka_key',
    python_callable=wait_for_kafka_key,
    dag=dag
)

# Step 4: Apply Kafka topics (no state rm, just trust infra is defined correctly)
apply_kafka_topics = BashOperator(
    task_id='terraform_apply_kafka_topics',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/gcp-key.json
        cd {terraform_dir}
        terraform apply -auto-approve -target=module.kafka_topics
    """,
    dag=dag
)

# DAG execution order
apply_core_resources >> generate_api_key >> wait_task >> apply_kafka_topics
