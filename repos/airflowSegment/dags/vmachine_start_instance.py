from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import json
import subprocess
import os
import time
import winrm

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 1,
    'retry_delay': timedelta(minutes=2)
}

dag = DAG(
    'vmachine_start_instance_v6',
    default_args=default_args,
    description='Start VM instance with game bot setup and complete deployment',
    schedule_interval=None,
    catchup=False
)

terraform_dir = "/opt/airflow/terraformSegment/vmachine/start_machine"

# Step 1: Initialize Terraform
terraform_init = BashOperator(
    task_id='terraform_init',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/terraformSegment/vmachine/gcp-key.json
        cd {terraform_dir}
        terraform init
    """,
    dag=dag
)

# Step 2: Apply Terraform to start VM instance
terraform_apply = BashOperator(
    task_id='terraform_apply',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/terraformSegment/vmachine/gcp-key.json
        cd {terraform_dir}
        terraform apply -auto-approve
    """,
    dag=dag
)

# Step 3: Get and save VM instance outputs
def save_vm_outputs():
    try:
        result = subprocess.run(
            ['terraform', 'output', '-json'],
            cwd=terraform_dir,
            capture_output=True,
            text=True,
            check=True
        )

        outputs = json.loads(result.stdout)

        vm_info = {
            "vm_external_ip": outputs.get("vm_external_ip", {}).get("value", "Unknown"),
            "windows_username": outputs.get("windows_username", {}).get("value", "Unknown"),
            "windows_password": outputs.get("windows_password", {}).get("value", "Unknown"),
            "vm_started_at": datetime.now().isoformat()
        }

        # Save VM info to terraform directory
        vm_info_path = f"{terraform_dir}/vm-instance.json"
        with open(vm_info_path, 'w') as f:
            json.dump(vm_info, f, indent=2)

        # Also save to parent vmachine directory
        parent_vm_info_path = "/opt/airflow/terraformSegment/vmachine/vm-instance.json"
        with open(parent_vm_info_path, 'w') as f:
            json.dump(vm_info, f, indent=2)

        print("✅ VM instance started successfully")
        print(f"🖥️ External IP: {vm_info['vm_external_ip']}")
        print(f"👤 Username: {vm_info['windows_username']}")
        print(f"🔒 Password: [HIDDEN]")
        print(f"⏰ Started at: {vm_info['vm_started_at']}")

    except subprocess.CalledProcessError as e:
        print(f"❌ Error getting terraform outputs: {e}")
        print(f"❌ stderr: {e.stderr}")
        raise
    except Exception as e:
        print(f"❌ Error saving VM outputs: {e}")
        raise

save_outputs = PythonOperator(
    task_id='save_vm_outputs',
    python_callable=save_vm_outputs,
    dag=dag
)

# Step 4: Verify bucket-info.json and game-credentials.json exist
def verify_required_files():
    try:
        bucket_info_path = "/opt/airflow/terraformSegment/vmachine/bucket-info.json"
        game_creds_path = "/opt/airflow/terraformSegment/vmachine/game-credentials.json"
        gcp_key_path = "/opt/airflow/terraformSegment/vmachine/gcp-key.json"

        missing_files = []

        if not os.path.exists(bucket_info_path):
            missing_files.append("bucket-info.json")
        else:
            print("✅ bucket-info.json found")

        if not os.path.exists(game_creds_path):
            missing_files.append("game-credentials.json")
        else:
            print("✅ game-credentials.json found")

        if not os.path.exists(gcp_key_path):
            missing_files.append("gcp-key.json")
        else:
            print("✅ gcp-key.json found")

        if missing_files:
            raise FileNotFoundError(f"Missing required files: {', '.join(missing_files)}")

        print("✅ All required files are present")

    except Exception as e:
        print(f"❌ Error verifying required files: {e}")
        raise

verify_files = PythonOperator(
    task_id='verify_required_files',
    python_callable=verify_required_files,
    dag=dag
)

# Step 5: Verify VM connection
def verify_vm_connection():
    vm_info_path = f"{terraform_dir}/vm-instance.json"

    if not os.path.exists(vm_info_path):
        raise Exception(f"❌ vm-instance.json not found at {vm_info_path}")

    print("✅ vm-instance.json file exists")

    try:
        with open(vm_info_path, 'r') as f:
            vm_info = json.load(f)

        print(f"✅ Successfully loaded VM info for IP: {vm_info['vm_external_ip']}")

        max_retries = 10
        retry_interval = 30  # seconds

        for attempt in range(max_retries):
            try:
                # Test WinRM connection
                session = winrm.Session(
                    f"http://{vm_info['vm_external_ip']}:5985/wsman",
                    auth=(vm_info['windows_username'], vm_info['windows_password']),
                    transport='basic'
                )

                # Simple test command
                result = session.run_cmd('echo "Connection test successful"')
                if result.status_code == 0:
                    print("✅ Successfully connected to Windows VM via WinRM")
                    return vm_info
                else:
                    raise Exception(f"WinRM connection test failed with status: {result.status_code}")

            except Exception as e:
                print(f"Attempt {attempt + 1}/{max_retries}: Connection failed - {e}")
                if attempt < max_retries - 1:
                    print(f"Waiting {retry_interval} seconds before retry...")
                    time.sleep(retry_interval)
                else:
                    raise Exception(f"❌ Failed to connect after {max_retries} attempts")

    except Exception as e:
        print(f"❌ Error verifying VM connection: {e}")
        raise

verify_connection = PythonOperator(
    task_id='verify_vm_connection',
    python_callable=verify_vm_connection,
    dag=dag
)

# Step 6: Wait for all files to be downloaded
def wait_for_files_download():
    vm_info_path = f"{terraform_dir}/vm-instance.json"

    try:
        with open(vm_info_path, 'r') as f:
            vm_info = json.load(f)

        session = winrm.Session(
            f"http://{vm_info['vm_external_ip']}:5985/wsman",
            auth=(vm_info['windows_username'], vm_info['windows_password']),
            transport='basic'
        )

        print("✅ Connected to VM for file download verification")

        # Define expected files from the metadata script
        expected_files = {
            "vc_redist.x64.exe": "VC++ Redistributable x64",
            "vc_redist.x86.exe": "VC++ Redistributable x86",
            "RealeraRelease_v1.zip": "RealeraRelease zip",
            "vs_buildtools.exe": "Visual Studio Build Tools",
            "microsoft-jdk-17.msi": "Microsoft OpenJDK 17",
            "launcher_package.zip": "Launcher package",
            "appdata_package.zip": "Appdata package",
            "game-bot-assembly-1.0.0.jar": "JAR file"
        }

        # Also check for extracted folders
        expected_folders = {
            "RealeraRelease": "RealeraRelease extracted folder"
        }

        max_wait_minutes = 15
        check_interval = 30  # seconds
        max_checks = (max_wait_minutes * 60) // check_interval

        print(f"🔍 Waiting for files to download (max {max_wait_minutes} minutes)...")

        for check in range(max_checks):
            print(f"📋 Check {check + 1}/{max_checks}: Verifying file downloads...")

            all_files_present = True
            missing_items = []

            # Check files
            for filename, description in expected_files.items():
                check_command = f'if (Test-Path "C:\\{filename}") {{ "EXISTS" }} else {{ "MISSING" }}'
                result = session.run_ps(check_command)
                output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

                if "MISSING" in output:
                    all_files_present = False
                    missing_items.append(f"{filename} ({description})")
                else:
                    print(f"  ✅ {filename}")

            # Check folders
            for foldername, description in expected_folders.items():
                check_command = f'if (Test-Path "C:\\{foldername}") {{ "EXISTS" }} else {{ "MISSING" }}'
                result = session.run_ps(check_command)
                output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

                if "MISSING" in output:
                    all_files_present = False
                    missing_items.append(f"{foldername}/ ({description})")
                else:
                    print(f"  ✅ {foldername}/")

            if all_files_present:
                print("🎉 All required files and folders are present!")

                # Additional check for launcher extraction
                launcher_check = 'if (Test-Path "C:\\RealeraRelease\\RealeraRelease_v1\\RealeraDX.exe") { "EXISTS" } else { "MISSING" }'
                result = session.run_ps(launcher_check)
                output = result.std_out.decode('utf-8').strip() if isinstance(result.std_out, bytes) else result.std_out.strip()

                if "EXISTS" in output:
                    print("  ✅ RealeraDX.exe found in extracted folder")
                    return True
                else:
                    print("  ⚠️ RealeraDX.exe not found, waiting for launcher extraction...")
            else:
                print(f"⏳ Missing items: {', '.join(missing_items)}")
                print(f"   Waiting {check_interval} seconds before next check...")
                time.sleep(check_interval)

        # If we reach here, not all files were downloaded in time
        raise Exception(f"❌ Timeout: Not all files downloaded after {max_wait_minutes} minutes")

    except Exception as e:
        print(f"❌ Error waiting for file downloads: {e}")
        raise

wait_for_downloads = PythonOperator(
    task_id='wait_for_files_download',
    python_callable=wait_for_files_download,
    dag=dag
)

# Step 7: Check and move mods folder
def check_and_move_mods_folder():
    vm_info_path = f"{terraform_dir}/vm-instance.json"

    try:
        with open(vm_info_path, 'r') as f:
            vm_info = json.load(f)

        session = winrm.Session(
            f"http://{vm_info['vm_external_ip']}:5985/wsman",
            auth=(vm_info['windows_username'], vm_info['windows_password']),
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
            create_dir_command = f'New-Item -Path "C:\\Users\\{vm_info["windows_username"]}\\AppData\\Roaming\\Realera\\Realera" -ItemType Directory -Force'
            result = session.run_ps(create_dir_command)

            if result.status_code == 0:
                print("✅ Destination directory structure created")
            else:
                raise Exception("Failed to create destination directory")

            # Move the directory
            move_command = f'Move-Item -Path "C:\\mods" -Destination "C:\\Users\\{vm_info["windows_username"]}\\AppData\\Roaming\\Realera\\Realera\\mods" -Force'
            result = session.run_ps(move_command)

            if result.status_code == 0:
                print("✅ Directory moved successfully!")

                # Verify the move was successful
                verify_command = f'if (Test-Path "C:\\Users\\{vm_info["windows_username"]}\\AppData\\Roaming\\Realera\\Realera\\mods") {{ "MOVED_SUCCESS" }} else {{ "MOVE_FAILED" }}'
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

# Step 8: Install Visual Studio Build Tools
def install_vs_buildtools():
    vm_info_path = f"{terraform_dir}/vm-instance.json"

    try:
        with open(vm_info_path, 'r') as f:
            vm_info = json.load(f)

        session = winrm.Session(
            f"http://{vm_info['vm_external_ip']}:5985/wsman",
            auth=(vm_info['windows_username'], vm_info['windows_password']),
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

# Step 9: Launch game and verify it's running
def launch_and_verify_game():
    vm_info_path = f"{terraform_dir}/vm-instance.json"

    try:
        with open(vm_info_path, 'r') as f:
            vm_info = json.load(f)

        session = winrm.Session(
            f"http://{vm_info['vm_external_ip']}:5985/wsman",
            auth=(vm_info['windows_username'], vm_info['windows_password']),
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
        create_task_command = f'''schtasks /create /tn "{task_name}" /tr "C:\\RealeraRelease\\RealeraRelease_v1\\RealeraDX.exe" /sc once /st 00:00 /it /ru "{vm_info['windows_username']}" /rp "{vm_info['windows_password']}" /rl highest'''

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
verify_files >> terraform_init >> terraform_apply >> save_outputs >> verify_connection >> wait_for_downloads >> move_mods_task >> install_buildtools_task >> launch_game_task