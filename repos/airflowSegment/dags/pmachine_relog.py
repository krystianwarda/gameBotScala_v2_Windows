# from airflow import DAG
# from airflow.operators.python import PythonOperator
# from airflow.models import Param
# from datetime import datetime, timedelta
# import json
# import paramiko
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
#     'pmachine_relog',
#     default_args=default_args,
#     description='Login to locked machine (no wake)',
#     schedule_interval=None,
#     catchup=False,
#     params={
#         "confirmation": Param(
#             default=False,
#             type="boolean",
#             description="Confirm that you want to login to the machine"
#         ),
#         "allow_reboot": Param(
#             default=False,
#             type="boolean",
#             description="Allow one-time autologon + reboot if unlock fails"
#         ),
#         "post_reboot_wait": Param(
#             default=180,
#             type="integer",
#             description="Seconds to wait for host to reboot and log in after autologon"
#         ),
#     },
#     tags=['pmachine', 'login', 'physical-input']
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
# def login_only(**context):
#     params = context['params']
#     allow_reboot = params.get('allow_reboot', False)
#     post_reboot_wait = params.get('post_reboot_wait', 180)
#
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
#         "login_methods_tried": [],
#         "allow_reboot": allow_reboot,
#         "post_reboot_wait": post_reboot_wait,
#     }
#
#     print(f"🔑 Attempting login to {ip}...")
#
#     try:
#         ssh_client = paramiko.SSHClient()
#         ssh_client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
#         ssh_client.connect(
#             hostname=ip,
#             username=username,
#             password=password,
#             timeout=10,
#             auth_timeout=10,
#             banner_timeout=10
#         )
#         print("✅ SSH connection established")
#         if try_multiple_unlock_methods(ssh_client, username, password, result):
#             if result.get("reboot_initiated") and allow_reboot:
#                 ssh_client.close()
#                 ok = wait_for_reboot_and_verify(ip, username, password, post_reboot_wait)
#                 result["login_successful"] = bool(ok)
#             else:
#                 result["login_successful"] = True
#             ssh_client.close()
#         else:
#             ssh_client.close()
#     except Exception as e:
#         print(f"❌ Login failed: {e}")
#
#     return result
#
# # Reuse these from your main DAG (import or copy)
# from dags.pmachine_wake_wake import try_multiple_unlock_methods, wait_for_reboot_and_verify
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
#     task_id='login_only',
#     python_callable=login_only,
#     dag=dag
# )
#
# validate_task >> load_credentials_task >> login_task