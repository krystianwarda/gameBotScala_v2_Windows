import json
import logging
from datetime import datetime, timedelta
import time
import winrm
from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.utils.task_group import TaskGroup
from airflow.operators.python import BranchPythonOperator
from airflow.operators.empty import EmptyOperator
from airflow.operators.empty import EmptyOperator

default_args = {
    "owner": "airflow",
    "start_date": datetime(2024, 1, 1),
    "retries": 0,
    "retry_delay": timedelta(minutes=2),
}

dag = DAG(
    dag_id="pmachine_launch_game_and_bot",
    default_args=default_args,
    description="Create folder and launch game with WinRM",
    schedule_interval=None,
    catchup=False,
    params={
        'target_machines': ['target_machine_1', 'target_machine_2', 'target_machine_3', 'target_machine_4']
    },
    tags=["winrm", "windows", "remote-exec"],
)

CREDENTIALS_FILE = "/opt/airflow/files/ssh-credentials.json"
EXECUTION_LOG = "/opt/airflow/files/launch_game_log.json"

def launch_game(machine_name, **context):
    game_results = []
    params = context['params']
    game_executable = params.get('game_executable', r"C:\RealeraRelease_v1\RealeraRelease_v1\RealeraDX.exe")
    with open(CREDENTIALS_FILE, 'r') as f:
        creds_data = json.load(f)
    vm = next((m for m in creds_data.get('machines', []) if m['name'] == machine_name), None)
    if not vm:
        raise ValueError(f"No VM found for: {machine_name}")

    vm_name = vm['name']
    vm_ip = vm['ip']
    username = vm.get('username')
    password = vm.get('password')

    if not username or not password:
        logging.warning(f"Skipping {vm_name} - missing credentials")
        return game_results

    try:
        session = winrm.Session(f"http://{vm_ip}:5985/wsman", auth=(username, password), transport='ntlm')
        logging.info(f"✅ Connected to {vm_name} ({vm_ip}) via WinRM")

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
            return game_results

        logging.info(f"✅ Game executable found on {vm_name}")

        task_name = "LaunchRealeraDX"
        session.run_cmd(f'schtasks /delete /tn "{task_name}" /f')
        create_cmd = (
            f'schtasks /create /tn "{task_name}" /tr "{game_executable}" '
            f'/sc once /st 00:00 /it /ru "{username}" /rp "{password}" /rl highest'
        )
        result = session.run_cmd(create_cmd)
        if result.status_code != 0:
            raise Exception(f"Failed to create scheduled task: {result.std_err.decode('utf-8') if result.std_err else ''}")

        logging.info(f"✅ Scheduled task created on {vm_name}")

        run_cmd = f'schtasks /run /tn "{task_name}"'
        result = session.run_cmd(run_cmd)
        if result.status_code != 0:
            raise Exception(f"Failed to run scheduled task: {result.std_err.decode('utf-8') if result.std_err else ''}")

        logging.info(f"✅ Scheduled task executed on {vm_name}")

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

    execution_data = {
        "execution_started_at": datetime.utcnow().isoformat() + "Z",
        "selected_vms": [vm_name],
        "game_executable": game_executable,
        "game_start_results": game_results
    }

    with open(EXECUTION_LOG, 'w') as f:
        json.dump(execution_data, f, indent=2)

    logging.info(f"Started game on {len([r for r in game_results if r['status'] == 'executed'])} VM(s)")
    return game_results

def launch_bot(machine_name, **context):
    bot_results = []
    with open(CREDENTIALS_FILE, 'r') as f:
        creds_data = json.load(f)
    vm = next((m for m in creds_data.get('machines', []) if m['name'] == machine_name), None)
    if not vm:
        raise ValueError(f"No VM found for: {machine_name}")

    vm_name = vm['name']
    vm_ip = vm['ip']
    username = vm.get('username')
    password = vm.get('password')

    if not username or not password:
        logging.warning(f"Skipping {vm_name} - missing credentials")
        return bot_results

    try:
        session = winrm.Session(
            f"http://{vm_ip}:5985/wsman",
            auth=(username, password),
            transport='ntlm'
        )
        logging.info(f"✅ Connected to {vm_name} ({vm_ip}) via WinRM")

        batch_content = (
            '@echo off\n'
            'cd C:/\n'
            'java -jar game-bot-assembly-1.0.0.jar\n'
        )
        write_batch = f'''
$batchContent = @"
{batch_content}
"@
Set-Content -Path "C:\\run_game_bot.bat" -Value $batchContent
'''
        result = session.run_ps(write_batch)
        if result.status_code != 0:
            raise Exception("Failed to create batch file")

        logging.info(f"✅ Batch file created on {vm_name}")

        task_name = "LaunchGameBotJar"
        session.run_cmd(f'schtasks /delete /tn "{task_name}" /f')
        create_task_command = (
            f'schtasks /create /tn "{task_name}" /tr "C:\\run_game_bot.bat" '
            f'/sc once /st 00:00 /it /ru "{username}" /rp "{password}" /rl highest'
        )
        result = session.run_cmd(create_task_command)
        if result.status_code != 0:
            error_output = result.std_err.decode('utf-8') if result.std_err else result.std_err
            raise Exception(f"Failed to create scheduled task: {error_output}")

        logging.info(f"✅ Scheduled task created on {vm_name}")

        run_task_command = f'schtasks /run /tn "{task_name}"'
        result = session.run_cmd(run_task_command)
        if result.status_code != 0:
            error_output = result.std_err.decode('utf-8') if result.std_err else result.std_err
            raise Exception(f"Failed to run scheduled task: {error_output}")

        logging.info(f"✅ Game bot jar launch task executed on {vm_name}")

        bot_results.append({
            "vm_name": vm_name,
            "vm_ip": vm_ip,
            "action": "launch_bot",
            "status": "executed",
            "error": None,
            "timestamp": datetime.utcnow().isoformat() + "Z"
        })

    except Exception as e:
        logging.error(f"❌ Failed to launch game bot on {vm_name}: {e}")
        bot_results.append({
            "vm_name": vm_name,
            "vm_ip": vm_ip,
            "action": "launch_bot",
            "status": "failed",
            "error": str(e),
            "timestamp": datetime.utcnow().isoformat() + "Z"
        })

    return bot_results


def get_machines(**context):
    machines = context['params'].get('target_machines', [])
    if isinstance(machines, str):
        machines = [m.strip() for m in machines.split(',')]
    return machines

# # Define TaskGroup for launching game per machine
# with TaskGroup("launch_game_per_machine", dag=dag) as tg_game:
#     game_tasks = []
#     for machine in dag.params['target_machines']:
#         task = PythonOperator(
#             task_id=f'launch_game_{machine}',
#             python_callable=launch_game,
#             op_kwargs={'machine_name': machine},
#             dag=dag,
#         )
#         game_tasks.append(task)

def create_dynamic_game_tasks(**context):
    """Returns task IDs for machines selected at runtime"""
    machines = context['params'].get('target_machines', [])
    if isinstance(machines, str):
        machines = [m.strip() for m in machines.split(',')]

    task_ids = [f'launch_game_per_machine.launch_game_{machine}' for machine in machines]
    if not task_ids:
        return ['skip_all_tasks']
    return task_ids

def create_dynamic_bot_tasks(**context):
    """Returns task IDs for machines selected at runtime"""
    machines = context['params'].get('target_machines', [])
    if isinstance(machines, str):
        machines = [m.strip() for m in machines.split(',')]

    task_ids = [f'launch_bot_per_machine.launch_bot_{machine}' for machine in machines]
    if not task_ids:
        return ['skip_all_tasks']
    return task_ids

# Get all possible machines from credentials file
with open(CREDENTIALS_FILE, 'r') as f:
    all_machines = [m['name'] for m in json.load(f).get('machines', [])]
with TaskGroup("launch_game_per_machine", dag=dag) as tg_game:
    game_branch = BranchPythonOperator(
        task_id='select_game_machines',
        python_callable=create_dynamic_game_tasks,
        dag=dag,
    )

    game_tasks = []
    for machine in all_machines:
        task = PythonOperator(
            task_id=f'launch_game_{machine}',
            python_callable=launch_game,
            op_kwargs={'machine_name': machine},
            dag=dag,
        )
        game_tasks.append(task)

    game_join = EmptyOperator(
        task_id='game_join',
        trigger_rule='none_failed_min_one_success',
        dag=dag,
    )

    game_branch >> game_tasks >> game_join

# Define TaskGroup for launching bot - create tasks for ALL possible machines
with TaskGroup("launch_bot_per_machine", dag=dag) as tg_bot:
    bot_branch = BranchPythonOperator(
        task_id='select_bot_machines',
        python_callable=create_dynamic_bot_tasks,
        dag=dag,
    )

    bot_tasks = []
    for machine in all_machines:
        task = PythonOperator(
            task_id=f'launch_bot_{machine}',
            python_callable=launch_bot,
            op_kwargs={'machine_name': machine},
            dag=dag,
        )
        bot_tasks.append(task)

    bot_join = EmptyOperator(
        task_id='bot_join',
        trigger_rule='none_failed_min_one_success',
        dag=dag,
    )

    bot_branch >> bot_tasks >> bot_join

skip_all = EmptyOperator(task_id='skip_all_tasks', dag=dag)

tg_game >> tg_bot