from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.utils.task_group import TaskGroup
from datetime import datetime, timedelta
import paramiko
import json
from airflow.models.param import Param

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 0,
    'retry_delay': timedelta(minutes=1)
}

dag = DAG(
    'vmachine_image_gcloud_set_up_launch',
    default_args=default_args,
    description='Set up GCP VMs with multiple files and configurations via SSH',
    schedule_interval=None,
    catchup=False,
    params={
        'target_vms': Param(
            default=[1,2,3,4],
            type=['array', 'string'],
            description="List of VM numbers to target (e.g., [1,2] or comma-separated string '1,2'). Available VMs: 1=Blazeorc, 2=Nova"
        ),
    }
)

VM_CREDENTIALS_FILE = '/opt/airflow/files/vm-credentials.json'
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

def setup_vm_launch(machine_number, **context):
    params = context['params']
    target_vms = params.get('target_vms', [1,2,3,4])

    # Convert target_vms to integers if they're strings
    if target_vms and isinstance(target_vms[0], str):
        target_vms = [int(vm) for vm in target_vms]

    # Skip if this VM is not in the target list
    if machine_number not in target_vms:
        print(f"Skipping VM {machine_number} - not in target list: {target_vms}")
        return

    with open(VM_CREDENTIALS_FILE, 'r') as f:
        vm_data = json.load(f)

    vm = next((v for v in vm_data.get('credentials', []) if v['machine_number'] == machine_number), None)
    if not vm:
        print(f"VM {machine_number} not found in credentials.")
        return

    ip = vm['vm_external_ip']
    username = vm['windows_username']
    password = vm['windows_password']
    character_name = vm['character_name']

    sdk_base = fr'C:/Users/{username}/AppData/Local/Google/Cloud SDK/google-cloud-sdk/bin'
    gcloud_path = fr'"{sdk_base}/gcloud"'
    gsutil_path = fr'"{sdk_base}/gsutil.cmd"'

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(ip, username=username, password=password)

    print(f"Setting up VM {machine_number} ({character_name})")

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

    # Create directory if it doesn't exist
    mkdir_cmd = fr'mkdir "{autorun_path}" 2>nul || echo Directory exists'
    ssh.exec_command(mkdir_cmd)

    # Copy bot_settings.txt
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

    # Copy credentials.txt
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

    # Create directory if it doesn't exist
    mkdir_cmd = fr'mkdir "{realera_path}" 2>nul || echo Directory exists'
    ssh.exec_command(mkdir_cmd)

    # Copy hotkeys_1.otml
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

    # Copy config.otml
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
    print(f"Step 6: Creating machine_info.txt with machine number {machine_number}")
    machine_info = json.dumps({"machine_number": machine_number})
    create_info_cmd = (
        fr'echo {machine_info} > C:\machine_info.txt'
    )
    ssh.exec_command(create_info_cmd)

    # Step 6: Copy appdata_package.zip, extract, and delete
    print(f"Step 5: Copying and extracting appdata_package.zip to {realera_path}")

    # Copy appdata_package.zip
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

    # Extract zip file
    extract_cmd1 = (
        fr'cd {realera_path} & '
        fr'powershell -Command "Expand-Archive -Path ''appdata_package.zip'' -DestinationPath . -Force"'
    )
    extract_cmd2 = (
        fr'cd {realera_path} ; '
        fr'powershell -Command "Expand-Archive -Path \"appdata_package.zip\" -DestinationPath . -Force" ; '
    )
    try_both_cmds(ssh, extract_cmd1, extract_cmd2)

    # Delete zip file
    delete_cmd = fr'del "{realera_path}appdata_package.zip"'
    ssh.exec_command(delete_cmd)

    print(f"Setup completed for VM {machine_number} ({character_name})")
    ssh.close()

# Keep all VMs in the TaskGroup but skip execution for non-selected ones
vms = [1, 2, 3, 4]  # All possible VMs

with TaskGroup("setup_per_vm", dag=dag) as tg:
    for vm in vms:
        PythonOperator(
            task_id=f'setup_vm_launch_{vm}',
            python_callable=setup_vm_launch,
            op_kwargs={'machine_number': vm},
            dag=dag
        )

tg