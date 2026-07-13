# Ensure ANDROID_HOME has platforms/android-35/android.jar for reflective Android tests.
# Soft, idempotent: no-op when jar already present. Never invents G:/.
# Used by windows-full-jvmtest / windows-jvmtest on the self-hosted Windows runner.
param(
  [string]$AndroidHome = $env:ANDROID_HOME,
  [string]$PreferredApi = "35"
)

$ErrorActionPreference = "Stop"

if (-not $AndroidHome -or [string]::IsNullOrWhiteSpace($AndroidHome)) {
  $AndroidHome = "C:\Users\dingyi\AppData\Local\Android\Sdk"
}
$env:ANDROID_HOME = $AndroidHome
if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $AndroidHome }

$platformJar = Join-Path $AndroidHome ("platforms\android-{0}\android.jar" -f $PreferredApi)
Write-Host "ENSURE_ANDROID_JAR androidHome=$AndroidHome preferred=$platformJar"

if (Test-Path -LiteralPath $platformJar) {
  $len = (Get-Item -LiteralPath $platformJar).Length
  if ($len -gt 0) {
    Write-Host "ENSURE_ANDROID_JAR status=present bytes=$len path=$platformJar"
    exit 0
  }
}

# Prefer any already-installed platforms/android-*/android.jar (highest API first).
$platformsRoot = Join-Path $AndroidHome "platforms"
if (Test-Path -LiteralPath $platformsRoot) {
  $existing = Get-ChildItem -LiteralPath $platformsRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^android-(\d+)$' } |
    ForEach-Object {
      $api = [int]$Matches[1]
      $jar = Join-Path $_.FullName "android.jar"
      if (Test-Path -LiteralPath $jar) {
        [pscustomobject]@{ Api = $api; Path = $jar; Length = (Get-Item -LiteralPath $jar).Length }
      }
    } |
    Where-Object { $_.Length -gt 0 } |
    Sort-Object Api -Descending
  if ($existing) {
    $best = $existing | Select-Object -First 1
    Write-Host "ENSURE_ANDROID_JAR status=present_other_api api=$($best.Api) bytes=$($best.Length) path=$($best.Path)"
    # If preferred is missing but another API exists, still OK for dual-path discovery.
    if ($best.Api -ne [int]$PreferredApi) {
      Write-Host "ENSURE_ANDROID_JAR note=preferred_api_missing using_api=$($best.Api)"
    }
    exit 0
  }
}

New-Item -ItemType Directory -Force -Path $AndroidHome | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $AndroidHome "licenses") | Out-Null
# Accept Android SDK licenses non-interactively (hash files used by sdkmanager).
$licenseDir = Join-Path $AndroidHome "licenses"
@(
  @{ Name = "android-sdk-license"; Body = "`n24333f8a63b6825ea9c5514f83c2829b004d1fee`n" },
  @{ Name = "android-sdk-preview-license"; Body = "`n84831b9409646a918e30573bab4c9c91346d8abd`n" }
) | ForEach-Object {
  $p = Join-Path $licenseDir $_.Name
  if (-not (Test-Path -LiteralPath $p)) {
    Set-Content -LiteralPath $p -Value $_.Body -NoNewline -Encoding ascii
  }
}

function Find-SdkManager {
  $candidates = @(
    (Join-Path $AndroidHome "cmdline-tools\latest\bin\sdkmanager.bat"),
    (Join-Path $AndroidHome "cmdline-tools\bin\sdkmanager.bat")
  )
  Get-ChildItem -LiteralPath (Join-Path $AndroidHome "cmdline-tools") -Recurse -Filter sdkmanager.bat -ErrorAction SilentlyContinue |
    ForEach-Object { $candidates += $_.FullName }
  foreach ($c in $candidates) {
    if ($c -and (Test-Path -LiteralPath $c)) { return $c }
  }
  return $null
}

$sdkmanager = Find-SdkManager
if ($sdkmanager) {
  Write-Host "ENSURE_ANDROID_JAR step=sdkmanager package=platforms;android-$PreferredApi tool=$sdkmanager"
  $pkg = "platforms;android-$PreferredApi"
  & $sdkmanager --sdk_root=$AndroidHome $pkg 2>&1 | ForEach-Object { Write-Host $_ }
  if (Test-Path -LiteralPath $platformJar) {
    $len = (Get-Item -LiteralPath $platformJar).Length
    Write-Host "ENSURE_ANDROID_JAR status=installed_via_sdkmanager bytes=$len path=$platformJar"
    exit 0
  }
  Write-Host "ENSURE_ANDROID_JAR warn=sdkmanager_finished_but_jar_missing path=$platformJar"
}

# Fallback: download official platform zip and extract android.jar only.
$zipUrl = "https://dl.google.com/android/repository/platform-35_r02.zip"
$cacheDir = Join-Path $AndroidHome ".lua-parser-cache"
New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
$zipPath = Join-Path $cacheDir "platform-35_r02.zip"
Write-Host "ENSURE_ANDROID_JAR step=download url=$zipUrl dest=$zipPath"
try {
  if (-not (Test-Path -LiteralPath $zipPath) -or (Get-Item -LiteralPath $zipPath).Length -lt 1MB) {
    Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath -UseBasicParsing
  }
} catch {
  Write-Host "ENSURE_ANDROID_JAR status=download_failed error=$($_.Exception.Message)"
  Write-Host "ENSURE_ANDROID_JAR status=absent — Android reflective tests will soft-skip"
  exit 0
}

$extractRoot = Join-Path $cacheDir "platform-35_r02-extract"
if (Test-Path -LiteralPath $extractRoot) {
  Remove-Item -LiteralPath $extractRoot -Recurse -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
Write-Host "ENSURE_ANDROID_JAR step=expand zip=$zipPath"
try {
  Expand-Archive -LiteralPath $zipPath -DestinationPath $extractRoot -Force
} catch {
  Write-Host "ENSURE_ANDROID_JAR status=expand_failed error=$($_.Exception.Message)"
  exit 0
}

$foundJar = Get-ChildItem -LiteralPath $extractRoot -Recurse -Filter android.jar -ErrorAction SilentlyContinue |
  Where-Object { $_.Length -gt 1MB } |
  Select-Object -First 1
if (-not $foundJar) {
  Write-Host "ENSURE_ANDROID_JAR status=android_jar_not_in_zip"
  exit 0
}

$destDir = Join-Path $AndroidHome "platforms\android-$PreferredApi"
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
Copy-Item -LiteralPath $foundJar.FullName -Destination $platformJar -Force
# Optional companion files improve realism but are not required for reflection.
foreach ($name in @("build.prop", "package.xml", "sdk.properties")) {
  $src = Get-ChildItem -LiteralPath $extractRoot -Recurse -Filter $name -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($src) {
    Copy-Item -LiteralPath $src.FullName -Destination (Join-Path $destDir $name) -Force -ErrorAction SilentlyContinue
  }
}

if (Test-Path -LiteralPath $platformJar) {
  $len = (Get-Item -LiteralPath $platformJar).Length
  Write-Host "ENSURE_ANDROID_JAR status=installed_via_zip bytes=$len path=$platformJar"
  exit 0
}

Write-Host "ENSURE_ANDROID_JAR status=absent — Android reflective tests will soft-skip"
exit 0
