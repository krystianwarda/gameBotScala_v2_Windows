from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.models import Param
from datetime import datetime, timedelta
import json
import paramiko
import subprocess
import time
import os

REPOS_FILES_DIR = "/opt/airflow/files"
SSH_CREDENTIALS_FILE = f"{REPOS_FILES_DIR}/ssh-credentials.json"

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
    'pmachine_login_to_machine',
    default_args=default_args,
    description='Login to locked machine using VNC only (no wake)',
    schedule_interval=None,
    catchup=False,
    params={
        "confirmation": Param(
            default=False,
            type="boolean",
            description="Confirm that you want to login to the machine"
        ),
        "post_vnc_wait": Param(
            default=10,
            type="integer",
            description="Seconds to wait after VNC unlock"
        ),
        "target_machine": Param(
            default="target_machine_1",
            type="string",
            description="Name of the machine to login to"
        ),
    },
    tags=['pmachine', 'login', 'vnc']
)

def validate_confirmation(**context):
    params = context['params']
    confirmation = params.get('confirmation', False)
    if not confirmation:
        raise ValueError("Login operation not confirmed. Please set 'confirmation' parameter to True to proceed.")
    print("✅ Login operation confirmed")
    return {"confirmed": True, "timestamp": datetime.utcnow().isoformat()}

def load_machine_credentials(**context):
    params = context['params']
    target_machine_name = params.get('target_machine', 'target_machine_1')
    with open(SSH_CREDENTIALS_FILE, 'r') as f:
        credentials = json.load(f)
    machine = next((m for m in credentials.get('machines', []) if m['name'] == target_machine_name and m.get('active', False)), None)
    if not machine:
        raise ValueError(f"Machine {target_machine_name} not found or not active in credentials file")
    print(f"Target machine: {machine['name']} ({machine['ip']})")
    return machine

def try_vnc_unlock(ip, vnc_password):
    print("🔍 VNC unlock: sending Ctrl+Alt+Del, password, Enter...")
    try:
        subprocess.run([
            "vncdotool", "-s", f"{ip}::5900", "-p", vnc_password, "key", "ctrl-alt-del"
        ], timeout=5)
        time.sleep(2)
        subprocess.run([
            "vncdotool", "-s", f"{ip}::5900", "-p", vnc_password, "type", vnc_password
        ], timeout=5)
        subprocess.run([
            "vncdotool", "-s", f"{ip}::5900", "-p", vnc_password, "key", "enter"
        ], timeout=2)
        print("✅ VNC unlock sequence sent")
        return True
    except Exception as e:
        print(f"❌ VNC unlock failed: {e}")
        return False

def login_vnc_only(**context):
    params = context['params']
    post_vnc_wait = params.get('post_vnc_wait', 10)
    task_instance = context['task_instance']
    machine_credentials = task_instance.xcom_pull(task_ids='load_machine_credentials')

    ip = machine_credentials['ip']
    username = machine_credentials['username']
    password = machine_credentials['password']

    result = {
        "machine_name": machine_credentials['name'],
        "ip": ip,
        "login_attempted": True,
        "login_successful": False,
        "method": "vnc_unlock"
    }

    print(f"🔑 Attempting VNC login to {ip}...")
    ok = try_vnc_unlock(ip, password)
    time.sleep(post_vnc_wait)
    result["login_successful"] = bool(ok)
    return result

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

login_task = PythonOperator(
    task_id='login_vnc_only',
    python_callable=login_vnc_only,
    dag=dag
)

validate_task >> load_credentials_task >> login_task


# # python
# from airflow import DAG
# from airflow.operators.python import PythonOperator
# from airflow.models import Param
# from datetime import datetime, timedelta
# import json
# import paramiko
# import subprocess
# import time
# import os
#
# REPOS_FILES_DIR = "/opt/airflow/files"
# SSH_CREDENTIALS_FILE = f"{REPOS_FILES_DIR}/ssh-credentials.json"
#
# default_args = {
#     'owner': 'airflow',
#     'start_date': datetime(2024, 1, 1),
#     'retries': 1,
#     'retry_delay': timedelta(minutes=2),
#     'depends_on_past': False,
#     'email_on_failure': False,
#     'email_on_retry': False
# }
#
# dag = DAG(
#     'pmachine_login_to_machine',
#     default_args=default_args,
#     description='Login to locked machine using VNC only (no wake)',
#     schedule_interval=None,
#     catchup=False,
#     params={
#         "confirmation": Param(
#             default=False,
#             type="boolean",
#             description="Confirm that you want to login to the machine"
#         ),
#         "post_vnc_wait": Param(
#             default=10,
#             type="integer",
#             description="Seconds to wait after VNC unlock"
#         ),
#     },
#     tags=['pmachine', 'login', 'vnc']
# )
#
# def validate_confirmation(**context):
#     params = context['params']
#     confirmation = params.get('confirmation', False)
#     if not confirmation:
#         raise ValueError("Login operation not confirmed. Please set 'confirmation' parameter to True to proceed.")
#     print("✅ Login operation confirmed")
#     return {"confirmed": True, "timestamp": datetime.utcnow().isoformat()}
#
# def load_machine_credentials(**context):
#     with open(SSH_CREDENTIALS_FILE, 'r') as f:
#         credentials = json.load(f)
#     active_machines = [m for m in credentials.get('machines', []) if m.get('active', False)]
#     if not active_machines:
#         raise ValueError("No active machines found in credentials file")
#     target_machine = active_machines[0]
#     print(f"Target machine: {target_machine['name']} ({target_machine['ip']})")
#     return target_machine
#
# def try_vnc_unlock(ip, vnc_password):
#     """Use VNC to send unlock keystrokes to the physical console."""
#     print("🔍 VNC unlock: sending Ctrl+Alt+Del, password, Enter...")
#     try:
#         subprocess.run([
#             "vncdotool", "-s", f"{ip}::5900", "-p", vnc_password, "key", "ctrl-alt-del"
#         ], timeout=5)
#         time.sleep(2)
#         subprocess.run([
#             "vncdotool", "-s", f"{ip}::5900", "-p", vnc_password, "type", vnc_password
#         ], timeout=5)
#         subprocess.run([
#             "vncdotool", "-s", f"{ip}::5900", "-p", vnc_password, "key", "enter"
#         ], timeout=2)
#         print("✅ VNC unlock sequence sent")
#         return True
#     except Exception as e:
#         print(f"❌ VNC unlock failed: {e}")
#         return False
#
# def login_vnc_only(**context):
#     params = context['params']
#     post_vnc_wait = params.get('post_vnc_wait', 10)
#     task_instance = context['task_instance']
#     machine_credentials = task_instance.xcom_pull(task_ids='load_machine_credentials')
#
#     ip = machine_credentials['ip']
#     username = machine_credentials['username']
#     password = machine_credentials['password']
#
#     result = {
#         "machine_name": machine_credentials['name'],
#         "ip": ip,
#         "login_attempted": True,
#         "login_successful": False,
#         "method": "vnc_unlock"
#     }
#
#     print(f"🔑 Attempting VNC login to {ip}...")
#     ok = try_vnc_unlock(ip, password)
#     time.sleep(post_vnc_wait)
#     result["login_successful"] = bool(ok)
#     return result
#
# validate_task = PythonOperator(
#     task_id='validate_confirmation',
#     python_callable=validate_confirmation,
#     dag=dag
# )
#
# load_credentials_task = PythonOperator(
#     task_id='load_machine_credentials',
#     python_callable=load_machine_credentials,
#     dag=dag
# )
#
# login_task = PythonOperator(
#     task_id='login_vnc_only',
#     python_callable=login_vnc_only,
#     dag=dag
# )
#
# validate_task >> load_credentials_task >> login_task