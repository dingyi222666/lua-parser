# Run ONE test slice on Windows self-hosted runner (progress-bar mode).
# Reads tasks/agent-runs/win-slices/slices.json + progress.json
# Writes results/<slice>-<runId>.json and updates progress.json status=running/result fields.
param(
  [string]$Slice = "",
  [string]$Mode = "auto"  # auto | force
)

$ErrorActionPreference = "Stop"
$AffinityMask = [IntPtr]0x3F

$Root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $Root "gradlew.bat"))) { $Root = (Get-Location).Path }
Set-Location $Root

$SliceRoot = Join-Path $Root "tasks\agent-runs\win-slices"
$SlicesPath = Join-Path $SliceRoot "slices.json"
$ProgressPath = Join-Path $SliceRoot "progress.json"
$ResultsDir = Join-Path $SliceRoot "results"
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
  $env:JAVA_HOME = "C:\Users\dingyi\.jdks\temurin-17.0.11"
}
$javaBin = Join-Path $env:JAVA_HOME "bin"
if (-not (Test-Path (Join-Path $javaBin "java.exe"))) {
  Write-Error "java.exe missing under JAVA_HOME=$env:JAVA_HOME"
  exit 1
}
$env:Path = "$javaBin;$env:Path"
if ([string]::IsNullOrWhiteSpace($env:GRADLE_OPTS)) {
  $env:GRADLE_OPTS = "-Dorg.gradle.workers.max=5"
}

function Limit-JavaProcesses {
  Get-Process -Name java,javaw -ErrorAction SilentlyContinue | ForEach-Object {
    try {
      $_.ProcessorAffinity = $AffinityMask
      $_.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::BelowNormal
    } catch {}
  }
}

if (-not (Test-Path $SlicesPath)) { Write-Error "missing $SlicesPath"; exit 1 }
if (-not (Test-Path $ProgressPath)) { Write-Error "missing $ProgressPath"; exit 1 }

$slicesDoc = Get-Content -Raw -Path $SlicesPath | ConvertFrom-Json
$progress = Get-Content -Raw -Path $ProgressPath | ConvertFrom-Json

# Pick slice
$chosen = $null
if ($Slice -and $Slice.Trim().Length -gt 0) {
  $chosen = $slicesDoc.slices | Where-Object { $_.id -eq $Slice } | Select-Object -First 1
  if (-not $chosen) { Write-Error "Unknown slice id: $Slice"; exit 1 }
} else {
  foreach ($s in $slicesDoc.slices) {
    $st = $progress.slices.($s.id).status
    if ($st -ne "success") { $chosen = $s; break }
  }
}

if (-not $chosen) {
  Write-Host "ALL_SLICES_GREEN"
  $progress.summary.pending = 0
  $progress.updatedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
  ($progress | ConvertTo-Json -Depth 12) | Set-Content -Path $ProgressPath -Encoding UTF8
  exit 0
}

$sliceId = $chosen.id
$runId = $env:GITHUB_RUN_ID
if (-not $runId) { $runId = "local-" + (Get-Date -Format "yyyyMMddHHmmss") }
$sha = $env:GITHUB_SHA
if (-not $sha) {
  try { $sha = (git rev-parse HEAD).Trim() } catch { $sha = "unknown" }
}

Write-Host "SLICE_PICKED=$sliceId runId=$runId"
Write-Host "SLICE_TESTS=$($chosen.tests -join ', ')"

# mark running
if (-not $progress.slices.PSObject.Properties.Name.Contains($sliceId)) {
  $progress.slices | Add-Member -NotePropertyName $sliceId -NotePropertyValue ([pscustomobject]@{})
}
$progress.slices.$sliceId.status = "running"
$progress.slices.$sliceId.lastRunId = "$runId"
$progress.slices.$sliceId.lastSha = "$sha"
$progress.slices.$sliceId.updatedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$progress.updatedAt = $progress.slices.$sliceId.updatedAt
($progress | ConvertTo-Json -Depth 12) | Set-Content -Path $ProgressPath -Encoding UTF8

# Build gradle args
$gArgs = New-Object System.Collections.Generic.List[string]
$gArgs.Add("jvmTest")
$gArgs.Add("--parallel")
$gArgs.Add("--max-workers=5")
foreach ($pat in $chosen.tests) {
  $gArgs.Add("--tests")
  $gArgs.Add($pat)
}

$argLine = ($gArgs | ForEach-Object {
  if ($_ -match '\s') { '"{0}"' -f $_ } else { $_ }
}) -join ' '

$gradlew = Join-Path $Root "gradlew.bat"
$sw = [System.Diagnostics.Stopwatch]::StartNew()

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
while (-not $p.HasExited) { Limit-JavaProcesses; Start-Sleep -Seconds 5 }
$sw.Stop()
$exitCode = $p.ExitCode
Write-Host "SLICE_GRADLE_EXIT=$exitCode durationSec=$([int]$sw.Elapsed.TotalSeconds)"

# Parse junit xml if present
$xmlDir = Join-Path $Root "build\test-results\jvmTest"
$failed = New-Object System.Collections.Generic.List[string]
$tests = 0; $failures = 0; $errors = 0; $skipped = 0
if (Test-Path $xmlDir) {
  Get-ChildItem -Path $xmlDir -Filter "TEST-*.xml" -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
    try {
      [xml]$doc = Get-Content -Raw $_.FullName
      $suite = $doc.testsuite
      if ($suite) {
        $tests += [int]$suite.tests
        $failures += [int]$suite.failures
        $errors += [int]$suite.errors
        if ($suite.skipped) { $skipped += [int]$suite.skipped }
        foreach ($tc in $suite.testcase) {
          $name = "$($tc.classname)#$($tc.name)"
          if ($tc.failure -or $tc.error) { [void]$failed.Add($name) }
        }
      }
    } catch {}
  }
}

$status = if ($exitCode -eq 0 -and ($failures + $errors) -eq 0) { "success" } else { "failure" }
$resultPath = Join-Path $ResultsDir ("{0}-{1}.json" -f $sliceId, $runId)
$resultObj = [ordered]@{
  sliceId = $sliceId
  runId = "$runId"
  sha = "$sha"
  status = $status
  exitCode = $exitCode
  durationSec = [int]$sw.Elapsed.TotalSeconds
  tests = $tests
  failures = $failures
  errors = $errors
  skipped = $skipped
  failedTests = @($failed | Select-Object -Unique)
  testsPatterns = @($chosen.tests)
  finishedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
}
($resultObj | ConvertTo-Json -Depth 8) | Set-Content -Path $resultPath -Encoding UTF8

# Update progress
$progress = Get-Content -Raw -Path $ProgressPath | ConvertFrom-Json
$progress.slices.$sliceId.status = $status
$progress.slices.$sliceId.lastRunId = "$runId"
$progress.slices.$sliceId.lastSha = "$sha"
$progress.slices.$sliceId.tests = $tests
$progress.slices.$sliceId.failures = $failures + $errors
$progress.slices.$sliceId.errors = $errors
$progress.slices.$sliceId.failedTests = @($failed | Select-Object -Unique)
$progress.slices.$sliceId.durationSec = [int]$sw.Elapsed.TotalSeconds
$progress.slices.$sliceId.artifact = "results/$(Split-Path $resultPath -Leaf)"
$progress.slices.$sliceId.updatedAt = $resultObj.finishedAt
$progress.updatedAt = $resultObj.finishedAt

# recount summary
$succ=0;$fail=0;$pend=0;$runn=0
foreach ($s in $slicesDoc.slices) {
  $st = $progress.slices.($s.id).status
  switch ($st) {
    "success" { $succ++ }
    "failure" { $fail++ }
    "running" { $runn++ }
    default { $pend++ }
  }
}
$progress.summary = [ordered]@{ total = $slicesDoc.slices.Count; pending = $pend; success = $succ; failure = $fail; running = $runn }
if (-not $progress.history) { $progress.history = @() }
$hist = [ordered]@{ sliceId=$sliceId; runId="$runId"; status=$status; tests=$tests; failures=($failures+$errors); durationSec=[int]$sw.Elapsed.TotalSeconds; at=$resultObj.finishedAt }
$progress.history = @($progress.history + $hist) | Select-Object -Last 50
($progress | ConvertTo-Json -Depth 12) | Set-Content -Path $ProgressPath -Encoding UTF8

# Human progress bar line
$done = $succ + $fail
Write-Host ("PROGRESS_BAR {0}/{1} success={2} failure={3} pending={4} current={5}:{6}" -f $done, $progress.summary.total, $succ, $fail, $pend, $sliceId, $status)

# Also copy result to GITHUB_WORKSPACE root for easy artifact
try {
  Copy-Item $resultPath (Join-Path $Root "slice-result.json") -Force
  Copy-Item $ProgressPath (Join-Path $Root "slice-progress.json") -Force
} catch {}

# Exit non-zero on slice failure so Action is red for that slice (expected)
exit $exitCode
