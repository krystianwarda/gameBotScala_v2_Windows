terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.0"
    }
  }
}

provider "google" {
  credentials = file("/opt/airflow/terraformSegment/vmachine/gcp-key.json")
  project     = "gamebot-469621"
  region      = "us-central1"
}

locals {
  game_credentials = jsondecode(file("/opt/airflow/terraformSegment/vmachine/game-credentials.json"))
}

resource "random_password" "windows_password" {
  length  = 16
  special = true
}

resource "google_compute_instance" "vm_instance" {
  name         = var.vm_name
  machine_type = "g2-standard-4"
  zone         = var.zone
  tags         = ["winrm-server"]

  boot_disk {
    initialize_params {
      image = "projects/windows-cloud/global/images/family/windows-2019"
    }
  }

  guest_accelerator {
    type  = "nvidia-l4"
    count = 1
  }

  network_interface {
    network = "default"
    access_config {}
  }

  service_account {
    email  = "default"
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  metadata = {
    windows-startup-script-ps1 = <<-EOT
    # Create a new user account
    $username = "gamebot"
    $password = "${random_password.windows_password.result}"
    $securePassword = ConvertTo-SecureString $password -AsPlainText -Force

    # Create the user
    New-LocalUser -Name $username -Password $securePassword -FullName "Game Bot User" -Description "Auto-created user for game bot"
    Add-LocalGroupMember -Group "Administrators" -Member $username
    Add-LocalGroupMember -Group "Remote Desktop Users" -Member $username

    # Save credentials and download status to JSON file
    $credentials = @{
      username = $username
      password = $password
    }
    $credentials | ConvertTo-Json -Depth 3 | Out-File -FilePath "C:\windows-key.json" -Encoding UTF8


    # Enable and configure WinRM
    Write-Output "Configuring WinRM..."
    try {
      winrm quickconfig -y -force
      winrm set winrm/config/service '@{AllowUnencrypted="true"}'
      winrm set winrm/config/service/auth '@{Basic="true"}'
      winrm set winrm/config/winrs '@{MaxMemoryPerShellMB="1024"}'
      New-NetFirewallRule -DisplayName "WinRM-HTTP" -Direction Inbound -LocalPort 5985 -Protocol TCP -Action Allow
      Write-Output "WinRM configured successfully"
    } catch {
      Write-Output "Error configuring WinRM: $($_.Exception.Message)"
    }

    # Initialize downloads tracking
    $downloads = @{}

    # Download VC++ Redistributable x64
    try {
      $url = "https://aka.ms/vs/17/release/vc_redist.x64.exe"
      $output = "C:\vc_redist.x64.exe"
      Write-Output "Downloading VC++ Redistributable x64..."
      Invoke-WebRequest -Uri $url -OutFile $output -UseBasicParsing
      if (Test-Path $output) {
        Start-Process -FilePath $output -ArgumentList "/quiet", "/norestart" -Wait
        $downloads["vc_redist_x64"] = $true
      } else {
        $downloads["vc_redist_x64"] = $false
      }
    } catch {
      Write-Output "Error downloading VC++ x64: $($_.Exception.Message)"
      $downloads["vc_redist_x64"] = $false
    }

    # Download VC++ Redistributable x86
    try {
      $url = "https://aka.ms/vs/17/release/vc_redist.x86.exe"
      $output = "C:\vc_redist.x86.exe"
      Write-Output "Downloading VC++ Redistributable x86..."
      Invoke-WebRequest -Uri $url -OutFile $output -UseBasicParsing
      if (Test-Path $output) {
        Start-Process -FilePath $output -ArgumentList "/quiet", "/norestart" -Wait
        $downloads["vc_redist_x86"] = $true
      } else {
        $downloads["vc_redist_x86"] = $false
      }
    } catch {
      Write-Output "Error downloading VC++ x86: $($_.Exception.Message)"
      $downloads["vc_redist_x86"] = $false
    }

    # Download and extract RealeraRelease zip
    try {
      $url = "https://otfiles.com/RealeraRelease_v1.zip?1756642309"
      $zipOutput = "C:\RealeraRelease_v1.zip"
      $extractPath = "C:\RealeraRelease"
      Write-Output "Downloading RealeraRelease..."
      Invoke-WebRequest -Uri $url -OutFile $zipOutput -UseBasicParsing
      if (Test-Path $zipOutput) {
        Expand-Archive -Path $zipOutput -DestinationPath $extractPath -Force
        Write-Output "RealeraRelease extracted to: $extractPath"
        $downloads["realera_zip"] = $true
        $downloads["realera_extracted"] = $true
      } else {
        $downloads["realera_zip"] = $false
        $downloads["realera_extracted"] = $false
      }
    } catch {
      Write-Output "Error downloading RealeraRelease: $($_.Exception.Message)"
      $downloads["realera_zip"] = $false
      $downloads["realera_extracted"] = $false
    }


    # Download Visual Studio Build Tools installer
    try {
      $vsUrl = "https://aka.ms/vs/17/release/vs_buildtools.exe"
      $vsOutput = "C:\vs_buildtools.exe"
      Write-Output "Downloading Visual Studio Build Tools installer..."
      Invoke-WebRequest -Uri $vsUrl -OutFile $vsOutput -UseBasicParsing

      if (Test-Path $vsOutput) {
        Write-Output "Visual Studio Build Tools installer downloaded successfully"
        $downloads["vs_buildtools"] = $true
      } else {
        $downloads["vs_buildtools"] = $false
      }
    } catch {
      Write-Output "Error downloading Visual Studio Build Tools: $($_.Exception.Message)"
      $downloads["vs_buildtools"] = $false
    }

    # Download and install Java 17 JDK (Microsoft OpenJDK)
    try {
      $javaUrl = "https://aka.ms/download-jdk/microsoft-jdk-17.0.12-windows-x64.msi"
      $javaOutput = "C:\microsoft-jdk-17.msi"
      Write-Output "Downloading Microsoft OpenJDK 17..."
      Invoke-WebRequest -Uri $javaUrl -OutFile $javaOutput -UseBasicParsing

      if (Test-Path $javaOutput) {
        Write-Output "Installing Microsoft OpenJDK 17..."
        Start-Process -FilePath "msiexec.exe" -ArgumentList "/i", $javaOutput, "/quiet", "/norestart" -Wait

        $javaHome = "$${env:ProgramFiles}\Microsoft\jdk-17.0.12.7-hotspot"
        if (Test-Path $javaHome) {
          [Environment]::SetEnvironmentVariable("JAVA_HOME", $javaHome, "Machine")
          $currentPath = [Environment]::GetEnvironmentVariable("PATH", "Machine")
          $newPath = "$javaHome\bin;$currentPath"
          [Environment]::SetEnvironmentVariable("PATH", $newPath, "Machine")
          Write-Output "Java 17 installed successfully and environment variables set"
          $downloads["java17"] = $true
        } else {
          Write-Output "Java installation completed but installation directory not found"
          $downloads["java17"] = $false
        }
      } else {
        $downloads["java17"] = $false
      }
    } catch {
      Write-Output "Error installing Java 17: $($_.Exception.Message)"
      $downloads["java17"] = $false
    }


    # Download GCS files using authenticated gsutil
    try {
      Write-Output "Downloading files from GCS bucket: ${var.bucket_name}"

      # Download launcher package
      try {
        Write-Output "Downloading launcher package using gsutil..."
        & gsutil cp "gs://${var.bucket_name}/launcher_package.zip" "C:\launcher_package.zip"

        if (Test-Path "C:\launcher_package.zip") {
          $size = (Get-Item "C:\launcher_package.zip").Length
          Write-Output "Launcher package downloaded successfully (Size: $size bytes)"

          $launcherDestination = "C:\RealeraRelease\RealeraRelease_v1"
          if (Test-Path $launcherDestination) {
            Expand-Archive -Path "C:\launcher_package.zip" -DestinationPath $launcherDestination -Force
            Write-Output "Launcher package extracted to: $launcherDestination"
            $downloads["launcher_package"] = $true
            $downloads["launcher_extracted"] = $true
          } else {
            Write-Output "Error: RealeraRelease_v1 folder not found at $launcherDestination"
            $downloads["launcher_package"] = $true
            $downloads["launcher_extracted"] = $false
          }
        } else {
          $downloads["launcher_package"] = $false
          $downloads["launcher_extracted"] = $false
        }
      } catch {
        Write-Output "Error downloading launcher package: $($_.Exception.Message)"
        $downloads["launcher_package"] = $false
        $downloads["launcher_extracted"] = $false
      }

      # Download and extract appdata package
      try {
        Write-Output "Downloading appdata package using gsutil..."
        & gsutil cp "gs://${var.bucket_name}/appdata_package.zip" "C:\appdata_package.zip"

        if (Test-Path "C:\appdata_package.zip") {
          Expand-Archive -Path "C:\appdata_package.zip" -DestinationPath "C:\" -Force
          Write-Output "Appdata package extracted to C:\ level"
          $downloads["appdata_package"] = $true
          $downloads["appdata_extracted"] = $true
        } else {
          $downloads["appdata_package"] = $false
          $downloads["appdata_extracted"] = $false
        }
      } catch {
        Write-Output "Error with appdata package: $($_.Exception.Message)"
        $downloads["appdata_package"] = $false
        $downloads["appdata_extracted"] = $false
      }

      # Download JAR file
      try {
        Write-Output "Downloading JAR file using gsutil..."
        & gsutil cp "gs://${var.bucket_name}/game-bot-assembly-1.0.0.jar" "C:\game-bot-assembly-1.0.0.jar"

        if (Test-Path "C:\game-bot-assembly-1.0.0.jar") {
          $size = (Get-Item "C:\game-bot-assembly-1.0.0.jar").Length
          Write-Output "JAR file downloaded successfully (Size: $size bytes)"
          $downloads["jar_file"] = $true
        } else {
          $downloads["jar_file"] = $false
        }
      } catch {
        Write-Output "Error downloading JAR file: $($_.Exception.Message)"
        $downloads["jar_file"] = $false
      }

    } catch {
      Write-Output "Error accessing GCS bucket: $($_.Exception.Message)"
      $downloads["launcher_package"] = $false
      $downloads["appdata_package"] = $false
      $downloads["launcher_extracted"] = $false
      $downloads["appdata_extracted"] = $false
      $downloads["jar_file"] = $false
    }

    Write-Output "Setup completed."
    EOT
  }

  scheduling {
    on_host_maintenance = "terminate"
    automatic_restart   = true
    preemptible         = false
  }
}

resource "google_compute_firewall" "allow_rdp" {
  name    = "allow-rdp"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["3389"]
  }

  source_ranges = ["0.0.0.0/0"]
}

resource "google_compute_firewall" "allow_winrm" {
  name    = "allow-winrm"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["5985"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["winrm-server"]
}