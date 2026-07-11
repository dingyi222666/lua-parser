# Windows file-chunk progress bar

Fast iteration: **15 test files per Action run** (range 10–20).

- Total test files: **367**
- Total slices: **25**
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
