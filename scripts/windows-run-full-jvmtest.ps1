# Full jvmTest on Windows self-hosted runner (CPU hard-capped).
# For TASK-043 tip-coherent suite evidence — NOT the slice progress bar.
# Affinity 0x3F / BelowNormal / workers.max=5 via windows-run-gradle-capped.ps1.
param(
  [switch]$CompileOnly
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $Root "gradlew.bat"))) { $Root = (Get-Location).Path }
Set-Location $Root

$EvidenceDir = Join-Path $Root "tasks\agent-runs\full-jvmtest"
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null

if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
  $env:JAVA_HOME = "C:\Users\dingyi\.jdks\temurin-17.0.11"
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$runId = $env:GITHUB_RUN_ID
if (-not $runId) { $runId = "local-" + (Get-Date -Format "yyyyMMddHHmmss") }
$sha = $env:GITHUB_SHA
if (-not $sha) {
  try { $sha = (git rev-parse HEAD).Trim() } catch { $sha = "unknown" }
}
$startedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")

Write-Host "FULL_JVMTEST mode=tip-coherent runId=$runId sha=$sha"
Write-Host "FULL_JVMTEST evidenceDir=$EvidenceDir"

$capped = Join-Path $Root "scripts\windows-run-gradle-capped.ps1"
if (-not (Test-Path $capped)) {
  Write-Error "missing $capped"
  exit 1
}

# 1) compile gate
Write-Host "FULL_JVMTEST step=compileTestKotlinJvm"
& $capped compileTestKotlinJvm --parallel --max-workers=5
$compileExit = $LASTEXITCODE
Write-Host "FULL_JVMTEST compileExit=$compileExit"
if ($compileExit -ne 0) {
  $failObj = [ordered]@{
    mode = "full-jvmtest"
    runId = "$runId"
    sha = "$sha"
    status = "compileFailure"
    compileExit = $compileExit
    startedAt = $startedAt
    finishedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    gate = "task-043-full-jvmtest"
  }
  ($failObj | ConvertTo-Json -Depth 6) | Set-Content -Path (Join-Path $Root "full-jvmtest-result.json") -Encoding UTF8
  Copy-Item (Join-Path $Root "full-jvmtest-result.json") (Join-Path $EvidenceDir ("full-jvmtest-{0}.json" -f $runId)) -Force
  exit $compileExit
}

if ($CompileOnly) {
  Write-Host "FULL_JVMTEST CompileOnly=true — stop after compile"
  exit 0
}

# 2) full jvmTest (no --tests filter)
Write-Host "FULL_JVMTEST step=jvmTest (full suite, no --tests filter)"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
& $capped jvmTest --parallel --max-workers=5
$testExit = $LASTEXITCODE
$sw.Stop()
Write-Host "FULL_JVMTEST jvmTestExit=$testExit durationSec=$([int]$sw.Elapsed.TotalSeconds)"

# Parse junit
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

$status = if ($testExit -eq 0 -and ($failures + $errors) -eq 0) { "success" } else { "failure" }
$finishedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$resultObj = [ordered]@{
  mode = "full-jvmtest"
  runId = "$runId"
  sha = "$sha"
  status = $status
  compileExit = 0
  exitCode = $testExit
  durationSec = [int]$sw.Elapsed.TotalSeconds
  tests = $tests
  failures = $failures
  errors = $errors
  skipped = $skipped
  failedTests = @($failed | Select-Object -Unique)
  gate = "task-043-full-jvmtest"
  startedAt = $startedAt
  finishedAt = $finishedAt
}
$outRoot = Join-Path $Root "full-jvmtest-result.json"
($resultObj | ConvertTo-Json -Depth 8) | Set-Content -Path $outRoot -Encoding UTF8
$outEvidence = Join-Path $EvidenceDir ("full-jvmtest-{0}.json" -f $runId)
Copy-Item $outRoot $outEvidence -Force
Write-Host ("FULL_JVMTEST_RESULT status={0} tests={1} failures={2} errors={3} skipped={4}" -f $status, $tests, ($failures + $errors), $errors, $skipped)
if ($failed.Count -gt 0) {
  Write-Host "FULL_JVMTEST_FAILED_SAMPLE:"
  $failed | Select-Object -First 40 | ForEach-Object { Write-Host "  FAIL $_" }
}

exit $testExit
