from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.utils.task_group import TaskGroup
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
    'pmachine_gcloud_download_jar',
    default_args=default_args,
    description='Download GCP file to selected machines via SSH',
    schedule_interval=None,
    catchup=False,
    params={
        'target_machines': ['target_machine_1','target_machine_2','target_machine_3', 'target_machine_4'],
        'gcp_bucket_file': 'game-bot-assembly-1.0.0.jar',
        'destination_path': 'C:/'
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

def run_gcloud_commands(machine_name, **context):
    params = context['params']
    gcp_bucket_file = params.get('gcp_bucket_file', 'game-bot-assembly-1.0.0.jar')
    destination_path = params.get('destination_path', 'C:/')

    with open(SSH_CREDENTIALS_FILE, 'r') as f:
        credentials = json.load(f)

    is_zip = gcp_bucket_file.lower().endswith('.zip')

    machine = next((m for m in credentials.get('machines', []) if m['name'] == machine_name), None)
    if not machine:
        print(f"Machine {machine_name} not found in credentials.")
        return

    ip = machine['ip']
    username = machine['username']
    password = machine['password']

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

machines = dag.params['target_machines']
if isinstance(machines, str):
    machines = [m.strip() for m in machines.split(',')]

with TaskGroup("download_per_machine", dag=dag) as tg:
    for machine in machines:
        PythonOperator(
            task_id=f'run_gcloud_commands_{machine}',
            python_callable=run_gcloud_commands,
            op_kwargs={'machine_name': machine},
            dag=dag
        )

tg