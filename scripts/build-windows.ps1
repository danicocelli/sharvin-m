$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$gradleHome = Join-Path $env:LOCALAPPDATA 'SharvinBuild\gradle-8.7'
$gradleBat = Join-Path $gradleHome 'bin\gradle.bat'
if (-not (Test-Path $gradleBat)) {
  $cache = Join-Path $env:LOCALAPPDATA 'SharvinBuild'
  New-Item -ItemType Directory -Force -Path $cache | Out-Null
  $zip = Join-Path $cache 'gradle-8.7-bin.zip'
  if (-not (Test-Path $zip)) {
    Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' -OutFile $zip
  }
  Expand-Archive -Path $zip -DestinationPath $cache -Force
}
& $gradleBat --no-daemon -p $project assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
