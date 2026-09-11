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
            languageId: /\.(lua|aly)$/i.test(entry.name) ? 'lua' : 'plaintext',
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

        // Wire coverage beyond completion, on the already-opened entry file.
        const documentSymbols = await request('textDocument/documentSymbol', {
          textDocument: { uri: entry.uri },
        }, 60000);
        if (!Array.isArray(documentSymbols)) {
          throw new Error(
            `documentSymbol must return an array; got ${documentSymbols === null ? 'null' : typeof documentSymbols}`
          );
        }
        const malformedDocumentSymbol = documentSymbols.find(
          (symbol) => typeof symbol?.name !== 'string' || typeof symbol?.kind !== 'number'
        );
        if (malformedDocumentSymbol) {
          throw new Error(
            `documentSymbol entries need name+kind; got ${JSON.stringify(malformedDocumentSymbol)}`
          );
        }

        const foldingRanges = await request('textDocument/foldingRange', {
          textDocument: { uri: entry.uri },
        }, 60000);
        if (!Array.isArray(foldingRanges)) {
          throw new Error(
            `foldingRange must return an array; got ${foldingRanges === null ? 'null' : typeof foldingRanges}`
          );
        }

        const semanticLegend = initialized.capabilities?.semanticTokensProvider?.legend;
        if (!Array.isArray(semanticLegend?.tokenTypes) || semanticLegend.tokenTypes.length === 0) {
          throw new Error('Initialize capabilities omitted semanticTokensProvider.legend.tokenTypes');
        }
        const semanticTokens = await request('textDocument/semanticTokens/full', {
          textDocument: { uri: entry.uri },
        }, 60000);
        if (!Array.isArray(semanticTokens?.data)) {
          throw new Error(
            `semanticTokens/full must return numeric data; got ${semanticTokens === null ? 'null' : typeof semanticTokens?.data}`
          );
        }
        if (semanticTokens.data.length % 5 !== 0) {
          throw new Error(
            `semanticTokens data length must be a multiple of 5; got ${semanticTokens.data.length}`
          );
        }
        for (let index = 3; index < semanticTokens.data.length; index += 5) {
          const tokenType = semanticTokens.data[index];
          if (tokenType < 0 || tokenType >= semanticLegend.tokenTypes.length) {
            throw new Error(
              `semanticTokens tokenType index ${tokenType} outside legend ` +
              `(0..${semanticLegend.tokenTypes.length - 1}) at data slot ${index}`
            );
          }
        }

        // Capability wire-coverage: every advertised capability must answer with a
        // well-shaped result (regression class: a single Kotlin delegation gap, e.g.
        // foldingRange, silently killing one feature while everything else stays
        // green). Unadvertised capabilities (codeAction, onTypeFormatting) are not
        // probed: a compliant server rejects requests it never advertised, so those
        // requests would produce noise instead of signal.
        const entryLines = entry.text.split('\n');
        const printOffset = entry.text.indexOf('print(');
        const identifierOffset = printOffset >= 0 ? printOffset + 1 : firstIdentifierOffset(entry.text);
        const identifierPosition = positionAt(entry.text, identifierOffset);

        // 1. textDocument/references — must return an array of Locations.
        const references = await request('textDocument/references', {
          textDocument: { uri: entry.uri },
          position: identifierPosition,
          context: { includeDeclaration: true },
        }, 60000);
        if (!Array.isArray(references)) {
          throw new Error(
            `references must return an array; got ${references === null ? 'null' : typeof references}`
          );
        }
        references.forEach((reference, index) => {
          if (typeof reference?.uri !== 'string') {
            throw new Error(`references[${index}] needs a string uri; got ${JSON.stringify(reference)}`);
          }
          assertRangeShape(reference.range, `references[${index}]`);
        });

        // 2. textDocument/documentHighlight — must return an array.
        const highlights = await request('textDocument/documentHighlight', {
          textDocument: { uri: entry.uri },
          position: identifierPosition,
        }, 60000);
        if (!Array.isArray(highlights)) {
          throw new Error(
            `documentHighlight must return an array; got ${highlights === null ? 'null' : typeof highlights}`
          );
        }
        highlights.forEach((highlight, index) => {
          assertRangeShape(highlight?.range, `documentHighlight[${index}]`);
        });

        // 3. textDocument/selectionRange — one position inside the first statement
        // must yield SelectionRange entries with a well-formed range/parent chain.
        const firstStatementLineIndex = entryLines.findIndex(
          (line) => line.trim() && !line.trim().startsWith('--')
        );
        if (firstStatementLineIndex < 0) {
          throw new Error('Entry file has no statement line to probe textDocument/selectionRange');
        }
        const firstStatementLine = entryLines[firstStatementLineIndex];
        const statementIdentifier = firstStatementLine.match(/[A-Za-z_][A-Za-z0-9_]*/);
        const selectionPosition = {
          line: firstStatementLineIndex,
          character: statementIdentifier
            ? statementIdentifier.index + 1
            : Math.max(0, Math.floor(firstStatementLine.length / 2)),
        };
        const selectionRanges = await request('textDocument/selectionRange', {
          textDocument: { uri: entry.uri },
          positions: [selectionPosition],
        }, 60000);
        if (!Array.isArray(selectionRanges)) {
          throw new Error(
            `selectionRange must return an array; got ${selectionRanges === null ? 'null' : typeof selectionRanges}`
          );
        }
        selectionRanges.forEach((selection, index) => {
          let current = selection;
          let depth = 0;
          while (current) {
            if (typeof current !== 'object') {
              throw new Error(
                `selectionRange[${index}] chain node must be an object; got ${typeof current}`
              );
            }
            assertRangeShape(current.range, `selectionRange[${index}] depth ${depth}`);
            if (
              current.parent !== undefined
              && current.parent !== null
              && typeof current.parent !== 'object'
            ) {
              throw new Error(
                `selectionRange[${index}] parent must be a SelectionRange object; got ${typeof current.parent}`
              );
            }
            current = current.parent;
            depth += 1;
            if (depth > 100) {
              throw new Error(`selectionRange[${index}] parent chain exceeds 100 nodes; likely cyclic`);
            }
          }
        });

        // 4. textDocument/foldingRange — already asserted above.

        // 5. textDocument/rangeFormatting — array of edits over a small line span
        // (empty is legitimate for code that is already formatted).
        const formatEndLine = Math.min(1, entryLines.length - 1);
        const formattingEdits = await request('textDocument/rangeFormatting', {
          textDocument: { uri: entry.uri },
          range: {
            start: { line: 0, character: 0 },
            end: { line: formatEndLine, character: entryLines[formatEndLine].length },
          },
        }, 60000);
        if (!Array.isArray(formattingEdits)) {
          throw new Error(
            `rangeFormatting must return an array of edits; got ` +
            `${formattingEdits === null ? 'null' : typeof formattingEdits}`
          );
        }
        formattingEdits.forEach((edit, index) => {
          assertRangeShape(edit?.range, `rangeFormatting edit ${index}`);
          if (typeof edit.newText !== 'string') {
            throw new Error(
              `rangeFormatting edit ${index} needs a string newText; got ${JSON.stringify(edit)}`
            );
          }
        });

        // 6. textDocument/prepareRename + textDocument/rename — prepare returns
        // null or a {range, placeholder} shape; rename returns a WorkspaceEdit.
        const preparedRename = await request('textDocument/prepareRename', {
          textDocument: { uri: entry.uri },
          position: identifierPosition,
        }, 60000);
        if (preparedRename !== null && preparedRename !== undefined) {
          // Modern {range, placeholder} shape, or the legacy bare Range form.
          const prepareRange = preparedRename.range || preparedRename;
          assertRangeShape(prepareRange, 'prepareRename');
          if (
            preparedRename.placeholder !== undefined
            && preparedRename.placeholder !== null
            && typeof preparedRename.placeholder !== 'string'
          ) {
            throw new Error(
              `prepareRename placeholder must be a string; got ${typeof preparedRename.placeholder}`
            );
          }
        }
        const renameResult = await request('textDocument/rename', {
          textDocument: { uri: entry.uri },
          position: identifierPosition,
          newName: 'smokeRenamedIdentifier42',
        }, 60000);
        if (!renameResult || typeof renameResult !== 'object') {
          throw new Error(
            `rename must return a WorkspaceEdit; got ${renameResult === null ? 'null' : typeof renameResult}`
          );
        }
        const hasChangesMap = Boolean(renameResult.changes) && typeof renameResult.changes === 'object';
        const hasDocumentChanges = Array.isArray(renameResult.documentChanges);
        if (!hasChangesMap && !hasDocumentChanges) {
          throw new Error(
            'rename WorkspaceEdit needs a changes map or a documentChanges array; got keys ' +
            `${JSON.stringify(Object.keys(renameResult))}`
          );
        }

        // 7. textDocument/inlayHint — must return an array over the full range.
        const documentEndPosition = {
          line: entryLines.length - 1,
          character: entryLines[entryLines.length - 1].length,
        };
        const inlayHints = await request('textDocument/inlayHint', {
          textDocument: { uri: entry.uri },
          range: { start: { line: 0, character: 0 }, end: documentEndPosition },
        }, 60000);
        if (!Array.isArray(inlayHints)) {
          throw new Error(
            `inlayHint must return an array; got ${inlayHints === null ? 'null' : typeof inlayHints}`
          );
        }
        inlayHints.forEach((hint, index) => {
          const hintPosition = hint?.position;
          if (
            !hintPosition
            || !Number.isInteger(hintPosition.line)
            || !Number.isInteger(hintPosition.character)
          ) {
            throw new Error(`inlayHint[${index}] needs a position; got ${JSON.stringify(hint)}`);
          }
          if (typeof hint.label !== 'string' && !Array.isArray(hint.label)) {
            throw new Error(
              `inlayHint[${index}] label must be a string or InlayHintLabelPart[]; got ${typeof hint.label}`
            );
          }
        });

        // 8. textDocument/codeAction — NOT advertised by this server, so only probe
        // if the capability ever shows up (then an array is mandatory).
        if (initialized.capabilities.codeActionProvider) {
          const codeActions = await request('textDocument/codeAction', {
            textDocument: { uri: entry.uri },
            range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
            context: { diagnostics: [] },
          }, 60000);
          if (!Array.isArray(codeActions)) {
            throw new Error(
              `codeAction must return an array; got ${codeActions === null ? 'null' : typeof codeActions}`
            );
          }
        } else {
          console.log('[smoke] skipping textDocument/codeAction probe: not advertised (server must reject it)');
        }

        // 9. textDocument/semanticTokens/full/delta — reuses resultId from the full
        // request above; SemanticTokens (data) or SemanticTokensDelta (edits) both fine.
        if (semanticTokens.resultId === null || semanticTokens.resultId === undefined) {
          console.log(
            '[smoke] ASSERTION SKIPPED: semanticTokens/full returned no resultId, ' +
            'textDocument/semanticTokens/full/delta cannot be probed'
          );
        } else {
          const deltaTokens = await request('textDocument/semanticTokens/full/delta', {
            textDocument: { uri: entry.uri },
            previousResultId: semanticTokens.resultId,
          }, 60000);
          const isFullTokens = Array.isArray(deltaTokens?.data);
          const isDeltaTokens = Array.isArray(deltaTokens?.edits);
          if (!isFullTokens && !isDeltaTokens) {
            throw new Error(
              'semanticTokens/full/delta must return SemanticTokens (data) or SemanticTokensDelta ' +
              `(edits); got ${JSON.stringify(deltaTokens)?.slice(0, 200)}`
            );
          }
          if (isDeltaTokens) {
            deltaTokens.edits.forEach((edit, index) => {
              if (!Number.isInteger(edit?.start) || !Number.isInteger(edit?.deleteCount)) {
                throw new Error(
                  `semanticTokens delta edit ${index} needs integer start+deleteCount; ` +
                  `got ${JSON.stringify(edit)}`
                );
              }
            });
          } else if (deltaTokens.data.length % 5 !== 0) {
            throw new Error(
              `semanticTokens/full/delta data length must be a multiple of 5; got ${deltaTokens.data.length}`
            );
          }
        }

        // 10. workspace/symbol — must return an array of symbol shapes.
        const workspaceSymbols = await request('workspace/symbol', { query: 'main' }, 60000);
        if (!Array.isArray(workspaceSymbols)) {
          throw new Error(
            `workspace/symbol must return an array; got ` +
            `${workspaceSymbols === null ? 'null' : typeof workspaceSymbols}`
          );
        }
        workspaceSymbols.forEach((symbol, index) => {
          if (typeof symbol?.name !== 'string' || typeof symbol?.kind !== 'number') {
            throw new Error(
              `workspace/symbol[${index}] needs name+kind (SymbolInformation/WorkspaceSymbol); ` +
              `got ${JSON.stringify(symbol)}`
            );
          }
        });

        console.log(
          `[smoke] capability probes passed on ${entry.name}: references, documentHighlight, ` +
          'selectionRange, rangeFormatting, prepareRename+rename, inlayHint, ' +
          'semanticTokens/full/delta, workspace/symbol'
        );

        // AndroLua layout (.aly) file: must open as a parsed Lua document, and an empty
        // property string must offer the loadlayout value domain (regression for the
        // quickSuggestions/empty-string completion path).
        const alyEntry = files.find((file) => file.name.endsWith('.aly'));
        if (alyEntry && typeof alyEntry.text === 'string') {
          notify('textDocument/didOpen', {
            textDocument: {
              uri: alyEntry.uri,
              languageId: 'lua',
              version: 1,
              text: alyEntry.text,
            },
          });
          const probeText = `${alyEntry.text}\nlocal probe = loadlayout({ LinearLayout, orientation = "" }, {})\n`;
          const quoteIndex = probeText.lastIndexOf('orientation = ""') + 'orientation = "'.length;
          notify('textDocument/didOpen', {
            textDocument: {
              uri: alyEntry.uri.replace(/\.aly$/, '.probe.aly'),
              languageId: 'lua',
              version: 1,
              text: probeText,
            },
          });
          const valueCompletion = await request('textDocument/completion', {
            textDocument: { uri: alyEntry.uri.replace(/\.aly$/, '.probe.aly') },
            position: positionAt(probeText, quoteIndex),
          }, 60000);
          const valueItems = (Array.isArray(valueCompletion) ? valueCompletion : valueCompletion?.items || [])
            .map((item) => item.label);
          if (!valueItems.includes('vertical') || !valueItems.includes('horizontal')) {
            throw new Error(`Layout string-value completion missing orientation tokens: ${valueItems.join(', ')}`);
          }
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

// Offset of a position strictly inside the first identifier of the source.
function firstIdentifierOffset(source) {
  const match = source.match(/[A-Za-z_][A-Za-z0-9_]*/);
  if (!match) throw new Error('Entry file contains no identifier to probe');
  return match.index + 1;
}

// Shared Range shape guard for capability probe results.
function assertRangeShape(range, label) {
  if (!range || typeof range !== 'object') {
    throw new Error(`${label}: expected a Range object; got ${JSON.stringify(range)}`);
  }
  for (const edge of [range.start, range.end]) {
    if (
      !edge
      || !Number.isInteger(edge.line) || edge.line < 0
      || !Number.isInteger(edge.character) || edge.character < 0
    ) {
      throw new Error(
        `${label}: range edges need non-negative integer line/character; got ${JSON.stringify(range)}`
      );
    }
  }
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
