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
from airflow.utils.task_group import TaskGroup


# Add this configuration at the top of the file (after imports)
# Prioritized zones for GPU availability (L4 GPUs)
GPU_ZONES = [
    # US regions
    "us-central1-a", "us-central1-b", "us-central1-c",
    "us-east1-b", "us-east1-c", "us-east1-d",
    "us-west1-a", "us-west1-b",
    "us-west4-a", "us-west4-b",
    # Europe regions
    "europe-west1-b", "europe-west1-c", "europe-west1-d",
    "europe-west4-a", "europe-west4-b", "europe-west4-c",
    "europe-west3-b",
    # Additional US zones
    "us-east4-a", "us-east4-b", "us-east4-c"
]

def find_available_zone(project_id, vm_name, base_image, tried_zones=None):
    """Try to find a zone with available GPU capacity"""
    if tried_zones is None:
        tried_zones = []

    env = _gcloud_auth(project_id)

    for zone in GPU_ZONES:
        if zone in tried_zones:
            continue

        print(f"Checking zone {zone} for GPU availability...")

        # Try to create instance in this zone
        create_cmd = [
            'gcloud', 'compute', 'instances', 'create', vm_name,
            f'--source-machine-image={base_image}',
            f'--zone={zone}',
            '--quiet'
        ]

        try:
            _run(create_cmd, env=env)
            print(f"✓ Successfully created VM in zone {zone}")
            return zone
        except subprocess.CalledProcessError as e:
            error_msg = e.stderr.lower()

            # Check if it's a resource exhaustion error
            if 'zone_resource_pool_exhausted' in error_msg or 'does not have enough resources' in error_msg:
                print(f"✗ Zone {zone} has no GPU capacity, trying next zone...")
                tried_zones.append(zone)

                # Parse suggested zones from error message
                if 'zonesavailable' in error_msg.replace(' ', '').replace('-', ''):
                    # Try to extract suggested zone from error
                    pass

                continue
            else:
                # Different error - re-raise
                print(f"✗ Failed in zone {zone} with non-capacity error: {e.stderr}")
                raise

    # If we exhausted all zones
    raise Exception(f"No zones available with GPU capacity. Tried zones: {', '.join(tried_zones)}")

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
    'vmachine_image_start_instances',
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
        "character_name_1": Param(default="Nova", type="string", description="Character name for VM 1"),
        "character_name_2": Param(default="Orion", type="string", description="Character name for VM 2"),
        "character_name_3": Param(default="Blazeorc", type="string", description="Character name for VM 3"),
        "character_name_4": Param(default="Shadowly", type="string", description="Character name for VM 4"),
        "base_image": Param(
#             default="gamebot-image-v2",
            default="test-gamebot-image-v3",
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
REPOS_FILES_DIR = "/opt/airflow/files"  # Changed from /opt/airflow/repos/airflowSegment/files
CREDENTIALS_OUT = f"{REPOS_FILES_DIR}/vm-credentials.json"
DEPLOY_INFO = f"{REPOS_FILES_DIR}/vm-from-image-deployment.json"
BUCKET_INFO = "/opt/airflow/terraformSegment/vmachine/bucket-info.json"
VM_STATUS_FILE = f"{REPOS_FILES_DIR}/vm-status-check.json"

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


# Update the prepare_deployment function to not assign zones initially
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

        # Don't assign zone yet - will be determined during creation
        vms.append({
            "machine_number": i,
            "character_name": character,
            "vm_name": vm_name,
            "zone": None  # Will be assigned during creation
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

    env = _gcloud_auth(project_id)
    bucket = detect_bucket() or "gamebot-files-bucket"

    try:
        upload_cmd = [
            'gsutil', 'cp',
            DEPLOY_INFO,
            f'gs://{bucket}/airflow/deploy-info.json'
        ]
        _run(upload_cmd, env=env)
        print(f"Uploaded deployment info to gs://{bucket}/airflow/deploy-info.json")
    except Exception as e:
        print(f"Warning: Failed to upload deployment info to GCS: {e}")

    print(f"Prepared deployment for {machine_count} VM(s) from image '{base_image}'.")
    print("Zones will be automatically selected based on GPU availability.")
    return info

def create_instances(**context):
    with open(DEPLOY_INFO, 'r') as f:
        info = json.load(f)

    project_id = info['project_id']
    base_image = info['base_image']
    env = _gcloud_auth(project_id)

    results = []
    global_tried_zones = []  # Track all tried zones across all VMs

    for vm in info['vms']:
        name = vm['vm_name']
        original_zone = vm['zone']

        print(f"\n{'='*60}")
        print(f"Creating instance {name}")
        print(f"Original zone: {original_zone}")
        print(f"{'='*60}")

        # Try to find an available zone
        try:
            actual_zone = find_available_zone(
                project_id=project_id,
                vm_name=name,
                base_image=base_image,
                tried_zones=global_tried_zones.copy()
            )

            # Update the zone in our deployment info
            vm['zone'] = actual_zone
            vm['original_zone'] = original_zone

            # Add this zone to global tried zones (distribute VMs across zones)
            if actual_zone not in global_tried_zones:
                global_tried_zones.append(actual_zone)

        except Exception as e:
            print(f"✗ Failed to create {name} in any zone: {str(e)}")
            vm['creation_failed'] = True
            vm['error'] = str(e)
            results.append(vm)
            continue

        # Fetch external IP
        ip_cmd = [
            'gcloud', 'compute', 'instances', 'describe', name,
            f'--zone={actual_zone}',
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
        print(f"✓ Instance {name} created in zone {actual_zone} with IP: {ip}")

    info['created_vms'] = results
    info['create_completed_at'] = datetime.utcnow().isoformat() + "Z"
    with open(DEPLOY_INFO, 'w') as f:
        json.dump(info, f, indent=2)

    # Print summary
    print(f"\n{'='*60}")
    print("DEPLOYMENT SUMMARY")
    print(f"{'='*60}")
    successful = [v for v in results if not v.get('creation_failed')]
    failed = [v for v in results if v.get('creation_failed')]
    print(f"✓ Successfully created: {len(successful)} VMs")
    if successful:
        for v in successful:
            print(f"  - {v['vm_name']} in {v['zone']}")
    print(f"✗ Failed: {len(failed)} VMs")
    if failed:
        for v in failed:
            print(f"  - {v['vm_name']}: {v.get('error', 'Unknown error')}")
    print(f"{'='*60}\n")

    return results



def generate_windows_credentials(**context):
    with open(DEPLOY_INFO, 'r') as f:
        info = json.load(f)

    project_id = info['project_id']
    env = _gcloud_auth(project_id)

    creds = []
    created_vms = info.get('created_vms', [])

    if not created_vms:
        print("WARNING: No VMs found in created_vms. Check create_instances task.")
        # Still write an empty credentials file
        os.makedirs(REPOS_FILES_DIR, exist_ok=True)
        with open(CREDENTIALS_OUT, 'w') as f:
            json.dump({
                "project_id": info['project_id'],
                "base_image": info['base_image'],
                "generated_at": datetime.utcnow().isoformat() + "Z",
                "credentials": [],
                "error": "no_vms_created"
            }, f, indent=2)
        print(f"Wrote empty credentials file to {CREDENTIALS_OUT}")
        return []

    for vm in created_vms:
        if vm.get('creation_failed'):
            print(f"Skipping credentials for failed VM {vm['vm_name']}")
            continue

        name = vm['vm_name']
        zone = vm['zone']
        username = "gamebot"

        # Try reset-windows-password with retries
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
                print(f"[{name}] Successfully generated password on attempt {attempt}")
                break
            except subprocess.CalledProcessError as e:
                print(f"[{name}] Attempt {attempt}/{max_retries} to set password failed: {e.stderr}")
                if attempt < max_retries:
                    time.sleep(delay)
                else:
                    print(f"[{name}] Giving up on password generation after {max_retries} attempts.")

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
                "windows_username": username,
                "windows_password": pwd_json.get('password', None)
            }

        creds.append(vm_entry)

    # Ensure directory exists
    os.makedirs(REPOS_FILES_DIR, exist_ok=True)

    # Write credentials file
    credentials_data = {
        "project_id": info['project_id'],
        "base_image": info['base_image'],
        "generated_at": datetime.utcnow().isoformat() + "Z",
        "credentials": creds
    }

    with open(CREDENTIALS_OUT, 'w') as f:
        json.dump(credentials_data, f, indent=2)

    print(f"Successfully wrote credentials for {len(creds)} VM(s) to {CREDENTIALS_OUT}")

    # Verify file was created
    if os.path.exists(CREDENTIALS_OUT):
        print(f"Verified: {CREDENTIALS_OUT} exists (size: {os.path.getsize(CREDENTIALS_OUT)} bytes)")
    else:
        raise Exception(f"ERROR: Failed to create {CREDENTIALS_OUT}")

    # Also try to upload immediately to GCS as backup
    try:
        bucket = detect_bucket()
        if bucket:
            upload_cmd = [
                'gsutil', 'cp',
                CREDENTIALS_OUT,
                f'gs://{bucket}/airflow/vm-credentials.json'
            ]
            _run(upload_cmd, env=env)
            print(f"Backup: Uploaded credentials to gs://{bucket}/airflow/vm-credentials.json")
    except Exception as e:
        print(f"Warning: Failed to backup credentials to GCS: {e}")

    return creds

def detect_bucket():
    if not os.path.exists(BUCKET_INFO):
        return None
    try:
        with open(BUCKET_INFO, 'r') as f:
            data = json.load(f)
        return data.get('bucket_name')
    except Exception:
        return None

def write_bucket_env():
    bucket = detect_bucket()
    if bucket:
        path = f"{REPOS_FILES_DIR}/bucket.env"
        with open(path, 'w') as f:
            f.write(f"BUCKET_NAME={bucket}\n")
        print(f"Detected bucket '{bucket}'. Wrote {path}.")
        return path
    print("No bucket-info.json found or missing bucket_name. Skipping gsutil upload step.")
    return None

def check_vm_readiness(**context):
    """Poll VM metadata every 15 seconds to ensure instances are fully ready"""
    with open(DEPLOY_INFO, 'r') as f:
        info = json.load(f)

    project_id = info['project_id']
    env = _gcloud_auth(project_id)
    
    max_wait_time = 600  # 10 minutes max wait
    check_interval = 15  # 15 seconds
    start_time = time.time()
    
    while True:
        all_ready = True
        vm_statuses = []
        
        for vm in info.get('created_vms', []):
            if vm.get('creation_failed'):
                continue
                
            name = vm['vm_name']
            zone = vm['zone']
            
            try:
                # Get instance metadata and status
                metadata_cmd = [
                    'gcloud', 'compute', 'instances', 'describe', name,
                    f'--zone={zone}',
                    '--format=json'
                ]
                result = _run(metadata_cmd, env=env)
                instance_data = json.loads(result.stdout)
                
                status = instance_data.get('status', 'UNKNOWN')
                
                # Check if Windows is ready by looking for specific metadata
                windows_ready = False
                if status == 'RUNNING':
                    # Check if sysprep finished and instance is ready
                    metadata_items = instance_data.get('metadata', {}).get('items', [])
                    for item in metadata_items:
                        if item.get('key') == 'windows-startup-script-ps1':
                            windows_ready = True
                            break
                    
                    # Additional check: try to get serial port output to confirm boot
                    try:
                        serial_cmd = [
                            'gcloud', 'compute', 'instances', 'get-serial-port-output', name,
                            f'--zone={zone}',
                            '--port=1'
                        ]
                        serial_result = _run(serial_cmd, env=env)
                        if 'Windows is ready' in serial_result.stdout or 'sysprep' in serial_result.stdout.lower():
                            windows_ready = True
                    except subprocess.CalledProcessError:
                        # Serial output might not be available yet
                        pass
                
                vm_status = {
                    'vm_name': name,
                    'status': status,
                    'windows_ready': windows_ready,
                    'checked_at': datetime.utcnow().isoformat() + "Z"
                }
                vm_statuses.append(vm_status)
                
                print(f"VM {name}: Status={status}, Windows Ready={windows_ready}")
                
                if status != 'RUNNING' or not windows_ready:
                    all_ready = False
                    
            except subprocess.CalledProcessError as e:
                print(f"Error checking status for {name}: {e.stderr}")
                all_ready = False
                vm_statuses.append({
                    'vm_name': name,
                    'status': 'ERROR',
                    'windows_ready': False,
                    'error': str(e),
                    'checked_at': datetime.utcnow().isoformat() + "Z"
                })
        
        # Write status to file
        status_data = {
            'check_completed_at': datetime.utcnow().isoformat() + "Z",
            'all_ready': all_ready,
            'vm_statuses': vm_statuses
        }
        
        with open(VM_STATUS_FILE, 'w') as f:
            json.dump(status_data, f, indent=2)
        
        if all_ready:
            print("All VMs are ready and Windows has finished startup!")
            break
            
        elapsed_time = time.time() - start_time
        if elapsed_time > max_wait_time:
            raise Exception(f"Timeout waiting for VMs to be ready after {max_wait_time} seconds")
        
        print(f"Waiting {check_interval} seconds before next check... (elapsed: {int(elapsed_time)}s)")
        time.sleep(check_interval)
    
    return vm_statuses
# Replace the task definitions section with this
prepare = PythonOperator(
    task_id='prepare_deployment',
    python_callable=prepare_deployment,
    dag=dag
)

def get_active_vm_tasks(**context):
    """Determine which VM creation tasks should run"""
    with open(DEPLOY_INFO, 'r') as f:
        info = json.load(f)

    machine_count = info['machine_count']
    return [f'create_vm_instances.create_vm_{i}' for i in range(1, machine_count + 1)]

branch_tasks = PythonOperator(
    task_id='determine_vm_tasks',
    python_callable=get_active_vm_tasks,
    dag=dag
)


# Replace the create_instances function with this parallel version
def create_single_instance(vm_info, project_id, base_image, global_tried_zones):
    """Create a single VM instance"""
    name = vm_info['vm_name']

    print(f"\n{'='*60}")
    print(f"Creating instance {name}")
    print(f"{'='*60}")

    env = _gcloud_auth(project_id)

    try:
        actual_zone = find_available_zone(
            project_id=project_id,
            vm_name=name,
            base_image=base_image,
            tried_zones=global_tried_zones.copy()
        )

        # Update zone info
        vm_info['zone'] = actual_zone
        vm_info['original_zone'] = None

        # Fetch external IP
        ip_cmd = [
            'gcloud', 'compute', 'instances', 'describe', name,
            f'--zone={actual_zone}',
            '--format=get(networkInterfaces[0].accessConfigs[0].natIP)'
        ]

        r = _run(ip_cmd, env=env)
        ip = (r.stdout or "").strip()

        vm_info['vm_external_ip'] = ip
        vm_info['created_at'] = datetime.utcnow().isoformat() + "Z"

        print(f"✓ Instance {name} created in zone {actual_zone} with IP: {ip}")
        return vm_info

    except Exception as e:
        print(f"✗ Failed to create {name}: {str(e)}")
        vm_info['creation_failed'] = True
        vm_info['error'] = str(e)
        vm_info['zone'] = None
        return vm_info

def create_instance_wrapper(**context):
    """Wrapper for parallel execution"""
    vm_index = context['ti'].task_id.split('_')[-1]

    with open(DEPLOY_INFO, 'r') as f:
        info = json.load(f)

    # Skip if this VM index exceeds machine_count
    machine_count = info['machine_count']
    vm_num = int(vm_index)

    if vm_num > machine_count:
        print(f"Skipping VM {vm_num} - only {machine_count} VMs requested")
        return None  # Return None to indicate this task should be skipped

    vm_info = info['vms'][vm_num - 1]
    project_id = info['project_id']
    base_image = info['base_image']

    # Get already tried zones from XCom
    tried_zones = context['ti'].xcom_pull(key='tried_zones', default=[])

    result = create_single_instance(vm_info, project_id, base_image, tried_zones)

    # Update tried zones in XCom
    if result.get('zone'):
        tried_zones.append(result['zone'])
        context['ti'].xcom_push(key='tried_zones', value=tried_zones)

    return result

def aggregate_results(**context):
    """Collect all results and update deployment file"""
    with open(DEPLOY_INFO, 'r') as f:
        info = json.load(f)

    machine_count = info['machine_count']
    results = []

    # Collect results from all parallel tasks
    for i in range(1, 5):  # Check all 4 possible tasks
        result = context['ti'].xcom_pull(task_ids=f'create_vm_instances.create_vm_{i}')
        if result is not None:  # Filter out skipped tasks
            results.append(result)

    info['created_vms'] = results
    info['create_completed_at'] = datetime.utcnow().isoformat() + "Z"

    with open(DEPLOY_INFO, 'w') as f:
        json.dump(info, f, indent=2)

    # Print summary
    print(f"\n{'='*60}")
    print("DEPLOYMENT SUMMARY")
    print(f"{'='*60}")
    successful = [v for v in results if not v.get('creation_failed')]
    failed = [v for v in results if v.get('creation_failed')]
    print(f"✓ Successfully created: {len(successful)} VMs")
    if successful:
        for v in successful:
            print(f"  - {v['vm_name']} in {v['zone']}")
    print(f"✗ Failed: {len(failed)} VMs")
    if failed:
        for v in failed:
            print(f"  - {v['vm_name']}: {v.get('error', 'Unknown error')}")
    print(f"{'='*60}\n")

    return results


# Create tasks for maximum possible VMs (4)
with TaskGroup("create_vm_instances", dag=dag) as create_vms_group:
    for i in range(1, 5):  # Maximum 4 VMs
        PythonOperator(
            task_id=f'create_vm_{i}',
            python_callable=create_instance_wrapper,
            dag=dag
        )

aggregate = PythonOperator(
    task_id='aggregate_vm_results',
    python_callable=aggregate_results,
    trigger_rule='none_failed',  # Run even if some VM creations fail
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

check_readiness = PythonOperator(
    task_id='check_vm_readiness',
    python_callable=check_vm_readiness,
    dag=dag
)

# Update task dependencies
prepare >> create_vms_group >> aggregate >> gen_creds >> bucket_env_task >> upload_to_gcs >> check_readiness