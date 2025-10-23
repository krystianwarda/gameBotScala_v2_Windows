from airflow import DAG
from airflow.operators.python import PythonOperator, BranchPythonOperator
from airflow.operators.empty import EmptyOperator
from airflow.utils.task_group import TaskGroup
from airflow.models import Param
from datetime import datetime, timedelta
import subprocess
import json
import os
import time
import winrm

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
    'vmachine_run_game_and_bot_only',
    default_args=default_args,
    description='Connect to running VMs, start game, wait 5 seconds, and run bot - NO FILE DOWNLOADS',
    schedule_interval=None,
    catchup=False,
    params={
        "target_vms": Param(
            default=[1,2,3,4],
            type=["array", "string"],
            description="List of VM numbers to target (e.g., [1,2] or comma-separated string '1,2')"
        ),
        "game_executable": Param(
            default="C:\\RealeraRelease_v1\\RealeraRelease_v1\\RealeraDX.exe",
            type="string",
            description="Path to game executable on VM"
        ),
        "bot_jar": Param(
            default="C:\\game-bot-assembly-1.0.0.jar",
            type="string",
            description="Path to bot JAR file on VM"
        ),
        "wait_time": Param(
            default=5,
            type="integer",
            minimum=1,
            maximum=60,
            description="Wait time in seconds between game start and bot start"
        )
    },
    tags=['vmachine', 'image', 'game', 'bot', 'no-download']
)

# Configuration
GOOGLE_CREDENTIALS = "/opt/airflow/terraformSegment/vmachine/gcp-key.json"
REPOS_FILES_DIR = "/opt/airflow/repos/airflowSegment/files"
LOCAL_CREDENTIALS = f"{REPOS_FILES_DIR}/vm-credentials-downloaded.json"
EXECUTION_LOG = f"{REPOS_FILES_DIR}/game-bot-execution.json"

def _run(cmd, env=None, cwd=None, check=True, text=True, capture_output=True):
    return subprocess.run(cmd, env=env, cwd=cwd, check=check, text=text, capture_output=capture_output)

def _gcloud_auth():
    env = os.environ.copy()
    env['GOOGLE_APPLICATION_CREDENTIALS'] = GOOGLE_CREDENTIALS
    _run(['gcloud', 'auth', 'activate-service-account', f'--key-file={GOOGLE_CREDENTIALS}'], env=env)
    return env

def download_vm_credentials(**context):
    """Download VM credentials from GCS"""
    env = _gcloud_auth()
    os.makedirs(REPOS_FILES_DIR, exist_ok=True)

    try:
        download_cmd = [
            'gsutil', 'cp',
            'gs://gamebot-files-bucket/airflow/vm-credentials.json',
            LOCAL_CREDENTIALS
        ]
        _run(download_cmd, env=env)

        with open(LOCAL_CREDENTIALS, 'r') as f:
            credentials = json.load(f)

        print(f"Downloaded credentials for {len(credentials.get('credentials', []))} VM(s)")
        return credentials

    except Exception as e:
        print(f"Failed to download VM credentials: {e}")
        raise

def start_game(machine_number, **context):
    """Start the game on a specific VM using WinRM and scheduled tasks"""
    params = context['params']
    target_vms = params.get('target_vms', [1,2,3,4])

    # Convert target_vms to integers if they're strings
    if isinstance(target_vms, str):
        target_vms = [int(vm.strip()) for vm in target_vms.split(',')]
    elif target_vms and isinstance(target_vms[0], str):
        target_vms = [int(vm) for vm in target_vms]

    # Skip if this VM is not in the target list
    if machine_number not in target_vms:
        print(f"Skipping VM {machine_number} - not in target list: {target_vms}")
        return {"machine_number": machine_number, "status": "skipped", "reason": "not_in_target_list"}

    game_executable = params['game_executable']

    with open(LOCAL_CREDENTIALS, 'r') as f:
        creds_data = json.load(f)

    vm = next((v for v in creds_data.get('credentials', []) if v['machine_number'] == machine_number), None)

    if not vm:
        raise ValueError(f"No VM found for: {machine_number}")

    if vm.get('windows_password') is None:
        print(f"Skipping {machine_number} - no password available")
        return {"machine_number": machine_number, "status": "skipped", "reason": "no_password"}

    vm_ip = vm['vm_external_ip']
    username = vm['windows_username']
    password = vm['windows_password']

    try:
        session = winrm.Session(
            f"http://{vm_ip}:5985/wsman",
            auth=(username, password),
            transport='basic'
        )

        print(f"✅ Connected to {machine_number} ({vm_ip}) via WinRM")

        check_game = f'if (Test-Path "{game_executable}") {{ "EXISTS" }} else {{ "NOT_FOUND" }}'
        result = session.run_ps(check_game)
        output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

        if "NOT_FOUND" in output:
            print(f"❌ Game executable not found at {game_executable} on {machine_number}")
            return {
                "machine_number": machine_number,
                "vm_ip": vm_ip,
                "character_name": vm['character_name'],
                "action": "start_game",
                "status": "failed",
                "error": f"Game executable not found at {game_executable}",
                "timestamp": datetime.utcnow().isoformat() + "Z"
            }

        print(f"✅ Game executable found on {machine_number}")

        task_name = "LaunchRealeraDX"
        delete_task_command = f'schtasks /delete /tn "{task_name}" /f'
        session.run_cmd(delete_task_command)

        create_task_command = f'''schtasks /create /tn "{task_name}" /tr "{game_executable}" /sc once /st 00:00 /it /ru "{username}" /rp "{password}" /rl highest'''
        result = session.run_cmd(create_task_command)
        if result.status_code != 0:
            raise Exception("Failed to create scheduled task")

        print(f"✅ Scheduled task created on {machine_number}")

        run_task_command = f'schtasks /run /tn "{task_name}"'
        result = session.run_cmd(run_task_command)

        if result.status_code == 0:
            print(f"✅ Game launch task executed on {machine_number}")

            time.sleep(10)

            check_process = 'Get-Process -Name "RealeraDX" -ErrorAction SilentlyContinue | Select-Object -Property Name, Id'
            result = session.run_ps(check_process)
            output = result.std_out.decode('utf-8') if isinstance(result.std_out, bytes) else result.std_out

            if "RealeraDX" in output:
                print(f"✅ Game is confirmed running on {machine_number}!")
                status = "executed"
                error = None
            else:
                print(f"⚠️ Game process not detected on {machine_number}, but launch command was executed")
                status = "executed"
                error = None
        else:
            raise Exception(f"Failed to run scheduled task with status: {result.status_code}")

        return {
            "machine_number": machine_number,
            "vm_ip": vm_ip,
            "character_name": vm['character_name'],
            "action": "start_game",
            "status": status,
            "error": error,
            "timestamp": datetime.utcnow().isoformat() + "Z"
        }

    except Exception as e:
        print(f"❌ Failed to start game on {machine_number}: {e}")
        return {
            "machine_number": machine_number,
            "vm_ip": vm_ip,
            "character_name": vm['character_name'],
            "action": "start_game",
            "status": "failed",
            "error": str(e),
            "timestamp": datetime.utcnow().isoformat() + "Z"
        }

def start_bot(machine_number, **context):
    """Start the bot JAR on a specific VM"""
    params = context['params']
    target_vms = params.get('target_vms', [1,2,3,4])

    # Convert target_vms to integers if they're strings
    if isinstance(target_vms, str):
        target_vms = [int(vm.strip()) for vm in target_vms.split(',')]
    elif target_vms and isinstance(target_vms[0], str):
        target_vms = [int(vm) for vm in target_vms]

    # Skip if this VM is not in the target list
    if machine_number not in target_vms:
        print(f"Skipping VM {machine_number} - not in target list: {target_vms}")
        return {"machine_number": machine_number, "status": "skipped", "reason": "not_in_target_list"}

    bot_jar = params['bot_jar']

    with open(LOCAL_CREDENTIALS, 'r') as f:
        creds_data = json.load(f)

    vm = next((v for v in creds_data.get('credentials', []) if v['machine_number'] == machine_number), None)

    if not vm or vm.get('windows_password') is None:
        print(f"Skipping {machine_number} - credentials not available")
        return {"machine_number": machine_number, "status": "skipped", "reason": "no_credentials"}

    vm_ip = vm['vm_external_ip']
    username = vm['windows_username']
    password = vm['windows_password']

    try:
        session = winrm.Session(
            f"http://{vm_ip}:5985/wsman",
            auth=(username, password),
            transport='basic'
        )

        print(f"✅ Connected to {machine_number} ({vm_ip}) via WinRM for bot launch")

        java_check = 'java -version'
        result = session.run_cmd(java_check)

        if result.status_code != 0:
            raise Exception("Java is not installed or not in PATH on the VM")

        print(f"✅ Java is available on {machine_number}")

        jar_check = f'if (Test-Path "{bot_jar}") {{ "EXISTS" }} else {{ "NOT_FOUND" }}'
        result = session.run_ps(jar_check)
        output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

        if "NOT_FOUND" in output:
            raise Exception(f"JAR file not found at {bot_jar}")

        print(f"✅ JAR file found on {machine_number}")

        print(f"🔄 Terminating any existing game-bot processes on {machine_number}...")
        kill_existing = 'Get-Process java -ErrorAction SilentlyContinue | Where-Object { (Get-WmiObject Win32_Process -Filter "ProcessId = $($_.Id)").CommandLine -like "*game-bot-assembly*" } | Stop-Process -Force'
        session.run_ps(kill_existing)

        task_name = "LaunchGameBotJar"
        delete_task_command = f'schtasks /delete /tn "{task_name}" /f'
        session.run_cmd(delete_task_command)

        jar_filename = bot_jar.split("\\")[-1]

        batch_content = f'''@echo off
cd /d C:\\
java -jar {jar_filename}
'''

        write_batch = f'''
$batchContent = @"
{batch_content}
"@
Set-Content -Path "C:\\run_game_bot.bat" -Value $batchContent
'''

        result = session.run_ps(write_batch)
        if result.status_code != 0:
            raise Exception("Failed to create batch file")

        print(f"✅ Batch file created on {machine_number}")

        create_task_command = f'''schtasks /create /tn "{task_name}" /tr "C:\\run_game_bot.bat" /sc once /st 00:00 /it /ru "{username}" /rp "{password}" /rl highest'''

        result = session.run_cmd(create_task_command)
        if result.status_code != 0:
            error_output = result.std_err.decode('utf-8') if isinstance(result.std_err, bytes) else result.std_err
            raise Exception(f"Failed to create scheduled task: {error_output}")

        print(f"✅ Scheduled task created on {machine_number}")

        run_task_command = f'schtasks /run /tn "{task_name}"'
        result = session.run_cmd(run_task_command)

        if result.status_code == 0:
            print(f"✅ Game-bot JAR launch task executed on {machine_number}")

            time.sleep(10)

            check_process = '''
Get-Process java -ErrorAction SilentlyContinue | ForEach-Object {
    $cmdLine = (Get-WmiObject Win32_Process -Filter "ProcessId = $($_.Id)").CommandLine
    if ($cmdLine -like "*game-bot-assembly*") {
        [PSCustomObject]@{
            ProcessName = $_.ProcessName
            Id = $_.Id
            CommandLine = $cmdLine
        }
    }
}
'''

            result = session.run_ps(check_process)

            if result.status_code == 0:
                output = result.std_out.decode('utf-8') if isinstance(result.std_out, bytes) else result.std_out
                if "java" in output and "game-bot-assembly" in output:
                    print(f"✅ Game-bot JAR process confirmed running on {machine_number}")
                    status = "executed"
                    error = None
                else:
                    print(f"⚠️ Unable to verify bot process on {machine_number}, but launch command was executed")
                    status = "executed"
                    error = None
            else:
                print(f"⚠️ Unable to verify process status on {machine_number}, but launch command was executed")
                status = "executed"
                error = None
        else:
            error_output = result.std_err.decode('utf-8') if isinstance(result.std_err, bytes) else result.std_err
            raise Exception(f"Failed to run scheduled task with status: {result.status_code}: {error_output}")

        return {
            "machine_number": machine_number,
            "vm_ip": vm_ip,
            "character_name": vm['character_name'],
            "action": "start_bot",
            "status": status,
            "error": error,
            "timestamp": datetime.utcnow().isoformat() + "Z"
        }

    except Exception as e:
        print(f"❌ Failed to start bot on {machine_number}: {e}")
        return {
            "machine_number": machine_number,
            "vm_ip": vm_ip,
            "character_name": vm['character_name'],
            "action": "start_bot",
            "status": "failed",
            "error": str(e),
            "timestamp": datetime.utcnow().isoformat() + "Z"
        }

def wait_before_bot(**context):
    """Wait specified time before starting bots"""
    params = context['params']
    wait_time = params['wait_time']

    print(f"Waiting {wait_time} seconds before starting bots...")
    time.sleep(wait_time)

    return f"Waited {wait_time} seconds"

def upload_execution_log(**context):
    """Upload execution log to GCS"""
    env = _gcloud_auth()

    try:
        upload_cmd = [
            'gsutil', 'cp',
            EXECUTION_LOG,
            'gs://gamebot-files-bucket/airflow/game-bot-execution-log.json'
        ]
        _run(upload_cmd, env=env)
        print("Uploaded execution log to GCS")
    except Exception as e:
        print(f"Failed to upload execution log: {e}")

# Initial task
download_creds_task = PythonOperator(
    task_id='download_vm_credentials',
    python_callable=download_vm_credentials,
    dag=dag
)

# Wait task
wait_task = PythonOperator(
    task_id='wait_before_starting_bots',
    python_callable=wait_before_bot,
    dag=dag
)

upload_log_task = PythonOperator(
    task_id='upload_execution_log',
    python_callable=upload_execution_log,
    dag=dag
)

# Static tasks for all possible VMs - simplifies branching
all_vms = [1, 2, 3, 4]

with TaskGroup("start_games_per_vm", dag=dag) as tg_game:
    for vm in all_vms:
        PythonOperator(
            task_id=f'start_game_{vm}',
            python_callable=start_game,
            op_kwargs={'machine_number': vm},
            dag=dag,
        )

with TaskGroup("start_bots_per_vm", dag=dag) as tg_bot:
    for vm in all_vms:
        PythonOperator(
            task_id=f'start_bot_{vm}',
            python_callable=start_bot,
            op_kwargs={'machine_number': vm},
            dag=dag,
        )

# Define task dependencies
download_creds_task >> tg_game >> wait_task >> tg_bot >> upload_log_task