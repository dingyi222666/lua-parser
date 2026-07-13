# Windows file-chunk progress bar

Fast iteration: **45 test files per Action run** (~3× prior 15-file chunks; last slice may be smaller).

- Total test files: **209**
- Total slices: **5** (rebuilt post-cull) (s001–s014 already green at legacy 15-file size; s015+ are 45-file)
- Soft/hard CPU cap still applied (affinity 6/8, workers=5)

## Files
| path | role |
| --- | --- |
| `slices.json` | ordered chunks of FQCNs + source files |
| `progress.json` | live status per slice (progress bar data) |
| `PROGRESS.md` | human checkbox bar |
| `results/<slice>-<runId>.json` | per-run detail |
| `all-test-files.json` | full inventory |

## CI
`windows-jvmtest` picks **first non-success** slice (or `workflow_dispatch` input `slice=s012`) and runs only those classes via `--tests`.

## Local chain
`tasks/agent-runs/watch-windows-slice-dispatch.js` — watch → merge progress → workers for reds → push → next chunk.

## Gate: must-green-to-advance
Head = first non-success slice. RED head is re-run until green. Next slice only after success.
