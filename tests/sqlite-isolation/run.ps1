param([string]$Jdk=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$jar=Join-Path $root 'output/tokaWorlds-1.0.3.jar'
$run=Join-Path $root ('target/sqlite-isolation-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force "$run/classes" | Out-Null
& "$Jdk/bin/javac.exe" -encoding UTF-8 -cp $jar -d "$run/classes" "$PSScriptRoot/org/sqlite/JDBC.java" "$PSScriptRoot/SqliteIsolationProbe.java"
if($LASTEXITCODE -ne 0){throw 'Probe compilation failed'}
& "$Jdk/bin/java.exe" -cp "$run/classes;$jar" SqliteIsolationProbe "$run/database"
if($LASTEXITCODE -ne 0){throw 'SQLite isolation failed'}
