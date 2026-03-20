package parser

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.BreakStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ContinueStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

private const val PARSER_REGRESSION_RESOURCE_ROOT = "/parser/regressions"

fun parse(version: LuaVersion, source: String, recovery: Boolean = false): ChunkNode {
    return LuaParser(luaVersion = version, errorRecovery = recovery).parse(source)
}

fun parseRecovering(version: LuaVersion, source: String): ChunkNode {
    return parse(version = version, source = source, recovery = true)
}

fun loadParserRegressionResource(path: String): String {
    val normalizedPath = when {
        path.startsWith(PARSER_REGRESSION_RESOURCE_ROOT) -> path
        path.startsWith("/") -> path
        else -> "$PARSER_REGRESSION_RESOURCE_ROOT/$path"
    }

    val stream = checkNotNull(object {}.javaClass.getResourceAsStream(normalizedPath)) {
        "Missing parser regression resource: $normalizedPath"
    }

    return stream.bufferedReader().use { it.readText() }
}

fun loadParserRegressionShape(path: String): String {
    val shapePath = when {
        path.endsWith(".shape.txt") -> path
        path.endsWith(".lua") -> path.removeSuffix(".lua") + ".shape.txt"
        else -> "$path.shape.txt"
    }
    return loadParserRegressionResource(shapePath)
}

fun parseResource(version: LuaVersion, path: String, recovery: Boolean = false): ChunkNode {
    return parse(version = version, source = loadParserRegressionResource(path), recovery = recovery)
}

fun assertResourceShape(version: LuaVersion, path: String, recovery: Boolean = false): ChunkNode {
    val chunk = parseResource(version = version, path = path, recovery = recovery)

    assertEquals(loadParserRegressionShape(path).trimEnd(), renderShape(chunk).trimEnd())

    return chunk
}

fun assertParseFails(version: LuaVersion, source: String, recovery: Boolean = false): Throwable {
    return assertFails {
        parse(version = version, source = source, recovery = recovery)
    }
}

inline fun <reified T : StatementNode> ChunkNode.firstStatement(): T {
    return assertIs<T>(body.statements.first())
}

fun ChunkNode.returnExpression(): ExpressionNode {
    return body.returnStatement?.arguments?.single()
        ?: error("Expected a single return expression")
}

fun ChunkNode.comments(): List<CommentStatement> {
    return body.statements.filterIsInstance<CommentStatement>()
}

fun renderShape(node: BaseASTNode): String {
    return when (node) {
        is ChunkNode -> "Chunk(${renderShape(node.body)})"
        is BlockNode -> buildString {
            append("Block[")
            append(node.statements.joinToString(";") { renderShape(it) })
            node.returnStatement?.let {
                if (node.statements.isNotEmpty()) append(';')
                append(renderShape(it))
            }
            append(']')
        }
        is ReturnStatement -> "Return(${node.arguments.joinToString(",") { renderShape(it) }})"
        is LocalStatement -> "Local(${node.init.joinToString(",") { renderShape(it) }}=${node.variables.joinToString(",") { renderShape(it) }})"
        is AssignmentStatement -> "Assign(${node.init.joinToString(",") { renderShape(it) }}=${node.variables.joinToString(",") { renderShape(it) }})"
        is CallStatement -> "CallStmt(${renderShape(node.expression)})"
        is WhileStatement -> "While(${renderShape(node.condition)}:${renderShape(node.body)})"
        is RepeatStatement -> "Repeat(${renderShape(node.body)}:${renderShape(node.condition)})"
        is DoStatement -> "Do(${renderShape(node.body)})"
        is BreakStatement -> "Break"
        is ContinueStatement -> "Continue"
        is LabelStatement -> "Label(${renderShape(node.identifier)})"
        is GotoStatement -> "Goto(${renderShape(node.identifier)})"
        is ForNumericStatement -> "ForNumeric(${renderShape(node.variable)}=${renderShape(node.start)},${renderShape(node.end)},${node.step?.let(::renderShape) ?: "null"}:${renderShape(node.body)})"
        is ForGenericStatement -> "ForGeneric(${node.variables.joinToString(",") { renderShape(it) }} in ${node.iterators.joinToString(",") { renderShape(it) }}:${renderShape(node.body)})"
        is WhenStatement -> "When(${renderShape(node.condition)}?${renderShape(node.ifCause)}:${node.elseCause?.let(::renderShape) ?: "null"})"
        is SwitchStatement -> "Switch(${renderShape(node.condition)}:${node.causes.joinToString(",") { renderShape(it) }})"
        is CaseCause -> "Case(${node.conditions.joinToString(",") { renderShape(it) }}:${renderShape(node.body)})"
        is DefaultCause -> "Default(${renderShape(node.body)})"
        is IfStatement -> "If(${node.causes.joinToString(",") { renderShape(it) }})"
        is ElseIfClause -> "ElseIf(${renderShape(node.condition)}:${renderShape(node.body)})"
        is ElseClause -> "Else(${renderShape(node.body)})"
        is IfClause -> "Clause(${renderShape(node.condition)}:${renderShape(node.body)})"
        is FunctionDeclaration -> "Function(${node.identifier?.let(::renderShape)},${node.body?.let(::renderShape) ?: "null"})"
        is LambdaDeclaration -> "Lambda(${node.params.joinToString(",") { renderShape(it) }}:${renderShape(node.expression)})"
        is BinaryExpression -> "Binary(${node.operator},${renderShape(node.left!!)},${renderShape(node.right!!)})"
        is UnaryExpression -> "Unary(${node.operator},${renderShape(node.arg)})"
        is StringCallExpression -> "StringCall(${renderShape(node.base)}:${node.arguments.joinToString(",") { renderShape(it) }})"
        is TableCallExpression -> "TableCall(${renderShape(node.base)}:${node.arguments.joinToString(",") { renderShape(it) }})"
        is CallExpression -> "Call(${renderShape(node.base)}:${node.arguments.joinToString(",") { renderShape(it) }})"
        is MemberExpression -> "Member(${renderShape(node.base)}${node.indexer}${node.identifier.name})"
        is IndexExpression -> "Index(${renderShape(node.base)}[${renderShape(node.index)}])"
        is TableConstructorExpression -> "Table(${node.fields.joinToString(",") { renderShape(it) }})"
        is TableKeyString -> "TableKeyString(${renderShape(node.key)}=${renderShape(node.value)})"
        is TableKey -> "TableKey(${renderShape(node.key)}=${renderShape(node.value)})"
        is ArrayConstructorExpression -> "Array(${node.values.joinToString(",") { renderShape(it) }})"
        is AttributeIdentifier -> "AttrId(${node.name}${node.attributeName?.let { "<$it>" } ?: ""})"
        is Identifier -> "Id(${node.name})"
        is ConstantNode -> "Const(${node.rawValue})"
        is VarargLiteral -> "Vararg"
        is CommentStatement -> "Comment(${if (node.isDocComment) "doc" else "line"}:${node.comment.trim()})"
        is StatementNode -> node::class.simpleName ?: "Statement"
        else -> node::class.simpleName ?: "Node"
    }
}
