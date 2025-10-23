from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.models import Param
from datetime import datetime, timedelta
import json
import os
import paramiko
import socket

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 1,
    'retry_delay': timedelta(minutes=2),
    'depends_on_past': False,
    'email_on_failure': False,
    'email_on_retry': False
}

dag = DAG(
    'pmachine_test_ssh',
    default_args=default_args,
    description='Connect to target machine via SSH and create folder',
    schedule_interval=None,
    catchup=False,

    params={
        "folder_name": Param(
            default="test2",
            type="string",
            description="Name of folder to create on target machine"
        ),
        "folder_path": Param(
            default="C:/",
            type="string",
            description="Base path where folder should be created"
        ),
        "target_machines": Param(
            default='target_machine_4',
            type="string",
            description="machine"
        )
    },
    tags=['pmachine', 'ssh', 'creation']
)

# Configuration
REPOS_FILES_DIR = "/opt/airflow/files"
SSH_CREDENTIALS_FILE = f"{REPOS_FILES_DIR}/ssh-credentials.json"
EXECUTION_LOG = f"{REPOS_FILES_DIR}/ssh-execution.json"
def load_ssh_credentials(**context):
    """Load SSH credentials from JSON file"""
    try:
        with open(SSH_CREDENTIALS_FILE, 'r') as f:
            credentials = json.load(f)

        # Filter only machine 4
        machine_4 = next(
            (machine for machine in credentials.get('machines', []) if machine.get('name') == 'target_machine_4'),
            None
        )

        if not machine_4:
            raise ValueError("Machine 4 not found in credentials file")

        print(f"Loaded credentials for machine 4")
        return [machine_4]

    except FileNotFoundError:
        raise FileNotFoundError(f"SSH credentials file not found at {SSH_CREDENTIALS_FILE}")
    except Exception as e:
        print(f"Failed to load SSH credentials: {e}")
        raise

def create_folder_on_machine(**context):
    """Connect to target machine via SSH and create folder"""
    params = context['params']
    folder_name = params['folder_name']
    folder_path = params['folder_path']

    # Get active machines from previous task
    task_instance = context['task_instance']
    active_machines = task_instance.xcom_pull(task_ids='load_ssh_credentials')

    if not active_machines:
        raise ValueError("No active machines available")

    # Use first active machine (as requested - only one machine)
    machine = active_machines[0]

    machine_name = machine['name']
    ip = machine['ip']
    username = machine['username']
    password = machine['password']

    full_folder_path = os.path.join(folder_path, folder_name)

    ssh_client = None
    result = {
        "machine_name": machine_name,
        "ip": ip,
        "username": username,
        "folder_path": full_folder_path,
        "action": "create_folder",
        "status": "failed",
        "error": None,
        "timestamp": datetime.utcnow().isoformat() + "Z"
    }

    try:
        # Create SSH client
        ssh_client = paramiko.SSHClient()
        ssh_client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

        print(f"Connecting to {machine_name} ({ip}) via SSH...")

        # Connect with timeout
        ssh_client.connect(
            hostname=ip,
            username=username,
            password=password,
            timeout=30,
            auth_timeout=30
        )

        print(f"✅ Successfully connected to {machine_name} ({ip})")

        # Check if folder already exists
        check_command = f'test -d "{full_folder_path}" && echo "EXISTS" || echo "NOT_EXISTS"'
        stdin, stdout, stderr = ssh_client.exec_command(check_command)
        check_result = stdout.read().decode().strip()

        if check_result == "EXISTS":
            print(f"📁 Folder '{full_folder_path}' already exists on {machine_name}")
            result["status"] = "already_exists"
        else:
            # Create the folder
            create_command = f'mkdir -p "{full_folder_path}"'
            stdin, stdout, stderr = ssh_client.exec_command(create_command)

            # Wait for command to complete
            exit_status = stdout.channel.recv_exit_status()

            if exit_status == 0:
                print(f"✅ Successfully created folder '{full_folder_path}' on {machine_name}")
                result["status"] = "created"

                # Verify folder was created
                verify_command = f'ls -la "{folder_path}" | grep "{folder_name}"'
                stdin, stdout, stderr = ssh_client.exec_command(verify_command)
                verify_output = stdout.read().decode().strip()

                if folder_name in verify_output:
                    print(f"✅ Folder creation verified on {machine_name}")
                    result["verification"] = "confirmed"
                else:
                    print(f"⚠️ Folder created but verification failed on {machine_name}")
                    result["verification"] = "failed"
            else:
                error_output = stderr.read().decode().strip()
                raise Exception(f"Failed to create folder. Exit status: {exit_status}, Error: {error_output}")

    except socket.timeout:
        error_msg = f"Connection timeout to {ip}"
        print(f"❌ {error_msg}")
        result["error"] = error_msg

    except paramiko.AuthenticationException:
        error_msg = f"Authentication failed for {username}@{ip}"
        print(f"❌ {error_msg}")
        result["error"] = error_msg

    except paramiko.SSHException as e:
        error_msg = f"SSH connection error to {ip}: {str(e)}"
        print(f"❌ {error_msg}")
        result["error"] = error_msg

    except Exception as e:
        error_msg = f"Failed to create folder on {machine_name}: {str(e)}"
        print(f"❌ {error_msg}")
        result["error"] = error_msg

    finally:
        if ssh_client:
            ssh_client.close()
            print(f"🔒 Closed SSH connection to {machine_name}")

    return result

def save_execution_log(**context):
    """Save execution results to log file"""
    params = context['params']
    task_instance = context['task_instance']

    # Get results from previous task
    folder_result = task_instance.xcom_pull(task_ids='create_folder_on_machine')

    execution_data = {
        "execution_started_at": datetime.utcnow().isoformat() + "Z",
        "parameters": {
            "folder_name": params['folder_name'],
            "folder_path": params['folder_path']
        },
        "results": [folder_result] if folder_result else [],
        "execution_completed_at": datetime.utcnow().isoformat() + "Z"
    }

    # Ensure directory exists
    os.makedirs(REPOS_FILES_DIR, exist_ok=True)

    # Save execution log
    with open(EXECUTION_LOG, 'w') as f:
        json.dump(execution_data, f, indent=2)

    print(f"Execution log saved to {EXECUTION_LOG}")

    # Print summary
    if folder_result and folder_result.get('status') in ['created', 'already_exists']:
        print(f"✅ Task completed successfully: {folder_result['status']}")
    else:
        print(f"❌ Task failed: {folder_result.get('error', 'Unknown error') if folder_result else 'No result'}")

    return execution_data

# Define tasks
load_credentials_task = PythonOperator(
    task_id='load_ssh_credentials',
    python_callable=load_ssh_credentials,
    dag=dag
)

create_folder_task = PythonOperator(
    task_id='create_folder_on_machine',
    python_callable=create_folder_on_machine,
    dag=dag
)

save_log_task = PythonOperator(
    task_id='save_execution_log',
    python_callable=save_execution_log,
    dag=dag
)

# Set task dependencies
load_credentials_task >> create_folder_task >> save_log_task