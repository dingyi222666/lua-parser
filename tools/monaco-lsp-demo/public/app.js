/**
 * Monaco Editor LSP demo client for lua-parser JVM LSP.
 *
 * Protocol transport:
 *   WebSocket messages are UTF-8 JSON-RPC 2.0 objects — one JSON object per
 *   WebSocket message (no LSP Content-Length framing). The Node bridge server
 *   is responsible for framing to/from the Java process stdio.
 *
 * Connect: ws://localhost:3099/lsp  (override with ?port= or ?ws=)
 * Info:    GET /api/info  → { workspaceUri, androidJar, sampleFiles, ... }
 * Files:   GET /api/files → [ { name, uri, path, text }, ... ]
 */

(function () {
  "use strict";

  // ---------------------------------------------------------------------------
  // Config
  // ---------------------------------------------------------------------------

  const params = new URLSearchParams(window.location.search);
  const wsPort = Number(params.get("port") || window.__DEFAULT_WS_PORT__ || window.location.port || 3099);
  const wsHost = params.get("host") || window.location.hostname || "127.0.0.1";
  const wsPath = params.get("path") || "/lsp";
  const WS_URL =
    params.get("ws") ||
    `ws://${wsHost}:${wsPort}${wsPath.startsWith("/") ? wsPath : "/" + wsPath}`;

  // Same origin as the demo page (bridge serves HTTP + WS on one port).
  const HTTP_BASE = "";

  // ---------------------------------------------------------------------------
  // DOM
  // ---------------------------------------------------------------------------

  const el = {
    statusDot: document.getElementById("statusDot"),
    statusText: document.getElementById("statusText"),
    btnStart: document.getElementById("btnStart"),
    btnStop: document.getElementById("btnStop"),
    btnRefreshFiles: document.getElementById("btnRefreshFiles"),
    fileList: document.getElementById("fileList"),
    wsEndpointLabel: document.getElementById("wsEndpointLabel"),
    currentPath: document.getElementById("currentPath"),
    sideMeta: document.getElementById("sideMeta"),
    tabs: document.getElementById("tabs"),
    editor: document.getElementById("editor"),
    editorEmpty: document.getElementById("editorEmpty"),
    bottom: document.getElementById("bottom"),
    panelDiagnostics: document.getElementById("panelDiagnostics"),
    panelLog: document.getElementById("panelLog"),
    diagCount: document.getElementById("diagCount"),
    logCount: document.getElementById("logCount"),
    btnClearLog: document.getElementById("btnClearLog"),
    btnToggleBottom: document.getElementById("btnToggleBottom"),
  };

  if (el.wsEndpointLabel) el.wsEndpointLabel.textContent = WS_URL;

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------

  /** @type {any} */
  let editor = null;
  /** @type {any} */
  let monacoApi = null;

  /** @type {WebSocket | null} */
  let socket = null;
  let nextId = 1;
  /** @type {Map<number, { resolve: Function, reject: Function, method: string, timer: any }>} */
  const pending = new Map();

  let serverInfo = {
    workspaceUri: window.__WORKSPACE_URI__ || null,
    workspaceDir: null,
    androidJar: null,
    androidJarPresent: false,
    sampleFiles: [],
    importPrefixes: ["java.lang", "android.widget", "android.view", "android.content"],
    androluaImports: ["TextView", "LinearLayout", "Activity"],
  };

  /** @type {Array<{ name: string, uri: string, path?: string, text?: string }>} */
  let workspaceFiles = [];

  /**
   * Open documents keyed by LSP URI.
   * @type {Map<string, {
   *   uri: string,
   *   name: string,
   *   languageId: string,
   *   version: number,
   *   model: any,
   *   openedOnServer: boolean
   * }>}
   */
  const openDocs = new Map();

  /** @type {string | null} */
  let activeUri = null;

  /** @type {Map<string, any[]>} */
  const diagnosticsByUri = new Map();

  let logLineCount = 0;
  let lspReady = false;

  /** @type {Array<{ dispose: Function }>} */
  let providerDisposables = [];

  // ---------------------------------------------------------------------------
  // UI helpers
  // ---------------------------------------------------------------------------

  function setStatus(kind, text) {
    if (el.statusDot) el.statusDot.className = "status-dot" + (kind ? " " + kind : "");
    if (el.statusText) el.statusText.textContent = text;
  }

  function setConnectingUi(isConnecting, isConnected) {
    if (el.btnStart) el.btnStart.disabled = isConnecting || isConnected;
    if (el.btnStop) el.btnStop.disabled = !isConnecting && !isConnected;
  }

  function basename(uriOrPath) {
    if (!uriOrPath) return "untitled";
    const cleaned = String(uriOrPath).replace(/[\\/]+$/, "");
    const parts = cleaned.split(/[\\/]/);
    let leaf = parts[parts.length - 1] || cleaned;
    try {
      leaf = decodeURIComponent(leaf);
    } catch (_) {
      /* keep */
    }
    return leaf;
  }

  function joinUri(baseUri, name) {
    if (!baseUri) return name;
    if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(name)) return name;
    const base = String(baseUri).replace(/\/+$/, "");
    const leaf = String(name).replace(/^\/+/, "");
    return base + "/" + leaf;
  }

  function positionFromMonaco(pos) {
    return { line: pos.lineNumber - 1, character: pos.column - 1 };
  }

  function rangeFromLsp(range) {
    if (!range || !range.start || !range.end) return null;
    return {
      startLineNumber: range.start.line + 1,
      startColumn: range.start.character + 1,
      endLineNumber: range.end.line + 1,
      endColumn: range.end.character + 1,
    };
  }

  function locationToMonaco(location) {
    if (!location || !monacoApi) return null;
    const uri = location.targetUri || location.uri;
    const range =
      location.targetSelectionRange || location.targetRange || location.range;
    const mRange = rangeFromLsp(range);
    if (!uri || !mRange) return null;
    return {
      uri: monacoApi.Uri.parse(uri),
      range: mRange,
    };
  }

  // ---------------------------------------------------------------------------
  // Logging
  // ---------------------------------------------------------------------------

  function appendLog(dir, body) {
    if (!el.panelLog) return;
    const line = document.createElement("div");
    line.className = "log-line";

    const now = new Date();
    const ts =
      String(now.getHours()).padStart(2, "0") +
      ":" +
      String(now.getMinutes()).padStart(2, "0") +
      ":" +
      String(now.getSeconds()).padStart(2, "0");

    const time = document.createElement("span");
    time.className = "log-time";
    time.textContent = ts;

    const d = document.createElement("span");
    d.className = "log-dir " + dir;
    d.textContent =
      dir === "out" ? "→ OUT" : dir === "in" ? "← IN" : dir === "err" ? "ERR" : "SYS";

    const b = document.createElement("span");
    b.className = "log-body";
    b.textContent = body;

    line.appendChild(time);
    line.appendChild(d);
    line.appendChild(b);
    el.panelLog.appendChild(line);
    logLineCount += 1;
    if (el.logCount) el.logCount.textContent = String(logLineCount);

    while (el.panelLog.children.length > 500) {
      el.panelLog.removeChild(el.panelLog.firstChild);
    }
    el.panelLog.scrollTop = el.panelLog.scrollHeight;
  }

  function logSys(msg) {
    appendLog("sys", msg);
  }
  function logErr(msg) {
    appendLog("err", msg);
  }

  function summarizeMessage(msg) {
    if (!msg || typeof msg !== "object") return String(msg);
    if (msg.method) {
      const idPart = msg.id != null ? " id=" + msg.id : "";
      if (msg.method === "textDocument/publishDiagnostics") {
        const uri = msg.params && msg.params.uri ? basename(msg.params.uri) : "?";
        const n =
          (msg.params && msg.params.diagnostics && msg.params.diagnostics.length) || 0;
        return msg.method + idPart + " " + uri + " (" + n + ")";
      }
      if (msg.method === "$/bridge") {
        const p = msg.params || {};
        return "$/bridge " + (p.type || "") + ": " + String(p.message || "").slice(0, 160);
      }
      if (msg.params && msg.params.textDocument && msg.params.textDocument.uri) {
        return msg.method + idPart + " " + basename(msg.params.textDocument.uri);
      }
      return msg.method + idPart;
    }
    if (msg.id != null && (msg.result !== undefined || msg.error)) {
      const pend = pending.get(msg.id);
      const method = pend ? pend.method : "response";
      if (msg.error) {
        return "error id=" + msg.id + " " + method + ": " + (msg.error.message || JSON.stringify(msg.error));
      }
      let hint = "ok";
      const r = msg.result;
      if (r == null) hint = "null";
      else if (Array.isArray(r)) hint = "array[" + r.length + "]";
      else if (r.capabilities) hint = "InitializeResult";
      else if (r.contents) hint = "Hover";
      else if (r.items) hint = "items[" + r.items.length + "]";
      else if (r.signatures) hint = "signatures[" + r.signatures.length + "]";
      else if (typeof r === "object") hint = "object";
      return method + " id=" + msg.id + " " + hint;
    }
    return JSON.stringify(msg).slice(0, 200);
  }

  // ---------------------------------------------------------------------------
  // JSON-RPC over WebSocket (one JSON object per message)
  // ---------------------------------------------------------------------------

  function sendRaw(obj) {
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      throw new Error("WebSocket is not open");
    }
    socket.send(JSON.stringify(obj));
    appendLog("out", summarizeMessage(obj));
  }

  function request(method, params, timeoutMs) {
    const id = nextId++;
    const msg = { jsonrpc: "2.0", id: id, method: method, params: params };
    return new Promise(function (resolve, reject) {
      const timer = setTimeout(function () {
        if (pending.has(id)) {
          pending.delete(id);
          reject(new Error("timeout " + method + " #" + id));
        }
      }, timeoutMs || 30000);
      pending.set(id, { resolve: resolve, reject: reject, method: method, timer: timer });
      try {
        sendRaw(msg);
      } catch (e) {
        clearTimeout(timer);
        pending.delete(id);
        reject(e);
      }
    });
  }

  function notify(method, params) {
    sendRaw({ jsonrpc: "2.0", method: method, params: params });
  }

  function handleIncoming(data) {
    let msg;
    try {
      msg = typeof data === "string" ? JSON.parse(data) : data;
    } catch (e) {
      logErr("Failed to parse WS message: " + String(e));
      return;
    }

    // Response to our request
    if (msg && msg.id != null && (msg.result !== undefined || msg.error !== undefined) && !msg.method) {
      appendLog("in", summarizeMessage(msg));
      const pend = pending.get(msg.id);
      if (!pend) return;
      pending.delete(msg.id);
      if (pend.timer) clearTimeout(pend.timer);
      if (msg.error) {
        const err = new Error(msg.error.message || "LSP error");
        err.code = msg.error.code;
        err.data = msg.error.data;
        pend.reject(err);
      } else {
        pend.resolve(msg.result);
      }
      return;
    }

    // Request or notification from server
    if (msg && msg.method) {
      appendLog("in", summarizeMessage(msg));
      handleServerMessage(msg);
      return;
    }

    appendLog("in", "unrecognized: " + JSON.stringify(msg).slice(0, 200));
  }

  function handleServerMessage(msg) {
    const method = msg.method;
    const params = msg.params || {};
    const id = msg.id;

    switch (method) {
      case "textDocument/publishDiagnostics":
        onPublishDiagnostics(params);
        break;
      case "$/bridge":
        if (params.type === "error" || params.type === "stderr") {
          logErr("[bridge] " + (params.message || ""));
        } else {
          logSys("[bridge] " + (params.type || "") + ": " + (params.message || ""));
        }
        break;
      case "window/logMessage":
        logSys("[server " + severityLevel(params.type) + "] " + (params.message || ""));
        break;
      case "window/showMessage":
        logSys("[show " + severityLevel(params.type) + "] " + (params.message || ""));
        break;
      case "window/showMessageRequest":
        if (id != null) sendRaw({ jsonrpc: "2.0", id: id, result: null });
        logSys("[showRequest] " + (params.message || ""));
        break;
      case "workspace/configuration":
        if (id != null) {
          const items = params.items || [];
          const result = items.map(function () {
            return {
              "jvm.androidJar": serverInfo.androidJar,
              "jvm.importPrefixes": serverInfo.importPrefixes,
              "androlua.imports": serverInfo.androluaImports,
            };
          });
          sendRaw({ jsonrpc: "2.0", id: id, result: result });
        }
        break;
      case "client/registerCapability":
      case "client/unregisterCapability":
      case "window/workDoneProgress/create":
        if (id != null) sendRaw({ jsonrpc: "2.0", id: id, result: null });
        break;
      case "$/progress":
        break;
      default:
        if (id != null) {
          sendRaw({
            jsonrpc: "2.0",
            id: id,
            error: { code: -32601, message: "Method not found: " + method },
          });
        }
        break;
    }
  }

  function severityLevel(type) {
    switch (type) {
      case 1:
        return "error";
      case 2:
        return "warn";
      case 3:
        return "info";
      case 4:
        return "log";
      default:
        return String(type || "?");
    }
  }

  // ---------------------------------------------------------------------------
  // Diagnostics
  // ---------------------------------------------------------------------------

  function onPublishDiagnostics(params) {
    if (!params || !params.uri || !monacoApi) return;
    const uri = params.uri;
    const diags = params.diagnostics || [];
    diagnosticsByUri.set(uri, diags);

    const model = monacoApi.editor.getModel(monacoApi.Uri.parse(uri));
    if (model) {
      const markers = diags.map(function (d) {
        const sev = d.severity;
        let severity = monacoApi.MarkerSeverity.Info;
        if (sev === 1) severity = monacoApi.MarkerSeverity.Error;
        else if (sev === 2) severity = monacoApi.MarkerSeverity.Warning;
        else if (sev === 3) severity = monacoApi.MarkerSeverity.Info;
        else if (sev === 4) severity = monacoApi.MarkerSeverity.Hint;

        const range = d.range || {
          start: { line: 0, character: 0 },
          end: { line: 0, character: 1 },
        };
        return {
          severity: severity,
          message: d.message || "",
          startLineNumber: range.start.line + 1,
          startColumn: range.start.character + 1,
          endLineNumber: range.end.line + 1,
          endColumn: Math.max(range.end.character + 1, range.start.character + 2),
          source: d.source || "lua-parser",
          code: d.code != null ? String(d.code) : undefined,
        };
      });
      monacoApi.editor.setModelMarkers(model, "lua-lsp", markers);
    }

    renderDiagnosticsPanel();
    renderFileList();
  }

  function renderDiagnosticsPanel() {
    if (!el.panelDiagnostics) return;
    const items = [];
    diagnosticsByUri.forEach(function (diags, uri) {
      diags.forEach(function (d) {
        items.push({ uri: uri, d: d });
      });
    });
    items.sort(function (a, b) {
      const sa = a.d.severity || 4;
      const sb = b.d.severity || 4;
      if (sa !== sb) return sa - sb;
      const la = (a.d.range && a.d.range.start && a.d.range.start.line) || 0;
      const lb = (b.d.range && b.d.range.start && b.d.range.start.line) || 0;
      return la - lb;
    });

    if (el.diagCount) el.diagCount.textContent = String(items.length);
    el.panelDiagnostics.innerHTML = "";

    if (!items.length) {
      const empty = document.createElement("div");
      empty.className = "panel-empty";
      empty.textContent = "No diagnostics.";
      el.panelDiagnostics.appendChild(empty);
      return;
    }

    items.forEach(function (item) {
      const uri = item.uri;
      const d = item.d;
      const row = document.createElement("div");
      row.className = "diag-item";

      const sevName =
        d.severity === 1
          ? "error"
          : d.severity === 2
            ? "warning"
            : d.severity === 3
              ? "info"
              : "hint";

      const sev = document.createElement("span");
      sev.className = "diag-sev " + sevName;
      sev.textContent = sevName;

      const msg = document.createElement("span");
      msg.className = "diag-msg";
      msg.textContent = d.message || "";

      const loc = document.createElement("span");
      loc.className = "diag-loc";
      const line = d.range && d.range.start ? d.range.start.line + 1 : "?";
      const col = d.range && d.range.start ? d.range.start.character + 1 : "?";
      loc.textContent = basename(uri) + ":" + line + ":" + col;

      row.appendChild(sev);
      row.appendChild(msg);
      row.appendChild(loc);

      row.addEventListener("click", function () {
        openDocument(uri).then(function () {
          if (editor && d.range && d.range.start) {
            const pos = {
              lineNumber: d.range.start.line + 1,
              column: d.range.start.character + 1,
            };
            editor.setPosition(pos);
            editor.revealPositionInCenter(pos);
            editor.focus();
          }
        });
      });

      el.panelDiagnostics.appendChild(row);
    });
  }

  // ---------------------------------------------------------------------------
  // HTTP helpers
  // ---------------------------------------------------------------------------

  async function fetchJson(url) {
    const res = await fetch(url, { cache: "no-store" });
    if (!res.ok) throw new Error(url + " → HTTP " + res.status);
    return res.json();
  }

  function updateSideMeta() {
    if (!el.sideMeta) return;
    const parts = [];
    if (serverInfo.workspaceDir) parts.push("root: " + serverInfo.workspaceDir);
    parts.push(
      "android.jar: " +
        (serverInfo.androidJarPresent
          ? "yes"
          : serverInfo.androidJar
            ? "missing"
            : "n/a")
    );
    el.sideMeta.textContent = parts.join(" · ");
  }

  async function loadServerInfo() {
    try {
      const info = await fetchJson(HTTP_BASE + "/api/info");
      serverInfo = {
        workspaceUri:
          info.workspaceUri ||
          info.workspace ||
          window.__WORKSPACE_URI__ ||
          serverInfo.workspaceUri,
        workspaceDir: info.workspaceDir || serverInfo.workspaceDir,
        androidJar: info.androidJar != null ? info.androidJar : serverInfo.androidJar,
        androidJarPresent: !!info.androidJarPresent,
        sampleFiles: info.sampleFiles || info.files || serverInfo.sampleFiles || [],
        importPrefixes: serverInfo.importPrefixes,
        androluaImports: serverInfo.androluaImports,
      };
      if (serverInfo.workspaceUri) {
        window.__WORKSPACE_URI__ = serverInfo.workspaceUri;
      }
      updateSideMeta();
      logSys(
        "api/info workspace=" +
          (serverInfo.workspaceUri || "?") +
          " androidJar=" +
          (serverInfo.androidJarPresent ? "present" : "none")
      );
    } catch (e) {
      logErr("GET /api/info failed: " + (e && e.message ? e.message : e));
      if (!serverInfo.workspaceUri && window.__WORKSPACE_URI__) {
        serverInfo.workspaceUri = window.__WORKSPACE_URI__;
      }
      updateSideMeta();
    }
  }

  async function loadFileList() {
    let files = [];
    try {
      const data = await fetchJson(HTTP_BASE + "/api/files");
      if (Array.isArray(data)) files = data;
      else if (data && Array.isArray(data.files)) files = data.files;
    } catch (e) {
      logSys("GET /api/files unavailable, using /api/info sampleFiles");
      files = serverInfo.sampleFiles || [];
    }

    if (!files.length && Array.isArray(serverInfo.sampleFiles)) {
      files = serverInfo.sampleFiles;
    }

    workspaceFiles = files.map(function (f) {
      if (typeof f === "string") {
        const name = basename(f);
        const uri = f.indexOf("://") >= 0 ? f : joinUri(serverInfo.workspaceUri, f.replace(/^\/+/, ""));
        return { name: name, uri: uri, path: f };
      }
      const name = f.name || basename(f.uri || f.path || "file.lua");
      const uri =
        f.uri ||
        (f.path && String(f.path).indexOf("://") >= 0
          ? f.path
          : joinUri(serverInfo.workspaceUri, f.path || name));
      return { name: name, uri: uri, path: f.path, text: f.text };
    });

    if (!workspaceFiles.length && serverInfo.workspaceUri) {
      // Fallback only when /api/files is empty (demo samples or alp root).
      const defaults = ["main.lua", "init.lua", "info.lua"];
      workspaceFiles = defaults.map(function (name) {
        return {
          name: name,
          uri: joinUri(serverInfo.workspaceUri, name),
          path: name,
        };
      });
    }

    renderFileList();
  }

  async function resolveFileText(file) {
    if (file && typeof file.text === "string") return file.text;

    // Refresh from /api/files which includes text
    try {
      const all = await fetchJson(HTTP_BASE + "/api/files");
      const list = Array.isArray(all) ? all : all && all.files ? all.files : [];
      const hit = list.find(function (x) {
        return x.uri === file.uri || x.name === file.name;
      });
      if (hit && typeof hit.text === "string") {
        file.text = hit.text;
        return hit.text;
      }
    } catch (_) {
      /* fall through */
    }

    const candidates = [
      HTTP_BASE + "/api/files/" + encodeURIComponent(file.name),
      HTTP_BASE + "/api/file?uri=" + encodeURIComponent(file.uri),
      HTTP_BASE + "/api/file?name=" + encodeURIComponent(file.name),
    ];
    for (let i = 0; i < candidates.length; i++) {
      try {
        const res = await fetch(candidates[i], { cache: "no-store" });
        if (!res.ok) continue;
        const ct = res.headers.get("content-type") || "";
        if (ct.indexOf("application/json") >= 0) {
          const j = await res.json();
          if (typeof j.text === "string") return j.text;
          if (typeof j.content === "string") return j.content;
          continue;
        }
        return await res.text();
      } catch (_) {
        /* try next */
      }
    }
    logErr("Could not load file content for " + file.name + "; opening empty buffer");
    return "";
  }

  // ---------------------------------------------------------------------------
  // File list / tabs
  // ---------------------------------------------------------------------------

  function renderFileList() {
    if (!el.fileList) return;
    el.fileList.innerHTML = "";
    workspaceFiles.forEach(function (f) {
      const li = document.createElement("li");
      if (activeUri === f.uri) li.classList.add("active");

      const icon = document.createElement("span");
      icon.className = "file-icon";
      icon.textContent = "◈";

      const name = document.createElement("span");
      name.className = "file-name";
      name.textContent = f.name;

      const badge = document.createElement("span");
      badge.className = "diag-badge";
      const diags = diagnosticsByUri.get(f.uri) || [];
      const errCount = diags.filter(function (d) {
        return d.severity === 1 || d.severity === 2;
      }).length;
      if (errCount > 0) {
        badge.textContent = String(errCount);
        badge.classList.add("visible");
      }

      li.appendChild(icon);
      li.appendChild(name);
      li.appendChild(badge);
      li.title = f.uri;
      li.addEventListener("click", function () {
        openDocument(f.uri, f).catch(function (e) {
          logErr(String(e));
        });
      });
      el.fileList.appendChild(li);
    });
  }

  function renderTabs() {
    if (!el.tabs) return;
    el.tabs.innerHTML = "";
    openDocs.forEach(function (doc, uri) {
      const tab = document.createElement("div");
      tab.className = "tab" + (uri === activeUri ? " active" : "");
      tab.setAttribute("role", "tab");
      tab.setAttribute("aria-selected", uri === activeUri ? "true" : "false");

      const name = document.createElement("span");
      name.className = "tab-name";
      name.textContent = doc.name;

      const close = document.createElement("button");
      close.type = "button";
      close.className = "tab-close";
      close.title = "Close";
      close.textContent = "×";
      close.addEventListener("click", function (ev) {
        ev.stopPropagation();
        closeDocument(uri);
      });

      tab.appendChild(name);
      tab.appendChild(close);
      tab.addEventListener("click", function () {
        activateDocument(uri);
      });
      el.tabs.appendChild(tab);
    });
    updateEditorVisibility();
  }

  function updateEditorVisibility() {
    const has = openDocs.size > 0 && activeUri && openDocs.has(activeUri);
    if (el.editor) el.editor.style.display = has ? "block" : "none";
    if (el.editorEmpty) el.editorEmpty.classList.toggle("visible", !has);
    if (has) {
      const doc = openDocs.get(activeUri);
      if (el.currentPath) el.currentPath.textContent = doc.uri;
    } else if (el.currentPath) {
      el.currentPath.textContent = "—";
    }
  }

  // ---------------------------------------------------------------------------
  // Document lifecycle (full sync)
  // ---------------------------------------------------------------------------

  function languageIdFor(name) {
    if (/\.lua$/i.test(name)) return "lua";
    return "plaintext";
  }

  async function openDocument(uri, fileMeta) {
    if (!monacoApi || !editor) throw new Error("Monaco not ready");

    let doc = openDocs.get(uri);
    if (doc) {
      activateDocument(uri);
      return doc;
    }

    const name = (fileMeta && fileMeta.name) || basename(uri);
    const meta =
      fileMeta ||
      workspaceFiles.find(function (f) {
        return f.uri === uri;
      }) || { name: name, uri: uri };

    const text = await resolveFileText(meta);
    const languageId = languageIdFor(name);
    const modelUri = monacoApi.Uri.parse(uri);
    let model = monacoApi.editor.getModel(modelUri);
    if (model) {
      model.setValue(text);
    } else {
      model = monacoApi.editor.createModel(text, languageId, modelUri);
    }

    doc = {
      uri: uri,
      name: name,
      languageId: languageId,
      version: 1,
      model: model,
      openedOnServer: false,
    };
    openDocs.set(uri, doc);

    model.onDidChangeContent(function () {
      if (!lspReady || !doc.openedOnServer) return;
      doc.version += 1;
      notify("textDocument/didChange", {
        textDocument: { uri: doc.uri, version: doc.version },
        contentChanges: [{ text: model.getValue() }],
      });
    });

    if (lspReady) didOpenOnServer(doc);

    activateDocument(uri);
    renderTabs();
    renderFileList();
    return doc;
  }

  function didOpenOnServer(doc) {
    if (!lspReady || doc.openedOnServer) return;
    notify("textDocument/didOpen", {
      textDocument: {
        uri: doc.uri,
        languageId: doc.languageId,
        version: doc.version,
        text: doc.model.getValue(),
      },
    });
    doc.openedOnServer = true;
  }

  function activateDocument(uri) {
    const doc = openDocs.get(uri);
    if (!doc || !editor) return;
    activeUri = uri;
    editor.setModel(doc.model);
    renderTabs();
    renderFileList();
    updateEditorVisibility();
    editor.focus();
  }

  function closeDocument(uri) {
    const doc = openDocs.get(uri);
    if (!doc) return;

    if (lspReady && doc.openedOnServer) {
      try {
        notify("textDocument/didClose", { textDocument: { uri: doc.uri } });
      } catch (_) {
        /* ignore */
      }
    }

    if (monacoApi) {
      monacoApi.editor.setModelMarkers(doc.model, "lua-lsp", []);
    }
    diagnosticsByUri.delete(uri);
    doc.model.dispose();
    openDocs.delete(uri);

    if (activeUri === uri) {
      const iter = openDocs.keys();
      const next = iter.next();
      activeUri = next.done ? null : next.value;
      if (activeUri && editor) {
        editor.setModel(openDocs.get(activeUri).model);
      } else if (editor) {
        editor.setModel(null);
      }
    }

    renderTabs();
    renderFileList();
    renderDiagnosticsPanel();
    updateEditorVisibility();
  }

  function reopenAllOnServer() {
    openDocs.forEach(function (doc) {
      doc.openedOnServer = false;
      doc.version = Math.max(1, doc.version);
      didOpenOnServer(doc);
    });
  }

  // ---------------------------------------------------------------------------
  // LSP initialize
  // ---------------------------------------------------------------------------

  function buildInitializeParams() {
    const rootUri = serverInfo.workspaceUri || window.__WORKSPACE_URI__ || null;
    const workspaceFolders = rootUri
      ? [{ uri: rootUri, name: "monaco-lsp-demo" }]
      : null;

    return {
      processId: null,
      clientInfo: { name: "monaco-lsp-demo", version: "1.0.0" },
      locale: "en-us",
      rootPath: null,
      rootUri: rootUri,
      capabilities: {
        workspace: {
          applyEdit: false,
          workspaceFolders: true,
          configuration: true,
          didChangeConfiguration: { dynamicRegistration: false },
        },
        textDocument: {
          synchronization: {
            dynamicRegistration: false,
            willSave: false,
            willSaveWaitUntil: false,
            didSave: false,
          },
          publishDiagnostics: {
            relatedInformation: true,
            tagSupport: { valueSet: [1, 2] },
            versionSupport: true,
          },
          hover: {
            dynamicRegistration: false,
            contentFormat: ["markdown", "plaintext"],
          },
          completion: {
            dynamicRegistration: false,
            completionItem: {
              snippetSupport: true,
              documentationFormat: ["markdown", "plaintext"],
              deprecatedSupport: true,
              preselectSupport: true,
            },
            contextSupport: true,
          },
          signatureHelp: {
            dynamicRegistration: false,
            signatureInformation: {
              documentationFormat: ["markdown", "plaintext"],
              parameterInformation: { labelOffsetSupport: true },
              activeParameterSupport: true,
            },
            contextSupport: true,
          },
          definition: {
            dynamicRegistration: false,
            linkSupport: true,
          },
          documentHighlight: {
            dynamicRegistration: false,
          },
          references: { dynamicRegistration: false },
        },
        window: {
          showMessage: {
            messageActionItem: { additionalPropertiesSupport: false },
          },
          workDoneProgress: false,
        },
        general: {
          positionEncodings: ["utf-16"],
        },
      },
      initializationOptions: {},
      trace: "off",
      workspaceFolders: workspaceFolders,
    };
  }

  function sendDidChangeConfiguration() {
    notify("workspace/didChangeConfiguration", {
      settings: {
        "jvm.androidJar": serverInfo.androidJar || "",
        "jvm.importPrefixes": serverInfo.importPrefixes,
        "androlua.imports": serverInfo.androluaImports,
      },
    });
  }

  // ---------------------------------------------------------------------------
  // Monaco providers
  // ---------------------------------------------------------------------------

  function disposeProviders() {
    providerDisposables.forEach(function (d) {
      try {
        d.dispose();
      } catch (_) {
        /* ignore */
      }
    });
    providerDisposables = [];
  }

  function markupToString(contents) {
    if (contents == null) return "";
    if (typeof contents === "string") return contents;
    if (contents.kind && contents.value != null) return String(contents.value);
    if (Array.isArray(contents)) {
      return contents
        .map(function (c) {
          if (typeof c === "string") return c;
          if (c && c.value != null) return String(c.value);
          return "";
        })
        .filter(Boolean)
        .join("\n\n");
    }
    if (contents.language && contents.value != null) {
      return "```" + contents.language + "\n" + contents.value + "\n```";
    }
    return String(contents);
  }

  function completionKindFromLsp(kind) {
    if (!monacoApi) return 0;
    const K = monacoApi.languages.CompletionItemKind;
    const table = {
      1: K.Text,
      2: K.Method,
      3: K.Function,
      4: K.Constructor,
      5: K.Field,
      6: K.Variable,
      7: K.Class,
      8: K.Interface,
      9: K.Module,
      10: K.Property,
      11: K.Unit,
      12: K.Value,
      13: K.Enum,
      14: K.Keyword,
      15: K.Snippet,
      16: K.Color,
      17: K.File,
      18: K.Reference,
      19: K.Folder,
      20: K.EnumMember,
      21: K.Constant,
      22: K.Struct,
      23: K.Event,
      24: K.Operator,
      25: K.TypeParameter,
    };
    return table[kind] || K.Text;
  }

  function registerProviders() {
    if (!monacoApi) return;
    disposeProviders();

    const selector = { language: "lua" };

    providerDisposables.push(
      monacoApi.languages.registerHoverProvider(selector, {
        provideHover: async function (model, position) {
          if (!lspReady) return null;
          try {
            const result = await request("textDocument/hover", {
              textDocument: { uri: model.uri.toString() },
              position: positionFromMonaco(position),
            });
            if (!result || result.contents == null) return null;
            const value = markupToString(result.contents);
            if (!value) return null;
            return {
              range: result.range ? rangeFromLsp(result.range) : undefined,
              contents: [{ value: value }],
            };
          } catch (e) {
            logErr("hover: " + (e.message || e));
            return null;
          }
        },
      })
    );

    providerDisposables.push(
      monacoApi.languages.registerCompletionItemProvider(selector, {
        triggerCharacters: [".", ":"],
        provideCompletionItems: async function (model, position, context) {
          if (!lspReady) return { suggestions: [] };
          try {
            let triggerKind = 1;
            if (
              context.triggerKind ===
              monacoApi.languages.CompletionTriggerKind.TriggerCharacter
            ) {
              triggerKind = 2;
            } else if (
              context.triggerKind ===
              monacoApi.languages.CompletionTriggerKind.TriggerForIncompleteCompletions
            ) {
              triggerKind = 3;
            }
            const result = await request("textDocument/completion", {
              textDocument: { uri: model.uri.toString() },
              position: positionFromMonaco(position),
              context: {
                triggerKind: triggerKind,
                triggerCharacter: context.triggerCharacter,
              },
            });
            const items = Array.isArray(result)
              ? result
              : result && Array.isArray(result.items)
                ? result.items
                : [];
            const word = model.getWordUntilPosition(position);
            const defaultRange = {
              startLineNumber: position.lineNumber,
              endLineNumber: position.lineNumber,
              startColumn: word.startColumn,
              endColumn: word.endColumn,
            };
            const suggestions = items.map(function (item, index) {
              const labelObj = item.label;
              const label =
                labelObj && typeof labelObj === "object"
                  ? labelObj.label
                  : String(labelObj || "");
              const insertText =
                item.insertText != null
                  ? item.insertText
                  : label;
              const itemRange =
                item.textEdit && item.textEdit.range
                  ? rangeFromLsp(item.textEdit.range)
                  : defaultRange;
              const text =
                item.textEdit && item.textEdit.newText != null
                  ? item.textEdit.newText
                  : insertText;
              return {
                label: label,
                kind: completionKindFromLsp(item.kind),
                detail: item.detail,
                documentation: item.documentation
                  ? markupToString(item.documentation)
                  : undefined,
                insertText: text,
                insertTextRules:
                  item.insertTextFormat === 2
                    ? monacoApi.languages.CompletionItemInsertTextRule.InsertAsSnippet
                    : undefined,
                range: itemRange,
                sortText: item.sortText || String(index).padStart(5, "0"),
                filterText: item.filterText || label,
                preselect: !!item.preselect,
              };
            });
            return {
              suggestions: suggestions,
              incomplete: !!(result && result.isIncomplete),
            };
          } catch (e) {
            logErr("completion: " + (e.message || e));
            return { suggestions: [] };
          }
        },
      })
    );

    providerDisposables.push(
      monacoApi.languages.registerSignatureHelpProvider(selector, {
        signatureHelpTriggerCharacters: ["(", ","],
        signatureHelpRetriggerCharacters: [","],
        provideSignatureHelp: async function (model, position) {
          if (!lspReady) return null;
          try {
            const result = await request("textDocument/signatureHelp", {
              textDocument: { uri: model.uri.toString() },
              position: positionFromMonaco(position),
            });
            if (!result || !result.signatures || !result.signatures.length) return null;
            return {
              value: {
                signatures: result.signatures.map(function (sig) {
                  return {
                    label: sig.label,
                    documentation: sig.documentation
                      ? markupToString(sig.documentation)
                      : undefined,
                    parameters: (sig.parameters || []).map(function (p) {
                      return {
                        label: p.label,
                        documentation: p.documentation
                          ? markupToString(p.documentation)
                          : undefined,
                      };
                    }),
                    activeParameter: sig.activeParameter,
                  };
                }),
                activeSignature: result.activeSignature || 0,
                activeParameter: result.activeParameter || 0,
              },
              dispose: function () {},
            };
          } catch (e) {
            logErr("signatureHelp: " + (e.message || e));
            return null;
          }
        },
      })
    );

    providerDisposables.push(
      monacoApi.languages.registerDefinitionProvider(selector, {
        provideDefinition: async function (model, position) {
          if (!lspReady) return null;
          try {
            const result = await request("textDocument/definition", {
              textDocument: { uri: model.uri.toString() },
              position: positionFromMonaco(position),
            });
            if (!result) return null;
            const locs = Array.isArray(result) ? result : [result];
            const out = [];
            for (let i = 0; i < locs.length; i++) {
              const loc = locs[i];
              const m = locationToMonaco(loc);
              if (m) {
                const targetUri = m.uri.toString();
                if (!openDocs.has(targetUri)) {
                  try {
                    await openDocument(targetUri);
                  } catch (_) {
                    /* still return location */
                  }
                }
                out.push(m);
              }
            }
            return out.length ? out : null;
          } catch (e) {
            logErr("definition: " + (e.message || e));
            return null;
          }
        },
      })
    );

    providerDisposables.push(
      monacoApi.languages.registerDocumentHighlightProvider(selector, {
        provideDocumentHighlights: async function (model, position) {
          if (!lspReady) return null;
          try {
            const result = await request("textDocument/documentHighlight", {
              textDocument: { uri: model.uri.toString() },
              position: positionFromMonaco(position),
            });
            if (!result || !Array.isArray(result)) return null;
            return result
              .map(function (h) {
                const range = rangeFromLsp(h.range);
                if (!range) return null;
                let kind = monacoApi.languages.DocumentHighlightKind.Text;
                if (h.kind === 2) kind = monacoApi.languages.DocumentHighlightKind.Read;
                else if (h.kind === 3)
                  kind = monacoApi.languages.DocumentHighlightKind.Write;
                return { range: range, kind: kind };
              })
              .filter(Boolean);
          } catch (_) {
            return null;
          }
        },
      })
    );
  }

  // ---------------------------------------------------------------------------
  // Connect / disconnect
  // ---------------------------------------------------------------------------

  async function startLsp() {
    if (
      socket &&
      (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)
    ) {
      return;
    }
    setConnectingUi(true, false);
    setStatus("connecting", "Connecting…");
    logSys("Connecting to " + WS_URL);

    await loadServerInfo();
    await loadFileList();

    await new Promise(function (resolve, reject) {
      let settled = false;
      try {
        socket = new WebSocket(WS_URL);
      } catch (e) {
        setConnectingUi(false, false);
        setStatus("error", "Failed to open WebSocket");
        logErr(String(e));
        reject(e);
        return;
      }

      socket.addEventListener("open", async function () {
        logSys("WebSocket open");
        setStatus("connecting", "Initializing…");
        try {
          const result = await request("initialize", buildInitializeParams(), 120000);
          logSys(
            "initialized capabilities: " +
              (result && result.capabilities
                ? Object.keys(result.capabilities).join(", ")
                : "(none)")
          );
          notify("initialized", {});
          sendDidChangeConfiguration();
          lspReady = true;
          setConnectingUi(false, true);
          setStatus("connected", "Connected");
          reopenAllOnServer();
          if (!settled) {
            settled = true;
            resolve();
          }
        } catch (e) {
          logErr("initialize failed: " + (e.message || e));
          setStatus("error", "Initialize failed");
          setConnectingUi(false, false);
          lspReady = false;
          try {
            socket.close();
          } catch (_) {}
          if (!settled) {
            settled = true;
            reject(e);
          }
        }
      });

      socket.addEventListener("message", function (ev) {
        handleIncoming(ev.data);
      });

      socket.addEventListener("error", function () {
        logErr("WebSocket error");
        setStatus("error", "Socket error");
      });

      socket.addEventListener("close", function (ev) {
        logSys("WebSocket closed code=" + ev.code + " reason=" + (ev.reason || ""));
        lspReady = false;
        setConnectingUi(false, false);
        setStatus(ev.code === 1000 ? "" : "error", "Disconnected");
        pending.forEach(function (p) {
          if (p.timer) clearTimeout(p.timer);
          p.reject(new Error("WebSocket closed"));
        });
        pending.clear();
        openDocs.forEach(function (doc) {
          doc.openedOnServer = false;
        });
        socket = null;
        if (!settled) {
          settled = true;
          reject(new Error("WebSocket closed before initialize"));
        }
      });
    });
  }

  function stopLsp() {
    if (!socket) {
      setConnectingUi(false, false);
      setStatus("", "Disconnected");
      return;
    }
    const s = socket;
    const finish = function () {
      try {
        s.close();
      } catch (_) {}
    };

    if (lspReady && s.readyState === WebSocket.OPEN) {
      request("shutdown", null)
        .catch(function () {
          return null;
        })
        .then(function () {
          try {
            notify("exit", undefined);
          } catch (_) {}
          finish();
        });
      setTimeout(finish, 800);
    } else {
      finish();
    }

    lspReady = false;
    setConnectingUi(false, false);
    setStatus("", "Stopping…");
  }

  // ---------------------------------------------------------------------------
  // Bottom panel chrome
  // ---------------------------------------------------------------------------

  function setupBottomPanel() {
    const tabs = document.querySelectorAll(".panel-tab");
    tabs.forEach(function (tab) {
      tab.addEventListener("click", function () {
        tabs.forEach(function (t) {
          t.classList.remove("active");
        });
        tab.classList.add("active");
        const panel = tab.getAttribute("data-panel");
        if (el.panelDiagnostics)
          el.panelDiagnostics.classList.toggle("active", panel === "diagnostics");
        if (el.panelLog) el.panelLog.classList.toggle("active", panel === "log");
      });
    });

    if (el.btnToggleBottom) {
      el.btnToggleBottom.addEventListener("click", function () {
        el.bottom.classList.toggle("collapsed");
        el.btnToggleBottom.textContent = el.bottom.classList.contains("collapsed")
          ? "▴"
          : "▾";
      });
    }

    if (el.btnClearLog) {
      el.btnClearLog.addEventListener("click", function () {
        if (el.panelLog) el.panelLog.innerHTML = "";
        logLineCount = 0;
        if (el.logCount) el.logCount.textContent = "0";
      });
    }
  }

  // ---------------------------------------------------------------------------
  // Monaco boot
  // ---------------------------------------------------------------------------

  function ensureLuaLanguage() {
    const langs = monacoApi.languages.getLanguages().map(function (l) {
      return l.id;
    });
    if (langs.indexOf("lua") !== -1) return;

    monacoApi.languages.register({
      id: "lua",
      extensions: [".lua"],
      aliases: ["Lua", "lua"],
    });
    monacoApi.languages.setMonarchTokensProvider("lua", {
      defaultToken: "",
      tokenPostfix: ".lua",
      keywords: [
        "and",
        "break",
        "do",
        "else",
        "elseif",
        "end",
        "false",
        "for",
        "function",
        "goto",
        "if",
        "in",
        "local",
        "nil",
        "not",
        "or",
        "repeat",
        "return",
        "then",
        "true",
        "until",
        "while",
      ],
      operators: [
        "+",
        "-",
        "*",
        "/",
        "%",
        "^",
        "#",
        "==",
        "~=",
        "<=",
        ">=",
        "<",
        ">",
        "=",
        ";",
        ":",
        ",",
        ".",
        "..",
        "...",
      ],
      symbols: /[=><!~?:&|+\-*\/\^%#]+/,
      tokenizer: {
        root: [
          [
            /[a-zA-Z_]\w*/,
            {
              cases: {
                "@keywords": "keyword",
                "@default": "identifier",
              },
            },
          ],
          { include: "@whitespace" },
          [/[{}()\[\]]/, "@brackets"],
          [
            /@symbols/,
            {
              cases: {
                "@operators": "operator",
                "@default": "",
              },
            },
          ],
          [/\d*\.\d+([eE][\-+]?\d+)?/, "number.float"],
          [/0[xX][0-9a-fA-F_]+/, "number.hex"],
          [/\d+/, "number"],
          [/[;,.]/, "delimiter"],
          [/"([^"\\]|\\.)*$/, "string.invalid"],
          [/'([^'\\]|\\.)*$/, "string.invalid"],
          [/"/, "string", "@string_double"],
          [/'/, "string", "@string_single"],
          [/\[(=*)\[/, "string", "@string_long"],
        ],
        whitespace: [
          [/[ \t\r\n]+/, ""],
          [/--\[(=*)\[/, "comment", "@comment_long"],
          [/--.*$/, "comment"],
        ],
        comment_long: [
          [/[^\]]+/, "comment"],
          [/\](=*)\]/, { token: "comment", next: "@pop" }],
          [/./, "comment"],
        ],
        string_double: [
          [/[^\\"]+/, "string"],
          [/\\./, "string.escape"],
          [/"/, "string", "@pop"],
        ],
        string_single: [
          [/[^\\']+/, "string"],
          [/\\./, "string.escape"],
          [/'/, "string", "@pop"],
        ],
        string_long: [
          [/[^\]]+/, "string"],
          [/\](=*)\]/, { token: "string", next: "@pop" }],
          [/./, "string"],
        ],
      },
    });
  }

  function createEditor() {
    monacoApi = window.monaco;
    monacoApi.editor.defineTheme("lua-parser-dark", {
      base: "vs-dark",
      inherit: true,
      rules: [
        { token: "comment", foreground: "6b7280", fontStyle: "italic" },
        { token: "keyword", foreground: "c792ea" },
        { token: "string", foreground: "c3e88d" },
        { token: "number", foreground: "f78c6c" },
      ],
      colors: {
        "editor.background": "#0f1115",
        "editor.foreground": "#e6eaf2",
        "editorLineNumber.foreground": "#4b5568",
        "editorLineNumber.activeForeground": "#9aa4b5",
        "editor.selectionBackground": "#243044",
        "editor.lineHighlightBackground": "#161a21",
        "editorWidget.background": "#161a21",
        "editorWidget.border": "#2a3140",
        "editorSuggestWidget.background": "#161a21",
        "editorSuggestWidget.border": "#2a3140",
        "editorHoverWidget.background": "#161a21",
        "editorHoverWidget.border": "#2a3140",
        "scrollbarSlider.background": "#2c344466",
        "scrollbarSlider.hoverBackground": "#3a455888",
      },
    });

    ensureLuaLanguage();

    editor = monacoApi.editor.create(el.editor, {
      value: "",
      language: "lua",
      theme: "lua-parser-dark",
      automaticLayout: true,
      minimap: { enabled: true, scale: 1 },
      fontSize: 13,
      fontFamily:
        "JetBrains Mono, SF Mono, Fira Code, Menlo, Consolas, monospace",
      tabSize: 4,
      insertSpaces: true,
      renderWhitespace: "selection",
      smoothScrolling: true,
      cursorBlinking: "smooth",
      scrollBeyondLastLine: false,
      padding: { top: 8 },
      fixedOverflowWidgets: true,
      wordBasedSuggestions: "off",
    });

    registerProviders();
    updateEditorVisibility();
  }

  // ---------------------------------------------------------------------------
  // Wire controls + boot
  // ---------------------------------------------------------------------------

  function wireControls() {
    if (el.btnStart) {
      el.btnStart.addEventListener("click", function () {
        startLsp()
          .then(async function () {
            // The server indexed the workspace during initialize; open only the entry file.
            try {
              const files = await fetchJson(HTTP_BASE + "/api/files");
              const list = Array.isArray(files) ? files : [];
              if (list.length) {
                const main =
                  list.find(function (f) {
                    return f.name === "main.lua";
                  }) || list[0];
                if (main) await openDocument(main.uri, main);
              } else {
                const main =
                  workspaceFiles.find(function (f) {
                    return f.name === "main.lua";
                  }) || workspaceFiles[0];
                if (main) await openDocument(main.uri, main);
              }
            } catch (e) {
              logErr("auto-open: " + (e.message || e));
            }
          })
          .catch(function () {
            /* logged */
          });
      });
    }

    if (el.btnStop) el.btnStop.addEventListener("click", function () {
      stopLsp();
    });

    if (el.btnRefreshFiles) {
      el.btnRefreshFiles.addEventListener("click", async function () {
        await loadServerInfo();
        await loadFileList();
        logSys("File list refreshed (" + workspaceFiles.length + ")");
      });
    }

    setupBottomPanel();
  }

  function boot() {
    wireControls();
    logSys(
      "Monaco LSP demo ready. Framing: one JSON-RPC object per WebSocket message."
    );
    logSys("Endpoint: " + WS_URL);
    setStatus("", "Disconnected");
    setConnectingUi(false, false);
    renderDiagnosticsPanel();

    function afterMonaco() {
      createEditor();
      loadServerInfo()
        .then(loadFileList)
        .catch(function () {
          return loadFileList();
        });
    }

    if (window.monaco && window.monaco.editor) {
      afterMonaco();
      return;
    }

    if (typeof require === "function") {
      require(["vs/editor/editor.main"], function () {
        afterMonaco();
      });
    } else {
      logErr("Monaco loader not available");
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
