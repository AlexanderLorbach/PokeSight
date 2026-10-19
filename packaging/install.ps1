# Installs PokeSight for the current user without an installer (no WiX needed).
# Called by the Gradle task "installLocal" with the app folder built by jpackage:
#   powershell -File packaging/install.ps1 -Source build/jpackage/PokeSight
param([Parameter(Mandatory = $true)][string]$Source)
$ErrorActionPreference = "Stop"

$exe = Join-Path $Source "PokeSight.exe"
if (-not (Test-Path $exe)) { throw "No PokeSight.exe in $Source - run the Gradle task jpackageImage first." }

$target = Join-Path $env:LOCALAPPDATA "Programs\PokeSight"
if (Get-Process -Name PokeSight -ErrorAction SilentlyContinue) { throw "PokeSight is running - close it first." }
if (Test-Path $target) { Remove-Item $target -Recurse -Force }
New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
Copy-Item $Source $target -Recurse

$shell = New-Object -ComObject WScript.Shell
$links = @(
    (Join-Path ([Environment]::GetFolderPath("Desktop")) "PokeSight.lnk"),
    (Join-Path ([Environment]::GetFolderPath("Programs")) "PokeSight.lnk")
)
foreach ($link in $links) {
    $shortcut = $shell.CreateShortcut($link)
    $shortcut.TargetPath = Join-Path $target "PokeSight.exe"
    $shortcut.WorkingDirectory = $target
    $shortcut.IconLocation = (Join-Path $target "PokeSight.exe") + ",0"
    $shortcut.Description = "PokeSight - live weaknesses of your opponents"
    $shortcut.Save()
}
Write-Host "PokeSight installed to $target (desktop and Start menu shortcuts created)."
