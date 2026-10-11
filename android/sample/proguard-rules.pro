# Instrumentation uses this field to inspect the real active LSP bridge.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-dontwarn kotlin.Cloneable$DefaultImpls
-keep class io.github.dingyi222666.luaparser.sample.R$* { *; }
-keep class io.github.dingyi222666.luaparser.sample.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.arch.** { *; }
-keep class androidx.test.** { *; }
-keep class io.github.rosemoe.sora.** { *; }
-keep class io.github.dingyi222666.luaparser.sample.LuaHighlightAnalyzer { *; }
# The separate instrumentation APK calls these editor/LSP APIs.
-keep class io.github.rosemoe.sora.lsp.editor.LspEditor { *; }
-keep class io.github.rosemoe.sora.widget.CodeEditor { *; }
-keep class io.github.rosemoe.sora.text.Content { *; }
-keep interface io.github.rosemoe.sora.text.Indexer { *; }
-keep class io.github.rosemoe.sora.text.CharPosition { *; }
-keep class io.github.rosemoe.sora.lang.styling.Styles { *; }
-keep interface io.github.rosemoe.sora.lang.styling.Spans { *; }
-keep interface io.github.rosemoe.sora.lang.styling.Spans$Reader { *; }
-keep interface io.github.rosemoe.sora.lang.styling.Span { *; }
-keep class io.github.rosemoe.sora.widget.component.EditorAutoCompletion { *; }
-keep class io.github.rosemoe.sora.widget.component.DefaultCompletionLayout { *; }
-keep class io.github.rosemoe.sora.lang.completion.CompletionItem { *; }
-keepclassmembers class io.github.dingyi222666.luaparser.sample.MainActivity {
    private io.github.rosemoe.sora.lsp.editor.LspEditor lspEditor;
    private java.util.List latestDiagnostics;
    public void onFileSelected(java.lang.String);
}
