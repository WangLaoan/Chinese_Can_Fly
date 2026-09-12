param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdkDir = Get-ChildItem "$root/.tools" -Directory -Filter 'jdk-*' | Select-Object -First 1
if (!$jdkDir) { throw 'Run scripts/bootstrap.ps1 first.' }
$env:JAVA_HOME = $jdkDir.FullName
$env:ANDROID_HOME = "$root/.tools/android-sdk"
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
Push-Location $root
try {
    & "$root/.tools/gradle-8.11.1/bin/gradle.bat" @Tasks --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
} finally { Pop-Location }
