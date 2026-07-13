#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const demo = path.resolve(__dirname, '..');
const repo = path.resolve(demo, '../..');
const init = path.join(demo, 'scripts/print-cp.init.gradle');
fs.writeFileSync(init, `
gradle.projectsLoaded {
  rootProject.allprojects { project ->
    project.afterEvaluate {
      def cfg = project.configurations.findByName('jvmRuntimeClasspath')
      if (cfg != null) {
        project.tasks.register('printLspClasspath') {
          doLast { println cfg.asPath }
        }
      }
    }
  }
}
`);
const gradlew = fs.existsSync(path.join(repo, 'gradlew.unix'))
  ? path.join(repo, 'gradlew.unix')
  : path.join(repo, 'gradlew');
const r = spawnSync('bash', [gradlew, '-I', init, '-q', 'printLspClasspath', 'jvmJar'], {
  cwd: repo,
  encoding: 'utf8',
  env: process.env,
  maxBuffer: 20 * 1024 * 1024,
});
if (r.status !== 0) {
  console.error(r.stderr || r.stdout);
  process.exit(r.status || 1);
}
const lines = (r.stdout || '').trim().split(/\r?\n/).filter(Boolean);
const cp = lines[lines.length - 1] || '';
fs.writeFileSync(path.join(demo, '.lsp-classpath'), cp + '\n');
console.log('wrote', path.join(demo, '.lsp-classpath'), 'entries', cp.split(path.delimiter).length);
