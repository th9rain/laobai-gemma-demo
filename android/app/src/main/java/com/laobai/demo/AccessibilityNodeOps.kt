package com.laobai.demo

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

enum class ControlKind {
    EDITABLE,
    SELECT,
}

data class SemanticTarget(
    val htmlId: String,
    val label: String,
    val hints: List<String> = emptyList(),
    val kind: ControlKind,
)

/**
 * Small, bounded accessibility-tree helpers. Every node returned from a find
 * method is an owned copy and must be passed to [recycle] by the caller.
 */
object AccessibilityNodeOps {
    private const val MAX_NODES = 1_500
    private const val MAX_DEPTH = 45
    private const val MAX_RELATION_DEPTH = 10
    private const val MAX_CHOICE_ANCESTOR_DEPTH = 3
    private const val MAX_CHOICE_RELATION_SCORE = 3

    fun visibleText(root: AccessibilityNodeInfo): String =
        collectText(root, visibleOnly = true)

    fun treeText(root: AccessibilityNodeInfo): String =
        collectText(root, visibleOnly = false)

    fun detectCase(root: AccessibilityNodeInfo?): DemoCase? {
        if (root == null) return null
        val text = treeText(root)
        return when {
            text.contains("北京市海淀区老年大学") ||
                text.contains("2026年秋季学期学员报名登记表") ||
                text.contains("基本信息") && text.contains("出生年月") ||
                text.contains("居住与健康信息") && text.contains("居住区") ||
                text.contains("紧急联系人") && text.contains("与本人关系") ||
                text.contains("选择报名课程") && text.contains("选择上课时间") ||
                text.contains("报名信息确认") -> DemoCase.ALWAYS_ON

            text.contains("北京市预约挂号统一平台") ||
                text.contains("选择医院") && text.contains("北京协和医院") ||
                text.contains("选择科室") && text.contains("消化内科") ||
                text.contains("选择医生") && text.contains("李明") ||
                text.contains("选择号源") && text.contains("10:00") ||
                text.contains("确认预约") && text.contains("当前就诊人") -> DemoCase.TRIGGER

            else -> null
        }
    }

    fun findControl(
        root: AccessibilityNodeInfo,
        target: SemanticTarget,
    ): AccessibilityNodeInfo? = withFlattened(root) { nodes ->
        nodes.firstOrNull { node ->
            if (!node.isEnabled) return@firstOrNull false
            val id = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
            matchesHtmlId(id, target.htmlId)
        }?.let(::copyNode)?.let { return@withFlattened it }

        val candidates = nodes.filter { it.isVisibleToUser && it.isEnabled && matchesKind(it, target.kind) }

        val hintMatches = candidates.filter { node ->
            target.hints.any { hint -> ownText(node).containsNormalized(hint) }
        }
        if (hintMatches.size == 1) {
            return@withFlattened copyNode(hintMatches.single())
        }

        val wantedLabel = normalize(target.label)
        val labelNode = nodes.firstOrNull { node ->
            node.isVisibleToUser && normalize(ownText(node)) == wantedLabel
        } ?: return@withFlattened null
        val labelBounds = Rect().also(labelNode::getBoundsInScreen)
        candidates
            .asSequence()
            .map { candidate ->
                val bounds = Rect().also(candidate::getBoundsInScreen)
                candidate to geometricDistance(labelBounds, bounds)
            }
            .filter { (_, distance) -> distance < labelDistanceLimit(labelBounds) }
            .minByOrNull { (_, distance) -> distance }
            ?.first
            ?.let(::copyNode)
    }

    fun findText(
        root: AccessibilityNodeInfo,
        text: String,
        exact: Boolean,
    ): AccessibilityNodeInfo? = withFlattened(root) { nodes ->
        val wanted = normalize(text)
        nodes.firstOrNull { node ->
            if (!node.isVisibleToUser || !node.isEnabled) return@firstOrNull false
            val actual = normalize(ownText(node))
            if (exact) actual == wanted else actual.contains(wanted)
        }?.let(::copyNode)
    }

    fun findActionText(
        root: AccessibilityNodeInfo,
        text: String,
        exact: Boolean,
    ): AccessibilityNodeInfo? = withFlattened(root) { nodes ->
        val wanted = normalize(text)
        val matches = nodes.filter { node ->
            if (!node.isVisibleToUser || !node.isEnabled) return@filter false
            val actual = normalize(ownText(node))
            if (exact) actual == wanted else actual.contains(wanted)
        }
        val actionable = matches.filter(::hasActionableSelfOrAncestor)
        (actionable.minByOrNull { node ->
            abs(normalize(ownText(node)).length - wanted.length)
        } ?: matches.firstOrNull())?.let(::copyNode)
    }

    /**
     * Finds one semantic action target and refuses ambiguous duplicate labels.
     * This is used for native WebView select dialogs where choosing the wrong
     * equal-named row would be harder to recover from than stopping safely.
     */
    fun findUniqueActionText(
        root: AccessibilityNodeInfo,
        text: String,
        exact: Boolean,
    ): AccessibilityNodeInfo? = withFlattened(root) { nodes ->
        val wanted = normalize(text)
        val ownedTargets = LinkedHashMap<NodeIdentity, AccessibilityNodeInfo>()
        nodes.asSequence()
            .filter { node ->
                if (!node.isVisibleToUser || !node.isEnabled) return@filter false
                val actual = normalize(ownText(node))
                val textMatches = if (exact) actual == wanted else actual.contains(wanted)
                textMatches && hasActionableSelfOrAncestor(node)
            }
            .forEach { match ->
                val target = findClickTargetCopy(match) ?: return@forEach
                if (!target.isVisibleToUser || !target.isEnabled) {
                    recycle(target)
                    return@forEach
                }
                val identity = nodeIdentity(target)
                if (ownedTargets.containsKey(identity)) {
                    recycle(target)
                } else {
                    ownedTargets[identity] = target
                }
            }

        if (ownedTargets.size == 1) {
            ownedTargets.values.single()
        } else {
            ownedTargets.values.forEach(::recycle)
            null
        }
    }

    /**
     * Resolves a radio/checkbox choice from its visible label.
     *
     * Chromium commonly exposes the label text and the underlying HTML input
     * as sibling accessibility nodes. In that shape there is no actionable
     * ancestor to climb from the text node, so [findActionText] alone cannot
     * reliably reach the checkable control. Prefer an explicit semantic
     * relation when one exists, then use a tightly bounded nearest-checkable
     * fallback for that WebView-specific sibling layout.
     */
    fun findChoiceControl(
        root: AccessibilityNodeInfo,
        text: String,
        exact: Boolean,
    ): AccessibilityNodeInfo? = withFlattened(root) { nodes ->
        val wanted = normalize(text)
        val labels = nodes.filter { node ->
            if (!node.isVisibleToUser || !node.isEnabled) return@filter false
            val actual = normalize(ownText(node))
            if (exact) actual == wanted else actual.contains(wanted)
        }
        if (labels.isEmpty()) return@withFlattened null

        val checkables = nodes.filter { node ->
            node.isVisibleToUser && node.isEnabled && node.isCheckable
        }
        if (checkables.isEmpty()) return@withFlattened null

        // Strongest signal: the control itself exposes the requested aria-label,
        // or Android exposes an explicit labelFor/labeledBy relation.
        val semanticMatches = LinkedHashSet<NodeIdentity>()
        checkables
            .filter { candidate -> matchesChoiceText(candidate, wanted, exact) }
            .mapTo(semanticMatches, ::nodeIdentity)

        labels.forEach { label ->
            val labelFor = runCatching { label.labelFor }.getOrNull()
            if (labelFor != null) {
                try {
                    if (labelFor.isVisibleToUser && labelFor.isEnabled && labelFor.isCheckable) {
                        semanticMatches += nodeIdentity(labelFor)
                    }
                } finally {
                    recycle(labelFor)
                }
            }
        }
        checkables.forEach { candidate ->
            val labeledBy = runCatching { candidate.labeledBy }.getOrNull()
            if (labeledBy != null) {
                try {
                    if (matchesChoiceText(labeledBy, wanted, exact)) {
                        semanticMatches += nodeIdentity(candidate)
                    }
                } finally {
                    recycle(labeledBy)
                }
            }
        }

        val semanticCandidates = distinctNodes(
            checkables.filter { nodeIdentity(it) in semanticMatches },
        )
        when (semanticCandidates.size) {
            1 -> return@withFlattened copyNode(semanticCandidates.single())
            in 2..Int.MAX_VALUE -> return@withFlattened null
        }

        // Chromium may expose the input and its text as siblings below one
        // small label wrapper. Only accept a unique candidate at the closest
        // structural level; a shared form/group ancestor is deliberately too broad.
        val structurallyScored = checkables.mapNotNull { candidate ->
            val score = labels.minOfOrNull { label ->
                localRelationDepth(label, candidate) ?: Int.MAX_VALUE
            } ?: Int.MAX_VALUE
            if (score <= MAX_CHOICE_RELATION_SCORE) candidate to score else null
        }
        val bestStructuralScore = structurallyScored.minOfOrNull { (_, score) -> score }
        if (bestStructuralScore != null) {
            val structuralCandidates = distinctNodes(
                structurallyScored
                    .filter { (_, score) -> score == bestStructuralScore }
                    .map { (candidate, _) -> candidate },
            )
            return@withFlattened structuralCandidates
                .singleOrNull()
                ?.let(::copyNode)
        }

        // Last resort for WebView sibling nodes without semantic relations.
        // The rectangles must actually overlap or be immediately adjacent, and
        // there must be exactly one such checkable in that tight local area.
        val geometricCandidates = distinctNodes(
            checkables.filter { candidate ->
                val candidateBounds = Rect().also(candidate::getBoundsInScreen)
                labels.any { label ->
                    val labelBounds = Rect().also(label::getBoundsInScreen)
                    areTightlyAdjacent(labelBounds, candidateBounds)
                }
            },
        )
        geometricCandidates.singleOrNull()?.let(::copyNode)
    }

    fun currentValue(node: AccessibilityNodeInfo): String =
        normalize(node.text?.toString().orEmpty())

    fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        if (!node.isEnabled || !node.isVisibleToUser || !node.isEditable) return false
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value,
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun choiceCheckedState(node: AccessibilityNodeInfo): Boolean? {
        findCheckableCopy(node)?.let { checkable ->
            return try {
                checkable.isChecked
            } finally {
                recycle(checkable)
            }
        }
        var current: AccessibilityNodeInfo? = copyNode(node)
        repeat(MAX_RELATION_DEPTH) {
            val candidate = current ?: return null
            if (candidate.isSelected) {
                recycle(candidate)
                return true
            }
            val parent = candidate.parent
            recycle(candidate)
            current = parent
        }
        current?.let(::recycle)
        return null
    }

    fun isChoiceChecked(node: AccessibilityNodeInfo): Boolean =
        choiceCheckedState(node) == true

    fun isActionable(node: AccessibilityNodeInfo): Boolean =
        hasActionableSelfOrAncestor(node)

    fun isVisibleInViewport(
        root: AccessibilityNodeInfo,
        node: AccessibilityNodeInfo,
    ): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        val viewport = Rect().also(root::getBoundsInScreen)
        val bounds = Rect().also(node::getBoundsInScreen)
        if (viewport.isEmpty || bounds.isEmpty) return false
        return bounds.intersect(viewport) && bounds.width() > 0 && bounds.height() > 0
    }

    fun safeClick(node: AccessibilityNodeInfo, intendedLabel: String): Boolean {
        val clickTarget = findClickTargetCopy(node) ?: return false
        return try {
            val semanticLabel = listOf(
                intendedLabel,
                ownText(clickTarget),
                subtreeText(clickTarget, maxNodes = 30),
            )
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (!AutomationSafetyPolicy.isSafeClick(semanticLabel)) return false
            if (!clickTarget.isEnabled || !clickTarget.isVisibleToUser) return false
            clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            recycle(clickTarget)
        }
    }

    fun scroll(root: AccessibilityNodeInfo, forward: Boolean): Boolean =
        withFlattened(root) { nodes ->
            val viewport = Rect().also(root::getBoundsInScreen)
            if (viewport.isEmpty) return@withFlattened false

            val genericAction = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            val verticalAction = if (forward) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            } else {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            }
            val horizontalActions = setOf(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
            )
            val minimumVerticalSpan = (viewport.height() / MIN_VERTICAL_SCROLL_SPAN_DIVISOR)
                .coerceAtLeast(1)

            nodes.asSequence()
                .mapNotNull { node ->
                    if (!node.isVisibleToUser || !node.isEnabled || !node.isScrollable) {
                        return@mapNotNull null
                    }

                    val actionIds = node.actionList.asSequence().map { it.id }.toSet()
                    val hasVerticalAction = verticalAction in actionIds
                    val hasGenericAction = genericAction in actionIds
                    if (!hasVerticalAction && !hasGenericAction) return@mapNotNull null

                    // Generic forward/backward is axis-ambiguous. A node that
                    // explicitly advertises horizontal scrolling is not a safe
                    // page-scroll target unless it also exposes this vertical action.
                    if (!hasVerticalAction && actionIds.any(horizontalActions::contains)) {
                        return@mapNotNull null
                    }

                    val visibleBounds = Rect().also(node::getBoundsInScreen)
                    if (!visibleBounds.intersect(viewport) ||
                        visibleBounds.height() < minimumVerticalSpan
                    ) {
                        return@mapNotNull null
                    }

                    VerticalScrollCandidate(
                        node = node,
                        action = if (hasVerticalAction) verticalAction else genericAction,
                        hasVerticalAction = hasVerticalAction,
                        visibleHeight = visibleBounds.height(),
                        visibleWidth = visibleBounds.width(),
                    )
                }
                // The page body normally has the greatest vertical viewport.
                // This keeps short horizontal tab strips out even when Chromium
                // exposes them as generic forward/backward scrollables.
                .maxWithOrNull(
                    compareBy<VerticalScrollCandidate> { it.visibleHeight }
                        .thenBy { it.hasVerticalAction }
                        .thenBy { it.visibleWidth },
                )
                ?.let { candidate -> candidate.node.performAction(candidate.action) }
                ?: false
        }

    fun showControlOnScreen(root: AccessibilityNodeInfo, target: SemanticTarget): Boolean =
        withFlattened(root) { nodes ->
            val byId = nodes.firstOrNull { node ->
                if (!node.isEnabled) return@firstOrNull false
                val id = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
                matchesHtmlId(id, target.htmlId)
            }
            val candidates = nodes.filter { it.isEnabled && matchesKind(it, target.kind) }
            val byHint = candidates.firstOrNull { node ->
                target.hints.any { hint -> ownText(node).containsNormalized(hint) }
            }
            val label = nodes.firstOrNull { node ->
                normalize(ownText(node)) == normalize(target.label)
            }
            (byId ?: byHint ?: label)
                ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                ?: false
        }

    fun showTextOnScreen(root: AccessibilityNodeInfo, text: String, exact: Boolean): Boolean =
        withFlattened(root) { nodes ->
            val wanted = normalize(text)
            nodes.firstOrNull { node ->
                val actual = normalize(ownText(node))
                if (exact) actual == wanted else actual.contains(wanted)
            }?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) ?: false
        }

    @Suppress("DEPRECATION")
    fun recycle(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }

    private fun collectText(root: AccessibilityNodeInfo, visibleOnly: Boolean): String =
        withFlattened(root) { nodes ->
            nodes.asSequence()
                .filter { !visibleOnly || it.isVisibleToUser }
                .map(::ownText)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")
        }

    private fun findClickTargetCopy(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findCheckableCopy(node)?.let { checkable ->
            if (checkable.isClickable) return checkable
            recycle(checkable)
        }

        var current: AccessibilityNodeInfo? = copyNode(node)
        repeat(MAX_RELATION_DEPTH) {
            val candidate = current ?: return null
            if (candidate.isClickable) return candidate
            val parent = candidate.parent
            recycle(candidate)
            current = parent
        }
        current?.let(::recycle)

        return withFlattened(node) { descendants ->
            descendants.firstOrNull { it !== node && it.isClickable }?.let(::copyNode)
        }
    }

    private fun findCheckableCopy(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return copyNode(node)
        withFlattened(node) { descendants ->
            descendants.firstOrNull { it !== node && it.isCheckable }?.let(::copyNode)
        }?.let { return it }

        var current = node.parent
        repeat(MAX_RELATION_DEPTH) {
            val candidate = current ?: return null
            if (candidate.isCheckable) return copyNode(candidate).also { recycle(candidate) }
            val parent = candidate.parent
            recycle(candidate)
            current = parent
        }
        current?.let(::recycle)
        return null
    }

    private fun hasActionableSelfOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable || node.isCheckable) return true
        var current = node.parent
        repeat(MAX_RELATION_DEPTH) {
            val candidate = current ?: return false
            val actionable = candidate.isClickable || candidate.isCheckable
            val parent = if (actionable) null else candidate.parent
            recycle(candidate)
            if (actionable) return true
            current = parent
        }
        current?.let(::recycle)
        return false
    }

    private fun subtreeText(root: AccessibilityNodeInfo, maxNodes: Int): String =
        withFlattened(root, maxNodes = maxNodes) { nodes ->
            nodes.asSequence()
                .map(::ownText)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")
        }

    private inline fun <T> withFlattened(
        root: AccessibilityNodeInfo,
        maxNodes: Int = MAX_NODES,
        block: (List<AccessibilityNodeInfo>) -> T,
    ): T {
        val nodes = flatten(root, maxNodes)
        return try {
            block(nodes)
        } finally {
            nodes.asSequence().filter { it !== root }.forEach(::recycle)
        }
    }

    private fun flatten(
        root: AccessibilityNodeInfo,
        maxNodes: Int,
    ): List<AccessibilityNodeInfo> {
        data class Pending(val node: AccessibilityNodeInfo, val depth: Int)

        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pending>()
        queue.add(Pending(root, 0))
        while (queue.isNotEmpty() && result.size < maxNodes) {
            val (node, depth) = queue.removeFirst()
            result += node
            if (depth >= MAX_DEPTH) continue
            for (index in 0 until node.childCount) {
                if (result.size + queue.size >= maxNodes) break
                node.getChild(index)?.let { queue.add(Pending(it, depth + 1)) }
            }
        }
        while (queue.isNotEmpty()) recycle(queue.removeFirst().node)
        return result
    }

    @Suppress("DEPRECATION")
    private fun copyNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain(node)

    private fun matchesHtmlId(resourceId: String, htmlId: String): Boolean {
        if (resourceId.isBlank()) return false
        return resourceId == htmlId ||
            resourceId.endsWith("/$htmlId") ||
            resourceId.endsWith(":id/$htmlId")
    }

    private fun matchesKind(node: AccessibilityNodeInfo, kind: ControlKind): Boolean {
        val className = node.className?.toString().orEmpty()
        return when (kind) {
            ControlKind.EDITABLE -> node.isEditable || className.endsWith("EditText")
            ControlKind.SELECT -> className.endsWith("Spinner") ||
                className.contains("Select", ignoreCase = true) ||
                node.isClickable && !node.isEditable && ownText(node).startsWith("请选择")
        }
    }

    private fun matchesChoiceText(
        node: AccessibilityNodeInfo,
        wanted: String,
        exact: Boolean,
    ): Boolean {
        val actual = normalize(ownText(node))
        return if (exact) actual == wanted else actual.contains(wanted)
    }

    private fun distinctNodes(nodes: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> =
        nodes.distinctBy(::nodeIdentity)

    private fun nodeIdentity(node: AccessibilityNodeInfo): NodeIdentity = NodeIdentity(
        windowId = node.windowId,
        sourceHash = node.hashCode(),
        viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault(""),
        className = node.className?.toString().orEmpty(),
        bounds = Rect().also(node::getBoundsInScreen),
        text = normalize(ownText(node)),
    )

    private fun localRelationDepth(
        first: AccessibilityNodeInfo,
        second: AccessibilityNodeInfo,
    ): Int? {
        val firstAncestors = ancestorCopies(first)
        val secondAncestors = ancestorCopies(second)
        return try {
            var best: Int? = null
            firstAncestors.forEachIndexed { firstDepth, firstNode ->
                secondAncestors.forEachIndexed { secondDepth, secondNode ->
                    if (firstNode == secondNode) {
                        val score = firstDepth + secondDepth
                        if (best == null || score < best!!) best = score
                    }
                }
            }
            best
        } finally {
            firstAncestors.forEach(::recycle)
            secondAncestors.forEach(::recycle)
        }
    }

    private fun ancestorCopies(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val ancestors = ArrayList<AccessibilityNodeInfo>()
        var current: AccessibilityNodeInfo? = copyNode(node)
        var depth = 0
        while (current != null && depth <= MAX_CHOICE_ANCESTOR_DEPTH) {
            val candidate = current
            ancestors += candidate
            current = candidate.parent
            depth += 1
        }
        current?.let(::recycle)
        return ancestors
    }

    private fun areTightlyAdjacent(first: Rect, second: Rect): Boolean {
        if (first.isEmpty || second.isEmpty) return false
        val horizontalGap = maxOf(
            0,
            maxOf(first.left, second.left) - minOf(first.right, second.right),
        )
        val verticalOverlap = minOf(first.bottom, second.bottom) > maxOf(first.top, second.top)
        val localGapLimit = (
            maxOf(first.height(), second.height()).coerceAtLeast(24) * 2
        ).coerceAtMost(180)
        return verticalOverlap && horizontalGap <= localGapLimit
    }

    private fun ownText(node: AccessibilityNodeInfo): String =
        listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.hintText?.toString(),
        ).filter { it.isNotBlank() }.distinct().joinToString(" ")

    private fun geometricDistance(first: Rect, second: Rect): Int {
        val firstX = (first.left + first.right) / 2
        val firstY = (first.top + first.bottom) / 2
        val secondX = (second.left + second.right) / 2
        val secondY = (second.top + second.bottom) / 2
        return abs(firstX - secondX) + abs(firstY - secondY)
    }

    private fun labelDistanceLimit(labelBounds: Rect): Int =
        (labelBounds.height().coerceAtLeast(24) * 12).coerceIn(240, 720)

    private fun String.containsNormalized(other: String): Boolean =
        normalize(this).contains(normalize(other))

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private data class NodeIdentity(
        val windowId: Int,
        val sourceHash: Int,
        val viewId: String,
        val className: String,
        val bounds: Rect,
        val text: String,
    )

    private data class VerticalScrollCandidate(
        val node: AccessibilityNodeInfo,
        val action: Int,
        val hasVerticalAction: Boolean,
        val visibleHeight: Int,
        val visibleWidth: Int,
    )

    private const val MIN_VERTICAL_SCROLL_SPAN_DIVISOR = 3
}
