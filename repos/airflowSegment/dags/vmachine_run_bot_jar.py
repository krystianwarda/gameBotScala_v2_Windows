from airflow import DAG
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import json
import winrm
import os
import time


default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 1,
    'retry_delay': timedelta(minutes=2)
}

dag = DAG(
    'vmachine_run_bot_jar_v4',
    default_args=default_args,
    description='Run game-bot-assembly JAR on VM',
    schedule_interval=None,
    catchup=False
)

def run_game_bot_jar():
    credentials_path = '/opt/airflow/terraformSegment/vmachine/vm-instance.json'

    # Check if credentials file exists
    if not os.path.exists(credentials_path):
        raise Exception(f"❌ vm-credentials.json not found at {credentials_path}")

    try:
        # Load VM credentials
        with open(credentials_path, 'r') as f:
            vm_credentials = json.load(f)

        print(f"✅ Loaded credentials for VM IP: {vm_credentials['vm_external_ip']}")

        # Connect to VM via WinRM
        session = winrm.Session(
            f"http://{vm_credentials['vm_external_ip']}:5985/wsman",
            auth=(vm_credentials['windows_username'], vm_credentials['windows_password']),
            transport='basic'
        )

        print("✅ Connected to VM via WinRM")

        # Check if Java is installed
        java_check = 'java -version'
        result = session.run_cmd(java_check)

        if result.status_code != 0:
            raise Exception("❌ Java is not installed or not in PATH on the VM")

        print("✅ Java is available on VM")

        # Check if JAR file exists
        jar_check = 'if (Test-Path "C:\\game-bot-assembly-1.0.0.jar") { "EXISTS" } else { "NOT_FOUND" }'
        result = session.run_ps(jar_check)
        output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

        if "NOT_FOUND" in output:
            raise Exception("❌ game-bot-assembly-1.0.0.jar not found at C:\\game-bot-assembly-1.0.0.jar")

        print("✅ JAR file found on VM")

        # Kill any existing Java processes running the game-bot
        print("🔄 Terminating any existing game-bot processes...")
        kill_existing = 'Get-Process java -ErrorAction SilentlyContinue | Where-Object { (Get-WmiObject Win32_Process -Filter "ProcessId = $($_.Id)").CommandLine -like "*game-bot-assembly*" } | Stop-Process -Force'
        session.run_ps(kill_existing)

        # Create scheduled task to run the JAR (same approach as game launch)
        task_name = "LaunchGameBotJar"

        print("🚀 Creating scheduled task to run JAR from C:\\ directory...")

        # Delete existing task if it exists
        delete_task_command = f'schtasks /delete /tn "{task_name}" /f'
        session.run_cmd(delete_task_command)

        # Create batch file to change directory and run JAR
        batch_content = '''@echo off
cd /d C:\\
java -jar game-bot-assembly-1.0.0.jar
'''

        # Write batch file to VM
        write_batch = f'''
        $batchContent = @"
{batch_content}
"@
        Set-Content -Path "C:\\run_game_bot.bat" -Value $batchContent
        '''

        result = session.run_ps(write_batch)
        if result.status_code != 0:
            raise Exception("Failed to create batch file")

        print("✅ Batch file created")

        # Create scheduled task to run the batch file
        create_task_command = f'''schtasks /create /tn "{task_name}" /tr "C:\\run_game_bot.bat" /sc once /st 00:00 /it /ru "{vm_credentials['windows_username']}" /rp "{vm_credentials['windows_password']}" /rl highest'''

        result = session.run_cmd(create_task_command)
        if result.status_code != 0:
            error_output = result.std_err.decode('utf-8') if isinstance(result.std_err, bytes) else result.std_err
            raise Exception(f"Failed to create scheduled task: {error_output}")

        print("✅ Scheduled task created")

        # Run the scheduled task
        run_task_command = f'schtasks /run /tn "{task_name}"'
        result = session.run_cmd(run_task_command)

        if result.status_code == 0:
            print("✅ Game-bot JAR launch task executed")

            # Wait for the process to start
            time.sleep(10)

            # Verify Java process with game-bot is running
            check_process = '''
            Get-Process java -ErrorAction SilentlyContinue | ForEach-Object {
                $cmdLine = (Get-WmiObject Win32_Process -Filter "ProcessId = $($_.Id)").CommandLine
                if ($cmdLine -like "*game-bot-assembly*") {
                    [PSCustomObject]@{
                        ProcessName = $_.ProcessName
                        Id = $_.Id
                        CommandLine = $cmdLine
                    }
                }
            }
            '''

            result = session.run_ps(check_process)

            if result.status_code == 0:
                output = result.std_out.decode('utf-8') if isinstance(result.std_out, bytes) else result.std_out
                if "java" in output and "game-bot-assembly" in output:
                    print(f"✅ Game-bot JAR process confirmed running:\n{output}")
                    print("🎯 Game-bot JAR execution completed successfully!")
                else:
                    # Fallback check
                    simple_check = 'Get-Process java -ErrorAction SilentlyContinue | Measure-Object | Select-Object -ExpandProperty Count'
                    simple_result = session.run_ps(simple_check)
                    java_count = simple_result.std_out.decode('utf-8').strip() if isinstance(simple_result.std_out, bytes) else simple_result.std_out.strip()

                    if int(java_count) > 0:
                        print(f"✅ Java processes detected ({java_count} running)")
                        print("🎯 Game-bot JAR execution task completed!")
                    else:
                        print("⚠️ No Java processes detected, but launch command was executed")
                        print("🎯 Game-bot JAR launch task completed!")
            else:
                print("⚠️ Unable to verify process status, but launch command was executed")
                print("🎯 Game-bot JAR launch task completed!")

        else:
            error_output = result.std_err.decode('utf-8') if isinstance(result.std_err, bytes) else result.std_err
            raise Exception(f"Failed to run scheduled task with status: {result.status_code}: {error_output}")

    except Exception as e:
        print(f"❌ Error running game-bot JAR: {e}")
        raise


# Create the task
run_jar_task = PythonOperator(
    task_id='run_game_bot_jar',
    python_callable=run_game_bot_jar,
    dag=dag
)

# Single task DAG
run_jar_task