from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.models import Param
from datetime import datetime, timedelta
import json
import os
import socket
import paramiko
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
    'pmachine_wake_up',
    default_args=default_args,
    description='Wake up target machine(s) from sleep using Wake-on-LAN',
    schedule_interval=None,
    catchup=False,
    params={
        "confirmation": Param(
            default=False,
            type="boolean",
            description="Confirm that you want to wake up the machine(s)"
        ),
        "wait_for_boot": Param(
            default=60,
            type="integer",
            description="Seconds to wait for machine(s) to boot before verification"
        ),
        "target_machines": Param(
            default=["target_machine_1"],
            type="array",
            description="List of machine names to wake up"
        )
    },
    tags=['pmachine', 'wake', 'power', 'wol']
)

REPOS_FILES_DIR = "/opt/airflow/files"
SSH_CREDENTIALS_FILE = f"{REPOS_FILES_DIR}/ssh-credentials.json"
EXECUTION_LOG = f"{REPOS_FILES_DIR}/wake-execution.json"

def validate_confirmation(**context):
    params = context['params']
    confirmation = params.get('confirmation', False)
    if not confirmation:
        raise ValueError("Wake operation not confirmed. Please set 'confirmation' parameter to True to proceed.")
    print("✅ Wake operation confirmed")
    return {"confirmed": True, "timestamp": datetime.utcnow().isoformat()}

def load_machine_credentials(**context):
    params = context['params']
    raw_targets = params.get('target_machines', [])
    print(f"🔎 Raw target_machines param: {raw_targets}")
    target_machines = []
    if isinstance(raw_targets, str):
        target_machines = [m.strip() for m in raw_targets.split(',') if m.strip()]
    elif isinstance(raw_targets, list):
        for item in raw_targets:
            if isinstance(item, str):
                target_machines.extend([m.strip() for m in item.split(',') if m.strip()])
    print(f"🔎 Parsed target_machines: {target_machines}")
    with open(SSH_CREDENTIALS_FILE, 'r') as f:
        credentials = json.load(f)
    selected = [
        m for m in credentials.get('machines', [])
        if m.get('active', False) and m['name'] in target_machines
    ]
    if not selected:
        raise ValueError("No matching active machines found for selection")
    print(f"Selected machines for wake: {[m['name'] for m in selected]}")
    return selected

def send_wake_on_lan(**context):
    task_instance = context['task_instance']
    machine_credentials_list = task_instance.xcom_pull(task_ids='load_machine_credentials')
    results = []
    for machine_credentials in machine_credentials_list:
        machine_name = machine_credentials['name']
        ip = machine_credentials['ip']
        mac_address = machine_credentials.get('mac_address')
        result = {
            "machine_name": machine_name,
            "ip": ip,
            "mac_address": mac_address,
            "status": "failed",
            "error": None,
            "wol_sent_at": None,
            "method": "wake_on_lan"
        }
        try:
            if not mac_address:
                print("⚠️ MAC address not provided. Attempting alternative wake methods...")
                ip_parts = ip.split('.')
                broadcast_ip = f"{ip_parts[0]}.{ip_parts[1]}.{ip_parts[2]}.255"
                for i in range(5):
                    os.system(f"ping -c 1 -b {broadcast_ip} > /dev/null 2>&1")
                    time.sleep(1)
                result["method"] = "broadcast_ping"
                result["status"] = "attempted"
                result["wol_sent_at"] = datetime.utcnow().isoformat()
                print(f"✅ Broadcast wake attempt sent to {machine_name}")
            else:
                print(f"📡 Sending Wake-on-LAN packet to {machine_name} ({mac_address})...")
                mac_clean = mac_address.replace(':', '').replace('-', '').replace('.', '')
                if len(mac_clean) != 12:
                    raise ValueError(f"Invalid MAC address format: {mac_address}")
                mac_bytes = bytes.fromhex(mac_clean)
                magic_packet = b'\xff' * 6 + mac_bytes * 16
                ip_parts = ip.split('.')
                broadcast_ip = f"{ip_parts[0]}.{ip_parts[1]}.{ip_parts[2]}.255"
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                ports = [7, 9, 40000]
                for port in ports:
                    sock.sendto(magic_packet, (broadcast_ip, port))
                    sock.sendto(magic_packet, (ip, port))
                sock.close()
                result["status"] = "sent"
                result["wol_sent_at"] = datetime.utcnow().isoformat()
                print(f"✅ Wake-on-LAN packet sent to {machine_name} ({mac_address})")
        except Exception as e:
            error_msg = f"Failed to send wake signal to {machine_name}: {str(e)}"
            print(f"❌ {error_msg}")
            result["error"] = error_msg
        results.append(result)
    return results

def wait_for_machine_boot(**context):
    params = context['params']
    wait_time = params.get('wait_for_boot', 60)
    task_instance = context['task_instance']
    machine_credentials_list = task_instance.xcom_pull(task_ids='load_machine_credentials')
    wake_results = task_instance.xcom_pull(task_ids='send_wake_on_lan')
    results = []
    for machine_credentials, wake_result in zip(machine_credentials_list, wake_results):
        machine_name = machine_credentials['name']
        ip = machine_credentials['ip']
        result = {
            "machine_name": machine_name,
            "ip": ip,
            "wait_time_seconds": wait_time,
            "boot_status": "unknown",
            "waited_at": datetime.utcnow().isoformat()
        }
        if wake_result.get('status') not in ['sent', 'attempted']:
            print(f"⚠️ Wake signal was not sent successfully for {machine_name}, skipping boot wait")
            result["boot_status"] = "skipped_no_wake_signal"
            results.append(result)
            continue
        print(f"⏰ Waiting {wait_time} seconds for {machine_name} to boot...")
        for i in range(0, wait_time, 10):
            remaining = wait_time - i
            if remaining > 0:
                print(f"⏰ {remaining} seconds remaining for {machine_name}...")
                time.sleep(min(10, remaining))
        result["boot_status"] = "wait_completed"
        print(f"✅ Boot wait period completed for {machine_name}")
        results.append(result)
    return results

def verify_machine_awake(**context):
    task_instance = context['task_instance']
    machine_credentials_list = task_instance.xcom_pull(task_ids='load_machine_credentials')
    results = []
    for machine_credentials in machine_credentials_list:
        machine_name = machine_credentials['name']
        ip = machine_credentials['ip']
        username = machine_credentials['username']
        password = machine_credentials['password']
        result = {
            "machine_name": machine_name,
            "ip": ip,
            "verification_attempted_at": datetime.utcnow().isoformat(),
            "is_awake": False,
            "connection_successful": False,
            "verification_method": "ssh_connection"
        }
        ssh_client = None
        try:
            print(f"🔍 Verifying if {machine_name} is awake...")
            ssh_client = paramiko.SSHClient()
            ssh_client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
            ssh_client.connect(
                hostname=ip,
                username=username,
                password=password,
                timeout=15,
                auth_timeout=15
            )
            result["connection_successful"] = True
            result["is_awake"] = True
            stdin, stdout, stderr = ssh_client.exec_command('echo "Machine is awake"', timeout=10)
            output = stdout.read().decode().strip()
            if "Machine is awake" in output:
                print(f"✅ {machine_name} is fully awake and responsive!")
                result["command_test"] = "success"
            else:
                print(f"⚠️ {machine_name} is connected but command test failed")
                result["command_test"] = "failed"
        except paramiko.AuthenticationException:
            print(f"❌ Authentication failed for {machine_name}")
            result["is_awake"] = True
            result["error"] = "authentication_failed"
        except Exception as e:
            print(f"⚠️ {machine_name} is not yet responsive: {str(e)}")
            result["is_awake"] = False
            result["error"] = str(e)
        finally:
            if ssh_client:
                ssh_client.close()
        results.append(result)
    return results

def save_execution_log(**context):
    params = context['params']
    task_instance = context['task_instance']
    confirmation = task_instance.xcom_pull(task_ids='validate_confirmation')
    wake_results = task_instance.xcom_pull(task_ids='send_wake_on_lan')
    boot_wait_results = task_instance.xcom_pull(task_ids='wait_for_machine_boot')
    verification_results = task_instance.xcom_pull(task_ids='verify_machine_awake')
    execution_data = {
        "execution_started_at": datetime.utcnow().isoformat(),
        "operation": "wake_up_machine",
        "parameters": params,
        "confirmation_result": confirmation,
        "wake_results": wake_results,
        "boot_wait_results": boot_wait_results,
        "verification_results": verification_results,
        "execution_completed_at": datetime.utcnow().isoformat()
    }
    os.makedirs(REPOS_FILES_DIR, exist_ok=True)
    with open(EXECUTION_LOG, 'w') as f:
        json.dump(execution_data, f, indent=2)
    print(f"Wake execution log saved to {EXECUTION_LOG}")
    for wake_result, verification_result in zip(wake_results, verification_results):
        if wake_result and wake_result.get('status') in ['sent', 'attempted']:
            print(f"✅ Wake signal sent for {wake_result['machine_name']}")
            if verification_result and verification_result.get('is_awake'):
                print(f"✅ Machine {wake_result['machine_name']} verified to be awake and responsive!")
            else:
                print(f"⚠️ Machine {wake_result['machine_name']} may still be booting or wake failed")
        else:
            print(f"❌ Wake operation failed for {wake_result.get('machine_name', 'Unknown')}: {wake_result.get('error', 'Unknown error') if wake_result else 'No result'}")
    return execution_data

validate_task = PythonOperator(
    task_id='validate_confirmation',
    python_callable=validate_confirmation,
    dag=dag
)

load_credentials_task = PythonOperator(
    task_id='load_machine_credentials',
    python_callable=load_machine_credentials,
    dag=dag
)

wake_task = PythonOperator(
    task_id='send_wake_on_lan',
    python_callable=send_wake_on_lan,
    dag=dag
)

boot_wait_task = PythonOperator(
    task_id='wait_for_machine_boot',
    python_callable=wait_for_machine_boot,
    dag=dag
)

verify_task = PythonOperator(
    task_id='verify_machine_awake',
    python_callable=verify_machine_awake,
    dag=dag
)

save_log_task = PythonOperator(
    task_id='save_execution_log',
    python_callable=save_execution_log,
    dag=dag
)

validate_task >> load_credentials_task >> wake_task >> boot_wait_task >> verify_task >> save_log_task