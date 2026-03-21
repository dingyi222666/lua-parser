package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ExpressionTypeEvaluatorTest {

    private val parser = LuaParser()

    @Test
    fun resolvesLocalIdentifierFromInitializer() {
        val harness = harness(
            """
            local seed = 1
            local value = seed
            """.trimIndent()
        )

        val type = harness.evaluator.evaluate(localInitializer(harness.chunk, "value"))
        val literal = assertIs<LiteralType>(type)
        assertSame(PrimitiveType.NUMBER, literal.baseType)
        assertEquals("1", literal.displayName)
    }

    @Test
    fun prefersDeclaredTypeOverInitializer() {
        val harness = harness(
            """
            ---@type string
            local value = 1
            local other = value
            """.trimIndent()
        )

        val type = harness.evaluator.evaluate(localInitializer(harness.chunk, "other"))
        assertSame(PrimitiveType.STRING, type)
    }

    @Test
    fun resolvesNearestShadowedDeclaration() {
        val harness = harness(
            """
            local value = 1
            do
                local value = "x"
                local current = value
            end
            """.trimIndent()
        )

        val type = harness.evaluator.evaluate(localInitializer(harness.chunk, "current"))
        val literal = assertIs<LiteralType>(type)
        assertSame(PrimitiveType.STRING, literal.baseType)
        assertEquals("\"x\"", literal.displayName)
    }

    @Test
    fun doesNotUseCurrentLocalInItsOwnInitializer() {
        val harness = harness("local value = value")

        val type = harness.evaluator.evaluate(localInitializer(harness.chunk, "value"))
        assertSame(UnknownType, type)
    }

    @Test
    fun resolvesParameterTypeFromDocResolvedFunctionDeclaration() {
        val harness = harness(
            """
            ---@param value string
            local function normalize(value)
                return value
            end
            """.trimIndent()
        )

        val function = namedFunction(harness.chunk, "normalize")
        val returnExpression = function.body!!.returnStatement!!.arguments.single()
        val type = harness.evaluator.evaluate(returnExpression)
        assertSame(PrimitiveType.STRING, type)
    }

    @Test
    fun returnsResolvedTypeForDocumentedFunctionDeclaration() {
        val harness = harness(
            """
            ---@param value number
            ---@return string
            local function normalize(value)
                return tostring(value)
            end
            """.trimIndent()
        )

        val type = harness.evaluator.evaluate(namedFunction(harness.chunk, "normalize"))
        val functionType = assertIs<FunctionType>(type)
        assertSame(PrimitiveType.NUMBER, functionType.parameters.single().type)
        assertSame(PrimitiveType.STRING, functionType.returnType)
    }

    @Test
    fun implementationInferenceIgnoresDocumentedReturnForDocumentedFunctionDeclaration() {
        val harness = harness(
            """
            ---@param value string
            ---@return number
            local function render(value)
                return 1, 2
            end
            """.trimIndent()
        )

        val function = namedFunction(harness.chunk, "render")
        val type = harness.evaluator.inferImplementationFunctionType(function)
        val functionType = assertIs<FunctionType>(type)
        assertEquals("value", functionType.parameters.single().name)
        assertEquals("string", functionType.parameters.single().type.displayName)
        assertEquals("1, 2", functionType.returnType.displayName)
    }

    @Test
    fun implementationInferenceUsesBodyReturnForColonMethodDeclaration() {
        val harness = harness(
            """
            local box = {}
            ---@param self table
            ---@param value number
            ---@param label string
            function box:render(value, label)
                return label
            end
            """.trimIndent()
        )

        val function = namedFunction(harness.chunk, "render")
        val type = harness.evaluator.inferImplementationFunctionType(function)
        val functionType = assertIs<FunctionType>(type)
        assertEquals(listOf("value", "label"), functionType.parameters.map { it.name })
        assertEquals(listOf("number", "string"), functionType.parameters.map { it.type.displayName })
        assertEquals("string", functionType.returnType.displayName)
    }

    @Test
    fun infersAnonymousFunctionTypeFromReturns() {
        val harness = harness("local fn = function(value) return value, 1 end")

        val type = harness.evaluator.evaluate(localInitializer(harness.chunk, "fn"))
        val functionType = assertIs<FunctionType>(type)
        assertSame(UnknownType, functionType.parameters.single().type)
        val returnType = assertIs<MultiReturnType>(functionType.returnType)
        assertSame(UnknownType, returnType.types[0])
        val literal = assertIs<LiteralType>(returnType.types[1])
        assertSame(PrimitiveType.NUMBER, literal.baseType)
        assertEquals("1", literal.displayName)
    }

    @Test
    fun evaluatesLambdaDeclaration() {
        val harness = harness("local fn = lambda value: value")
        val lambda = assertIs<LambdaDeclaration>(localInitializer(harness.chunk, "fn"))
        val type = harness.evaluator.evaluate(
            lambda,
            ExpressionTypeEvaluator.Context(
                lexicalScopeId = harness.scopeId(lambda),
                localOverrides = mapOf("value" to PrimitiveType.STRING)
            )
        )

        val functionType = assertIs<FunctionType>(type)
        assertSame(PrimitiveType.STRING, functionType.parameters.single().type)
        assertSame(PrimitiveType.STRING, functionType.returnType)
    }

    @Test
    fun memberAccessUsesResolvedClassFieldAndMethodTypes() {
        val harness = harness(
            """
            ---@class User
            ---@field name string
            ---@method User.getName fun(self: User): string
            
            ---@type User
            local user = {}
            local fieldValue = user.name
            local methodValue = user.getName
            """.trimIndent()
        )

        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "fieldValue")))
        val methodType = assertIs<FunctionType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "methodValue")))
        assertSame(PrimitiveType.STRING, methodType.returnType)
    }

    @Test
    fun memberAccessOverUnionsRequiresMemberOnEveryBranch() {
        val harness = harness(
            """
            ---@type { value: string } | { value: number }
            local item = {}
            local current = item.value
            """.trimIndent()
        )

        val type = harness.evaluator.evaluate(localInitializer(harness.chunk, "current"))
        val union = assertIs<UnionType>(type)
        assertEquals(setOf(PrimitiveType.STRING, PrimitiveType.NUMBER), union.types)
    }

    @Test
    fun resolvesAppliedGenericMemberAccessThroughLocals() {
        val harness = harness(
            """
            ---@class Box<T>
            ---@field value T
            ---@type Box<string>
            local box = {}
            local current = box.value
            """.trimIndent()
        )

        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "current")))
    }

    @Test
    fun indexAccessSupportsArrayTableAndTupleShapes() {
        val harness = harness(
            """
            ---@alias Pair [string, number]
            local array = [1, 2]
            local arrayValue = array[1]
            local record = { name = "lua" }
            local tableValue = record["name"]
            ---@type Pair
            local tuple = { "x", 1 }
            local tupleValue = tuple[2]
            """.trimIndent()
        )

        val arrayValueType = assertIs<UnionType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "arrayValue")))
        val arrayLiteralMembers = arrayValueType.types.map { assertIs<LiteralType>(it).displayName }.toSet()
        assertEquals(setOf("1", "2"), arrayLiteralMembers)
        val tableValueType = assertIs<LiteralType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "tableValue")))
        assertSame(PrimitiveType.STRING, tableValueType.baseType)
        assertEquals("\"lua\"", tableValueType.displayName)
        assertSame(PrimitiveType.NUMBER, harness.evaluator.evaluate(localInitializer(harness.chunk, "tupleValue")))
    }

    @Test
    fun resolvesShortStringCallImportAndBindClassTargetsFromWorkspaceContext() {
        val importModule = ModuleType(moduleName = "Locale")
        val bindModule = ModuleType(moduleName = "Context")
        val harness = harness(
            "local bindClass = luajava.bindClass\nlocal import = require \"import\"\nlocal Context = bindClass \"android.content.Context\"\nlocal Locale = import \"java.util.Locale\"",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    when (target) {
                        "android.content.Context" -> WorkspaceImportedSymbol(
                            alias = "Context",
                            moduleName = "Context",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/android/content/Context.lua"),
                            moduleType = bindModule
                        )
                        "java.util.Locale" -> WorkspaceImportedSymbol(
                            alias = "Locale",
                            moduleName = "Locale",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/util/Locale.lua"),
                            moduleType = importModule
                        )
                        else -> null
                    }
                }
            )
        )

        val contextType = harness.evaluator.evaluate(localInitializer(harness.chunk, "Context"))
        val localeType = harness.evaluator.evaluate(localInitializer(harness.chunk, "Locale"))

        assertSame(bindModule, contextType)
        assertSame(importModule, localeType)
    }

    @Test
    fun resolvesRealiasedImportAndBindClassTargetsFromWorkspaceContext() {
        val importModule = ModuleType(moduleName = "Locale")
        val bindModule = ModuleType(moduleName = "Context")
        val harness = harness(
            "local bindClass = luajava.bindClass\nlocal bind = bindClass\nlocal againBind = bind\nlocal import = require(\"import\")\nlocal load = import\nlocal againLoad = load\nlocal Context = againBind \"android.content.Context\"\nlocal Locale = againLoad \"java.util.Locale\"",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    when (target) {
                        "android.content.Context" -> WorkspaceImportedSymbol(
                            alias = "Context",
                            moduleName = "Context",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/android/content/Context.lua"),
                            moduleType = bindModule
                        )
                        "java.util.Locale" -> WorkspaceImportedSymbol(
                            alias = "Locale",
                            moduleName = "Locale",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/util/Locale.lua"),
                            moduleType = importModule
                        )
                        else -> null
                    }
                }
            )
        )

        val contextType = harness.evaluator.evaluate(localInitializer(harness.chunk, "Context"))
        val localeType = harness.evaluator.evaluate(localInitializer(harness.chunk, "Locale"))

        assertSame(bindModule, contextType)
        assertSame(importModule, localeType)
    }

    @Test
    fun resolvesNewInstanceCallsToWorkspaceBackedJvmClassTypes() {
        val builderModule = ModuleType(
            moduleName = "StringBuilder",
            fields = mapOf(
                "__class" to TableType(
                    fields = mapOf("length" to PrimitiveType.NUMBER),
                    methods = mapOf("append" to FunctionType(returnType = PrimitiveType.STRING))
                )
            )
        )
        val harness = harness(
            "local newInstance = luajava.newInstance\nlocal create = newInstance\nlocal builder = create(\"java.lang.StringBuilder\")",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    when (target) {
                        "java.lang.StringBuilder" -> WorkspaceImportedSymbol(
                            alias = "StringBuilder",
                            moduleName = "StringBuilder",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/lang/StringBuilder.lua"),
                            moduleType = builderModule
                        )
                        else -> null
                    }
                }
            )
        )

        val builderType = harness.evaluator.evaluate(localInitializer(harness.chunk, "builder"))
        val instanceType = assertIs<TableType>(builderType)
        assertSame(PrimitiveType.NUMBER, instanceType.fields["length"])
        val appendType = assertIs<FunctionType>(instanceType.methods.getValue("append"))
        assertSame(PrimitiveType.STRING, appendType.returnType)
    }

    @Test
    fun resolvesShortStringCallNewInstanceTargetsFromWorkspaceContext() {
        val stringModule = ModuleType(
            moduleName = "String",
            fields = mapOf(
                "__class" to TableType(fields = mapOf("length" to PrimitiveType.NUMBER))
            )
        )
        val harness = harness(
            "local text = luajava.newInstance \"java.lang.String\"",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    when (target) {
                        "java.lang.String" -> WorkspaceImportedSymbol(
                            alias = "String",
                            moduleName = "String",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/lang/String.lua"),
                            moduleType = stringModule
                        )
                        else -> null
                    }
                }
            )
        )

        val instanceType = assertIs<TableType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "text")))
        assertSame(PrimitiveType.NUMBER, instanceType.fields["length"])
    }

    @Test
    fun resolvesCreateProxyCallsToWorkspaceBackedJvmInterfaceTypes() {
        val runnableModule = ModuleType(
            moduleName = "Runnable",
            fields = mapOf(
                "__class" to TableType(methods = mapOf("run" to FunctionType(returnType = PrimitiveType.NIL)))
            )
        )
        val harness = harness(
            "local createProxy = luajava.createProxy\nlocal proxy = createProxy(\"java.lang.Runnable\", {})",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    when (target) {
                        "java.lang.Runnable" -> WorkspaceImportedSymbol(
                            alias = "Runnable",
                            moduleName = "Runnable",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/lang/Runnable.lua"),
                            moduleType = runnableModule
                        )
                        else -> null
                    }
                }
            )
        )

        val proxyType = assertIs<TableType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "proxy")))
        val runType = assertIs<FunctionType>(proxyType.methods.getValue("run"))
        assertSame(PrimitiveType.NIL, runType.returnType)
    }

    @Test
    fun resolvesCreateProxyCallsToIntersectionOfWorkspaceBackedJvmInterfaceTypes() {
        val runnableClass = TableType(methods = mapOf("run" to FunctionType(returnType = PrimitiveType.NIL)))
        val comparatorClass = TableType(methods = mapOf("compare" to FunctionType(returnType = PrimitiveType.NUMBER)))
        val harness = harness(
            "local proxy = luajava.createProxy(\"java.lang.Runnable\", \"java.util.Comparator\", {})",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    when (target) {
                        "java.lang.Runnable" -> WorkspaceImportedSymbol(
                            alias = "Runnable",
                            moduleName = "Runnable",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/lang/Runnable.lua"),
                            moduleType = ModuleType(moduleName = "Runnable", fields = mapOf("__class" to runnableClass))
                        )
                        "java.util.Comparator" -> WorkspaceImportedSymbol(
                            alias = "Comparator",
                            moduleName = "Comparator",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/util/Comparator.lua"),
                            moduleType = ModuleType(moduleName = "Comparator", fields = mapOf("__class" to comparatorClass))
                        )
                        else -> null
                    }
                }
            )
        )

        val proxyType = assertIs<io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType>(
            harness.evaluator.evaluate(localInitializer(harness.chunk, "proxy"))
        )
        assertEquals(setOf(runnableClass, comparatorClass), proxyType.types)
    }

    @Test
    fun resolvesLoadLibCallsToWorkspaceBackedJvmStaticMembers() {
        val systemModule = ModuleType(
            moduleName = "System",
            methods = mapOf("currentTimeMillis" to FunctionType(returnType = PrimitiveType.NUMBER))
        )
        val harness = harness(
            "local loadLib = luajava.loadLib\nlocal load = loadLib\nlocal currentTimeMillis = load(\"java.lang.System\", \"currentTimeMillis\")",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    when (target) {
                        "java.lang.System" -> WorkspaceImportedSymbol(
                            alias = "System",
                            moduleName = "System",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/lang/System.lua"),
                            moduleType = systemModule
                        )
                        else -> null
                    }
                }
            )
        )

        val memberType = assertIs<FunctionType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "currentTimeMillis")))
        assertSame(PrimitiveType.NUMBER, memberType.returnType)
    }

    @Test
    fun resolvesShortStringLoadLibTargetsFromWorkspaceContext() {
        val localeModule = ModuleType(
            moduleName = "Locale",
            methods = mapOf("getDefault" to FunctionType(returnType = PrimitiveType.STRING))
        )
        val harness = harness(
            "local getDefault = luajava.loadLib \"java.util.Locale\", \"getDefault\"",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    when (target) {
                        "java.util.Locale" -> WorkspaceImportedSymbol(
                            alias = "Locale",
                            moduleName = "Locale",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/util/Locale.lua"),
                            moduleType = localeModule
                        )
                        else -> null
                    }
                }
            )
        )

        val memberType = assertIs<FunctionType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "getDefault")))
        assertSame(PrimitiveType.STRING, memberType.returnType)
    }

    @Test
    fun resolvesConstructorStyleCallsOnWorkspaceBackedJvmModulesToInstanceTypes() {
        val builderModule = ModuleType(
            moduleName = "StringBuilder",
            fields = mapOf(
                "__class" to TableType(
                    fields = mapOf("length" to PrimitiveType.NUMBER),
                    methods = mapOf("append" to FunctionType(returnType = PrimitiveType.STRING))
                )
            )
        )
        val harness = harness(
            "local builder = StringBuilder()",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    when (target) {
                        "java.lang.StringBuilder" -> WorkspaceImportedSymbol(
                            alias = "StringBuilder",
                            moduleName = "StringBuilder",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/lang/StringBuilder.lua"),
                            moduleType = builderModule
                        )
                        else -> null
                    }
                },
                importedSymbols = mapOf(
                    "StringBuilder" to WorkspaceImportedSymbol(
                        alias = "StringBuilder",
                        moduleName = "StringBuilder",
                        providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/lang/StringBuilder.lua"),
                        moduleType = builderModule
                    )
                )
            )
        )

        val builderType = harness.evaluator.evaluate(localInitializer(harness.chunk, "builder"))
        val instanceType = assertIs<TableType>(builderType)
        assertSame(PrimitiveType.NUMBER, instanceType.fields["length"])
        val appendType = assertIs<FunctionType>(instanceType.methods.getValue("append"))
        assertSame(PrimitiveType.STRING, appendType.returnType)
    }

    @Test
    fun resolvesTableImportCallsToArrayOfImportedModuleTypes() {
        val localeModule = ModuleType(moduleName = "Locale")
        val contextModule = ModuleType(moduleName = "Context")
        val harness = harness(
            "local import = require(\"import\")\nlocal classes = import({ \"java.util.Locale\", \"android.content.Context\" })",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    when (target) {
                        "java.util.Locale" -> WorkspaceImportedSymbol(
                            alias = "Locale",
                            moduleName = "Locale",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/util/Locale.lua"),
                            moduleType = localeModule
                        )
                        "android.content.Context" -> WorkspaceImportedSymbol(
                            alias = "Context",
                            moduleName = "Context",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/android/content/Context.lua"),
                            moduleType = contextModule
                        )
                        else -> null
                    }
                }
            )
        )

        val type = assertIs<io.github.dingyi222666.luaparser.semantic.types.model.ArrayType>(
            harness.evaluator.evaluate(localInitializer(harness.chunk, "classes"))
        )
        val elementType = assertIs<UnionType>(type.elementType)
        assertEquals(setOf(localeModule, contextModule), elementType.types)
    }


    @Test
    fun resolvesWildcardImportCallToAndroidLuaStylePackageModule() {
        val textViewModule = ModuleType(moduleName = "TextView")
        val packageModule = ModuleType(
            moduleName = "android.widget",
            fields = mapOf("TextView" to textViewModule),
            indexSignature = ModuleType.IndexSignature(PrimitiveType.STRING, UnknownType)
        )
        val harness = harness(
            "local import = require(\"import\")\nlocal widget = import(\"android.widget.*\")\nlocal TextView = widget.TextView",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    if (target == "android.widget.*") {
                        WorkspaceImportedSymbol(
                            alias = "android.widget",
                            moduleName = "android.widget",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/packages/android/widget.lua"),
                            moduleType = packageModule
                        )
                    } else {
                        null
                    }
                }
            )
        )

        val widgetType = assertIs<ModuleType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "widget")))
        val textViewType = harness.evaluator.evaluate(localInitializer(harness.chunk, "TextView"))

        assertEquals("android.widget", widgetType.moduleName)
        assertSame(packageModule, widgetType)
        assertSame(textViewModule, textViewType)
    }

    @Test
    fun resolvesDexPrefixedWildcardImportTargetsToAndroidLuaStylePackageModule() {
        val textViewModule = ModuleType(moduleName = "TextView")
        val packageModule = ModuleType(
            moduleName = "android.widget",
            fields = mapOf("TextView" to textViewModule),
            indexSignature = ModuleType.IndexSignature(PrimitiveType.STRING, UnknownType)
        )
        val harness = harness(
            "local import = require(\"import\")\nlocal widget = import(\"plugin.dex:android.widget.*\")\nlocal TextView = widget.TextView",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    if (target == "plugin.dex:android.widget.*") {
                        WorkspaceImportedSymbol(
                            alias = "android.widget",
                            moduleName = "android.widget",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/packages/android/widget.lua"),
                            moduleType = packageModule
                        )
                    } else {
                        null
                    }
                }
            )
        )

        val widgetType = assertIs<ModuleType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "widget")))
        val textViewType = harness.evaluator.evaluate(localInitializer(harness.chunk, "TextView"))

        assertEquals("android.widget", widgetType.moduleName)
        assertSame(packageModule, widgetType)
        assertSame(textViewModule, textViewType)
    }

    @Test
    fun resolvesRealiasedWildcardImportTargetsToAndroidLuaStylePackageModule() {
        val textViewModule = ModuleType(moduleName = "TextView")
        val packageModule = ModuleType(
            moduleName = "android.widget",
            fields = mapOf("TextView" to textViewModule),
            indexSignature = ModuleType.IndexSignature(PrimitiveType.STRING, UnknownType)
        )
        val harness = harness(
            "local import = require(\"import\")\nlocal load = import\nlocal again = load\nlocal widget = again \"android.widget.*\"\nlocal TextView = widget.TextView",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    if (target == "android.widget.*") {
                        WorkspaceImportedSymbol(
                            alias = "android.widget",
                            moduleName = "android.widget",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/packages/android/widget.lua"),
                            moduleType = packageModule
                        )
                    } else {
                        null
                    }
                }
            )
        )

        val widgetType = assertIs<ModuleType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "widget")))
        val textViewType = harness.evaluator.evaluate(localInitializer(harness.chunk, "TextView"))

        assertEquals("android.widget", widgetType.moduleName)
        assertSame(packageModule, widgetType)
        assertSame(textViewModule, textViewType)
    }

    @Test
    fun resolvesDexPrefixedImportTargetsFromWorkspaceContext() {
        val contextModule = ModuleType(moduleName = "Context")
        val harness = harness(
            "local import = require(\"import\")\nlocal Context = import(\"plugin.dex:android.content.Context\")",
            SemanticWorkspaceContext(
                resolveImportTarget = { target ->
                    if (target == "plugin.dex:android.content.Context") {
                        WorkspaceImportedSymbol(
                            alias = "Context",
                            moduleName = "Context",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/android/content/Context.lua"),
                            moduleType = contextModule
                        )
                    } else {
                        null
                    }
                }
            )
        )

        val contextType = harness.evaluator.evaluate(localInitializer(harness.chunk, "Context"))
        assertSame(contextModule, contextType)
    }

    @Test
    fun localDeclarationShadowsWorkspaceImportedSymbolWithSameAlias() {
        val importedString = ModuleType(moduleName = "String")
        val harness = harness(
            "local String = 1\nlocal current = String",
            SemanticWorkspaceContext(
                importedSymbols = mapOf(
                    "String" to WorkspaceImportedSymbol(
                        alias = "String",
                        moduleName = "String",
                        providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/lang/String.lua"),
                        moduleType = importedString
                    )
                )
            )
        )

        val type = harness.evaluator.evaluate(localInitializer(harness.chunk, "current"))
        val literal = assertIs<LiteralType>(type)
        assertSame(PrimitiveType.NUMBER, literal.baseType)
        assertEquals("1", literal.displayName)
    }
    @Test
    fun localDeclarationShadowsResolveImportedSymbolLookup() {
        val harness = harness(
            "local Locale = 1\nlocal current = Locale",
            SemanticWorkspaceContext(
                resolveImportedSymbol = { name ->
                    if (name == "Locale") {
                        WorkspaceImportedSymbol(
                            alias = "Locale",
                            moduleName = "Locale",
                            providerPath = io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath.of("__jvm__/classes/java/util/Locale.lua"),
                            moduleType = ModuleType(moduleName = "Locale")
                        )
                    } else {
                        null
                    }
                }
            )
        )

        val type = harness.evaluator.evaluate(localInitializer(harness.chunk, "current"))
        val literal = assertIs<LiteralType>(type)
        assertSame(PrimitiveType.NUMBER, literal.baseType)
        assertEquals("1", literal.displayName)
    }

    @Test
    fun overload_calls_choose_expected_branch() {
        val harness = harness(
            """
            ---@overload fun(value: string): string
            ---@param value number
            ---@return number
            local function normalize(value)
                return value
            end
            local fromNumber = normalize(1)
            local fromString = normalize("x")
            """.trimIndent()
        )

        assertSame(PrimitiveType.NUMBER, harness.evaluator.evaluate(localInitializer(harness.chunk, "fromNumber")))
        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "fromString")))
    }

    @Test
    fun resolvesAppliedGenericMethodCallReturnType() {
        val harness = harness(
            """
            ---@class Box<T>
            ---@field value T
            ---@method Box:get(): T
            ---@type Box<string>
            local box = {}
            local current = box:get()
            """.trimIndent()
        )

        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "current")))
    }

    @Test
    fun memberAccessReadsInheritedGenericFieldFromAppliedSubclassLocal() {
        val harness = harness(
            """
            ---@class Base<T>
            ---@field value T
            ---@class Box<T>: Base<T>
            ---@type Box<string>
            local box = {}
            local current = box.value
            """.trimIndent()
        )

        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "current")))
    }

    @Test
    fun methodCallReadsInheritedGenericMethodFromAppliedSubclassLocal() {
        val harness = harness(
            """
            ---@class Base<T>
            ---@method Base:get(): T
            ---@class Box<T>: Base<T>
            ---@type Box<string>
            local box = {}
            local current = box:get()
            """.trimIndent()
        )

        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "current")))
    }

    @Test
    fun aliasWrappedAppliedSubclassFlowsThroughLocalTypeAnnotation() {
        val harness = harness(
            """
            ---@class Base<T>
            ---@field value T
            ---@method Base:get(): T
            ---@class Box<T>: Base<T>
            ---@alias StringBox Box<string>
            ---@type StringBox
            local box = {}
            local fieldValue = box.value
            local methodValue = box:get()
            """.trimIndent()
        )

        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "fieldValue")))
        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "methodValue")))
    }

    @Test
    fun aliasWrappedCallableCallResolvesReturnType() {
        val harness = harness(
            """
            ---@alias Mapper<T> fun(value: T): T
            ---@type Mapper<string>
            local map = function(value) return value end
            local current = map("x")
            """.trimIndent()
        )

        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "current")))
    }

    @Test
    fun memberCallThroughOverloadedMethodUsesSelectedReturnType() {
        val harness = harness(
            """
            ---@class Widget
            ---@method Widget:pick(): number
            ---@overload fun(self: Widget, value: string): string
            ---@type Widget
            local widget = {}
            local noArg = widget:pick()
            local withString = widget:pick("x")
            """.trimIndent()
        )

        assertSame(PrimitiveType.NUMBER, harness.evaluator.evaluate(localInitializer(harness.chunk, "noArg")))
        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "withString")))
    }

    @Test
    fun colonMethodCallPrependsSelfArgument() {
        val harness = harness(
            """
            ---@class User
            ---@method User:getName(): string
            ---@type User
            local user = {}
            local current = user:getName()
            """.trimIndent()
        )

        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "current")))
    }

    @Test
    fun nonCallableCallFallsBackToUnknown() {
        val harness = harness("local value = (1)()")

        val type = harness.evaluator.evaluate(localInitializer(harness.chunk, "value"))
        assertSame(UnknownType, type)
    }

    @Test
    fun tableConstructorBuildsFieldsAndMethods() {
        val harness = harness(
            """
            local value = {
                name = "lua",
                ["size"] = 1,
                run = function() return true end
            }
            """.trimIndent()
        )

        val type = harness.evaluator.evaluate(localInitializer(harness.chunk, "value"))
        val tableType = assertIs<TableType>(type)
        val nameType = assertIs<LiteralType>(tableType.fields.getValue("name"))
        assertSame(PrimitiveType.STRING, nameType.baseType)
        assertEquals("\"lua\"", nameType.displayName)
        val sizeType = assertIs<LiteralType>(tableType.fields.getValue("size"))
        assertSame(PrimitiveType.NUMBER, sizeType.baseType)
        assertEquals("1", sizeType.displayName)
        assertIs<FunctionType>(tableType.methods.getValue("run"))
    }

    @Test
    fun binaryAndUnaryOperatorsReturnExpectedCoarseTypes() {
        val harness = harness(
            """
            local sum = 1 + 2
            local flag = not sum
            local joined = "a" .. "b"
            local either = 1 or "x"
            """.trimIndent()
        )

        assertSame(PrimitiveType.NUMBER, harness.evaluator.evaluate(localInitializer(harness.chunk, "sum")))
        assertSame(PrimitiveType.BOOLEAN, harness.evaluator.evaluate(localInitializer(harness.chunk, "flag")))
        assertSame(PrimitiveType.STRING, harness.evaluator.evaluate(localInitializer(harness.chunk, "joined")))
        val eitherType = assertIs<UnionType>(harness.evaluator.evaluate(localInitializer(harness.chunk, "either")))
        val literalMembers = eitherType.types.map { assertIs<LiteralType>(it).displayName }.toSet()
        assertEquals(setOf("1", "\"x\""), literalMembers)
    }

    @Test
    fun type_resolver_preserves_function_display_name_when_attaching_generic_type_parameters() {
        val chunk = parser.parse(
            """
            ---@generic T
            ---@param value T
            ---@return T
            local function identity(value)
                return value
            end
            """.trimIndent()
        )
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        val declaration = resolved.declarationIndex.declarations.first { it.name == "identity" && it.kind == DeclarationKind.FUNCTION }

        assertEquals("fun<T>(value: T): T", declaration.declaredType?.displayName)
    }

    private fun harness(source: String): Harness {
        return harness(source, SemanticWorkspaceContext())
    }

    private fun harness(source: String, workspaceContext: SemanticWorkspaceContext): Harness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            chunk = chunk,
            binder = resolved,
            evaluator = ExpressionTypeEvaluator(resolved, workspaceContext)
        )
    }

    private fun localInitializer(chunk: ChunkNode, name: String): ExpressionNode {
        val statement = findLocalStatement(chunk.body, name)
        val index = statement.init.indexOfFirst { it.name == name }
        return statement.variables[if (index < statement.variables.size) index else statement.variables.lastIndex]
    }

    private fun namedFunction(chunk: ChunkNode, name: String): FunctionDeclaration {
        return findStatements(chunk.body)
            .filterIsInstance<FunctionDeclaration>()
            .first { function ->
                when (val identifier = function.identifier) {
                    is Identifier -> identifier.name == name
                    is io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression -> identifier.identifier.name == name
                    else -> false
                }
            }
    }

    private fun findLocalStatement(block: BlockNode, name: String): LocalStatement {
        return findStatements(block)
            .filterIsInstance<LocalStatement>()
            .first { statement -> statement.init.any { it.name == name } }
    }

    private fun findStatements(block: BlockNode): List<io.github.dingyi222666.luaparser.parser.ast.node.StatementNode> {
        val result = mutableListOf<io.github.dingyi222666.luaparser.parser.ast.node.StatementNode>()

        fun visit(current: BlockNode) {
            current.statements.forEach { statement ->
                result += statement
                when (statement) {
                    is DoStatement -> visit(statement.body)
                    is FunctionDeclaration -> statement.body?.let(::visit)
                    is io.github.dingyi222666.luaparser.parser.ast.node.IfStatement -> statement.causes.forEach { visit(it.body) }
                    is io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement -> visit(statement.body)
                    is io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement -> visit(statement.body)
                    is io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement -> visit(statement.body)
                    is io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement -> visit(statement.body)
                    is io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement -> statement.causes.forEach { cause ->
                        when (cause) {
                            is io.github.dingyi222666.luaparser.parser.ast.node.CaseCause -> visit(cause.body)
                            is io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause -> visit(cause.body)
                        }
                    }

                    else -> Unit
                }
            }
        }

        visit(block)
        return result
    }

    private data class Harness(
        val chunk: ChunkNode,
        val binder: BinderPassResult,
        val evaluator: ExpressionTypeEvaluator
    ) {
        fun scopeId(node: ExpressionNode) =
            binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
    }
}
