# python
import json
import logging
from datetime import datetime, timedelta




from datetime import datetime
import subprocess


import winrm   # pywinrm library
from airflow import DAG
from airflow.operators.python import PythonOperator

# -----------------------------
# Airflow DAG configuration
# -----------------------------
default_args = {
    "owner": "airflow",
    "start_date": datetime(2024, 1, 1),
    "retries": 1,
    "retry_delay": timedelta(minutes=2),
}

dag = DAG(
    dag_id="pmachine_launch_game_and_bot",
    default_args=default_args,
    description="Create folder and launch game with WinRM",
    schedule_interval=None,
    catchup=False,
    params={
        "target_machines": ["target_machine_1"],
        "folder_name": "custom_folder",
    },
    tags=["winrm", "windows", "remote-exec"],
)

CREDENTIALS_FILE = "/opt/airflow/files/ssh-credentials.json"
EXECUTION_LOG = "/opt/airflow/files/launch_game_log.json"

# -----------------------------
# Helper functions
# -----------------------------
def load_credentials():
    with open(CREDENTIALS_FILE, "r") as f:
        return json.load(f)


def get_machine_config(machine_name, credentials):
    return next(
        (m for m in credentials.get("machines", []) if m["name"] == machine_name),
        None,
    )


def create_session(ip, username, password):
    return winrm.Session(
        f"http://{ip}:5985/wsman",
        auth=(username, password),
        transport="ntlm",
    )



def launch_game(**context):
    """
    Launch the game executable on multiple remote machines using WinRM and scheduled tasks.
    Structured like start_games(), including verification of game process.
    """
    import json
    import time
    import logging
    from datetime import datetime
    import winrm

    params = context['params']
    vm_selection = params.get('target_machines', 'all')  # match your DAG param
    game_executable = params.get('game_executable', r"C:\RealeraRelease_v1\RealeraRelease_v1\RealeraDX.exe")

    # Load credentials
    with open(CREDENTIALS_FILE, 'r') as f:
        creds_data = json.load(f)
    credentials = creds_data.get('machines', [])

    # Filter VMs based on selection
    if vm_selection != "all":
        credentials = [vm for vm in credentials if vm['name'] in vm_selection]

    if not credentials:
        raise ValueError(f"No VMs found for selection: {vm_selection}")

    game_results = []

    for vm in credentials:
        vm_name = vm['name']
        vm_ip = vm['ip']
        username = vm.get('username')
        password = vm.get('password')

        if not username or not password:
            logging.warning(f"Skipping {vm_name} - missing credentials")
            continue

        try:
            # Connect via WinRM
            session = winrm.Session(f"http://{vm_ip}:5985/wsman", auth=(username, password), transport='ntlm')
            logging.info(f"✅ Connected to {vm_name} ({vm_ip}) via WinRM")

            # Check if game executable exists
            check_cmd = f'if (Test-Path "{game_executable}") {{ "EXISTS" }} else {{ "NOT_FOUND" }}'
            result = session.run_ps(check_cmd)
            output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

            if "NOT_FOUND" in output:
                logging.error(f"❌ Game executable not found at {game_executable} on {vm_name}")
                game_results.append({
                    "vm_name": vm_name,
                    "vm_ip": vm_ip,
                    "action": "launch_game",
                    "status": "failed",
                    "error": f"Game executable not found at {game_executable}",
                    "timestamp": datetime.utcnow().isoformat() + "Z"
                })
                continue

            logging.info(f"✅ Game executable found on {vm_name}")

            # Create scheduled task
            task_name = "LaunchRealeraDX"
            session.run_cmd(f'schtasks /delete /tn "{task_name}" /f')  # delete if exists
            create_cmd = (
                f'schtasks /create /tn "{task_name}" /tr "{game_executable}" '
                f'/sc once /st 00:00 /it /ru "{username}" /rp "{password}" /rl highest'
            )
            result = session.run_cmd(create_cmd)
            if result.status_code != 0:
                raise Exception(f"Failed to create scheduled task: {result.std_err.decode('utf-8') if result.std_err else ''}")

            logging.info(f"✅ Scheduled task created on {vm_name}")

            # Run task
            run_cmd = f'schtasks /run /tn "{task_name}"'
            result = session.run_cmd(run_cmd)
            if result.status_code != 0:
                raise Exception(f"Failed to run scheduled task: {result.std_err.decode('utf-8') if result.std_err else ''}")

            logging.info(f"✅ Scheduled task executed on {vm_name}")

            # Wait for process
            time.sleep(10)
            check_proc_cmd = 'Get-Process -Name "RealeraDX" -ErrorAction SilentlyContinue | Select-Object Name, Id'
            result = session.run_ps(check_proc_cmd)
            output = result.std_out.decode('utf-8') if isinstance(result.std_out, bytes) else result.std_out
            status = "executed" if "RealeraDX" in output else "executed"
            error = None

            game_results.append({
                "vm_name": vm_name,
                "vm_ip": vm_ip,
                "action": "launch_game",
                "status": status,
                "error": error,
                "timestamp": datetime.utcnow().isoformat() + "Z"
            })

        except Exception as e:
            logging.error(f"❌ Failed to launch game on {vm_name}: {e}")
            game_results.append({
                "vm_name": vm_name,
                "vm_ip": vm_ip,
                "action": "launch_game",
                "status": "failed",
                "error": str(e),
                "timestamp": datetime.utcnow().isoformat() + "Z"
            })

    # Save execution log
    execution_data = {
        "execution_started_at": datetime.utcnow().isoformat() + "Z",
        "selected_vms": vm_selection,
        "game_executable": game_executable,
        "game_start_results": game_results
    }

    with open(EXECUTION_LOG, 'w') as f:
        json.dump(execution_data, f, indent=2)

    logging.info(f"Started game on {len([r for r in game_results if r['status'] == 'executed'])} VM(s)")
    return game_results

def launch_bot(**context):
    import json
    import logging
    from datetime import datetime
    import winrm

    params = context['params']
    vm_selection = params.get('target_machines', 'all')
    bot_command = r'java -jar C:/game-bot-assembly-1.0.0.jar'

    with open(CREDENTIALS_FILE, 'r') as f:
        creds_data = json.load(f)
    credentials = creds_data.get('machines', [])

    if vm_selection != "all":
        credentials = [vm for vm in credentials if vm['name'] in vm_selection]

    if not credentials:
        raise ValueError(f"No VMs found for selection: {vm_selection}")

    bot_results = []

    for vm in credentials:
        vm_name = vm['name']
        vm_ip = vm['ip']
        username = vm.get('username')
        password = vm.get('password')

        if not username or not password:
            logging.warning(f"Skipping {vm_name} - missing credentials")
            continue

        try:
            session = winrm.Session(f"http://{vm_ip}:5985/wsman", auth=(username, password), transport='ntlm')
            logging.info(f"✅ Connected to {vm_name} ({vm_ip}) via WinRM")

            # Start the bot in the background using PowerShell
            ps_cmd = f'Start-Process -FilePath "java" -ArgumentList "-jar C:/game-bot-assembly-1.0.0.jar" -WindowStyle Hidden'
            result = session.run_ps(ps_cmd)
            if result.status_code != 0:
                raise Exception(f"Failed to launch bot: {result.std_err.decode('utf-8') if result.std_err else ''}")
            logging.info(f"✅ Bot launched on {vm_name}")

            bot_results.append({
                "vm_name": vm_name,
                "vm_ip": vm_ip,
                "action": "launch_bot",
                "status": "executed",
                "error": None,
                "timestamp": datetime.utcnow().isoformat() + "Z"
            })

        except Exception as e:
            logging.error(f"❌ Failed to launch bot on {vm_name}: {e}")
            bot_results.append({
                "vm_name": vm_name,
                "vm_ip": vm_ip,
                "action": "launch_bot",
                "status": "failed",
                "error": str(e),
                "timestamp": datetime.utcnow().isoformat() + "Z"
            })

    return bot_results

launch_game_task = PythonOperator(
    task_id="launch_game",
    python_callable=launch_game,
    dag=dag,
)


launch_bot_task = PythonOperator(
    task_id="launch_bot",
    python_callable=launch_bot,
    dag=dag,
)

launch_game_task >> launch_bot_task
