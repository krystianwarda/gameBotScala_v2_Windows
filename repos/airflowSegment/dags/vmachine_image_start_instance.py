# python
from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.operators.bash import BashOperator
from airflow.models import Param
from datetime import datetime, timedelta
import subprocess
import json
import os
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

dag = DAG(
    'vmachine_image_start_instance',
    default_args=default_args,
    description='Create 1-4 Windows VMs from a saved machine image using gcloud/gsutil (no Terraform)',
    schedule_interval=None,
    catchup=False,
    params={
        "machine_count": Param(
            default=1,
            type="integer",
            minimum=1,
            maximum=4,
            description="Number of VM instances to create (1-4)"
        ),
        "character_name_1": Param(default="Player1", type="string", description="Character name for VM 1"),
        "character_name_2": Param(default="Player2", type="string", description="Character name for VM 2"),
        "character_name_3": Param(default="Player3", type="string", description="Character name for VM 3"),
        "character_name_4": Param(default="Player4", type="string", description="Character name for VM 4"),
        "base_image": Param(
            default="vm-gaming-bot-image-20250903-231202",
            type="string",
            description="Base GCP machine image name to use"
        ),
        "project_id": Param(default="gamebot-469621", type="string", description="GCP Project ID"),
        "region": Param(default="us-central1", type="string", description="GCP region")
    },
    tags=['vmachine', 'image', 'start_instance']
)

# Configuration
GOOGLE_CREDENTIALS = "/opt/airflow/terraformSegment/vmachine/gcp-key.json"
REPOS_FILES_DIR = "/opt/airflow/repos/airflowSegment/files"
CREDENTIALS_OUT = f"{REPOS_FILES_DIR}/vm-credentials.json"
DEPLOY_INFO = f"{REPOS_FILES_DIR}/vm-from-image-deployment.json"
BUCKET_INFO = "/opt/airflow/terraformSegment/vmachine/bucket-info.json"

ZONES_ROUND_ROBIN = ["us-central1-c"]

def _run(cmd, env=None, cwd=None, check=True, text=True, capture_output=True):
    return subprocess.run(cmd, env=env, cwd=cwd, check=check, text=text, capture_output=capture_output)

def _gcloud_auth(project_id: str):
    env = os.environ.copy()
    env['GOOGLE_APPLICATION_CREDENTIALS'] = GOOGLE_CREDENTIALS
    # Activate SA and set project (idempotent)
    _run(['gcloud', 'auth', 'activate-service-account', f'--key-file={GOOGLE_CREDENTIALS}'], env=env)
    _run(['gcloud', 'config', 'set', 'project', project_id], env=env)
    return env

def _sanitize_name(val: str) -> str:
    return ''.join(c.lower() for c in val if c.isalnum())

def prepare_deployment(**context):
    params = context['params']
    machine_count = int(params['machine_count'])
    base_image = params['base_image']
    project_id = params['project_id']
    region = params['region']

    os.makedirs(REPOS_FILES_DIR, exist_ok=True)

    vms = []
    for i in range(1, machine_count + 1):
        character = params.get(f'character_name_{i}', f'Player{i}')
        sanitized = _sanitize_name(character)
        vm_name = f"game-bot-vm-{sanitized}-{i}"
        zone = ZONES_ROUND_ROBIN[(i - 1) % len(ZONES_ROUND_ROBIN)]

        vms.append({
            "machine_number": i,
            "character_name": character,
            "vm_name": vm_name,
            "zone": zone
        })

    info = {
        "project_id": project_id,
        "region": region,
        "base_image": base_image,
        "machine_count": machine_count,
        "vms": vms,
        "prepared_at": datetime.utcnow().isoformat() + "Z"
    }

    with open(DEPLOY_INFO, 'w') as f:
        json.dump(info, f, indent=2)

    print(f"Prepared deployment for {machine_count} VM(s) from image '{base_image}'.")
    return info

def create_instances(**context):
    with open(DEPLOY_INFO, 'r') as f:
        info = json.load(f)

    project_id = info['project_id']
    base_image = info['base_image']
    env = _gcloud_auth(project_id)

    results = []
    for vm in info['vms']:
        name = vm['vm_name']
        zone = vm['zone']

        print(f"Creating instance {name} in {zone} from image {base_image}...")
        # Create instance from machine image
        create_cmd = [
            'gcloud', 'compute', 'instances', 'create', name,
            f'--source-machine-image={base_image}',
            f'--zone={zone}',
            '--quiet'
        ]
        # If you need specific network tags or service account scopes, add them here.

        try:
            _run(create_cmd, env=env)
        except subprocess.CalledProcessError as e:
            print(f"Failed to create {name}: {e.stderr}")
            vm['creation_failed'] = True
            results.append(vm)
            continue

        # Fetch external IP
        ip_cmd = [
            'gcloud', 'compute', 'instances', 'describe', name,
            f'--zone={zone}',
            '--format=get(networkInterfaces[0].accessConfigs[0].natIP)'
        ]
        ip = ""
        try:
            r = _run(ip_cmd, env=env)
            ip = (r.stdout or "").strip()
        except subprocess.CalledProcessError as e:
            print(f"Warning: could not get external IP for {name}: {e.stderr}")

        vm['vm_external_ip'] = ip
        vm['created_at'] = datetime.utcnow().isoformat() + "Z"
        results.append(vm)
        print(f"Instance {name} created with IP: {ip}")

    info['created_vms'] = results
    info['create_completed_at'] = datetime.utcnow().isoformat() + "Z"
    with open(DEPLOY_INFO, 'w') as f:
        json.dump(info, f, indent=2)

    # Do not fail the whole task if some instances failed; proceed with what succeeded
    return results

def generate_windows_credentials(**context):
    with open(DEPLOY_INFO, 'r') as f:
        info = json.load(f)

    project_id = info['project_id']
    env = _gcloud_auth(project_id)

    creds = []
    for vm in info.get('created_vms', []):
        if vm.get('creation_failed'):
            print(f"Skipping credentials for failed VM {vm['vm_name']}")
            continue

        name = vm['vm_name']
        zone = vm['zone']
        username = f"botadmin{vm['machine_number']}"

        # Try reset-windows-password with retries (instance may need a short time)
        max_retries = 10
        delay = 15
        pwd_json = None

        for attempt in range(1, max_retries + 1):
            try:
                cmd = [
                    'gcloud', 'compute', 'reset-windows-password', name,
                    f'--zone={zone}',
                    f'--user={username}',
                    '--quiet',
                    '--format=json'
                ]
                r = _run(cmd, env=env)
                pwd_json = json.loads(r.stdout)
                break
            except subprocess.CalledProcessError as e:
                print(f"[{name}] Attempt {attempt}/{max_retries} to set password failed: {e.stderr}")
                if attempt < max_retries:
                    time.sleep(delay)
                else:
                    print(f"[{name}] Giving up on password generation.")
        
        if not pwd_json:
            vm_entry = {
                "machine_number": vm['machine_number'],
                "character_name": vm['character_name'],
                "vm_name": name,
                "zone": zone,
                "vm_external_ip": vm.get('vm_external_ip', ''),
                "windows_username": username,
                "windows_password": None,
                "error": "password_generation_failed"
            }
        else:
            vm_entry = {
                "machine_number": vm['machine_number'],
                "character_name": vm['character_name'],
                "vm_name": name,
                "zone": zone,
                "vm_external_ip": vm.get('vm_external_ip', ''),
                "windows_username": pwd_json.get('username', username),
                "windows_password": pwd_json.get('password', None)
            }

        creds.append(vm_entry)

    os.makedirs(REPOS_FILES_DIR, exist_ok=True)
    with open(CREDENTIALS_OUT, 'w') as f:
        json.dump({
            "project_id": info['project_id'],
            "base_image": info['base_image'],
            "generated_at": datetime.utcnow().isoformat() + "Z",
            "credentials": creds
        }, f, indent=2)

    print(f"Wrote credentials for {len(creds)} VM(s) to {CREDENTIALS_OUT}")
    return creds

def detect_bucket():
    if not os.path.exists(BUCKET_INFO):
        return None
    try:
        with open(BUCKET_INFO, 'r') as f:
            data = json.load(f)
        # Use 'bucket_name' if present; otherwise return None
        return data.get('bucket_name')
    except Exception:
        return None

def write_bucket_env():
    bucket = detect_bucket()
    if bucket:
        # Exported as a small file to be sourced by BashOperator
        path = f"{REPOS_FILES_DIR}/bucket.env"
        with open(path, 'w') as f:
            f.write(f"BUCKET_NAME={bucket}\n")
        print(f"Detected bucket '{bucket}'. Wrote {path}.")
        return path
    print("No bucket-info.json found or missing bucket_name. Skipping gsutil upload step.")
    return None

prepare = PythonOperator(
    task_id='prepare_deployment',
    python_callable=prepare_deployment,
    dag=dag
)

create = PythonOperator(
    task_id='create_instances_from_image',
    python_callable=create_instances,
    dag=dag
)

gen_creds = PythonOperator(
    task_id='generate_windows_credentials_and_save',
    python_callable=generate_windows_credentials,
    dag=dag
)

bucket_env_task = PythonOperator(
    task_id='detect_and_write_bucket_env',
    python_callable=write_bucket_env,
    dag=dag
)

upload_to_gcs = BashOperator(
    task_id='optional_upload_credentials_to_gcs',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS="{GOOGLE_CREDENTIALS}"
        # If bucket.env exists, source it and upload; otherwise, no-op
        if [ -f "{REPOS_FILES_DIR}/bucket.env" ]; then
          . "{REPOS_FILES_DIR}/bucket.env"
          if [ -n "${{BUCKET_NAME:-}}" ]; then
            echo "Uploading credentials to gs://$BUCKET_NAME/airflow/vm-credentials.json"
            gsutil cp "{CREDENTIALS_OUT}" "gs://$BUCKET_NAME/airflow/vm-credentials.json"
          else
            echo "BUCKET_NAME not set. Skipping upload."
          fi
        else
          echo "No bucket.env present. Skipping upload."
        fi
    """,
    dag=dag
)

prepare >> create >> gen_creds >> bucket_env_task >> upload_to_gcs