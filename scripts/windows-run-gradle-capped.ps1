# Hard-cap Gradle/Java CPU on self-hosted Windows runner.
# Affinity mask 0x3F = cores 0-5 on 8-core host (~75-80% of machine).
# Priority BelowNormal so the host stays responsive.
# Periodically re-applies affinity/priority to java/javaw children.
param(
  [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
  [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"
$AffinityMask = [IntPtr]0x3F  # 6 of 8 cores

$Root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $Root "gradlew.bat"))) {
  $Root = (Get-Location).Path
}
Set-Location $Root

if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
  $env:JAVA_HOME = "C:\Users\dingyi\.jdks\temurin-17.0.11"
}
$javaBin = Join-Path $env:JAVA_HOME "bin"
if (-not (Test-Path (Join-Path $javaBin "java.exe"))) {
  Write-Error "java.exe not found under JAVA_HOME=$env:JAVA_HOME"
  exit 1
}
$env:Path = "$javaBin;$env:Path"

if ([string]::IsNullOrWhiteSpace($env:GRADLE_OPTS)) {
  $env:GRADLE_OPTS = "-Dorg.gradle.workers.max=5"
} elseif ($env:GRADLE_OPTS -notmatch "org\.gradle\.workers\.max") {
  $env:GRADLE_OPTS = "$($env:GRADLE_OPTS) -Dorg.gradle.workers.max=5"
}

function Limit-JavaProcesses {
  Get-Process -Name java,javaw -ErrorAction SilentlyContinue | ForEach-Object {
    try {
      $_.ProcessorAffinity = $AffinityMask
      $_.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::BelowNormal
    } catch {}
  }
}

$argLine = ($GradleArgs | ForEach-Object {
  if ($_ -match '\s') { '"{0}"' -f ($_ -replace '"', '\"') } else { $_ }
}) -join ' '

$gradlew = Join-Path $Root "gradlew.bat"
if (-not (Test-Path $gradlew)) {
  Write-Error "gradlew.bat not found at $gradlew"
  exit 1
}

Write-Host "cpu-cap: JAVA_HOME=$env:JAVA_HOME affinity=0x3F workers soft-cap via GRADLE_OPTS=$env:GRADLE_OPTS"
Write-Host "cpu-cap: running gradlew $argLine"

# Start via cmd so gradlew.bat works; inherit current process env (JAVA_HOME/Path).
$p = Start-Process -FilePath "cmd.exe" `
  -ArgumentList @("/c", "`"$gradlew`" $argLine") `
  -WorkingDirectory $Root `
  -PassThru `
  -NoNewWindow

try {
  $p.ProcessorAffinity = $AffinityMask
  $p.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::BelowNormal
} catch {}

Limit-JavaProcesses

while (-not $p.HasExited) {
  Limit-JavaProcesses
  Start-Sleep -Seconds 5
}

Limit-JavaProcesses
Write-Host "cpu-cap: exit=$($p.ExitCode)"
exit $p.ExitCode
