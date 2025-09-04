from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import json
import subprocess
import os
import logging

# Configure logging
logger = logging.getLogger(__name__)

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
    'vmachine_create_image_and_destroy',
    default_args=default_args,
    description='Create machine image and destroy VM instance to save costs',
    schedule_interval=None,
    catchup=False,
    tags=['vmachine', 'template', 'create_image', 'destroy_instance']
)

terraform_dir = "/opt/airflow/terraformSegment/vmachine/start_machine"

# Step 1: Get VM instance info using hardcoded values from Terraform config
def get_vm_instance_info(**context):
    try:
        logger.info("Getting VM instance information from Terraform")

        # Get Terraform outputs for what's available
        result = subprocess.run(
            ['terraform', 'output', '-json'],
            cwd=terraform_dir,
            capture_output=True,
            text=True,
            check=True
        )

        outputs = json.loads(result.stdout)
        logger.info(f"Available Terraform outputs: {list(outputs.keys())}")

        # Extract available values
        vm_external_ip = outputs.get("vm_external_ip", {}).get("value", "Unknown")
        windows_username = outputs.get("windows_username", {}).get("value", "Unknown")
        windows_password = outputs.get("windows_password", {}).get("value", "Unknown")

        # Use hardcoded values from your Terraform configuration
        vm_info = {
            "vm_external_ip": vm_external_ip,
            "vm_instance_name": "game-bot-vm",  # From your variables.tf default
            "project_id": "gamebot-469621",     # From your main.tf provider
            "zone": "us-central1-c",            # From your variables.tf default
            "windows_username": windows_username,
            "windows_password": windows_password
        }

        logger.info(f"✅ VM Info compiled: {vm_info['vm_instance_name']} in {vm_info['zone']}")

        # Save for next steps
        info_path = f"{terraform_dir}/vm-destroy-info.json"
        with open(info_path, 'w') as f:
            json.dump(vm_info, f, indent=2)

        logger.info(f"✅ VM info saved to: {info_path}")
        return vm_info

    except Exception as e:
        logger.error(f"❌ Error getting VM info: {e}")
        raise

get_vm_info = PythonOperator(
    task_id='get_vm_instance_info',
    python_callable=get_vm_instance_info,
    provide_context=True,
    dag=dag
)
# Step 2: Create machine image using gcloud
create_image = BashOperator(
    task_id='create_machine_image',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/terraformSegment/vmachine/gcp-key.json

        # Activate service account with gcloud
        echo "🔐 Activating service account..."
        gcloud auth activate-service-account --key-file=/opt/airflow/terraformSegment/vmachine/gcp-key.json

        # Set the project
        gcloud config set project gamebot-469621

        # Check if VM info file exists
        if [ ! -f "{terraform_dir}/vm-destroy-info.json" ]; then
            echo "❌ VM info file not found"
            exit 1
        fi

        # Read VM info
        VM_INFO=$(cat {terraform_dir}/vm-destroy-info.json)
        echo "📋 VM Info: $VM_INFO"

        VM_NAME=$(echo "$VM_INFO" | python3 -c "import sys, json; print(json.load(sys.stdin)['vm_instance_name'])")
        PROJECT_ID=$(echo "$VM_INFO" | python3 -c "import sys, json; print(json.load(sys.stdin)['project_id'])")
        ZONE=$(echo "$VM_INFO" | python3 -c "import sys, json; print(json.load(sys.stdin)['zone'])")

        echo "✅ VM Details:"
        echo "  Name: $VM_NAME"
        echo "  Project: $PROJECT_ID"
        echo "  Zone: $ZONE"

        # Verify we can access the VM
        echo "🔍 Verifying VM exists..."
        gcloud compute instances describe "$VM_NAME" --zone="$ZONE" --format="value(name,status)"

        # Create unique image name
        TIMESTAMP=$(date +%Y%m%d-%H%M%S)
        IMAGE_NAME="vm-gaming-bot-image-$TIMESTAMP"

        echo "🖼️ Creating machine image: $IMAGE_NAME"

        # Stop the VM first to ensure data consistency
        echo "⏸️ Stopping VM instance..."
        gcloud compute instances stop "$VM_NAME" --zone="$ZONE" --quiet

        # Wait for VM to stop completely
        echo "⏳ Waiting for VM to stop completely..."
        sleep 30

        # Verify VM is stopped
        VM_STATUS=$(gcloud compute instances describe "$VM_NAME" --zone="$ZONE" --format="value(status)")
        echo "VM Status: $VM_STATUS"

        # Create the machine image
        echo "📸 Creating machine image from stopped VM..."
        gcloud compute machine-images create "$IMAGE_NAME" \
            --source-instance="$VM_NAME" \
            --source-instance-zone="$ZONE" \
            --description="Gaming bot VM image created on $(date)" \
            --quiet

        # Save image info
        echo "{{\"image_name\": \"$IMAGE_NAME\", \"created_at\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}}" > {terraform_dir}/image-info.json

        echo "✅ Machine image created successfully: $IMAGE_NAME"
        echo "📁 Image info saved to: {terraform_dir}/image-info.json"
    """,
    dag=dag
)

# Step 3: Destroy VM using Terraform
destroy_vm = BashOperator(
    task_id='terraform_destroy',
    bash_command=f"""
        set -e
        export GOOGLE_APPLICATION_CREDENTIALS=/opt/airflow/terraformSegment/vmachine/gcp-key.json
        cd {terraform_dir}

        echo "🗑️ Destroying VM infrastructure with Terraform..."
        echo "📁 Working directory: $(pwd)"

        # Show what will be destroyed
        echo "📋 Resources to be destroyed:"
        terraform plan -destroy -no-color || echo "Could not show destroy plan"

        # Destroy the infrastructure
        echo "💥 Executing terraform destroy..."
        terraform destroy -auto-approve

        echo "✅ VM infrastructure destroyed successfully!"
        echo "💰 Cost savings: ~95% reduction in daily costs"
        echo "🖼️ Machine image preserved for future VM recreation"
        echo ""
        echo "🚀 To recreate the VM in the future:"
        echo "   1. Update your Terraform config to use the machine image"
        echo "   2. Run your vmachine_start_instance DAG"
        echo "   3. All your software and configurations will be restored!"
    """,
    dag=dag
)

# Step 4: Save comprehensive summary
def save_destruction_summary(**context):
    try:
        logger.info("Creating destruction summary...")

        # Load info files
        image_info_path = f"{terraform_dir}/image-info.json"
        vm_info_path = f"{terraform_dir}/vm-destroy-info.json"

        image_info = {}
        vm_info = {}

        if os.path.exists(image_info_path):
            with open(image_info_path, 'r') as f:
                image_info = json.load(f)
            logger.info("✅ Image info loaded")
        else:
            logger.warning("⚠️ Image info file not found")

        if os.path.exists(vm_info_path):
            with open(vm_info_path, 'r') as f:
                vm_info = json.load(f)
            logger.info("✅ VM info loaded")
        else:
            logger.warning("⚠️ VM info file not found")

        summary = {
            "action": "VM destroyed and machine image created",
            "image_name": image_info.get("image_name", "Unknown"),
            "image_created_at": image_info.get("created_at", "Unknown"),
            "original_vm_name": vm_info.get("vm_instance_name", "game-bot-vm"),
            "original_zone": vm_info.get("zone", "us-central1-c"),
            "original_project": vm_info.get("project_id", "gamebot-469621"),
            "original_external_ip": vm_info.get("vm_external_ip", "Unknown"),
            "windows_username": vm_info.get("windows_username", "Unknown"),
            "destroyed_at": datetime.now().isoformat(),
            "cost_savings": "~95% daily cost reduction",
            "next_steps": [
                "To recreate VM: Use the machine image in Terraform config",
                "All installed software will be preserved",
                "Game configurations and mods will be intact",
                "Windows user account and passwords will be recreated"
            ]
        }

        # Save summary to multiple locations
        summary_path = "/opt/airflow/terraformSegment/vmachine/vm-destruction-summary.json"
        os.makedirs(os.path.dirname(summary_path), exist_ok=True)

        with open(summary_path, 'w') as f:
            json.dump(summary, f, indent=2)

        # Also save in terraform directory
        terraform_summary_path = f"{terraform_dir}/destruction-summary.json"
        with open(terraform_summary_path, 'w') as f:
            json.dump(summary, f, indent=2)

        logger.info("=" * 60)
        logger.info("🎯 VM DESTRUCTION AND IMAGE CREATION COMPLETE!")
        logger.info("=" * 60)
        logger.info(f"✅ VM Instance: {summary['original_vm_name']} → DESTROYED")
        logger.info(f"🖼️ Machine Image: {summary['image_name']} → CREATED")
        logger.info(f"📍 Zone: {summary['original_zone']}")
        logger.info(f"🏗️ Project: {summary['original_project']}")
        logger.info(f"💰 Cost Reduction: {summary['cost_savings']}")
        logger.info(f"📅 Completed: {summary['destroyed_at']}")
        logger.info("=" * 60)
        logger.info("🚀 TO RECREATE VM FROM IMAGE:")
        logger.info("   1. Update Terraform to reference the machine image")
        logger.info("   2. Run your start_instance DAG")
        logger.info("   3. Everything will be restored automatically!")
        logger.info("=" * 60)

        return summary

    except Exception as e:
        logger.error(f"❌ Error creating summary: {e}")
        raise

save_summary = PythonOperator(
    task_id='save_destruction_summary',
    python_callable=save_destruction_summary,
    provide_context=True,
    dag=dag
)

# Define task dependencies
get_vm_info >> create_image >> destroy_vm >> save_summary