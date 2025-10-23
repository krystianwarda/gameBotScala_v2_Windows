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
    'vmachine_image_gcloud_download_file',
    default_args=default_args,
    description='Download GCP file to GCP VMs via SSH',
    schedule_interval=None,
    catchup=False,
    params={
        'target_vms': Param(
            default=[1,2,3,4],
            type=['array', 'string'],
            description="List of VM numbers to target (e.g., [1,2] or comma-separated string '1,2'). Available VMs: 1=Blazeorc, 2=Nova"
        ),
        'gcp_bucket_file': Param(
            default='game-bot-assembly-1.0.0.jar',
            type='string',
            description="appdata_package.zip or kafka-creds.json"
        ),
        'destination_path': Param(
            default='C:/',
            type='string',
            description="C:/Users/gamebot/AppData/Roaming/Realera/Realera/   or    C:/RealeraRelease_v1/autorun_settings"
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

def run_gcloud_commands_vm(machine_number, **context):
    params = context['params']
    target_vms = params.get('target_vms', [1,2,3,4])

    # Convert target_vms to integers if they're strings
    if target_vms and isinstance(target_vms[0], str):
        target_vms = [int(vm) for vm in target_vms]

    # Skip if this VM is not in the target list
    if machine_number not in target_vms:
        print(f"Skipping VM {machine_number} - not in target list: {target_vms}")
        return

    gcp_bucket_file = params.get('gcp_bucket_file', 'game-bot-assembly-1.0.0.jar')
    destination_path = params.get('destination_path', 'C:/')

    with open(VM_CREDENTIALS_FILE, 'r') as f:
        vm_data = json.load(f)

    is_zip = gcp_bucket_file.lower().endswith('.zip')

    vm = next((v for v in vm_data.get('credentials', []) if v['machine_number'] == machine_number), None)
    if not vm:
        print(f"VM {machine_number} not found in credentials.")
        return

    ip = vm['vm_external_ip']
    username = vm['windows_username']
    password = vm['windows_password']

    sdk_base = fr'C:/Users/{username}/AppData/Local/Google/Cloud SDK/google-cloud-sdk/bin'
    gcloud_path = fr'"{sdk_base}/gcloud"'
    gsutil_path = fr'"{sdk_base}/gsutil.cmd"'

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(ip, username=username, password=password)

    download_cmd1 = (
        fr'cmd /c "cd {destination_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=\"C:\gcp-key.json\" & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/{gcp_bucket_file} ."'
    )
    download_cmd2 = (
        fr'cd {destination_path} & '
        fr'{gcloud_path} auth activate-service-account --key-file=C:\gcp-key.json & '
        fr'{gsutil_path} cp gs://{DEFAULT_BUCKET}/{gcp_bucket_file} .'
    )
    try_both_cmds(ssh, download_cmd1, download_cmd2)

    if is_zip:
        extract_cmd1 = (
            fr'cd {destination_path} & '
            fr'powershell -Command "Expand-Archive -Path ''{gcp_bucket_file}'' -DestinationPath . -Force"'
        )
        extract_cmd2 = (
            fr'cd {destination_path} ; '
            fr'powershell -Command "Expand-Archive -Path \"{gcp_bucket_file}\" -DestinationPath . -Force" ; '
        )
        try_both_cmds(ssh, extract_cmd1, extract_cmd2)

    ssh.close()

# Keep all VMs in the TaskGroup but skip execution for non-selected ones
vms = [1, 2, 3, 4]  # All possible VMs

with TaskGroup("download_per_vm", dag=dag) as tg:
    for vm in vms:
        PythonOperator(
            task_id=f'run_gcloud_commands_{vm}',
            python_callable=run_gcloud_commands_vm,
            op_kwargs={'machine_number': vm},
            dag=dag
        )

tg