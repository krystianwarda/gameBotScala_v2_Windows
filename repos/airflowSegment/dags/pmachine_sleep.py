from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.models import Param
from datetime import datetime, timedelta
import json
import os
import paramiko
import time

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 1,
    'retry_delay': timedelta(minutes=2),
    'depends_on_past': False,
    'email_on_failure': False,
    'email_on_retry': False
}

REPOS_FILES_DIR = "/opt/airflow/files"
SSH_CREDENTIALS_FILE = f"{REPOS_FILES_DIR}/ssh-credentials.json"
EXECUTION_LOG = f"{REPOS_FILES_DIR}/sleep-execution.json"

dag = DAG(
    'pmachine_sleep',
    default_args=default_args,
    description='Put target machine(s) into sleep mode',
    schedule_interval=None,
    catchup=False,
    params={
        "confirmation": Param(
            default=False,
            type="boolean",
            description="Confirm that you want to put the machine(s) into sleep mode"
        ),
        "delay_seconds": Param(
            default=10,
            type="integer",
            description="Delay in seconds before executing sleep command"
        ),
        "target_machines": Param(
            default=["target_machine_1"],
            type="array",
            description="List of machine names to put to sleep"
        )
    },
    tags=['pmachine', 'sleep', 'power']
)

def load_ssh_credentials(**context):
    """Load SSH credentials for selected target machines (comma-separated supported)"""
    params = context['params']
    raw_targets = params.get('target_machines', [])
    print(f"🔎 Raw target_machines param: {raw_targets}")
    # Flatten and split comma-separated strings in the list
    target_machines = []
    if isinstance(raw_targets, str):
        target_machines = [m.strip() for m in raw_targets.split(',') if m.strip()]
    elif isinstance(raw_targets, list):
        for item in raw_targets:
            if isinstance(item, str):
                target_machines.extend([m.strip() for m in item.split(',') if m.strip()])
    print(f"🔎 Parsed target_machines: {target_machines}")
    with open(SSH_CREDENTIALS_FILE, 'r') as f:
        credentials = json.load(f)
    selected = [
        m for m in credentials.get('machines', [])
        if m.get('active', False) and m['name'] in target_machines
    ]
    if not selected:
        raise ValueError("No matching active machines found for selection")
    print(f"Selected machines for sleep: {[m['name'] for m in selected]}")
    return selected

def execute_sleep_command(**context):
    """Execute sleep command on all selected machines"""
    params = context['params']
    delay_seconds = params.get('delay_seconds', 10)
    task_instance = context['task_instance']
    ssh_credentials_list = task_instance.xcom_pull(task_ids='load_ssh_credentials')
    results = []
    for ssh_credentials in ssh_credentials_list:
        ip = ssh_credentials['ip']
        username = ssh_credentials['username']
        password = ssh_credentials['password']
        machine_name = ssh_credentials['name']
        ssh_client = None
        result = {
            "machine_name": machine_name,
            "ip": ip,
            "username": username,
            "delay_seconds": delay_seconds,
            "status": "failed",
            "error": None,
            "command_executed_at": None,
            "sleep_initiated_at": None
        }
        try:
            ssh_client = paramiko.SSHClient()
            ssh_client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            print(f"Connecting to {machine_name} ({ip}) for sleep operation...")
            ssh_client.connect(
                hostname=ip,
                username=username,
                password=password,
                timeout=30
            )
            result["command_executed_at"] = datetime.utcnow().isoformat()
            stdin, stdout, stderr = ssh_client.exec_command('echo %OS%', timeout=10)
            os_output = stdout.read().decode().strip()
            if 'Windows' in os_output or '%OS%' not in os_output:
                if delay_seconds > 0:
                    sleep_command = f'powershell.exe -Command "Start-Sleep -Seconds {delay_seconds}; Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false)"'
                else:
                    sleep_command = 'powershell.exe -Command "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false)"'
                print(f"Detected Windows OS. Using PowerShell command with {delay_seconds}s delay")
            else:
                if delay_seconds > 0:
                    sleep_command = f'sleep {delay_seconds} && systemctl suspend'
                else:
                    sleep_command = 'systemctl suspend'
                print(f"Detected Unix-like OS. Using systemctl command with {delay_seconds}s delay")
            print(f"⏰ Executing sleep command with {delay_seconds} second delay...")
            print(f"Command: {sleep_command}")
            stdin, stdout, stderr = ssh_client.exec_command(sleep_command, timeout=delay_seconds + 15)
            time.sleep(min(3, delay_seconds + 1))
            if stdout.channel.exit_status_ready():
                exit_status = stdout.channel.recv_exit_status()
                error_output = stderr.read().decode().strip()
                if exit_status == 0:
                    print("✅ Sleep command completed successfully")
                    result["status"] = "success"
                    result["sleep_initiated_at"] = datetime.utcnow().isoformat()
                else:
                    error_msg = f"Sleep command failed with exit status {exit_status}: {error_output}"
                    print(f"❌ {error_msg}")
                    result["error"] = error_msg
            else:
                print("✅ Sleep command initiated successfully")
                result["status"] = "success"
                result["sleep_initiated_at"] = datetime.utcnow().isoformat()
                print(f"🛌 Machine {machine_name} will enter sleep mode in {delay_seconds} seconds")
        except paramiko.AuthenticationException:
            error_msg = "Authentication failed - invalid credentials"
            print(f"❌ {error_msg}")
            result["error"] = error_msg
        except paramiko.SSHException as e:
            error_msg = f"SSH connection failed: {str(e)}"
            print(f"❌ {error_msg}")
            result["error"] = error_msg
        except Exception as e:
            error_msg = f"Sleep operation failed: {str(e)}"
            print(f"❌ {error_msg}")
            result["error"] = error_msg
        finally:
            if ssh_client:
                ssh_client.close()
                print(f"🔒 Closed SSH connection to {machine_name}")
        results.append(result)
    return results

def verify_machine_status(**context):
    """Attempt to verify if machines went to sleep"""
    task_instance = context['task_instance']
    sleep_results = task_instance.xcom_pull(task_ids='execute_sleep_command')
    ssh_credentials_list = task_instance.xcom_pull(task_ids='load_ssh_credentials')
    verifications = []
    for ssh_credentials, sleep_result in zip(ssh_credentials_list, sleep_results):
        if sleep_result.get('status') != 'success':
            print(f"⚠️ Sleep command was not successful for {ssh_credentials['name']}, skipping verification")
            verifications.append({"machine_name": ssh_credentials['name'], "verification_skipped": True, "reason": "Sleep command failed"})
            continue
        print(f"⏰ Waiting 30 seconds before verification for {ssh_credentials['name']}...")
        time.sleep(30)
        ip = ssh_credentials['ip']
        machine_name = ssh_credentials['name']
        result = {
            "machine_name": machine_name,
            "ip": ip,
            "verification_attempted_at": datetime.utcnow().isoformat(),
            "is_sleeping": False,
            "verification_method": "ping_test"
        }
        try:
            print(f"🔍 Attempting to verify sleep status of {machine_name}...")
            ssh_client = paramiko.SSHClient()
            ssh_client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            ssh_client.connect(
                hostname=ip,
                username=ssh_credentials['username'],
                password=ssh_credentials['password'],
                timeout=10
            )
            ssh_client.close()
            print("⚠️ Machine appears to still be awake (connection successful)")
            result["is_sleeping"] = False
        except Exception:
            print("✅ Machine appears to be sleeping (connection failed)")
            result["is_sleeping"] = True
        verifications.append(result)
    return verifications

def save_execution_log(**context):
    """Save complete execution log for all machines"""
    params = context['params']
    task_instance = context['task_instance']
    confirmation = task_instance.xcom_pull(task_ids='validate_confirmation')
    sleep_results = task_instance.xcom_pull(task_ids='execute_sleep_command')
    verification_results = task_instance.xcom_pull(task_ids='verify_machine_status')
    execution_data = {
        "execution_started_at": datetime.utcnow().isoformat(),
        "parameters": params,
        "confirmation_result": confirmation,
        "sleep_results": sleep_results,
        "verification_results": verification_results,
        "execution_completed_at": datetime.utcnow().isoformat()
    }
    os.makedirs(REPOS_FILES_DIR, exist_ok=True)
    with open(EXECUTION_LOG, 'w') as f:
        json.dump(execution_data, f, indent=2)
    print(f"Execution log saved to {EXECUTION_LOG}")
    for sleep_result, verification_result in zip(sleep_results, verification_results):
        if sleep_result and sleep_result.get('status') == 'success':
            print(f"✅ Sleep operation completed successfully for {sleep_result['machine_name']}")
            if verification_result and verification_result.get('is_sleeping'):
                print(f"✅ Machine {sleep_result['machine_name']} verified to be in sleep mode")
            else:
                print(f"⚠️ Could not verify machine {sleep_result['machine_name']} sleep status")
        else:
            print(f"❌ Sleep operation failed for {sleep_result.get('machine_name', 'Unknown')}: {sleep_result.get('error', 'Unknown error') if sleep_result else 'No result'}")
    return execution_data

def validate_confirmation(**context):
    """Validate that user confirmed the sleep operation"""
    params = context['params']
    confirmation = params.get('confirmation', False)
    if not confirmation:
        raise ValueError("Sleep operation not confirmed. Please set 'confirmation' parameter to True to proceed.")
    print("✅ Sleep operation confirmed")
    return {"confirmed": True, "timestamp": datetime.utcnow().isoformat()}

validate_task = PythonOperator(
    task_id='validate_confirmation',
    python_callable=validate_confirmation,
    dag=dag
)

load_credentials_task = PythonOperator(
    task_id='load_ssh_credentials',
    python_callable=load_ssh_credentials,
    dag=dag
)

sleep_task = PythonOperator(
    task_id='execute_sleep_command',
    python_callable=execute_sleep_command,
    dag=dag
)

verify_task = PythonOperator(
    task_id='verify_machine_status',
    python_callable=verify_machine_status,
    dag=dag
)

save_log_task = PythonOperator(
    task_id='save_execution_log',
    python_callable=save_execution_log,
    dag=dag
)

validate_task >> load_credentials_task >> sleep_task >> verify_task >> save_log_task