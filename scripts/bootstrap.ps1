$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $root '.tools'
New-Item -ItemType Directory -Force $toolRoot | Out-Null
function Download($url, $destination) {
    if (!(Test-Path $destination)) {
        & curl.exe -fL --retry 2 --connect-timeout 30 --max-time 600 -o $destination $url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
    }
}
$jdkDir = Get-ChildItem $toolRoot -Directory -Filter 'jdk-*' | Select-Object -First 1
if (!$jdkDir) {
    $assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows'
    $package = $assets[0].binary.package
    Download $package.link "$toolRoot/jdk.zip"
    if ((Get-FileHash "$toolRoot/jdk.zip" -Algorithm SHA256).Hash.ToLower() -ne $package.checksum) { throw 'JDK checksum mismatch' }
    Expand-Archive "$toolRoot/jdk.zip" $toolRoot -Force
    $jdkDir = Get-ChildItem $toolRoot -Directory -Filter 'jdk-*' | Select-Object -First 1
}
$env:JAVA_HOME = $jdkDir.FullName
$env:ANDROID_HOME = "$toolRoot/android-sdk"
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
if (!(Test-Path "$toolRoot/gradle-8.11.1/bin/gradle.bat")) {
    Download 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' "$toolRoot/gradle.zip"
    $checksum = (Invoke-RestMethod 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256').Trim()
    if ((Get-FileHash "$toolRoot/gradle.zip" -Algorithm SHA256).Hash.ToLower() -ne $checksum) { throw 'Gradle checksum mismatch' }
    Expand-Archive "$toolRoot/gradle.zip" $toolRoot -Force
}
if (!(Test-Path "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat")) {
    Download 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' "$toolRoot/android-tools.zip"
    Expand-Archive "$toolRoot/android-tools.zip" "$toolRoot/android-unpack" -Force
    New-Item -ItemType Directory -Force "$env:ANDROID_HOME/cmdline-tools" | Out-Null
    Move-Item -LiteralPath "$toolRoot/android-unpack/cmdline-tools" -Destination "$env:ANDROID_HOME/cmdline-tools/latest"
}
$sdkManager = "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat"
(1..30 | ForEach-Object { 'y' }) | & $sdkManager --licenses
& $sdkManager 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed' }
Write-Output 'Android toolchain ready.'
