from airflow import DAG
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import json
import winrm

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 1,
    'retry_delay': timedelta(minutes=2)
}

dag = DAG(
    'vmachine_create_folder_test',
    default_args=default_args,
    description='Connect to Windows VM and create test1 folder in C:/',
    schedule_interval=None,
    catchup=False
)

def create_folder_on_vm():
    credentials_path = '/opt/airflow/terraformSegment/vmachine/vm-credentials.json'

    try:
        with open(credentials_path, 'r') as f:
            vm_credentials = json.load(f)
        print(f"✅ Successfully loaded credentials")

        # Try WinRM connection
        session = winrm.Session(
            f"http://{vm_credentials['vm_ip']}:5985/wsman",
            auth=(vm_credentials['username'], vm_credentials['password']),
            transport='basic'
        )

        print("✅ Successfully connected to Windows VM via WinRM")

        command = 'New-Item -Path C:\\test1 -ItemType Directory -Force'
        print("📁 Creating folder C:\\test1...")

        result = session.run_ps(command)

        if result.status_code == 0:
            print("✅ Folder C:\\test1 created successfully!")
        else:
            print(f"❌ Command failed with status: {result.status_code}")
            raise Exception("Folder creation failed")

    except Exception as e:
        print(f"❌ Error: {e}")
        raise

create_folder_task = PythonOperator(
    task_id='create_test1_folder',
    python_callable=create_folder_on_vm,
    dag=dag
)

create_folder_task