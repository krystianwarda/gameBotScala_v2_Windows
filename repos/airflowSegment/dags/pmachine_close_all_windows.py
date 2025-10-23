# import json
# import logging
# from datetime import datetime, timedelta
# import winrm
# from airflow import DAG
# from airflow.operators.python import PythonOperator
#
# CREDENTIALS_FILE = "/opt/airflow/files/ssh-credentials.json"
#
# default_args = {
#     "owner": "airflow",
#     "start_date": datetime(2024, 1, 1),
#     "retries": 1,
#     "retry_delay": timedelta(minutes=2),
# }
#
# dag = DAG(
#     dag_id="pmachine_close_all_windows",
#     default_args=default_args,
#     description="Close all opened windows on remote machines via WinRM",
#     schedule_interval=None,
#     catchup=False,
#     params={
#         "target_machines": ["target_machine_2"],
#     },
#     tags=["winrm", "windows", "remote-exec"],
# )
#
#
#
# def close_all_windows(**context):
#     params = context['params']
#     vm_selection = params.get('target_machines', 'all')
#
#     with open(CREDENTIALS_FILE, 'r') as f:
#         creds_data = json.load(f)
#     credentials = creds_data.get('machines', [])
#
#     if vm_selection != "all":
#         credentials = [vm for vm in credentials if vm['name'] in vm_selection]
#
#     if not credentials:
#         raise ValueError(f"No VMs found for selection: {vm_selection}")
#
#     close_results = []
#
#     for vm in credentials:
#         vm_name = vm['name']
#         vm_ip = vm['ip']
#         username = vm.get('username')
#         password = vm.get('password')
#
#         if not username or not password:
#             logging.warning(f"Skipping {vm_name} - missing credentials")
#             continue
#
#         try:
#             session = winrm.Session(
#                 f"http://{vm_ip}:5985/wsman",
#                 auth=(username, password),
#                 transport='ntlm'
#             )
#             logging.info(f"✅ Connected to {vm_name} ({vm_ip}) via WinRM")
#
#             # Use taskkill to forcefully close all user processes except system-critical ones
# ps_script = r'''
# $exclude = @(
#     "explorer.exe", "winlogon.exe", "csrss.exe", "lsass.exe", "services.exe", "svchost.exe",
#     "System", "Idle", "wininit.exe", "dwm.exe", "smss.exe", "spoolsv.exe", "lsm.exe",
#     "fontdrvhost.exe", "conhost.exe", "taskhostw.exe", "sihost.exe", "SearchIndexer.exe"
# )
# Get-Process | Where-Object { $exclude -notcontains $_.ProcessName } | ForEach-Object { try { Stop-Process -Id $_.Id -Force } catch {} }
# '''
#             result = session.run_ps(ps_script)
#             if result.status_code != 0:
#                 raise Exception(f"Failed to close windows: {result.std_err.decode('utf-8') if result.std_err else ''}")
#
#             logging.info(f"✅ Forcefully closed all windows on {vm_name}")
#
#             close_results.append({
#                 "vm_name": vm_name,
#                 "vm_ip": vm_ip,
#                 "action": "close_all_windows",
#                 "status": "executed",
#                 "error": None,
#                 "timestamp": datetime.utcnow().isoformat() + "Z"
#             })
#
#         except Exception as e:
#             logging.error(f"❌ Failed to close windows on {vm_name}: {e}")
#             close_results.append({
#                 "vm_name": vm_name,
#                 "vm_ip": vm_ip,
#                 "action": "close_all_windows",
#                 "status": "failed",
#                 "error": str(e),
#                 "timestamp": datetime.utcnow().isoformat() + "Z"
#             })
#
#     return close_results
#
#
# close_windows_task = PythonOperator(
#     task_id="close_all_windows",
#     python_callable=close_all_windows,
#     dag=dag,
# )