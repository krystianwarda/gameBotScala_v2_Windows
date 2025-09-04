from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.providers.google.cloud.transfers.local_to_gcs import LocalFilesystemToGCSOperator

from datetime import datetime, timedelta

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 0,
    'retry_delay': timedelta(minutes=5)
}

dag = DAG(
    'stream_analytics_build_and_upload_flink_jar',
    default_args=default_args,
    description='Builds Flink fat JAR with sbt and uploads to GCS',
    schedule_interval=None,
    catchup=False,
    tags=[ 'stream_analytics', 'flink', 'build', 'jar']
)

flink_dir = "/opt/airflow/flinkSegment"
jar_file = "flink-gcp-scala-assembly-0.1.0-SNAPSHOT.jar"
jar_output_path = f"{flink_dir}/target/scala-2.12/{jar_file}"
bucket_name = "flink_jar_bucket"

# Step 1: Build JAR with sbt assembly
build_flink_jar = BashOperator(
    task_id='sbt_assembly',
    bash_command=f"""
        export PATH=$PATH:/usr/local/bin:/usr/bin &&
        cd {flink_dir} &&
        sbt clean assembly
    """,
    dag=dag
)

upload_jar_to_gcs = BashOperator(
    task_id='upload_jar_to_gcs',
    bash_command=f"""
        gcloud auth activate-service-account --key-file=/opt/airflow/gcp-key.json &&
        gsutil cp "{jar_output_path}" gs://{bucket_name}/{jar_file}
    """,
    dag=dag
)



build_flink_jar >> upload_jar_to_gcs
