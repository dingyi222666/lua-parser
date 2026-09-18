'use strict';

/**
 * AndroLua Lua Support — thin VSCode launcher for the luaparser JVM language server.
 *
 * All intelligence lives in the jar. This file only:
 *   1. spawns `java` with the server jar and talks LSP over stdio
 *      (io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt reads
 *      LSP messages from System.in and writes responses to System.out; it takes
 *      no command line arguments — the workspace arrives inside `initialize`),
 *   2. forwards `luaparser.serverSettings` verbatim via
 *      workspace/didChangeConfiguration (the server is push-configuration).
 *
 * Launch modes, chosen automatically per server start:
 *   - classpath mode (default artifact today): the published jvmJar is a thin
 *     jar with no Main-Class manifest attribute, so if a sibling `lib` folder
 *     exists next to the jar we launch:
 *         java <jvmArgs> -cp <jarPath><sep><lib>/* io.github...LuaLanguageServerLauncherKt
 *   - jar mode (future fat jar / relocated artifact):
 *         java <jvmArgs> -jar <jarPath>
 */

const vscode = require('vscode');
const path = require('path');
const fs = require('fs');
const { execFile } = require('child_process');
const {
    LanguageClient,
    TransportKind,
    DidChangeConfigurationNotification,
} = require('vscode-languageclient/node');

const CLIENT_ID = 'androluaLuaSupport';
const CLIENT_NAME = 'AndroLua Lua Support';
const SERVER_MAIN_CLASS =
    'io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt';

/** @type {LanguageClient | null} */
let client = null;
/** True once client.start() resolved; guards settings pushes on a dead client. */
let clientRunning = false;
/** @type {vscode.Disposable | null} */
let configListener = null;

/**
 * @param {string} message
 */
function showLaunchError(message) {
    vscode.window.showErrorMessage(`${CLIENT_NAME}: ${message}`);
}

function readConfig() {
    const config = vscode.workspace.getConfiguration('luaparser');
    return {
        jarPath: config.get('jarPath', 'build/libs/luaparser-jvm-1.0.4.jar'),
        javaPath: config.get('javaPath', 'java'),
        jvmArgs: config.get('jvmArgs', []),
        serverSettings: config.get('serverSettings', {}),
    };
}

/**
 * Resolves the configured jar path. Absolute paths are used as-is; relative
 * paths are resolved against the first workspace folder, then the extension
 * install directory (so the repo default works when luaparser itself is open).
 */
function resolveServerJar(extensionPath) {
    const raw = readConfig().jarPath.trim();
    if (raw.length === 0) {
        return { jarPath: '', error: '"luaparser.jarPath" is empty.' };
    }
    if (path.isAbsolute(raw)) {
        return { jarPath: raw };
    }
    const bases = (vscode.workspace.workspaceFolders || [])
        .map((folder) => folder.uri.fsPath);
    bases.push(extensionPath);
    const candidates = bases.map((base) => path.join(base, raw));
    const existing = candidates.find((candidate) => fs.existsSync(candidate));
    return { jarPath: existing || candidates[0] };
}

/**
 * The current repo artifact is a thin jar (no Main-Class manifest attribute),
 * so `java -jar` cannot run it. The server runs from a classpath of the jar
 * plus dependency jars staged in a `lib` folder next to the jar.
 * Returns the lib dir when it exists and holds at least one .jar.
 */
function findDependencyLibDir(jarPath) {
    const libDir = path.join(path.dirname(jarPath), 'lib');
    try {
        const entries = fs.readdirSync(libDir);
        const hasJars = entries.some((entry) =>
            entry.toLowerCase().endsWith('.jar'));
        return hasJars ? libDir : null;
    } catch {
        return null;
    }
}

/**
 * Exact command line (stdio transport; the launcher reads LSP from stdin and
 * writes LSP to stdout, and needs no extra CLI arguments):
 *   classpath mode: <java> <jvmArgs...> -cp <jarPath><sep><lib>/* <MAIN_CLASS>
 *   jar mode:       <java> <jvmArgs...> -jar <jarPath>
 */
function buildServerOptions(javaPath, jvmArgs, jarPath, libDir, cwd) {
    const args = jvmArgs.slice();
    if (libDir) {
        args.push(
            '-cp',
            jarPath + path.delimiter + path.join(libDir, '*'),
            SERVER_MAIN_CLASS,
        );
    } else {
        args.push('-jar', jarPath);
    }
    return {
        command: javaPath,
        args,
        transport: TransportKind.stdio,
        options: { cwd },
    };
}

function buildClientOptions() {
    return {
        documentSelector: [
            // The `lua` language id covers both contributed extensions (.lua, .aly).
            { language: 'lua', scheme: 'file' },
            { language: 'lua', scheme: 'untitled' },
        ],
        synchronize: {
            fileEvents: vscode.workspace.createFileSystemWatcher('**/*.{lua,aly}'),
        },
        outputChannelName: CLIENT_NAME,
    };
}

/** Forwards luaparser.serverSettings verbatim; the server is push-configuration. */
function pushServerSettings() {
    if (!client || !clientRunning) {
        return;
    }
    const { serverSettings } = readConfig();
    client.sendNotification(DidChangeConfigurationNotification.type, {
        settings: serverSettings || {},
    });
}

function checkJava(javaPath) {
    return new Promise((resolve, reject) => {
        // `java -version` reports on stderr but exits 0 on success.
        execFile(javaPath, ['-version'], (error) => {
            if (error) {
                reject(error);
            } else {
                resolve();
            }
        });
    });
}

/**
 * @returns {Promise<boolean>} whether the client was started
 */
async function startServer(context) {
    const config = readConfig();

    const resolved = resolveServerJar(context.extensionPath);
    if (resolved.error || !resolved.jarPath || !fs.existsSync(resolved.jarPath)) {
        showLaunchError(
            `Lua language server jar not found at "${resolved.jarPath || ''}". ` +
                'Build it with "./gradlew jvmJar" (and stage dependency jars into a ' +
                '"lib" folder next to it — see the extension README), or point ' +
                '"luaparser.jarPath" at an existing server jar.',
        );
        return false;
    }

    try {
        await checkJava(config.javaPath);
    } catch (error) {
        showLaunchError(
            `Could not run Java at "${config.javaPath}" (${error && error.message ? error.message : error}). ` +
                'Install a JDK 11+ or fix "luaparser.javaPath".',
        );
        return false;
    }

    const libDir = findDependencyLibDir(resolved.jarPath);
    const workspaceFolder = vscode.workspace.workspaceFolders
        && vscode.workspace.workspaceFolders.length > 0
        ? vscode.workspace.workspaceFolders[0].uri.fsPath
        : path.dirname(resolved.jarPath);
    const serverOptions = buildServerOptions(
        config.javaPath,
        config.jvmArgs,
        resolved.jarPath,
        libDir,
        workspaceFolder,
    );
    const clientOptions = buildClientOptions();

    client = new LanguageClient(CLIENT_ID, CLIENT_NAME, serverOptions, clientOptions);
    context.subscriptions.push(client);
    try {
        await client.start();
    } catch (error) {
        showLaunchError(
            `Failed to start the Lua language server (${error && error.message ? error.message : error}).`,
        );
        client = null;
        clientRunning = false;
        return false;
    }
    clientRunning = true;
    pushServerSettings();
    return true;
}

async function stopServer() {
    if (client) {
        clientRunning = false;
        const current = client;
        client = null;
        try {
            await current.stop();
        } catch {
            // The server exits with the client pipe closed either way.
        }
    }
}

async function restartServer(context) {
    await stopServer();
    return startServer(context);
}

/** @param {vscode.ExtensionContext} context */
function activate(context) {
    configListener = vscode.workspace.onDidChangeConfiguration((event) => {
        if (!event.affectsConfiguration('luaparser')) {
            return;
        }
        const launchKeys = ['jarPath', 'javaPath', 'jvmArgs'];
        const launchChanged = launchKeys.some((key) =>
            event.affectsConfiguration(`luaparser.${key}`));
        if (launchChanged) {
            restartServer(context);
        } else if (event.affectsConfiguration('luaparser.serverSettings')) {
            pushServerSettings();
        }
    });
    context.subscriptions.push(configListener);

    return startServer(context);
}

function deactivate() {
    clientRunning = false;
    if (configListener) {
        configListener.dispose();
        configListener = null;
    }
    if (client) {
        return client.stop();
    }
    return undefined;
}

module.exports = {
    activate,
    deactivate,
};
