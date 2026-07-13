#!/usr/bin/env node
/**
 * Monaco LSP demo bridge:
 *  - HTTP static files + /api/*
 *  - WebSocket /lsp  (one JSON-RPC object per WS message)
 *  - Spawns JVM lua-parser LSP over stdio (Content-Length framing)
 *
 * Usage (from repo root or this dir):
 *   node tools/monaco-lsp-demo/server.mjs
 *   # or: cd tools/monaco-lsp-demo && npm start
 *
 * Env:
 *   PORT=3099
 *   JAVA_HOME=...
 *   ANDROID_JAR=...   optional override
 *   LUA_PARSER_ROOT=... repo root (auto-detected)
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
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
const JAVA_HOME = process.env.JAVA_HOME ||
  '/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home';

function resolveAndroidJar() {
  if (process.env.ANDROID_JAR && fs.existsSync(process.env.ANDROID_JAR)) {
    return process.env.ANDROID_JAR;
  }
  const candidates = [
    path.join(process.env.HOME || '', 'Library/Android/sdk/platforms/android-35/android.jar'),
    path.join(process.env.HOME || '', 'Library/Android/sdk/platforms/android-34/android.jar'),
    '/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar',
  ];
  for (const c of candidates) {
    if (c && fs.existsSync(c)) return c;
  }
  return candidates[0] || '';
}

function fileUri(absPath) {
  const normalized = path.resolve(absPath).split(path.sep).join('/');
  // file:///Users/... on macOS
  if (normalized.startsWith('/')) return 'file://' + encodeURI(normalized);
  return 'file:///' + encodeURI(normalized.replace(/^([A-Za-z]):/, '$1:'));
}

function listWorkspaceFiles() {
  if (!fs.existsSync(WORKSPACE_DIR)) return [];
  return fs.readdirSync(WORKSPACE_DIR)
    .filter((n) => n.endsWith('.lua') || n.endsWith('.aly'))
    .sort()
    .map((name) => {
      const abs = path.join(WORKSPACE_DIR, name);
      return {
        name,
        path: abs,
        uri: fileUri(abs),
        text: fs.readFileSync(abs, 'utf8'),
      };
    });
}

function resolveJvmClasspath() {
  const jar = path.join(REPO_ROOT, 'build/libs/luaparser-jvm-1.0.3.jar');
  if (!fs.existsSync(jar)) {
    throw new Error(
      `Missing ${jar}. Run: bash ./gradlew.unix jvmJar  (from repo root)`
    );
  }
  // Prefer a cached classpath dump written by scripts/write-lsp-classpath.mjs
  const cpFile = path.join(DEMO_ROOT, '.lsp-classpath');
  if (fs.existsSync(cpFile)) {
    const extra = fs.readFileSync(cpFile, 'utf8').trim();
    if (extra) return [jar, ...extra.split(path.delimiter).filter(Boolean)].join(path.delimiter);
  }
  // Fallback: known dependency layout from gradle caches (best-effort)
  const home = process.env.HOME || '';
  const g = path.join(home, '.gradle/caches/modules-2/files-2.1');
  const findJar = (group, artifact, version) => {
    const base = path.join(g, group, artifact, version);
    if (!fs.existsSync(base)) return null;
    const walk = (d) => {
      for (const ent of fs.readdirSync(d, { withFileTypes: true })) {
        const p = path.join(d, ent.name);
        if (ent.isDirectory()) {
          const hit = walk(p);
          if (hit) return hit;
        } else if (ent.name === `${artifact}-${version}.jar`) {
          return p;
        }
      }
      return null;
    };
    return walk(base);
  };
  const deps = [
    findJar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.0'),
    findJar('org.eclipse.lsp4j', 'org.eclipse.lsp4j', '0.23.1'),
    findJar('org.eclipse.lsp4j', 'org.eclipse.lsp4j.jsonrpc', '0.23.1'),
    findJar('com.google.code.gson', 'gson', '2.14.0'),
    findJar('org.jetbrains', 'annotations', '13.0'),
    findJar('com.google.errorprone', 'error_prone_annotations', '2.48.0'),
  ].filter(Boolean);
  if (deps.length < 4) {
    console.warn('[bridge] classpath deps incomplete; run npm run classpath after gradle');
  }
  return [jar, ...deps].join(path.delimiter);
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
    const classpath = resolveJvmClasspath();
    const javaBin = path.join(JAVA_HOME, 'bin', 'java');
    const java = fs.existsSync(javaBin) ? javaBin : 'java';
    const main = 'io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt';
    console.log('[bridge] spawn', java, '-cp', classpath.slice(0, 80) + '…', main);
    child = spawn(java, ['-cp', classpath, main], {
      cwd: REPO_ROOT,
      env: {
        ...process.env,
        JAVA_HOME,
        ANDROID_HOME: process.env.ANDROID_HOME || path.join(process.env.HOME || '', 'Library/Android/sdk'),
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    sendStatus('spawn', 'JVM LSP started', { pid: child.pid });

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
    try {
      if (child && !child.killed) {
        child.stdin.end();
        child.kill('SIGTERM');
        setTimeout(() => {
          if (child && !child.killed) child.kill('SIGKILL');
        }, 2000);
      }
    } catch { /* ignore */ }
  });
});

server.listen(PORT, '127.0.0.1', () => {
  const info = apiInfo();
  console.log(`[monaco-lsp-demo] http://127.0.0.1:${PORT}/`);
  console.log(`[monaco-lsp-demo] workspace: ${info.workspaceDir}`);
  console.log(`[monaco-lsp-demo] android.jar: ${info.androidJar} present=${info.androidJarPresent}`);
  console.log(`[monaco-lsp-demo] repo: ${REPO_ROOT}`);
});
