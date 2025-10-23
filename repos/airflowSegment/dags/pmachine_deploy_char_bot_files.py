from airflow import DAG
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import paramiko
import json
import os

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 0,
    'retry_delay': timedelta(minutes=1)
}

dag = DAG(
    'pmachine_gcloud_download_character_settings',
    default_args=default_args,
    description='Download character bot_settings.txt to selected machine via SSH',
    schedule_interval=None,
    catchup=False,
    params={
        'target_machines': ['target_machine_2'],
        'character_name': 'Blazeorc',  # Default character
        'destination_path': 'C:/RealeraRelease_v1/autorun_settings/'
    }
)

SSH_CREDENTIALS_FILE = '/opt/airflow/files/ssh-credentials.json'
DEFAULT_BUCKET = 'gamebot-files-bucket'
GAME_CREDENTIALS_FILE = '/opt/airflow/files/game-credentials.json'


def run_character_settings_download(**context):
    params = context['params']
    target_machines = params.get('target_machines', ['target_machine_1'])
    character_name = params.get('character_name', 'Blazeorc')
    destination_path = params.get('destination_path', 'C:/RealeraRelease_v1/autorun_settings/')

    gcp_bucket_file = f'character_files/{character_name}/bot_settings.txt'

    with open(SSH_CREDENTIALS_FILE, 'r') as f:
        credentials = json.load(f)
    with open(GAME_CREDENTIALS_FILE, 'r') as f:
        game_creds = json.load(f)

    char_cred = next((a for a in game_creds['accounts'] if a['name'] == character_name), None)
    if not char_cred:
        print(f"Character {character_name} not found in game credentials.")
        return
    cred_line = f"{char_cred['account_number']} {char_cred['password']}"

    for machine_name in target_machines:
        machine = next((m for m in credentials.get('machines', []) if m['name'] == machine_name), None)
        if not machine:
            print(f"Machine {machine_name} not found in credentials.")
            continue

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

        # Download bot_settings.txt
        stdin, stdout, stderr = ssh.exec_command(download_cmd1)
        out = stdout.read().decode('cp1252', errors='replace')
        err = stderr.read().decode('cp1252', errors='replace')
        exit_status = stdout.channel.recv_exit_status()
        print(out)
        print(err)
        if exit_status != 0:
            print("First command failed, trying fallback...")
            stdin, stdout, stderr = ssh.exec_command(download_cmd2)
            print(stdout.read().decode('cp1252', errors='replace'))
            print(stderr.read().decode('cp1252', errors='replace'))

        # Write credentials.txt
        cred_file_path = os.path.join(destination_path, 'credentials.txt')
        # Use echo to write the credentials (overwrite)
        echo_cmd = fr'echo {cred_line} > "{cred_file_path}"'
        stdin, stdout, stderr = ssh.exec_command(echo_cmd)
        print(stdout.read().decode('cp1252', errors='replace'))
        print(stderr.read().decode('cp1252', errors='replace'))

        ssh.close()

download_character_settings = PythonOperator(
    task_id='run_character_settings_download',
    python_callable=run_character_settings_download,
    dag=dag
)

download_character_settings