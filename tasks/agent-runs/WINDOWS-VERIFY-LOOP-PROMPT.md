# WINDOWS-VERIFY SLICE LOOP PROMPT (fast iteration)
# Fire on every cron tick. EXECUTE IMMEDIATELY — no recap.

You are master for lua-parser Windows **file-chunk** verification (NOT full jvmTest).

## Mode
- Branch: `windows-verify` only (never main).
- CI: Action `windows-jvmtest` runs **one slice** of ~15 test files (10–20).
- Progress bar: `tasks/agent-runs/win-slices/progress.json` + `PROGRESS.md`
- Inventory: `tasks/agent-runs/win-slices/slices.json` (25 slices / 367 files / chunk 15)
- CPU hard-cap: affinity 6/8 + workers=5 still on.
- Workers: **≤30 concurrent**, 1 task = 1 agent. No docs filler.
- Full-chain script: `tasks/agent-runs/watch-windows-slice-dispatch.js`
- Forbidden: Mac full jvmTest; full-suite Windows jvmTest; watch-only without merge/workers/push.

## On every tick — NOW

### 1) Inventory
```bash
cd /Users/dingyi/projects/java_projects/lua-parser
gh run list --branch windows-verify --workflow=windows-jvmtest.yml --limit 5
git branch --show-current
python3 -c "import json;p=json.load(open('tasks/agent-runs/win-slices/progress.json'));print(p['summary'])"
```
If `watch-windows-slice-dispatch` already live for current/latest run → status line only.

### 2) State machine
**A. Action in_progress/queued**
- Ensure slice full-chain workflow is running (watch→download→merge→≤30 workers→push).
- Launch `tasks/agent-runs/watch-windows-slice-dispatch.js` if none live.

**B. Action completed**
- If chain not done for that runId: launch/repair slice full-chain.
- Chain merges progress bar, materializes ≤30 product tasks for reds, workers, pushes.
- Push of updated `progress.json` triggers **next** pending slice automatically.

**C. Idle / no run**
- If pending slices remain and runner free: `git push` if unpushed, else `gh workflow run windows-jvmtest.yml --ref windows-verify`
- If all slices success: report progress bar complete; consider TASK-043 evidence from accumulated results.

### 3) Status line
Action URL + slice id if known + progress summary (success/failure/pending) + chain workflow id + workers.

## Key paths
- Workflow: `.github/workflows/windows-jvmtest.yml`
- Slice runner: `scripts/windows-run-slice.ps1`
- Progress: `tasks/agent-runs/win-slices/`
- Chain: `tasks/agent-runs/watch-windows-slice-dispatch.js`
