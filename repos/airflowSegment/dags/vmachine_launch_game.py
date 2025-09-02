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
    'vmachine_launch_realera_v4',
    default_args=default_args,
    description='Launch RealeraDX.exe using Task Scheduler for interactive session',
    schedule_interval=None,
    catchup=False
)

def launch_realera_app():
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

        # Create a scheduled task to run the application interactively
        task_name = "LaunchRealeraDX"

        # Delete existing task if it exists
        delete_task_command = f'schtasks /delete /tn "{task_name}" /f'
        print("🧹 Removing any existing scheduled task...")
        session.run_cmd(delete_task_command)

        # Create new scheduled task
        create_task_command = f'''schtasks /create /tn "{task_name}" /tr "C:\\RealeraRelease\\RealeraRelease_v1\\RealeraDX.exe" /sc once /st 00:00 /it /ru "{vm_credentials['username']}" /rp "{vm_credentials['password']}" /rl highest'''
        print("📅 Creating scheduled task...")

        result = session.run_cmd(create_task_command)
        if result.status_code == 0:
            print("✅ Scheduled task created successfully!")
        else:
            print(f"Task creation result: {result.status_code}")
            error_output = result.std_err.decode('utf-8') if isinstance(result.std_err, bytes) else result.std_err
            print(f"Error: {error_output}")

        # Run the scheduled task immediately
        run_task_command = f'schtasks /run /tn "{task_name}"'
        print("🚀 Running scheduled task to launch RealeraDX.exe...")

        result = session.run_cmd(run_task_command)
        output = result.std_out.decode('utf-8') if isinstance(result.std_out, bytes) else result.std_out
        print(f"Task run result: {output}")

        if result.status_code == 0:
            print("✅ Scheduled task executed successfully!")
        else:
            print(f"Task execution failed with status: {result.status_code}")
            error_output = result.std_err.decode('utf-8') if isinstance(result.std_err, bytes) else result.std_err
            print(f"Error: {error_output}")

        print("✅ Launch process completed!")

    except Exception as e:
        print(f"❌ Error: {e}")
        raise

launch_realera_task = PythonOperator(
    task_id='launch_realera_app_v4',
    python_callable=launch_realera_app,
    dag=dag
)

launch_realera_task