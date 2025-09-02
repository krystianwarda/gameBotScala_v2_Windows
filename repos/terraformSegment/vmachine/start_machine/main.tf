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

  # Add service account with proper scopes
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
      # Enable WinRM service
      winrm quickconfig -y -force

      # Configure WinRM for basic authentication (less secure but works)
      winrm set winrm/config/service '@{AllowUnencrypted="true"}'
      winrm set winrm/config/service/auth '@{Basic="true"}'
      winrm set winrm/config/winrs '@{MaxMemoryPerShellMB="1024"}'

      # Set up firewall rule for WinRM
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

    # Download GCS files
    try {
      Write-Output "Downloading files from GCS bucket: ${var.bucket_name}"

      # Download launcher package
      try {
        $launcherUrl = "https://storage.googleapis.com/${var.bucket_name}/launcher_package.zip"
        Write-Output "Downloading launcher from: $launcherUrl"
        Invoke-WebRequest -Uri $launcherUrl -OutFile "C:\launcher_package.zip" -UseBasicParsing
        if (Test-Path "C:\launcher_package.zip") {
          $size = (Get-Item "C:\launcher_package.zip").Length
          Write-Output "Launcher package downloaded successfully (Size: $size bytes)"

          # Extract launcher package to RealeraRelease_v1 folder
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

      # Download and extract appdata package to C:\ level
      try {
        $appdataUrl = "https://storage.googleapis.com/${var.bucket_name}/appdata_package.zip"
        Write-Output "Downloading appdata from: $appdataUrl"
        Invoke-WebRequest -Uri $appdataUrl -OutFile "C:\appdata_package.zip" -UseBasicParsing

        if (Test-Path "C:\appdata_package.zip") {
          # Extract directly to C:\ level
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

    } catch {
      Write-Output "Error accessing GCS bucket: $($_.Exception.Message)"
      $downloads["launcher_package"] = $false
      $downloads["appdata_package"] = $false
      $downloads["launcher_extracted"] = $false
      $downloads["appdata_extracted"] = $false
    }


    # Download and install Visual Studio Build Tools
    try {
      $vsUrl = "https://aka.ms/vs/17/release/vs_buildtools.exe"
      $vsOutput = "C:\vs_buildtools.exe"
      Write-Output "Downloading Visual Studio Build Tools..."
      Invoke-WebRequest -Uri $vsUrl -OutFile $vsOutput -UseBasicParsing

      if (Test-Path $vsOutput) {
        Write-Output "Installing Visual Studio Build Tools with required components..."
        $vsArgs = @(
          "--quiet",
          "--wait",
          "--add", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
          "--add", "Microsoft.VisualStudio.Component.Windows11SDK.22621",
          "--add", "Microsoft.VisualStudio.Component.Graphics.Tools",
          "--add", "Microsoft.VisualStudio.Component.DirectXTools",
          "--includeRecommended"
        )
        Start-Process -FilePath $vsOutput -ArgumentList $vsArgs -Wait
        $downloads["vs_buildtools"] = $true
        Write-Output "Visual Studio Build Tools installation completed"
      } else {
        $downloads["vs_buildtools"] = $false
      }
    } catch {
      Write-Output "Error with Visual Studio Build Tools: $($_.Exception.Message)"
      $downloads["vs_buildtools"] = $false
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