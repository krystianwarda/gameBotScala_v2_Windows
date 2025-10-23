from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.utils.task_group import TaskGroup
from airflow.models.param import Param
from datetime import datetime, timedelta
import paramiko
import json

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 0,
    'retry_delay': timedelta(minutes=1)
}

dag = DAG(
    'pmachine_set_up_launch',
    default_args=default_args,
    description='Set up physical machines with files from GCP via SSH',
    schedule_interval=None,
    catchup=False,
    params={
        'target_machines': Param(
            default=[1, 2, 3, 4],
            type=['array', 'string'],
            description="List of physical machine numbers to target (e.g., [1,2] or comma-separated string '1,2'). Available: 1,2,3"
        ),
    }
)

SSH_CREDENTIALS_FILE = '/opt/airflow/files/ssh-credentials.json'
DEFAULT_BUCKET = 'gamebot-files-bucket'

def try_both_cmds(ssh, cmd1, cmd2):
    stdin, stdout, stderr = ssh.exec_command(cmd1)
    out = stdout.read().decode('cp1252', errors='replace')
    err = stderr.read().decode('cp1252', errors='replace')
    exit_status = stdout.channel.recv_exit_status()
    print(out)
    print(err)
    if exit_status == 0:
        return
    print("First command failed, trying fallback...")
    stdin, stdout, stderr = ssh.exec_command(cmd2)
    print(stdout.read().decode('cp1252', errors='replace'))
    print(stderr.read().decode('cp1252', errors='replace'))

def setup_physical_machine(machine_number, **context):
    params = context['params']
    target_machines = params.get('target_machines', [1, 2, 3,4])

    # Normalize param to list[int]
    if isinstance(target_machines, str):
        target_machines = [int(x.strip()) for x in target_machines.split(',') if x.strip()]
    elif target_machines and isinstance(target_machines[0], str):
        target_machines = [int(x) for x in target_machines]

    # Skip if not selected
    if machine_number not in target_machines:
        print(f"Skipping machine {machine_number} - not in target list: {target_machines}")
        return

    with open(SSH_CREDENTIALS_FILE, 'r') as f:
        credentials = json.load(f)

    machine_key = f"target_machine_{machine_number}"
    machine = next((m for m in credentials.get('machines', []) if m.get('name') == machine_key), None)
    if not machine:
        print(f"Machine {machine_key} not found in credentials.")
        return

    ip = (machine.get('ip') or '').strip()
    username = machine.get('username')
    password = machine.get('password')
    character_name = machine.get('character_name', f"machine_{machine_number}")

    sdk_base = fr'C:/Users/{username}/AppData/Local/Google/Cloud SDK/google-cloud-sdk/bin'
    gcloud_path = fr'"{sdk_base}/gcloud"'
    gsutil_path = fr'"{sdk_base}/gsutil.cmd"'

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(ip, username=username, password=password)

    print(f"Setting up physical machine {machine_number} ({character_name})")

    # Step 1: Copy game-bot-assembly-1.0.0.jar to C:/
    print("Step 1: Copying game-bot-assembly-1.0.0.jar to C:/")
    download_cmd1 = (
        fr'cmd /c "cd C:/ & '
        fr'{gcloud_path} auth activate-service-account --key-file=\"C:\gcp-key.json\" & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/game-bot-assembly-1.0.0.jar ."'
    )
    download_cmd2 = (
        fr'cd C:/ & '
        fr'{gcloud_path} auth activate-service-account --key-file=C:\gcp-key.json & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/game-bot-assembly-1.0.0.jar .'
    )
    try_both_cmds(ssh, download_cmd1, download_cmd2)

    # Step 2: Copy kafka-creds.json to C:/
    print("Step 2: Copying kafka-creds.json to C:/")
    download_cmd1 = (
        fr'cmd /c "cd C:/ & '
        fr'{gcloud_path} auth activate-service-account --key-file=\"C:\gcp-key.json\" & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/kafka-creds.json ."'
    )
    download_cmd2 = (
        fr'cd C:/ & '
        fr'{gcloud_path} auth activate-service-account --key-file=C:\gcp-key.json & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/kafka-creds.json .'
    )
    try_both_cmds(ssh, download_cmd1, download_cmd2)

    # Step 3: Copy character-specific files to autorun_settings
    autorun_path = r'C:/RealeraRelease_v1/autorun_settings/'
    print(f"Step 3: Copying character files for {character_name} to {autorun_path}")
    mkdir_cmd = fr'mkdir "{autorun_path}" 2>nul || echo Directory exists'
    ssh.exec_command(mkdir_cmd)

    # bot_settings.txt
    download_cmd1 = (
        fr'cmd /c "cd {autorun_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=\"C:\gcp-key.json\" & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/character_files/{character_name}/bot_settings.txt ."'
    )
    download_cmd2 = (
        fr'cd {autorun_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=C:\gcp-key.json & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/character_files/{character_name}/bot_settings.txt .'
    )
    try_both_cmds(ssh, download_cmd1, download_cmd2)

    # credentials.txt
    download_cmd1 = (
        fr'cmd /c "cd {autorun_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=\"C:\gcp-key.json\" & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/character_files/{character_name}/credentials.txt ."'
    )
    download_cmd2 = (
        fr'cd {autorun_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=C:\gcp-key.json & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/character_files/{character_name}/credentials.txt .'
    )
    try_both_cmds(ssh, download_cmd1, download_cmd2)

    # Step 4: Copy hotkeys and config files to Realera AppData
    realera_path = fr'C:\Users\{username}\AppData\Roaming\Realera\Realera/'
    print(f"Step 4: Copying hotkeys and config files for {character_name} to {realera_path}")
    mkdir_cmd = fr'mkdir "{realera_path}" 2>nul || echo Directory exists'
    ssh.exec_command(mkdir_cmd)

    # hotkeys_1.otml
    download_cmd1 = (
        fr'cmd /c "cd {realera_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=\"C:\gcp-key.json\" & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/character_files/{character_name}/hotkeys_1.otml ."'
    )
    download_cmd2 = (
        fr'cd {realera_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=C:\gcp-key.json & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/character_files/{character_name}/hotkeys_1.otml .'
    )
    try_both_cmds(ssh, download_cmd1, download_cmd2)

    # config.otml
    download_cmd1 = (
        fr'cmd /c "cd {realera_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=\"C:\gcp-key.json\" & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/character_files/{character_name}/config.otml ."'
    )
    download_cmd2 = (
        fr'cd {realera_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=C:\gcp-key.json & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/character_files/{character_name}/config.otml .'
    )
    try_both_cmds(ssh, download_cmd1, download_cmd2)

    # Step 5: Create machine_info.txt with machine number
    print(f"Step 5: Creating machine_info.txt with machine number {machine_number}")
    machine_info = json.dumps({"machine_number": machine_number})
    create_info_cmd = fr'echo {machine_info} > C:\machine_info.txt'
    ssh.exec_command(create_info_cmd)

    # Step 6: Copy appdata_package.zip, extract, and delete
    print(f"Step 6: Copying and extracting appdata_package.zip to {realera_path}")
    download_cmd1 = (
        fr'cmd /c "cd {realera_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=\"C:\gcp-key.json\" & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/appdata_package.zip ."'
    )
    download_cmd2 = (
        fr'cd {realera_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=C:\gcp-key.json & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/appdata_package.zip .'
    )
    try_both_cmds(ssh, download_cmd1, download_cmd2)

    extract_cmd1 = (
        fr'cd {realera_path} & '
        fr'powershell -Command "Expand-Archive -Path ''appdata_package.zip'' -DestinationPath . -Force"'
    )
    extract_cmd2 = (
        fr'cd {realera_path} ; '
        fr'powershell -Command "Expand-Archive -Path \"appdata_package.zip\" -DestinationPath . -Force" ; '
    )
    try_both_cmds(ssh, extract_cmd1, extract_cmd2)

    delete_cmd = fr'del "{realera_path}appdata_package.zip"'
    ssh.exec_command(delete_cmd)

    print(f"Setup completed for physical machine {machine_number} ({character_name})")
    ssh.close()

# Keep all machines 1..3 and skip internally if not selected
all_machines = [1, 2, 3, 4]

with TaskGroup("setup_per_machine", dag=dag) as tg:
    for m in all_machines:
        PythonOperator(
            task_id=f'setup_pmachine_launch_{m}',
            python_callable=setup_physical_machine,
            op_kwargs={'machine_number': m},
            dag=dag
        )

tg