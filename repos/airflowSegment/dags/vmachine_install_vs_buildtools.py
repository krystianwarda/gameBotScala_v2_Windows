from airflow import DAG
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import json
import winrm

default_args = {
    'owner': 'airflow',
    'start_date': datetime(2024, 1, 1),
    'retries': 1,
    'retry_delay': timedelta(minutes=2)
}

dag = DAG(
    'vmachine_install_vs_buildtools',
    default_args=default_args,
    description='Install Visual Studio Build Tools on Windows VM',
    schedule_interval=None,
    catchup=False
)

def install_vs_buildtools():
    credentials_path = '/opt/airflow/terraformSegment/vmachine/vm-credentials.json'

    try:
        with open(credentials_path, 'r') as f:
            vm_credentials = json.load(f)
        print(f"✅ Successfully loaded credentials")

        session = winrm.Session(
            f"http://{vm_credentials['vm_ip']}:5985/wsman",
            auth=(vm_credentials['username'], vm_credentials['password']),
            transport='basic'
        )

        print("✅ Successfully connected to Windows VM via WinRM")

        # PowerShell script to install VS Build Tools
        powershell_script = '''
        try {
            if (Test-Path "C:\\vs_buildtools.exe") {
                Write-Output "✅ VS Build Tools installer found at C:\\vs_buildtools.exe"

                Write-Output "🔧 Installing Visual Studio Build Tools with required components..."
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

                if ($process.ExitCode -eq 0) {
                    Write-Output "✅ Visual Studio Build Tools installation completed successfully"
                    exit 0
                } else {
                    Write-Output "❌ Installation failed with exit code: $($process.ExitCode)"
                    exit 1
                }
            } else {
                Write-Output "❌ VS Build Tools installer not found at C:\\vs_buildtools.exe"
                Write-Output "Please ensure the installer was downloaded during VM setup"
                exit 1
            }
        } catch {
            Write-Output "❌ Error during installation: $($_.Exception.Message)"
            exit 1
        }
        '''

        # Execute PowerShell script
        print("🚀 Starting Visual Studio Build Tools installation...")
        result = session.run_ps(powershell_script)

        output = result.std_out.decode('utf-8') if isinstance(result.std_out, bytes) else result.std_out
        print(f"Installation output: {output}")

        if result.status_code == 0:
            print("✅ Visual Studio Build Tools installation completed successfully!")
        else:
            print(f"❌ Installation failed with status: {result.status_code}")
            error_output = result.std_err.decode('utf-8') if isinstance(result.std_err, bytes) else result.std_err
            print(f"Error: {error_output}")
            raise Exception(f"VS Build Tools installation failed with code {result.status_code}")

    except Exception as e:
        print(f"❌ Error: {e}")
        raise

install_vs_buildtools_task = PythonOperator(
    task_id='install_vs_buildtools',
    python_callable=install_vs_buildtools,
    dag=dag
)

install_vs_buildtools_task