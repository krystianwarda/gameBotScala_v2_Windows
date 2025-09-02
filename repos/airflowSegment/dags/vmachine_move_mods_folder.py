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
    'vmachine_move_mods_folder_v3',
    default_args=default_args,
    description='Move C:\\mods directory to C:\\Users\\gamebot\\AppData\\Roaming\\Realera\\Realera\\',
    schedule_interval=None,
    catchup=False
)

def move_mods_folder():
    credentials_path = '/opt/airflow/terraformSegment/vmachine/vm-credentials.json'

    try:
        with open(credentials_path, 'r') as f:
            vm_credentials = json.load(f)
        print(f"✅ Successfully loaded credentials")

        session = winrm.Session(
            f"http://{vm_credentials['vm_ip']}:5985/wsman",
            auth=(vm_credentials['username'], vm_credentials['password']),
            transport='basic'
        )

        print("✅ Successfully connected to Windows VM via WinRM")

        # Create destination directory structure
        create_dir_command = 'New-Item -Path "C:\\Users\\gamebot\\AppData\\Roaming\\Realera\\Realera" -ItemType Directory -Force'
        print("📁 Creating destination directory structure...")

        result = session.run_ps(create_dir_command)
        if result.status_code == 0:
            print("✅ Destination directory structure created successfully!")
        else:
            print(f"❌ Directory creation failed with status: {result.status_code}")
            print(f"Error: {result.std_err}")
            raise Exception("Directory creation failed")

        # Check if source directory exists
        check_source_command = 'if (Test-Path "C:\\mods") { "EXISTS" } else { "NOT_FOUND" }'
        print("🔍 Checking if C:\\mods exists...")

        result = session.run_ps(check_source_command)
        output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()
        print(f"Source check result: '{output}'")
        print(f"Status code: {result.status_code}")

        if result.status_code == 0 and "EXISTS" in output:
            print("✅ Source directory C:\\mods confirmed to exist")

            # Move the directory
            move_command = 'Move-Item -Path "C:\\mods" -Destination "C:\\Users\\gamebot\\AppData\\Roaming\\Realera\\Realera\\mods" -Force'
            print("📦 Moving C:\\mods to destination...")

            result = session.run_ps(move_command)
            if result.status_code == 0:
                print("✅ Directory moved successfully!")
                output = result.std_out.decode('utf-8') if isinstance(result.std_out, bytes) else result.std_out
                print(f"Output: {output}")
            else:
                print(f"❌ Move operation failed with status: {result.status_code}")
                error_output = result.std_err.decode('utf-8') if isinstance(result.std_err, bytes) else result.std_err
                print(f"Error output: {error_output}")
                raise Exception("Directory move failed")
        else:
            print(f"⚠️ Source directory C:\\mods not found")
            print(f"PowerShell output: '{output}'")
            error_output = result.std_err.decode('utf-8') if isinstance(result.std_err, bytes) else result.std_err
            print(f"PowerShell error: '{error_output}'")
            raise Exception("Source directory not found")

    except Exception as e:
        print(f"❌ Error: {e}")
        raise

move_mods_task = PythonOperator(
    task_id='move_mods_folder_v3',
    python_callable=move_mods_folder,
    dag=dag
)

move_mods_task