# Run ONE test slice on Windows self-hosted runner (progress-bar mode).
# GATE: must-green-to-advance — only the first non-success slice runs; failures re-run
# the SAME slice until status=success. Never skip a red slice to go to the next.
param(
  [string]$Slice = "",
  [string]$Mode = "auto"  # auto | force (force still only runs requested id; does not mark others success)
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

function Get-SliceStatus($progress, $id) {
  try { return [string]$progress.slices.$id.status } catch { return "pending" }
}

# ConvertFrom-Json yields PSCustomObjects that reject new property assignment in PS 5.1
# (PropertyNotFound). Pending slices only have status/label/fileCount — set lastRunId
# etc. via Add-Member -Force so first-run slices do not crash before gradle.
function Set-NoteProp($obj, [string]$name, $value) {
  if ($null -eq $obj) { return }
  if ($obj.PSObject.Properties.Name -contains $name) {
    $obj.$name = $value
  } else {
    $obj | Add-Member -NotePropertyName $name -NotePropertyValue $value -Force
  }
}

function Ensure-SliceObject($progress, [string]$id) {
  if (-not ($progress.slices.PSObject.Properties.Name -contains $id)) {
    $progress.slices | Add-Member -NotePropertyName $id -NotePropertyValue ([pscustomobject]@{}) -Force
  }
  $sliceObj = $progress.slices.$id
  if ($null -eq $sliceObj) {
    $sliceObj = [pscustomobject]@{}
    $progress.slices | Add-Member -NotePropertyName $id -NotePropertyValue $sliceObj -Force
  }
  return $sliceObj
}

if (-not (Test-Path $SlicesPath)) { Write-Error "missing $SlicesPath"; exit 1 }
if (-not (Test-Path $ProgressPath)) { Write-Error "missing $ProgressPath"; exit 1 }

$slicesDoc = Get-Content -Raw -Path $SlicesPath | ConvertFrom-Json
$progress = Get-Content -Raw -Path $ProgressPath | ConvertFrom-Json

Write-Host "GATE=must-green-to-advance (failure blocks next slice)"

# Find first non-success in order (the blocked head)
$head = $null
foreach ($s in $slicesDoc.slices) {
  $st = Get-SliceStatus $progress $s.id
  if ($st -ne "success") { $head = $s; break }
}

$chosen = $null
if ($Slice -and $Slice.Trim().Length -gt 0) {
  $req = $slicesDoc.slices | Where-Object { $_.id -eq $Slice } | Select-Object -First 1
  if (-not $req) { Write-Error "Unknown slice id: $Slice"; exit 1 }
  # GATE: cannot run a later slice while an earlier one is not green
  if ($head -and $req.id -ne $head.id) {
    $headSt = Get-SliceStatus $progress $head.id
    Write-Host "GATE_BLOCKED requested=$($req.id) but head=$($head.id) status=$headSt"
    Write-Host "Must fix and green $head.id before running $($req.id)"
    exit 2
  }
  $chosen = $req
} else {
  $chosen = $head
}

if (-not $chosen) {
  Write-Host "ALL_SLICES_GREEN"
  $progress.summary.pending = 0
  $progress.activeSlice = $null
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

$prev = Get-SliceStatus $progress $sliceId
Write-Host "SLICE_PICKED=$sliceId prevStatus=$prev runId=$runId"
Write-Host "SLICE_FILE_COUNT=$($chosen.fileCount)"
Write-Host "SLICE_TESTS=$($chosen.tests -join ', ')"
if ($prev -eq "failure") {
  Write-Host "GATE_RERUN same red slice until green — will not advance"
}

# mark running (Add-Member for first-run pending slices that lack lastRunId/etc.)
$sliceObj = Ensure-SliceObject $progress $sliceId
$nowMark = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
Set-NoteProp $sliceObj "status" "running"
Set-NoteProp $sliceObj "lastRunId" "$runId"
Set-NoteProp $sliceObj "lastSha" "$sha"
Set-NoteProp $sliceObj "updatedAt" $nowMark
$progress.activeSlice = $sliceId
$progress.strategy = "must-green-to-advance"
$progress.updatedAt = $nowMark
($progress | ConvertTo-Json -Depth 12) | Set-Content -Path $ProgressPath -Encoding UTF8

# Build gradle args — one FQCN per --tests
$gArgs = New-Object System.Collections.Generic.List[string]
$gArgs.Add("jvmTest")
$gArgs.Add("--parallel")
$gArgs.Add("--max-workers=5")
foreach ($pat in $chosen.tests) {
  $gArgs.Add("--tests")
  $gArgs.Add([string]$pat)
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

# Parse junit xml
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
  gate = "must-green-to-advance"
  finishedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
}
($resultObj | ConvertTo-Json -Depth 8) | Set-Content -Path $resultPath -Encoding UTF8

# Update progress
$progress = Get-Content -Raw -Path $ProgressPath | ConvertFrom-Json
$sliceObj = Ensure-SliceObject $progress $sliceId
Set-NoteProp $sliceObj "status" $status
Set-NoteProp $sliceObj "lastRunId" "$runId"
Set-NoteProp $sliceObj "lastSha" "$sha"
Set-NoteProp $sliceObj "tests" $tests
Set-NoteProp $sliceObj "failures" ($failures + $errors)
Set-NoteProp $sliceObj "errors" $errors
Set-NoteProp $sliceObj "failedTests" @($failed | Select-Object -Unique)
Set-NoteProp $sliceObj "durationSec" ([int]$sw.Elapsed.TotalSeconds)
Set-NoteProp $sliceObj "artifact" ("results/$(Split-Path $resultPath -Leaf)")
Set-NoteProp $sliceObj "updatedAt" $resultObj.finishedAt
$progress.activeSlice = $sliceId
$progress.strategy = "must-green-to-advance"
$progress.updatedAt = $resultObj.finishedAt

$succ=0;$fail=0;$pend=0;$runn=0;$filesDone=0
foreach ($s in $slicesDoc.slices) {
  $st = Get-SliceStatus $progress $s.id
  switch ($st) {
    "success" { $succ++; $filesDone += [int]$s.fileCount }
    "failure" { $fail++ }
    "running" { $runn++ }
    default { $pend++ }
  }
}
$progress.summary = [ordered]@{
  total = $slicesDoc.slices.Count
  totalFiles = $slicesDoc.totalFiles
  pending = $pend
  success = $succ
  failure = $fail
  running = $runn
  filesDone = $filesDone
}
# history: ConvertFrom-Json may yield a single PSObject (no op_Addition) — always list-append
$hist = [pscustomobject]@{
  sliceId = $sliceId
  runId = "$runId"
  status = $status
  tests = $tests
  failures = ($failures + $errors)
  durationSec = [int]$sw.Elapsed.TotalSeconds
  at = $resultObj.finishedAt
}
$histList = New-Object System.Collections.Generic.List[object]
if ($null -ne $progress.history) {
  foreach ($item in @($progress.history)) { [void]$histList.Add($item) }
}
[void]$histList.Add($hist)
while ($histList.Count -gt 50) { $histList.RemoveAt(0) }
$progress.history = $histList.ToArray()
($progress | ConvertTo-Json -Depth 12) | Set-Content -Path $ProgressPath -Encoding UTF8

Write-Host ("PROGRESS_BAR {0}/{1} success={2} failure={3} pending={4} current={5}:{6}" -f ($succ), $progress.summary.total, $succ, $fail, $pend, $sliceId, $status)
if ($status -eq "success") {
  Write-Host "GATE_OPEN next slice may run after this progress is committed"
} else {
  Write-Host "GATE_CLOSED slice $sliceId is RED — fix then re-run SAME slice; do not advance"
}

try {
  Copy-Item $resultPath (Join-Path $Root "slice-result.json") -Force
  Copy-Item $ProgressPath (Join-Path $Root "slice-progress.json") -Force
} catch {}

exit $exitCode
