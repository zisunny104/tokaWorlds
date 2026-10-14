param([string]$Maven='mvn',[string]$Repository=(Join-Path (Split-Path $PSScriptRoot -Parent) 'plugin-modernization/build/m2'))
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
 & $Maven "-Dmaven.repo.local=$Repository" clean package
 if($LASTEXITCODE -ne 0){throw '編譯失敗'}
 New-Item -ItemType Directory -Force output | Out-Null
 Copy-Item target/tokaWorlds-1.0.2.jar output/tokaWorlds-1.0.2.jar -Force
 Get-FileHash output/tokaWorlds-1.0.2.jar
} finally {Pop-Location}
