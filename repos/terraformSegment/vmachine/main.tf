resource "google_storage_bucket" "game_bot_bucket" {
  name     = var.bucket_name
  location = var.region
  force_destroy = true
}

# resource "google_storage_bucket_object" "game_jar" {
#   name   = "game-instance.jar"
#   bucket = google_storage_bucket.game_bot_bucket.name
#   source = "C:\\Users\\kryst\\AppData\\Roaming\\Realera\\Realera\\game.jar"
# }
#
# resource "google_storage_bucket_object" "bot_jar" {
#   name   = "bot-instance.jar"
#   bucket = google_storage_bucket.game_bot_bucket.name
#   source = "C:/Projects/gameBotScala_v2_Windows/repos/scalaSegment/target/scala-2.13/game-bot-assembly-1.0.0.jar"
# }


resource "random_password" "windows_password" {
  length  = 16
  special = true
}

resource "google_compute_instance" "vm_instance" {
  name         = var.vm_name
  machine_type = "g2-standard-4" # N1 machine type with 2 vCPUs and 7.5 GB memory
  zone         = var.zone

  boot_disk {
    initialize_params {
      image = "projects/windows-cloud/global/images/family/windows-2019" # Windows Server OS
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

  # Enable RDP
  Set-ItemProperty -Path 'HKLM:\System\CurrentControlSet\Control\Terminal Server' -name "fDenyTSConnections" -value 0
  Enable-NetFirewallRule -DisplayGroup "Remote Desktop"

  # Set screen resolution to 800x600
  try {
    Write-Output "Setting screen resolution to 800x600..."

    # Set registry values for default screen resolution
    $regPath = "HKLM:\SYSTEM\CurrentControlSet\Control\Video\{*}\0000"
    $regPaths = Get-ChildItem "HKLM:\SYSTEM\CurrentControlSet\Control\Video" | Where-Object {$_.Name -like "*{*}*"}

    foreach ($path in $regPaths) {
      $videoPath = "$($path.PSPath)\0000"
      if (Test-Path $videoPath) {
        Set-ItemProperty -Path $videoPath -Name "DefaultSettings.XResolution" -Value 800 -Type DWord -Force
        Set-ItemProperty -Path $videoPath -Name "DefaultSettings.YResolution" -Value 600 -Type DWord -Force
        Set-ItemProperty -Path $videoPath -Name "DefaultSettings.BitsPerPel" -Value 32 -Type DWord -Force
        Set-ItemProperty -Path $videoPath -Name "DefaultSettings.VRefresh" -Value 60 -Type DWord -Force
      }
    }

    # Also set for RDP connections
    Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp" -Name "DesktopWidth" -Value 800 -Type DWord -Force
    Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp" -Name "DesktopHeight" -Value 600 -Type DWord -Force

    Write-Output "Screen resolution settings applied"
  } catch {
    Write-Output "Error setting screen resolution: $_"
  }

  # Set up download preferences
  $ProgressPreference = 'SilentlyContinue'
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

  # Download results tracking
  $downloads = @{}

  # Download VC++ Redistributable x64
  try {
    $url = "https://aka.ms/vs/17/release/vc_redist.x64.exe"
    $output = "C:\vc_redist.x64.exe"

    Write-Output "Downloading VC++ Redistributable x64..."
    Invoke-WebRequest -Uri $url -OutFile $output -UseBasicParsing

    if (Test-Path $output) {
      $fileSize = (Get-Item $output).length
      Write-Output "VC++ x64 downloaded successfully ($([math]::Round($fileSize / 1MB, 2)) MB)"
      $downloads["vc_redist_x64"] = $true

      # Install VC++ x64 silently
      Write-Output "Installing VC++ Redistributable x64..."
      Start-Process -FilePath $output -ArgumentList "/quiet", "/norestart" -Wait
      Write-Output "VC++ x64 installation completed"
    } else {
      $downloads["vc_redist_x64"] = $false
    }
  } catch {
    Write-Output "Error with VC++ x64: $_"
    $downloads["vc_redist_x64"] = $false
  }

  # Download VC++ Redistributable x86
  try {
    $url = "https://aka.ms/vs/17/release/vc_redist.x86.exe"
    $output = "C:\vc_redist.x86.exe"

    Write-Output "Downloading VC++ Redistributable x86..."
    Invoke-WebRequest -Uri $url -OutFile $output -UseBasicParsing

    if (Test-Path $output) {
      $fileSize = (Get-Item $output).length
      Write-Output "VC++ x86 downloaded successfully ($([math]::Round($fileSize / 1MB, 2)) MB)"
      $downloads["vc_redist_x86"] = $true

      # Install VC++ x86 silently
      Write-Output "Installing VC++ Redistributable x86..."
      Start-Process -FilePath $output -ArgumentList "/quiet", "/norestart" -Wait
      Write-Output "VC++ x86 installation completed"
    } else {
      $downloads["vc_redist_x86"] = $false
    }
  } catch {
    Write-Output "Error with VC++ x86: $_"
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
      $fileSize = (Get-Item $zipOutput).length
      Write-Output "RealeraRelease downloaded successfully ($([math]::Round($fileSize / 1MB, 2)) MB)"
      $downloads["realera_zip"] = $true

      # Extract zip file
      Write-Output "Extracting RealeraRelease..."
      Expand-Archive -Path $zipOutput -DestinationPath $extractPath -Force

      # Find and install executable files in the extracted folder
      $exeFiles = Get-ChildItem -Path $extractPath -Filter "*.exe" -Recurse

      foreach ($exe in $exeFiles) {
        try {
          Write-Output "Installing: $($exe.Name)"
          # Try silent installation with common parameters
          $installArgs = @("/S", "/SILENT", "/VERYSILENT", "/quiet", "/norestart")

          foreach ($arg in $installArgs) {
            try {
              Start-Process -FilePath $exe.FullName -ArgumentList $arg -Wait -NoNewWindow
              Write-Output "Successfully installed $($exe.Name) with $arg"
              break
            } catch {
              continue
            }
          }
        } catch {
          Write-Output "Could not install $($exe.Name): $_"
        }
      }

      $downloads["realera_installed"] = $true
    } else {
      $downloads["realera_zip"] = $false
      $downloads["realera_installed"] = $false
    }
  } catch {
    Write-Output "Error with RealeraRelease: $_"
    $downloads["realera_zip"] = $false
    $downloads["realera_installed"] = $false
  }

  # Save credentials and download status to JSON file
  $credentials = @{
    username = $username
    password = $password
    downloads = $downloads
    screen_resolution = "800x600"
    installation_completed = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
  }

  $credentials | ConvertTo-Json -Depth 3 | Out-File -FilePath "C:\windows-key.json" -Encoding UTF8

  Write-Output "Setup completed. Status saved to C:\windows-key.json"
  EOT
  }

#   metadata = {
#     windows-startup-script-ps1 = <<-EOT
#   # Create a new user account
#   $username = "gamebot"
#   $password = "${random_password.windows_password.result}"
#   $securePassword = ConvertTo-SecureString $password -AsPlainText -Force
#
#   # Create the user
#   New-LocalUser -Name $username -Password $securePassword -FullName "Game Bot User" -Description "Auto-created user for game bot"
#   Add-LocalGroupMember -Group "Administrators" -Member $username
#   Add-LocalGroupMember -Group "Remote Desktop Users" -Member $username
#
#   # Enable RDP
#   Set-ItemProperty -Path 'HKLM:\System\CurrentControlSet\Control\Terminal Server' -name "fDenyTSConnections" -value 0
#   Enable-NetFirewallRule -DisplayGroup "Remote Desktop"
#
#   # Set up download preferences
#   $ProgressPreference = 'SilentlyContinue'
#   [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
#
#   # Download results tracking
#   $downloads = @{}
#
#   # Download VC++ Redistributable x64
#   try {
#     $url = "https://aka.ms/vs/17/release/vc_redist.x64.exe"
#     $output = "C:\vc_redist.x64.exe"
#
#     Write-Output "Downloading VC++ Redistributable x64..."
#     Invoke-WebRequest -Uri $url -OutFile $output -UseBasicParsing
#
#     if (Test-Path $output) {
#       $fileSize = (Get-Item $output).length
#       Write-Output "VC++ x64 downloaded successfully ($([math]::Round($fileSize / 1MB, 2)) MB)"
#       $downloads["vc_redist_x64"] = $true
#
#       # Install VC++ x64 silently
#       Write-Output "Installing VC++ Redistributable x64..."
#       Start-Process -FilePath $output -ArgumentList "/quiet", "/norestart" -Wait
#       Write-Output "VC++ x64 installation completed"
#     } else {
#       $downloads["vc_redist_x64"] = $false
#     }
#   } catch {
#     Write-Output "Error with VC++ x64: $_"
#     $downloads["vc_redist_x64"] = $false
#   }
#
#   # Download VC++ Redistributable x86
#   try {
#     $url = "https://aka.ms/vs/17/release/vc_redist.x86.exe"
#     $output = "C:\vc_redist.x86.exe"
#
#     Write-Output "Downloading VC++ Redistributable x86..."
#     Invoke-WebRequest -Uri $url -OutFile $output -UseBasicParsing
#
#     if (Test-Path $output) {
#       $fileSize = (Get-Item $output).length
#       Write-Output "VC++ x86 downloaded successfully ($([math]::Round($fileSize / 1MB, 2)) MB)"
#       $downloads["vc_redist_x86"] = $true
#
#       # Install VC++ x86 silently
#       Write-Output "Installing VC++ Redistributable x86..."
#       Start-Process -FilePath $output -ArgumentList "/quiet", "/norestart" -Wait
#       Write-Output "VC++ x86 installation completed"
#     } else {
#       $downloads["vc_redist_x86"] = $false
#     }
#   } catch {
#     Write-Output "Error with VC++ x86: $_"
#     $downloads["vc_redist_x86"] = $false
#   }
#
#   # Download and extract RealeraRelease zip
#   try {
#     $url = "https://otfiles.com/RealeraRelease_v1.zip?1756642309"
#     $zipOutput = "C:\RealeraRelease_v1.zip"
#     $extractPath = "C:\RealeraRelease"
#
#     Write-Output "Downloading RealeraRelease..."
#     Invoke-WebRequest -Uri $url -OutFile $zipOutput -UseBasicParsing
#
#     if (Test-Path $zipOutput) {
#       $fileSize = (Get-Item $zipOutput).length
#       Write-Output "RealeraRelease downloaded successfully ($([math]::Round($fileSize / 1MB, 2)) MB)"
#       $downloads["realera_zip"] = $true
#
#       # Extract zip file
#       Write-Output "Extracting RealeraRelease..."
#       Expand-Archive -Path $zipOutput -DestinationPath $extractPath -Force
#
#       # Find and install executable files in the extracted folder
#       $exeFiles = Get-ChildItem -Path $extractPath -Filter "*.exe" -Recurse
#
#       foreach ($exe in $exeFiles) {
#         try {
#           Write-Output "Installing: $($exe.Name)"
#           # Try silent installation with common parameters
#           $installArgs = @("/S", "/SILENT", "/VERYSILENT", "/quiet", "/norestart")
#
#           foreach ($arg in $installArgs) {
#             try {
#               Start-Process -FilePath $exe.FullName -ArgumentList $arg -Wait -NoNewWindow
#               Write-Output "Successfully installed $($exe.Name) with $arg"
#               break
#             } catch {
#               continue
#             }
#           }
#         } catch {
#           Write-Output "Could not install $($exe.Name): $_"
#         }
#       }
#
#       $downloads["realera_installed"] = $true
#     } else {
#       $downloads["realera_zip"] = $false
#       $downloads["realera_installed"] = $false
#     }
#   } catch {
#     Write-Output "Error with RealeraRelease: $_"
#     $downloads["realera_zip"] = $false
#     $downloads["realera_installed"] = $false
#   }
#
#   # Save credentials and download status to JSON file
#   $credentials = @{
#     username = $username
#     password = $password
#     downloads = $downloads
#     installation_completed = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
#   }
#
#   $credentials | ConvertTo-Json -Depth 3 | Out-File -FilePath "C:\windows-key.json" -Encoding UTF8
#
#   Write-Output "Setup completed. Status saved to C:\windows-key.json"
#   EOT
#   }



  scheduling {
    on_host_maintenance = "terminate"
    automatic_restart   = true
    preemptible         = false
  }
}

resource "local_file" "vm_credentials" {
  content = jsonencode({
    vm_ip    = google_compute_instance.vm_instance.network_interface[0].access_config[0].nat_ip
    username = "gamebot"
    password = random_password.windows_password.result
  })
  filename = "${path.module}/vm-credentials.json"
}

resource "google_compute_firewall" "allow_rdp" {
  name    = "allow-rdp"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["3389"]
  }

  source_ranges = ["0.0.0.0/0"] # Restrict this to trusted IPs for security
}