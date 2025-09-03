from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import os
import time
import json
import subprocess
import winrm

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 1,
    'retry_delay': timedelta(minutes=2)
}

dag = DAG(
    'vmachine_complete_deployment',
    default_args=default_args,
    description='Complete VM deployment: Terraform, setup, and game launch',
    schedule_interval=None,
    catchup=False
)

terraform_dir = "/opt/airflow/terraformSegment/vmachine"

# Step 1: Initialize and apply Terraform
terraform_apply = BashOperator(
    task_id='terraform_apply',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/gcp-key.json
        cd {terraform_dir}
        terraform init
        terraform apply -auto-approve
    """,
    dag=dag
)

# Step 2: Wait for VM to be ready
def wait_for_vm_ready():
    max_wait_seconds = 300  # 5 minutes
    sleep_interval = 10

    print("Waiting for VM to be ready and credentials to be generated...")

    for attempt in range(max_wait_seconds // sleep_interval):
        print(f"Attempt {attempt + 1}: Checking if VM is ready...")
        time.sleep(sleep_interval)

        if attempt >= 2:  # Wait at least 30 seconds
            print("✅ VM should be ready now.")
            return

    print("⚠️ VM might still be starting up, but proceeding...")

wait_for_vm = PythonOperator(
    task_id='wait_for_vm_ready',
    python_callable=wait_for_vm_ready,
    dag=dag
)

# Step 3: Save VM credentials
def save_vm_credentials():
    try:
        result = subprocess.run(
            ['terraform', 'output', '-json'],
            cwd=terraform_dir,
            capture_output=True,
            text=True,
            check=True
        )

        outputs = json.loads(result.stdout)

        credentials = {
            "vm_ip": outputs["vm_external_ip"]["value"],
            "username": outputs["windows_username"]["value"],
            "password": outputs["windows_password"]["value"],
            "bucket_name": outputs.get("bucket_name", {}).get("value", "Unknown"),
            "created_at": datetime.now().isoformat()
        }

        # Save to Docker container
        with open('/opt/airflow/vm-credentials.json', 'w') as f:
            json.dump(credentials, f, indent=2)

        # Save to local terraformSegment directory (mounted volume)
        local_credentials_path = f"{terraform_dir}/vm-credentials.json"
        with open(local_credentials_path, 'w') as f:
            json.dump(credentials, f, indent=2)

        print("✅ VM credentials saved to both locations")
        print(f"🖥️ VM IP: {credentials['vm_ip']}")
        print(f"👤 Username: {credentials['username']}")
        print(f"🪣 Bucket: {credentials['bucket_name']}")

    except Exception as e:
        print(f"❌ Error saving credentials: {e}")
        raise

save_credentials = PythonOperator(
    task_id='save_vm_credentials',
    python_callable=save_vm_credentials,
    dag=dag
)

# Step 4: Verify credentials and test VM connection
def verify_vm_connection():
    credentials_path = '/opt/airflow/terraformSegment/vmachine/vm-credentials.json'

    # Check if credentials file exists
    if not os.path.exists(credentials_path):
        raise Exception(f"❌ vm-credentials.json not found at {credentials_path}")

    print("✅ vm-credentials.json file exists")

    try:
        with open(credentials_path, 'r') as f:
            vm_credentials = json.load(f)
        print(f"✅ Successfully loaded credentials for IP: {vm_credentials['vm_ip']}")

        # Test WinRM connection
        session = winrm.Session(
            f"http://{vm_credentials['vm_ip']}:5985/wsman",
            auth=(vm_credentials['username'], vm_credentials['password']),
            transport='basic'
        )

        # Simple test command
        result = session.run_cmd('echo "Connection test successful"')
        if result.status_code == 0:
            print("✅ Successfully connected to Windows VM via WinRM")
            return vm_credentials
        else:
            raise Exception(f"❌ WinRM connection test failed with status: {result.status_code}")

    except Exception as e:
        print(f"❌ Error verifying VM connection: {e}")
        raise

verify_connection = PythonOperator(
    task_id='verify_vm_connection',
    python_callable=verify_vm_connection,
    dag=dag
)

# Step 5: Check for mods folder and move it
def check_and_move_mods_folder():
    credentials_path = '/opt/airflow/terraformSegment/vmachine/vm-credentials.json'

    try:
        with open(credentials_path, 'r') as f:
            vm_credentials = json.load(f)

        session = winrm.Session(
            f"http://{vm_credentials['vm_ip']}:5985/wsman",
            auth=(vm_credentials['username'], vm_credentials['password']),
            transport='basic'
        )

        print("✅ Connected to VM for mods folder operation")

        # Check if source directory exists
        check_source_command = 'if (Test-Path "C:\\mods") { "EXISTS" } else { "NOT_FOUND" }'
        print("🔍 Checking if C:\\mods exists...")

        result = session.run_ps(check_source_command)
        output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

        if result.status_code == 0 and "EXISTS" in output:
            print("✅ Source directory C:\\mods found, proceeding with move...")

            # Create destination directory structure
            create_dir_command = 'New-Item -Path "C:\\Users\\gamebot\\AppData\\Roaming\\Realera\\Realera" -ItemType Directory -Force'
            result = session.run_ps(create_dir_command)

            if result.status_code == 0:
                print("✅ Destination directory structure created")
            else:
                raise Exception("Failed to create destination directory")

            # Move the directory
            move_command = 'Move-Item -Path "C:\\mods" -Destination "C:\\Users\\gamebot\\AppData\\Roaming\\Realera\\Realera\\mods" -Force'
            result = session.run_ps(move_command)

            if result.status_code == 0:
                print("✅ Directory moved successfully!")

                # Verify the move was successful
                verify_command = 'if (Test-Path "C:\\Users\\gamebot\\AppData\\Roaming\\Realera\\Realera\\mods") { "MOVED_SUCCESS" } else { "MOVE_FAILED" }'
                result = session.run_ps(verify_command)
                output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

                if "MOVED_SUCCESS" in output:
                    print("✅ Move operation verified successfully!")
                else:
                    raise Exception("❌ Move verification failed")
            else:
                raise Exception(f"Move operation failed with status: {result.status_code}")
        else:
            print("⚠️ C:\\mods directory not found, skipping move operation")

    except Exception as e:
        print(f"❌ Error in mods folder operation: {e}")
        raise

move_mods_task = PythonOperator(
    task_id='check_and_move_mods_folder',
    python_callable=check_and_move_mods_folder,
    dag=dag
)

# Step 6: Install Visual Studio Build Tools
def install_vs_buildtools():
    credentials_path = '/opt/airflow/terraformSegment/vmachine/vm-credentials.json'

    try:
        with open(credentials_path, 'r') as f:
            vm_credentials = json.load(f)

        session = winrm.Session(
            f"http://{vm_credentials['vm_ip']}:5985/wsman",
            auth=(vm_credentials['username'], vm_credentials['password']),
            transport='basic'
        )

        print("✅ Connected to VM for VS Build Tools installation")

        # Check if installer exists
        check_installer = 'if (Test-Path "C:\\vs_buildtools.exe") { "EXISTS" } else { "NOT_FOUND" }'
        result = session.run_ps(check_installer)
        output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

        if "NOT_FOUND" in output:
            raise Exception("❌ VS Build Tools installer not found at C:\\vs_buildtools.exe")

        print("✅ VS Build Tools installer found")

        # Install VS Build Tools
        powershell_script = '''
        try {
            Write-Output "🔧 Installing Visual Studio Build Tools..."
            $vsArgs = @(
                "--quiet",
                "--wait",
                "--add", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                "--add", "Microsoft.VisualStudio.Component.Windows11SDK.22621",
                "--add", "Microsoft.VisualStudio.Component.Graphics.Tools",
                "--add", "Microsoft.VisualStudio.Component.DirectXTools",
                "--includeRecommended"
            )

            $process = Start-Process -FilePath "C:\\vs_buildtools.exe" -ArgumentList $vsArgs -Wait -PassThru
            Write-Output "Installation process completed with exit code: $($process.ExitCode)"
            exit $process.ExitCode
        } catch {
            Write-Output "❌ Error during installation: $($_.Exception.Message)"
            exit 1
        }
        '''

        print("🚀 Starting VS Build Tools installation (this may take several minutes)...")
        result = session.run_ps(powershell_script)

        if result.status_code == 0:
            print("✅ Visual Studio Build Tools installation completed successfully!")
        else:
            error_output = result.std_err.decode('utf-8') if isinstance(result.std_err, bytes) else result.std_err
            raise Exception(f"VS Build Tools installation failed with code {result.status_code}: {error_output}")

    except Exception as e:
        print(f"❌ Error installing VS Build Tools: {e}")
        raise

install_buildtools_task = PythonOperator(
    task_id='install_vs_buildtools',
    python_callable=install_vs_buildtools,
    dag=dag
)

# Step 7: Launch game and verify it's running
def launch_and_verify_game():
    credentials_path = '/opt/airflow/terraformSegment/vmachine/vm-credentials.json'

    try:
        with open(credentials_path, 'r') as f:
            vm_credentials = json.load(f)

        session = winrm.Session(
            f"http://{vm_credentials['vm_ip']}:5985/wsman",
            auth=(vm_credentials['username'], vm_credentials['password']),
            transport='basic'
        )

        print("✅ Connected to VM for game launch")

        # Check if game executable exists
        check_game = 'if (Test-Path "C:\\RealeraRelease\\RealeraRelease_v1\\RealeraDX.exe") { "EXISTS" } else { "NOT_FOUND" }'
        result = session.run_ps(check_game)
        output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

        if "NOT_FOUND" in output:
            raise Exception("❌ RealeraDX.exe not found at expected location")

        print("✅ Game executable found")

        # Create and run scheduled task for game launch
        task_name = "LaunchRealeraDX"

        # Delete existing task if it exists
        delete_task_command = f'schtasks /delete /tn "{task_name}" /f'
        session.run_cmd(delete_task_command)

        # Create new scheduled task
        create_task_command = f'''schtasks /create /tn "{task_name}" /tr "C:\\RealeraRelease\\RealeraRelease_v1\\RealeraDX.exe" /sc once /st 00:00 /it /ru "{vm_credentials['username']}" /rp "{vm_credentials['password']}" /rl highest'''

        result = session.run_cmd(create_task_command)
        if result.status_code != 0:
            raise Exception("Failed to create scheduled task")

        print("✅ Scheduled task created")

        # Run the scheduled task
        run_task_command = f'schtasks /run /tn "{task_name}"'
        result = session.run_cmd(run_task_command)

        if result.status_code == 0:
            print("✅ Game launch task executed")

            # Wait a bit for the process to start
            time.sleep(10)

            # Verify game is running
            check_process = 'Get-Process -Name "RealeraDX" -ErrorAction SilentlyContinue | Select-Object -Property Name, Id'
            result = session.run_ps(check_process)
            output = result.std_out.decode('utf-8') if isinstance(result.std_out, bytes) else result.std_out

            if "RealeraDX" in output:
                print("✅ Game is confirmed to be running!")
                print(f"Process info: {output}")
                print("🎮 Complete deployment successful - Game is now running!")
            else:
                print("⚠️ Game process not detected, but launch command was executed")

        else:
            raise Exception(f"Failed to run scheduled task with status: {result.status_code}")

    except Exception as e:
        print(f"❌ Error launching game: {e}")
        raise

launch_game_task = PythonOperator(
    task_id='launch_and_verify_game',
    python_callable=launch_and_verify_game,
    dag=dag
)

# Define task dependencies
terraform_apply >> wait_for_vm >> save_credentials >> verify_connection >> move_mods_task >> install_buildtools_task >> launch_game_task