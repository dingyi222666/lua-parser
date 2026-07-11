# Hard-cap Gradle/Java CPU on self-hosted Windows runner.
# Affinity mask 0x3F = cores 0-5 on 8-core host (~75-80% of machine).
# Priority BelowNormal so the host stays responsive.
# Periodically re-applies affinity/priority to java/javaw children.
param(
  [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
  [string[]]$GradleArgs
)

$ErrorActionPreference = "Continue"
$AffinityMask = [IntPtr]0x3F  # 6 of 8 cores

$Root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $Root "gradlew.bat"))) {
  $Root = (Get-Location).Path
}
Set-Location $Root

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
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "cmd.exe"
$psi.Arguments = "/c `"$gradlew`" $argLine"
$psi.WorkingDirectory = $Root
$psi.UseShellExecute = $false

$p = New-Object System.Diagnostics.Process
$p.StartInfo = $psi
[void]$p.Start()

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
exit $p.ExitCode
