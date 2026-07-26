#!/usr/bin/env node
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const demoRoot = path.resolve(__dirname, '..');
const port = Number(process.env.MONACO_LSP_SMOKE_PORT || 32000 + (process.pid % 10000));
const baseUrl = `http://127.0.0.1:${port}`;
const bridge = spawn(process.execPath, ['server.mjs'], {
  cwd: demoRoot,
  env: { ...process.env, PORT: String(port) },
  stdio: ['ignore', 'pipe', 'pipe'],
});

bridge.stdout.on('data', (chunk) => process.stdout.write(chunk));
bridge.stderr.on('data', (chunk) => process.stderr.write(chunk));

let bridgeExited = false;
bridge.once('exit', () => {
  bridgeExited = true;
});

async function waitForHealth() {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    if (bridgeExited) throw new Error('Monaco bridge exited before becoming healthy');
    try {
      const response = await fetch(`${baseUrl}/api/health`, { cache: 'no-store' });
      if (response.ok) return;
    } catch {
      // Bridge or npm dependency startup is still in progress.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error('Timed out waiting for Monaco bridge health');
}

async function stopBridge() {
  if (bridgeExited) return;
  bridge.kill('SIGTERM');
  await Promise.race([
    new Promise((resolve) => bridge.once('exit', resolve)),
    new Promise((resolve) => setTimeout(resolve, 15000)),
  ]);
  if (!bridgeExited) bridge.kill('SIGKILL');
}

function runProtocol(info, files) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`ws://127.0.0.1:${port}/lsp`);
    const pending = new Map();
    let nextId = 1;
    let diagnosticsResolve = null;
    let diagnosticsTimer = null;
    let settled = false;

    const fail = (error) => {
      if (settled) return;
      settled = true;
      pending.forEach(({ timer, reject: rejectRequest }) => {
        clearTimeout(timer);
        rejectRequest(error);
      });
      pending.clear();
      if (diagnosticsTimer) clearTimeout(diagnosticsTimer);
      reject(error);
    };

    const send = (message) => socket.send(JSON.stringify(message));
    const notify = (method, params) => send({ jsonrpc: '2.0', method, params });
    const request = (method, params, timeoutMs = 30000) => {
      const id = nextId++;
      return new Promise((resolveRequest, rejectRequest) => {
        const timer = setTimeout(() => {
          pending.delete(id);
          rejectRequest(new Error(`Timed out waiting for ${method}`));
        }, timeoutMs);
        pending.set(id, { resolve: resolveRequest, reject: rejectRequest, timer });
        send({ jsonrpc: '2.0', id, method, params });
      });
    };

    socket.on('message', (data) => {
      let message;
      try {
        message = JSON.parse(data.toString());
      } catch (error) {
        fail(error);
        return;
      }
      if (message.id != null && !message.method) {
        const requestState = pending.get(message.id);
        if (!requestState) return;
        pending.delete(message.id);
        clearTimeout(requestState.timer);
        if (message.error) {
          requestState.reject(new Error(message.error.message || JSON.stringify(message.error)));
        } else {
          requestState.resolve(message.result);
        }
        return;
      }
      if (message.method === 'textDocument/publishDiagnostics' && diagnosticsResolve) {
        if (diagnosticsTimer) clearTimeout(diagnosticsTimer);
        diagnosticsResolve(message.params);
        diagnosticsResolve = null;
        return;
      }
      if (message.id != null && message.method) {
        const result = message.method === 'workspace/configuration'
          ? (message.params?.items || []).map(() => ({}))
          : null;
        send({ jsonrpc: '2.0', id: message.id, result });
      }
    });

    socket.once('error', fail);
    socket.once('close', () => fail(new Error('WebSocket closed before smoke protocol completed')));
    socket.once('open', async () => {
      try {
        const initialized = await request('initialize', {
          processId: null,
          clientInfo: { name: 'monaco-lsp-demo-smoke', version: '1.0.0' },
          rootUri: info.workspaceUri,
          capabilities: {
            workspace: { workspaceFolders: true, configuration: true },
            textDocument: { publishDiagnostics: { relatedInformation: true } },
          },
          workspaceFolders: [{ uri: info.workspaceUri, name: 'monaco-lsp-demo' }],
        }, 180000);
        if (!initialized?.capabilities) throw new Error('Initialize response omitted capabilities');
        notify('initialized', {});

        const entry = files.find((file) => file.name === 'main.lua')
          || files.find((file) => file.name.endsWith('/main.lua'))
          || files[0];
        if (!entry || typeof entry.text !== 'string') {
          throw new Error('Workspace API did not return an entry file with source text');
        }
        const diagnostics = new Promise((resolveDiagnostics, rejectDiagnostics) => {
          diagnosticsResolve = resolveDiagnostics;
          diagnosticsTimer = setTimeout(
            () => rejectDiagnostics(new Error('Timed out waiting for didOpen diagnostics')),
            60000
          );
        });
        notify('textDocument/didOpen', {
          textDocument: {
            uri: entry.uri,
            languageId: entry.name.endsWith('.lua') ? 'lua' : 'plaintext',
            version: 1,
            text: entry.text,
          },
        });
        const published = await diagnostics;
        if (published.uri !== entry.uri || !Array.isArray(published.diagnostics)) {
          throw new Error('Invalid diagnostics response for opened entry file');
        }

        const dynamicTableFile = files.find((file) => file.name === 'mods/dingyi.lua');
        if (!dynamicTableFile || typeof dynamicTableFile.text !== 'string') {
          throw new Error('Full demo workspace is missing mods/dingyi.lua');
        }
        const completionText = `${dynamicTableFile.text}\ntable.\n`;
        const completionOffset = completionText.lastIndexOf('table.') + 'table.'.length;
        notify('textDocument/didOpen', {
          textDocument: {
            uri: dynamicTableFile.uri,
            languageId: 'lua',
            version: 1,
            text: completionText,
          },
        });
        const completionResult = await request('textDocument/completion', {
          textDocument: { uri: dynamicTableFile.uri },
          position: positionAt(completionText, completionOffset),
          context: { triggerKind: 2, triggerCharacter: '.' },
        }, 60000);
        const completionItems = Array.isArray(completionResult)
          ? completionResult
          : completionResult?.items || [];
        const completionLabels = completionItems.map((item) => item.label);
        if (!completionLabels.includes('addObserver') || !completionLabels.includes('insert')) {
          throw new Error(
            `Dynamic table completion missing custom/builtin members: ${completionLabels.join(', ')}`
          );
        }

        await request('shutdown', null, 30000);
        notify('exit');
        settled = true;
        if (diagnosticsTimer) clearTimeout(diagnosticsTimer);
        socket.close();
        console.log(
          `[smoke] initialized ${files.length} workspace files, opened ${entry.name}, ` +
          'and completed table.addObserver in mods/dingyi.lua'
        );
        resolve();
      } catch (error) {
        fail(error);
        socket.close();
      }
    });
  });
}

function positionAt(source, offset) {
  const prefix = source.slice(0, offset);
  const lines = prefix.split('\n');
  return {
    line: lines.length - 1,
    character: lines[lines.length - 1].length,
  };
}

try {
  await waitForHealth();
  const [pageResponse, appResponse, infoResponse, filesResponse] = await Promise.all([
    fetch(`${baseUrl}/`, { cache: 'no-store' }),
    fetch(`${baseUrl}/app.js`, { cache: 'no-store' }),
    fetch(`${baseUrl}/api/info`, { cache: 'no-store' }),
    fetch(`${baseUrl}/api/files`, { cache: 'no-store' }),
  ]);
  if (!pageResponse.ok || !appResponse.ok || !infoResponse.ok || !filesResponse.ok) {
    throw new Error(
      `Demo HTTP failed: page=${pageResponse.status} app=${appResponse.status} info=${infoResponse.status} files=${filesResponse.status}`
    );
  }
  const [page, app] = await Promise.all([pageResponse.text(), appResponse.text()]);
  if (!page.includes('./app.js') || !app.includes('buildInitializeParams')) {
    throw new Error('Demo page did not load its browser client source');
  }
  const info = await infoResponse.json();
  const files = await filesResponse.json();
  if (!info.workspaceUri || !Array.isArray(files) || files.length === 0) {
    throw new Error('Workspace API returned incomplete project metadata');
  }
  await runProtocol(info, files);
} finally {
  await stopBridge();
}
