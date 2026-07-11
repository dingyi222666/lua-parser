export const meta = {
  name: 'watch-windows-slice-dispatch',
  description: 'Watch Windows slice Action → merge progress bar → ≤30 workers → push next slice',
  phases: [
    { title: 'Watch', detail: 'Poll slice Action until completed' },
    { title: 'Download', detail: 'Download junit-xml + slice-progress artifacts' },
    { title: 'Merge', detail: 'Merge progress.json + PROGRESS.md from artifacts' },
    { title: 'Materialize', detail: 'Create product tasks for this slice reds (≤30)' },
    { title: 'Workers', detail: '30 concurrent product workers max' },
    { title: 'Push', detail: 'Commit progress+fixes on windows-verify and push' },
  ],
}

// Leave empty to auto-detect latest windows-verify windows-jvmtest run
const RUN_ID_OVERRIDE = ''
const REPO = '/Users/dingyi/projects/java_projects/lua-parser'
const SLICE_ROOT = REPO + '/tasks/agent-runs/win-slices'
const ANDROID_JAR = '/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar'
const WAVE_PREFIX = 'WINSLICE'

function workerPrompt(tid, wave) {
  return [
    'ROLE: Worker agent for lua-parser slice-iteration mode.',
    'WAVE: ' + wave,
    'TASK: TASK-' + tid,
    'REPO: ' + REPO,
    '',
    'CONTRACT (strict):',
    '1. Work ONLY on TASK-' + tid + '. Read tasks/TASK-' + tid + '.md fully first.',
    '2. Claim: status=in_progress, owner=worker-' + wave + '-TASK-' + tid + ', UTC progress note.',
    '3. Locks: locks/tasks/TASK-' + tid + '.lock + locks/files for each edited path. If locked by live other task, STOP leave ready.',
    '4. Implement product/test for THIS task AC only — fix the red tests listed in the task.',
    '5. AST quirk: LocalStatement/AssignmentStatement .init=names/LHS, .variables=RHS.',
    '6. Host android.jar: ' + ANDROID_JAR + ' (never G:/).',
    '7. FORBIDDEN: gradle/compile/jvmTest/Java verification, docs-only filler, git push, editing other task requirements.',
    '8. CRITICAL: No KDoc with nested */ (never write android-*/android.jar inside backticks in KDoc). Keep KDoc simple ASCII.',
    '9. Do NOT call package-internal APIs across packages.',
    '10. When done: status=review, owner=unassigned, write tasks/agent-runs/TASK-' + tid + '-WORKER-' + wave + '.last.txt',
    '11. One task only. No batching.',
    '',
    'Return JSON: {taskId:' + tid + ', finalStatus, filesChanged, summary, blockedReason:null or string}',
  ].join('\n')
}

const workerSchema = {
  type: 'object',
  properties: {
    taskId: { type: 'number' },
    finalStatus: { type: 'string' },
    filesChanged: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
    blockedReason: { type: ['string', 'null'] },
  },
  required: ['taskId', 'finalStatus', 'summary'],
  additionalProperties: true,
}

// ---------- Discover RUN_ID ----------
phase('Watch')
const discover = await agent(
  [
    'ROLE: Discover the active or latest windows-jvmtest Action on branch windows-verify.',
    'REPO: ' + REPO,
    'OVERRIDE_RUN_ID: ' + (RUN_ID_OVERRIDE || '(none)'),
    '',
    'If OVERRIDE non-empty use it. Else:',
    'gh run list --branch windows-verify --workflow=windows-jvmtest.yml --limit 5 --json databaseId,status,conclusion,headSha,url,createdAt,displayTitle',
    'Prefer: in_progress/queued first; else most recent completed.',
    '',
    'Then poll until that run status is completed (max ~60 min, sleep 40-50s between polls).',
    'Log steps each poll (jvm-slice job).',
    'FORBIDDEN: cancel run; push; local full jvmTest.',
    'Return JSON: {runId, status, conclusion, url, headSha, stepsSummary, polls}',
  ].join('\n'),
  {
    label: 'watch-slice-run',
    phase: 'Watch',
    model: 'sonnet',
    effort: 'medium',
    schema: {
      type: 'object',
      properties: {
        runId: { type: 'string' },
        status: { type: 'string' },
        conclusion: { type: ['string', 'null'] },
        url: { type: 'string' },
        headSha: { type: 'string' },
        stepsSummary: { type: 'string' },
        polls: { type: 'number' },
      },
      required: ['runId', 'status', 'stepsSummary'],
      additionalProperties: true,
    },
  }
)

const RUN_ID = String((discover && discover.runId) || RUN_ID_OVERRIDE || '').trim()
const WAVE = WAVE_PREFIX + '-' + RUN_ID
const ART_DIR = REPO + '/tasks/agent-runs/win-xml-' + RUN_ID
log('discovered runId=' + RUN_ID + ' conclusion=' + (discover && discover.conclusion))

// ---------- Download ----------
phase('Download')
const download = await agent(
  [
    'ROLE: Download slice artifacts for run ' + RUN_ID + '.',
    'WORKDIR: ' + REPO,
    'Confirm completed: gh run view ' + RUN_ID + ' --json status,conclusion',
    'If still running: poll sleep 40 until completed (max 30 min).',
    '',
    'mkdir -p ' + ART_DIR + '/junit ' + ART_DIR + '/slice ' + ART_DIR + '/html',
    'cd ' + REPO,
    'gh run download ' + RUN_ID + ' -n junit-xml -D ' + ART_DIR + '/junit 2>&1 || true',
    'gh run download ' + RUN_ID + ' -n slice-progress -D ' + ART_DIR + '/slice 2>&1 || true',
    'gh run download ' + RUN_ID + ' -n test-report-html -D ' + ART_DIR + '/html 2>&1 || true',
    'find ' + ART_DIR + ' -type f | head -80',
    'find ' + ART_DIR + ' -name "TEST-*.xml" | wc -l',
    'find ' + ART_DIR + ' -name "slice-result.json" -o -name "progress.json" -o -name "slice-progress.json" | head',
    '',
    'Return {ok, runId, xmlCount, hasSliceResult, artDir, conclusion, notes}',
  ].join('\n'),
  {
    label: 'download-slice',
    phase: 'Download',
    model: 'haiku',
    effort: 'low',
    schema: {
      type: 'object',
      properties: {
        ok: { type: 'boolean' },
        runId: { type: 'string' },
        xmlCount: { type: 'number' },
        hasSliceResult: { type: 'boolean' },
        artDir: { type: 'string' },
        conclusion: { type: 'string' },
        notes: { type: 'string' },
      },
      required: ['ok', 'notes'],
      additionalProperties: true,
    },
  }
)

// ---------- Merge progress bar ----------
phase('Merge')
const merge = await agent(
  [
    'ROLE: Merge Windows slice progress bar into repo working tree.',
    'REPO: ' + REPO,
    'SLICE_ROOT: ' + SLICE_ROOT,
    'ART: ' + ART_DIR,
    'RUN_ID: ' + RUN_ID,
    '',
    '1. Locate artifact files: slice-result.json, progress.json, slice-progress.json, results/*.json under ' + ART_DIR,
    '2. Prefer CI-updated progress.json from artifact over local if it has newer updatedAt / history for this runId.',
    '3. Copy/merge into:',
    '   - ' + SLICE_ROOT + '/progress.json',
    '   - ' + SLICE_ROOT + '/results/ (copy any new result json)',
    '4. Rewrite ' + SLICE_ROOT + '/PROGRESS.md checkbox bar from progress.json:',
    '   [x] success, [F] failure, [ ] pending, [~] running',
    '   Include summary line: success/failure/pending/total and filesDone.',
    '5. Parse ALL TEST-*.xml under art for failed classname#method list; write ' + ART_DIR + '/failures.txt',
    '6. Write report ' + REPO + '/tasks/agent-runs/REVIEW-SLICE-' + RUN_ID + '.last.txt as JSON:',
    '   {runId, sliceId, status, tests, failures, failedTests, progressSummary, notes}',
    '',
    'Do NOT push. Do NOT run gradle.',
    'Return {sliceId, status, failureCount, failedTests, progressSummary, notes}',
  ].join('\n'),
  {
    label: 'merge-progress',
    phase: 'Merge',
    model: 'sonnet',
    effort: 'high',
    schema: {
      type: 'object',
      properties: {
        sliceId: { type: 'string' },
        status: { type: 'string' },
        failureCount: { type: 'number' },
        failedTests: { type: 'array', items: { type: 'string' } },
        progressSummary: { type: 'object' },
        notes: { type: 'string' },
      },
      required: ['sliceId', 'status', 'failureCount', 'notes'],
      additionalProperties: true,
    },
  }
)

// ---------- Materialize up to 30 product tasks ----------
phase('Materialize')
const materialize = await agent(
  [
    'ROLE: Materialize ≤30 product fix tasks for THIS slice reds only.',
    'REPO: ' + REPO,
    'RUN_ID: ' + RUN_ID,
    'WAVE: ' + WAVE,
    'Merge result: ' + JSON.stringify(merge || {}),
    'Failures file: ' + ART_DIR + '/failures.txt',
    'Report: ' + REPO + '/tasks/agent-runs/REVIEW-SLICE-' + RUN_ID + '.last.txt',
    '',
    'Rules:',
    '- If failureCount==0 / status success: write empty worker id list; notes=slice green.',
    '- Else create or reuse ready product tasks for failed tests (cluster related methods; max 30 tasks).',
    '- Task AC must list classname#method; evidence Windows slice run ' + RUN_ID + '.',
    '- NO docs filler.',
    '- Write ' + REPO + '/tasks/agent-runs/' + WAVE + '-WORKER-IDS.json as JSON number array (max 30).',
    '',
    'FORBIDDEN: implement fixes yourself; gradle; push.',
    'Return {taskIds:number[], created:number[], reused:number[], notes}',
  ].join('\n'),
  {
    label: 'materialize-slice-tasks',
    phase: 'Materialize',
    model: 'sonnet',
    effort: 'high',
    schema: {
      type: 'object',
      properties: {
        taskIds: { type: 'array', items: { type: 'number' } },
        created: { type: 'array', items: { type: 'number' } },
        reused: { type: 'array', items: { type: 'number' } },
        notes: { type: 'string' },
      },
      required: ['taskIds', 'notes'],
      additionalProperties: true,
    },
  }
)

const ids = Array.isArray(materialize && materialize.taskIds)
  ? materialize.taskIds.slice(0, 30)
  : []
log('worker ids (' + ids.length + '): ' + JSON.stringify(ids))

// ---------- Workers: single batch up to 30 ----------
phase('Workers')
let workers = []
if (ids.length) {
  workers = await parallel(
    ids.map((tid) => () =>
      agent(workerPrompt(tid, WAVE), {
        label: 'w-T' + tid,
        phase: 'Workers',
        model: 'sonnet',
        effort: 'medium',
        schema: workerSchema,
      })
    )
  )
  workers = (workers || []).filter(Boolean)
} else {
  log('no workers — slice green or no tasks')
}

// ---------- Push progress + fixes; retrigger next slice ----------
phase('Push')
const push = await agent(
  [
    'ROLE: Commit slice progress + product fixes on windows-verify and push to run NEXT slice.',
    'REPO: ' + REPO,
    'BRANCH MUST BE windows-verify. NEVER main.',
    'RUN_ID: ' + RUN_ID,
    'WAVE: ' + WAVE,
    '',
    '1. git checkout windows-verify if needed; git status -sb',
    '2. Stage:',
    '   - tasks/agent-runs/win-slices/progress.json',
    '   - tasks/agent-runs/win-slices/PROGRESS.md',
    '   - tasks/agent-runs/win-slices/results/**',
    '   - tasks/agent-runs/REVIEW-SLICE-* / WORKER-IDS / failures notes if useful',
    '   - product src/ + tasks/TASK-*.md from workers',
    '   - scripts/ and workflow if already part of tree',
    '3. Do NOT stage: .claude/, org/, handoff noise, sh.exe.stackdump, gradlew.lf/unix junk',
    '4. If nothing to commit: still ensure progress files staged if dirty; if truly clean return pushed:false',
    '5. Commit: ci(windows): slice progress after run ' + RUN_ID + ' (' + WAVE + ')',
    '6. git push origin windows-verify',
    '7. gh run list --branch windows-verify --workflow=windows-jvmtest.yml --limit 2',
    '   Next run should auto-pick next non-success slice via progress.json.',
    '',
    'FORBIDDEN: push main; force push; empty meaningless commits without progress/product.',
    'Return {pushed, commit, newRunId, notes}',
  ].join('\n'),
  {
    label: 'push-next-slice',
    phase: 'Push',
    model: 'sonnet',
    effort: 'medium',
    schema: {
      type: 'object',
      properties: {
        pushed: { type: 'boolean' },
        commit: { type: 'string' },
        newRunId: { type: ['string', 'null'] },
        notes: { type: 'string' },
      },
      required: ['pushed', 'notes'],
      additionalProperties: true,
    },
  }
)

return {
  discover,
  download,
  merge,
  materialize,
  workerCount: workers.length,
  workerSample: workers.slice(0, 10),
  push,
}
