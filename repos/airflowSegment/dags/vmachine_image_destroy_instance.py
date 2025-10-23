from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.operators.bash import BashOperator
from airflow.models import Param
from datetime import datetime, timedelta
import subprocess
import json
import os

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
    'vmachine_destroy_from_image',
    default_args=default_args,
    description='Stop and delete ALL Windows VMs created from machine image using gcloud/gsutil',
    schedule_interval=None,
    catchup=False,
    params={
        "project_id": Param(default="gamebot-469621", type="string", description="GCP Project ID"),
        "force_delete": Param(
            default=False,
            type="boolean",
            description="Force delete VMs even if some operations fail"
        )
    },
    tags=['vmachine', 'image', 'destroy_instance']
)

# Configuration
GOOGLE_CREDENTIALS = "/opt/airflow/terraformSegment/vmachine/gcp-key.json"
REPOS_FILES_DIR = "/opt/airflow/repos/airflowSegment/files"
CREDENTIALS_FILE = f"{REPOS_FILES_DIR}/vm-credentials.json"
DEPLOY_INFO = f"{REPOS_FILES_DIR}/vm-from-image-deployment.json"
BUCKET_INFO = "/opt/airflow/terraformSegment/vmachine/bucket-info.json"
CLEANUP_LOG = f"{REPOS_FILES_DIR}/vm-cleanup.json"

def _run(cmd, env=None, cwd=None, check=True, text=True, capture_output=True):
    return subprocess.run(cmd, env=env, cwd=cwd, check=check, text=text, capture_output=capture_output)

def _gcloud_auth(project_id: str):
    env = os.environ.copy()
    env['GOOGLE_APPLICATION_CREDENTIALS'] = GOOGLE_CREDENTIALS
    _run(['gcloud', 'auth', 'activate-service-account', f'--key-file={GOOGLE_CREDENTIALS}'], env=env)
    _run(['gcloud', 'config', 'set', 'project', project_id], env=env)
    return env

def discover_all_vms(**context):
    params = context['params']
    project_id = params['project_id']

    env = _gcloud_auth(project_id)

    # Discover ALL VMs that match our naming pattern (game-bot-vm-*)
    discover_cmd = [
        'gcloud', 'compute', 'instances', 'list',
        '--filter=name~"^game-bot-vm-.*"',
        '--format=json'
    ]

    try:
        result = _run(discover_cmd, env=env)
        all_vms = json.loads(result.stdout)
    except subprocess.CalledProcessError as e:
        print(f"Failed to discover VMs: {e.stderr}")
        all_vms = []

    vms_to_delete = []
    for vm in all_vms:
        vm_name = vm['name']
        zone = vm['zone'].split('/')[-1]  # Extract zone name from full URL
        status = vm['status']

        vms_to_delete.append({
            "vm_name": vm_name,
            "zone": zone,
            "status": status,
            "discovered": True
        })

    # Also check deployment info if it exists for additional context
    deployment_vms = []
    if os.path.exists(DEPLOY_INFO):
        try:
            with open(DEPLOY_INFO, 'r') as f:
                deploy_info = json.load(f)
            deployment_vms = deploy_info.get('created_vms', [])
        except Exception as e:
            print(f"Warning: Could not read deployment info: {e}")
            deploy_info = {}
    else:
        deploy_info = {}

    cleanup_info = {
        "project_id": project_id,
        "cleanup_started_at": datetime.utcnow().isoformat() + "Z",
        "discovery_method": "gcloud_list_all_game_bot_vms",
        "original_deployment": deploy_info,
        "deployment_vms_count": len(deployment_vms),
        "discovered_vms": all_vms,
        "vms_to_delete": vms_to_delete,
        "total_vms_to_delete": len(vms_to_delete)
    }

    os.makedirs(REPOS_FILES_DIR, exist_ok=True)
    with open(CLEANUP_LOG, 'w') as f:
        json.dump(cleanup_info, f, indent=2)

    print(f"Discovered {len(vms_to_delete)} VM(s) to delete (all game-bot-vm-* instances)")
    for vm in vms_to_delete:
        print(f"  - {vm['vm_name']} in {vm['zone']} (status: {vm['status']})")

    return cleanup_info

def stop_instances(**context):
    params = context['params']
    force_delete = params['force_delete']

    with open(CLEANUP_LOG, 'r') as f:
        cleanup_info = json.load(f)

    project_id = cleanup_info['project_id']
    env = _gcloud_auth(project_id)

    stopped_vms = []
    failed_stops = []

    for vm in cleanup_info['vms_to_delete']:
        vm_name = vm['vm_name']
        zone = vm['zone']
        current_status = vm.get('status', 'UNKNOWN')

        print(f"Processing {vm_name} in {zone} (current status: {current_status})...")

        # Skip if already stopped or terminated
        if current_status in ['TERMINATED', 'STOPPED']:
            print(f"VM {vm_name} is already {current_status}, skipping stop")
            vm['stopped_at'] = datetime.utcnow().isoformat() + "Z"
            vm['skip_reason'] = f"already_{current_status.lower()}"
            stopped_vms.append(vm)
            continue

        stop_cmd = [
            'gcloud', 'compute', 'instances', 'stop', vm_name,
            f'--zone={zone}',
            '--quiet'
        ]

        try:
            _run(stop_cmd, env=env)
            vm['stopped_at'] = datetime.utcnow().isoformat() + "Z"
            stopped_vms.append(vm)
            print(f"Successfully stopped {vm_name}")
        except subprocess.CalledProcessError as e:
            print(f"Failed to stop {vm_name}: {e.stderr}")
            vm['stop_error'] = str(e.stderr)
            failed_stops.append(vm)
            if not force_delete:
                raise Exception(f"Failed to stop VM {vm_name}. Use force_delete=True to continue anyway.")

    cleanup_info['stopped_vms'] = stopped_vms
    cleanup_info['failed_stops'] = failed_stops
    cleanup_info['stop_completed_at'] = datetime.utcnow().isoformat() + "Z"

    with open(CLEANUP_LOG, 'w') as f:
        json.dump(cleanup_info, f, indent=2)

    print(f"Stopped/Skipped {len(stopped_vms)} VM(s), {len(failed_stops)} failed")
    return stopped_vms

def delete_instances(**context):
    params = context['params']
    force_delete = params['force_delete']

    with open(CLEANUP_LOG, 'r') as f:
        cleanup_info = json.load(f)

    project_id = cleanup_info['project_id']
    env = _gcloud_auth(project_id)

    deleted_vms = []
    failed_deletes = []

    # Delete all VMs (stopped VMs and any that failed to stop if force_delete is True)
    vms_to_delete = cleanup_info.get('stopped_vms', [])
    if force_delete:
        vms_to_delete.extend(cleanup_info.get('failed_stops', []))

    for vm in vms_to_delete:
        vm_name = vm['vm_name']
        zone = vm['zone']

        print(f"Deleting instance {vm_name} in {zone}...")

        delete_cmd = [
            'gcloud', 'compute', 'instances', 'delete', vm_name,
            f'--zone={zone}',
            '--quiet'
        ]

        try:
            _run(delete_cmd, env=env)
            vm['deleted_at'] = datetime.utcnow().isoformat() + "Z"
            deleted_vms.append(vm)
            print(f"Successfully deleted {vm_name}")
        except subprocess.CalledProcessError as e:
            print(f"Failed to delete {vm_name}: {e.stderr}")
            vm['delete_error'] = str(e.stderr)
            failed_deletes.append(vm)
            if not force_delete:
                raise Exception(f"Failed to delete VM {vm_name}. Use force_delete=True to continue anyway.")

    cleanup_info['deleted_vms'] = deleted_vms
    cleanup_info['failed_deletes'] = failed_deletes
    cleanup_info['delete_completed_at'] = datetime.utcnow().isoformat() + "Z"

    with open(CLEANUP_LOG, 'w') as f:
        json.dump(cleanup_info, f, indent=2)

    print(f"Deleted {len(deleted_vms)} VM(s), {len(failed_deletes)} failed")
    return deleted_vms

def detect_bucket():
    if not os.path.exists(BUCKET_INFO):
        return None
    try:
        with open(BUCKET_INFO, 'r') as f:
            data = json.load(f)
        return data.get('bucket_name')
    except Exception:
        return None

def cleanup_files(**context):
    with open(CLEANUP_LOG, 'r') as f:
        cleanup_info = json.load(f)

    # Remove local files
    files_to_remove = [CREDENTIALS_FILE, DEPLOY_INFO]
    removed_files = []

    for file_path in files_to_remove:
        if os.path.exists(file_path):
            try:
                os.remove(file_path)
                removed_files.append(file_path)
                print(f"Removed local file: {file_path}")
            except Exception as e:
                print(f"Failed to remove {file_path}: {e}")

    cleanup_info['removed_local_files'] = removed_files
    cleanup_info['cleanup_completed_at'] = datetime.utcnow().isoformat() + "Z"

    with open(CLEANUP_LOG, 'w') as f:
        json.dump(cleanup_info, f, indent=2)

    print(f"Cleanup completed. Removed {len(removed_files)} local files.")
    return removed_files

def write_bucket_env():
    bucket = detect_bucket()
    if bucket:
        path = f"{REPOS_FILES_DIR}/bucket.env"
        with open(path, 'w') as f:
            f.write(f"BUCKET_NAME={bucket}\n")
        print(f"Detected bucket '{bucket}'. Wrote {path}.")
        return path
    print("No bucket-info.json found or missing bucket_name. Skipping gsutil cleanup.")
    return None

discover_task = PythonOperator(
    task_id='discover_all_game_bot_vms',
    python_callable=discover_all_vms,
    dag=dag
)

stop_task = PythonOperator(
    task_id='stop_instances',
    python_callable=stop_instances,
    dag=dag
)

delete_task = PythonOperator(
    task_id='delete_instances',
    python_callable=delete_instances,
    dag=dag
)

bucket_env_task = PythonOperator(
    task_id='detect_and_write_bucket_env',
    python_callable=write_bucket_env,
    dag=dag
)

cleanup_gcs = BashOperator(
    task_id='cleanup_gcs_files',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS="{GOOGLE_CREDENTIALS}"
        if [ -f "{REPOS_FILES_DIR}/bucket.env" ]; then
          . "{REPOS_FILES_DIR}/bucket.env"
          if [ -n "${{BUCKET_NAME:-}}" ]; then
            echo "Removing credentials from gs://$BUCKET_NAME/airflow/vm-credentials.json"
            gsutil -m rm "gs://$BUCKET_NAME/airflow/vm-credentials.json" || echo "File may not exist in bucket"
          else
            echo "BUCKET_NAME not set. Skipping GCS cleanup."
          fi
        else
          echo "No bucket.env present. Skipping GCS cleanup."
        fi
    """,
    dag=dag
)

cleanup_files_task = PythonOperator(
    task_id='cleanup_local_files',
    python_callable=cleanup_files,
    dag=dag
)

upload_cleanup_log = BashOperator(
    task_id='upload_cleanup_log_to_gcs',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS="{GOOGLE_CREDENTIALS}"
        if [ -f "{REPOS_FILES_DIR}/bucket.env" ]; then
          . "{REPOS_FILES_DIR}/bucket.env"
          if [ -n "${{BUCKET_NAME:-}}" ]; then
            echo "Uploading cleanup log to gs://$BUCKET_NAME/airflow/vm-cleanup.json"
            gsutil cp "{CLEANUP_LOG}" "gs://$BUCKET_NAME/airflow/vm-cleanup.json"
          else
            echo "BUCKET_NAME not set. Cleanup log remains local only."
          fi
        else
          echo "No bucket.env present. Cleanup log remains local only."
        fi
    """,
    dag=dag
)

discover_task >> stop_task >> delete_task >> bucket_env_task >> cleanup_gcs >> cleanup_files_task >> upload_cleanup_log