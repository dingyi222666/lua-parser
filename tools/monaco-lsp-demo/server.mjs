#!/usr/bin/env node
/**
 * Monaco LSP demo bridge:
 *  - HTTP static files + /api/*
 *  - WebSocket /lsp  (one JSON-RPC object per WS message)
 *  - Starts the JVM lua-parser LSP through Gradle over stdio (Content-Length framing)
 *
 * Usage (from repo root or this dir):
 *   node tools/monaco-lsp-demo/server.mjs
 *   # or: cd tools/monaco-lsp-demo && npm start
 *
 * Env:
 *   PORT=3099
 *   JAVA_HOME=...      JDK used to run Gradle
 *   ANDROID_JAR=...   optional override
 *   LUA_PARSER_ROOT=... repo root (auto-detected)
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import { WebSocketServer } from 'ws';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const DEMO_ROOT = __dirname;
const PUBLIC_DIR = path.join(DEMO_ROOT, 'public');
const WORKSPACE_DIR = path.join(DEMO_ROOT, 'workspace');

function findRepoRoot() {
  if (process.env.LUA_PARSER_ROOT) return path.resolve(process.env.LUA_PARSER_ROOT);
  let dir = DEMO_ROOT;
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'build.gradle.kts')) &&
        fs.existsSync(path.join(dir, 'src', 'jvmMain'))) {
      return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return path.resolve(DEMO_ROOT, '../..');
}

const REPO_ROOT = findRepoRoot();
const PORT = Number(process.env.PORT || 3099);

function resolveAndroidJar() {
  if (process.env.ANDROID_JAR && fs.existsSync(process.env.ANDROID_JAR)) {
    return process.env.ANDROID_JAR;
  }
  const sdkRoots = [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    process.env.HOME && path.join(process.env.HOME, 'Library', 'Android', 'sdk'),
    process.env.HOME && path.join(process.env.HOME, 'Android', 'Sdk'),
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'),
  ].filter(Boolean);
  for (const sdkRoot of sdkRoots) {
    const platforms = path.join(sdkRoot, 'platforms');
    if (!fs.existsSync(platforms)) continue;
    const versions = fs.readdirSync(platforms, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && /^android-\d+$/.test(entry.name))
      .sort((a, b) => Number(b.name.slice(8)) - Number(a.name.slice(8)));
    for (const version of versions) {
      const candidate = path.join(platforms, version.name, 'android.jar');
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  return '';
}

function fileUri(absPath) {
  return pathToFileURL(path.resolve(absPath)).href;
}

function listWorkspaceFiles() {
  if (!fs.existsSync(WORKSPACE_DIR)) return [];
  /** Recursive .lua/.aly under workspace (AndroLua .alp projects use subdirs). */
  const out = [];
  const walk = (dir, relBase = '') => {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      if (ent.name.startsWith('.')) continue;
      const abs = path.join(dir, ent.name);
      const rel = relBase ? `${relBase}/${ent.name}` : ent.name;
      if (ent.isDirectory()) {
        // Skip heavy binary/native trees from .alp packages
        if (ent.name === 'libs' || ent.name === 'image' || ent.name === 'oat') continue;
        walk(abs, rel);
        continue;
      }
      if (!ent.name.endsWith('.lua') && !ent.name.endsWith('.aly')) continue;
      out.push({
        name: rel,
        path: abs,
        uri: fileUri(abs),
        text: fs.readFileSync(abs, 'utf8'),
      });
    }
  };
  walk(WORKSPACE_DIR);
  return out.sort((a, b) => a.name.localeCompare(b.name));
}

function javaMajor(javaCommand) {
  const result = spawnSync(javaCommand, ['-version'], { encoding: 'utf8' });
  const output = `${result.stdout || ''}\n${result.stderr || ''}`;
  const match = /version\s+"(?:1\.)?(\d+)/.exec(output);
  return match ? Number(match[1]) : null;
}

function javaHomeCandidates(parent, suffix = '') {
  if (!parent || !fs.existsSync(parent)) return [];
  return fs.readdirSync(parent, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => path.join(parent, entry.name, suffix))
    .filter((candidate) => fs.existsSync(candidate));
}

function resolveJava17Home() {
  const executable = process.platform === 'win32' ? 'java.exe' : 'java';
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME);

  if (process.platform === 'darwin' && fs.existsSync('/usr/libexec/java_home')) {
    const result = spawnSync('/usr/libexec/java_home', ['-v', '17'], { encoding: 'utf8' });
    if (result.status === 0 && result.stdout.trim()) candidates.push(result.stdout.trim());
    candidates.push(...javaHomeCandidates(
      path.join(process.env.HOME || '', 'Library', 'Java', 'JavaVirtualMachines'),
      path.join('Contents', 'Home')
    ));
  } else if (process.platform === 'win32') {
    candidates.push(...javaHomeCandidates(path.join(process.env.USERPROFILE || '', '.jdks')));
    candidates.push(...javaHomeCandidates(path.join(process.env.ProgramFiles || '', 'Java')));
    candidates.push(...javaHomeCandidates(path.join(process.env.ProgramFiles || '', 'Eclipse Adoptium')));
  } else {
    candidates.push(...javaHomeCandidates('/usr/lib/jvm'));
    candidates.push(...javaHomeCandidates(path.join(process.env.HOME || '', '.jdks')));
  }
  candidates.push(...javaHomeCandidates(path.join(process.env.HOME || '', '.sdkman', 'candidates', 'java')));

  for (const candidate of [...new Set(candidates.map((home) => path.resolve(home)))]) {
    const java = path.join(candidate, 'bin', executable);
    if (fs.existsSync(java) && javaMajor(java) === 17) return candidate;
  }
  if (javaMajor('java') === 17) return null;
  throw new Error(
    'JDK 17 is required to start the Monaco LSP demo. Set JAVA_HOME to a JDK 17 installation.'
  );
}

function resolveGradleLaunch() {
  const wrapper = path.join(REPO_ROOT, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  if (!fs.existsSync(wrapper)) {
    throw new Error(`Missing Gradle wrapper: ${wrapper}`);
  }
  const javaHome = resolveJava17Home();
  const env = { ...process.env };
  if (javaHome) {
    env.JAVA_HOME = javaHome;
    env.PATH = `${path.join(javaHome, 'bin')}${path.delimiter}${env.PATH || ''}`;
  }
  return {
    command: wrapper,
    args: ['--no-daemon', '--console=plain', '-q', 'runLuaLanguageServer'],
    shell: process.platform === 'win32',
    env,
    javaHome: javaHome || env.JAVA_HOME || 'PATH java',
  };
}

/** Content-Length framed JSON-RPC reader from a stream. */
class LspFramer {
  constructor() {
    this.buffer = Buffer.alloc(0);
    this.headers = null;
    this.contentLength = null;
  }

  push(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    const messages = [];
    while (true) {
      if (this.contentLength == null) {
        const sep = this.buffer.indexOf('\r\n\r\n');
        if (sep < 0) break;
        const headerText = this.buffer.slice(0, sep).toString('utf8');
        this.buffer = this.buffer.slice(sep + 4);
        let len = null;
        for (const line of headerText.split(/\r\n/)) {
          const m = /^Content-Length:\s*(\d+)/i.exec(line);
          if (m) len = Number(m[1]);
        }
        if (len == null) {
          // skip garbage
          continue;
        }
        this.contentLength = len;
      }
      if (this.buffer.length < this.contentLength) break;
      const body = this.buffer.slice(0, this.contentLength).toString('utf8');
      this.buffer = this.buffer.slice(this.contentLength);
      this.contentLength = null;
      try {
        messages.push(JSON.parse(body));
      } catch (e) {
        console.error('[bridge] bad JSON from LSP', e.message);
      }
    }
    return messages;
  }
}

function frameMessage(obj) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8');
  const header = Buffer.from(`Content-Length: ${body.length}\r\n\r\n`, 'utf8');
  return Buffer.concat([header, body]);
}

function mime(filePath) {
  if (filePath.endsWith('.html')) return 'text/html; charset=utf-8';
  if (filePath.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (filePath.endsWith('.css')) return 'text/css; charset=utf-8';
  if (filePath.endsWith('.json')) return 'application/json; charset=utf-8';
  if (filePath.endsWith('.lua')) return 'text/plain; charset=utf-8';
  return 'application/octet-stream';
}

function apiInfo() {
  const androidJar = resolveAndroidJar();
  return {
    repoRoot: REPO_ROOT,
    workspaceDir: WORKSPACE_DIR,
    workspaceUri: fileUri(WORKSPACE_DIR),
    androidJar,
    androidJarPresent: !!(androidJar && fs.existsSync(androidJar)),
    sampleFiles: listWorkspaceFiles().map((f) => ({ name: f.name, uri: f.uri })),
    lspMain: 'io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt',
    lspLaunch: process.platform === 'win32'
      ? 'gradlew.bat --no-daemon --console=plain -q runLuaLanguageServer'
      : './gradlew --no-daemon --console=plain -q runLuaLanguageServer',
    port: PORT,
    transport: 'websocket-json (one JSON-RPC object per message) → stdio Content-Length',
  };
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', `http://127.0.0.1:${PORT}`);
  if (url.pathname === '/api/info') {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(apiInfo(), null, 2));
    return;
  }
  if (url.pathname === '/api/files') {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(listWorkspaceFiles(), null, 2));
    return;
  }
  if (url.pathname === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  let rel = url.pathname === '/' ? '/index.html' : url.pathname;
  rel = path.normalize(rel).replace(/^(\.\.[/\\])+/, '');
  const filePath = path.join(PUBLIC_DIR, rel);
  if (!filePath.startsWith(PUBLIC_DIR) || !fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('not found');
    return;
  }
  res.writeHead(200, { 'Content-Type': mime(filePath) });
  fs.createReadStream(filePath).pipe(res);
});

const wss = new WebSocketServer({ server, path: '/lsp' });
const lspChildren = new Set();

function stopLspChild(child) {
  if (!child || child.exitCode != null) return;
  try {
    child.stdin.end();
  } catch { /* ignore */ }
  const forceTimer = setTimeout(() => {
    if (child.exitCode == null) child.kill('SIGTERM');
  }, 5000);
  forceTimer.unref();
  child.once('exit', () => clearTimeout(forceTimer));
}

wss.on('connection', (ws) => {
  console.log('[bridge] client connected');
  let child = null;
  let framer = new LspFramer();
  let closed = false;

  const sendStatus = (type, message, extra = {}) => {
    if (ws.readyState === ws.OPEN) {
      ws.send(JSON.stringify({ jsonrpc: '2.0', method: '$/bridge', params: { type, message, ...extra } }));
    }
  };

  try {
    const launch = resolveGradleLaunch();
    console.log('[bridge] JDK 17:', launch.javaHome);
    console.log('[bridge] spawn', launch.command, ...launch.args);
    child = spawn(launch.command, launch.args, {
      cwd: REPO_ROOT,
      env: launch.env,
      shell: launch.shell,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    lspChildren.add(child);
    child.once('spawn', () => {
      sendStatus('spawn', 'Gradle LSP task started', { pid: child.pid });
    });
    child.once('error', (error) => {
      console.error('[bridge] failed to start Gradle LSP task', error);
      sendStatus('error', String(error.message || error));
      if (ws.readyState === ws.OPEN) ws.close();
    });

    child.stdout.on('data', (chunk) => {
      for (const msg of framer.push(chunk)) {
        if (ws.readyState === ws.OPEN) {
          ws.send(JSON.stringify(msg));
        }
      }
    });
    child.stderr.on('data', (chunk) => {
      const text = chunk.toString('utf8');
      process.stderr.write('[lsp-stderr] ' + text);
      sendStatus('stderr', text.slice(0, 2000));
    });
    child.on('exit', (code, signal) => {
      lspChildren.delete(child);
      console.log('[bridge] lsp exit', code, signal);
      sendStatus('exit', `LSP process exited code=${code} signal=${signal}`);
      if (ws.readyState === ws.OPEN) ws.close();
    });
  } catch (e) {
    console.error('[bridge] failed to spawn LSP', e);
    sendStatus('error', String(e.message || e));
    ws.close();
    return;
  }

  ws.on('message', (data) => {
    if (!child || !child.stdin.writable) return;
    let text = Buffer.isBuffer(data) ? data.toString('utf8') : String(data);
    // Allow either raw JSON or already-framed (pass-through body)
    try {
      const obj = JSON.parse(text);
      child.stdin.write(frameMessage(obj));
    } catch {
      // if client sent Content-Length frame, write as-is
      child.stdin.write(Buffer.from(text, 'utf8'));
    }
  });

  ws.on('close', () => {
    if (closed) return;
    closed = true;
    console.log('[bridge] client disconnected');
    stopLspChild(child);
  });
});

function shutdownBridge() {
  wss.clients.forEach((ws) => ws.close());
  lspChildren.forEach(stopLspChild);
  server.close();
}

process.once('SIGINT', shutdownBridge);
process.once('SIGTERM', shutdownBridge);

server.listen(PORT, '127.0.0.1', () => {
  const info = apiInfo();
  console.log(`[monaco-lsp-demo] http://127.0.0.1:${PORT}/`);
  console.log(`[monaco-lsp-demo] workspace: ${info.workspaceDir}`);
  console.log(`[monaco-lsp-demo] android.jar: ${info.androidJar} present=${info.androidJarPresent}`);
  console.log(`[monaco-lsp-demo] repo: ${REPO_ROOT}`);
});
