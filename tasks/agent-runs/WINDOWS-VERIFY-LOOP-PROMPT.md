# WINDOWS-VERIFY SLICE LOOP — MUST-GREEN-TO-ADVANCE
# Fire every cron tick. EXECUTE IMMEDIATELY.

You are master for lua-parser Windows file-chunk verification.

## Hard gate
A slice must be success before the next slice runs.
- Head = first slice in slices.json order with status != success.
- RED head -> workers fix -> re-run SAME head only.
- GREEN head -> advance to next pending.
- NEVER skip a red slice. NEVER mark red as success without evidence.

## Mode
- Branch: windows-verify only (never main).
- ~45 test files / Action (~3× prior 15-file slices; 18 slices, 367 files; s001–s014 legacy 15-file green retained).
- Progress: tasks/agent-runs/win-slices/progress.json + PROGRESS.md
- strategy: must-green-to-advance
- Workers: <=30 concurrent, 1 task = 1 agent. No docs filler.
- Full-chain: tasks/agent-runs/watch-windows-slice-dispatch.js
- Forbidden: full jvmTest; Mac full suite; watch-only without merge/workers/push.

## On every tick — NOW

### 1) Inventory
```bash
cd /Users/dingyi/projects/java_projects/lua-parser
gh run list --branch windows-verify --workflow=windows-jvmtest.yml --limit 5
python3 - <<'PY'
import json
p=json.load(open('tasks/agent-runs/win-slices/progress.json'))
s=json.load(open('tasks/agent-runs/win-slices/slices.json'))
print('strategy', p.get('strategy'), 'chunkSize', s.get('chunkSize'), 'summary', p.get('summary'))
for sl in s['slices']:
    st=p['slices'][sl['id']]['status']
    if st!='success':
        print('HEAD', sl['id'], st, 'files', sl['fileCount']); break
else:
    print('ALL_GREEN')
PY
```
If slice full-chain already live for current run -> status line only (no duplicate).

### 2) State machine
A. Action in_progress/queued: ensure watch-windows-slice-dispatch.js running.
B. Action completed: merge progress; if RED: <=30 workers -> push (gate hold, same slice); if GREEN: push progress -> next pending.
C. Idle + pending/failure head: push if needed or gh workflow run windows-jvmtest.yml --ref windows-verify.
D. ALL_GREEN: report progress bar complete for TASK-043 evidence path.

### 3) Status line
Action URL + HEAD slice id/status + summary + chain id + workers + chunkSize.

## Key paths
- scripts/windows-run-slice.ps1 (GATE in picker)
- tasks/agent-runs/win-slices/
- tasks/agent-runs/watch-windows-slice-dispatch.js
