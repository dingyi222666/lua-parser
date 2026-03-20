package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Range

object LegacyModuleEnvironmentPass {
    fun analyze(path: VirtualPath, facts: DocumentFacts): LegacyModuleEnvironment {
        val topLevelLegacyCalls = facts.legacyModuleCalls
            .filter { it.isTopLevel }
            .associateBy { it.range }

        val segments = facts.environmentSegments.mapNotNull { segment ->
            if (segment.mode == ModuleEnvironmentMode.CHUNK) {
                return@mapNotNull null
            }

            val triggerRange = segment.triggerRange ?: return@mapNotNull null
            val call = topLevelLegacyCalls[triggerRange] ?: return@mapNotNull null

            LegacyModuleEnvironment.Segment(
                moduleName = call.moduleName,
                mode = segment.mode,
                hasSeeAllFallback = segment.mode == ModuleEnvironmentMode.LEGACY_MODULE_SEEALL,
                range = Range(segment.start, segment.end),
                triggerRange = triggerRange,
                bindings = legacyEnvironmentBindings(call.moduleName)
            )
        }

        return LegacyModuleEnvironment(segments = segments)
    }
}
