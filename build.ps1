param([string]$Maven='mvn',[string]$Repository=(Join-Path (Split-Path $PSScriptRoot -Parent) 'plugin-modernization/build/m2'))
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
$pom = [xml](Get-Content -Raw (Join-Path $PSScriptRoot 'pom.xml')); $n = $pom.project.artifactId; $v = $pom.project.version
try {
 & $Maven "-Dmaven.repo.local=$Repository" clean package
 if($LASTEXITCODE -ne 0){throw '編譯失敗'}
 New-Item -ItemType Directory -Force output | Out-Null
 Copy-Item target/$n-$v.jar output/$n-$v.jar -Force
 Get-FileHash output/$n-$v.jar
} finally {Pop-Location}

