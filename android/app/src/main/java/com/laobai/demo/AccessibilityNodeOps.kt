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
                text.contains("紧急联系人信息") && text.contains("与本人关系") ||
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
        val candidates = nodes.filter { it.isVisibleToUser && it.isEnabled && matchesKind(it, target.kind) }

        candidates.firstOrNull { node ->
            val id = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
            matchesHtmlId(id, target.htmlId)
        }?.let(::copyNode)?.let { return@withFlattened it }

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
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            nodes.asReversed()
                .firstOrNull { it.isVisibleToUser && it.isScrollable }
                ?.performAction(action)
                ?: false
        }

    fun showControlOnScreen(root: AccessibilityNodeInfo, target: SemanticTarget): Boolean =
        withFlattened(root) { nodes ->
            val candidates = nodes.filter { it.isEnabled && matchesKind(it, target.kind) }
            val byId = candidates.firstOrNull { node ->
                val id = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
                matchesHtmlId(id, target.htmlId)
            }
            val byHint = candidates.firstOrNull { node ->
                target.hints.any { hint -> ownText(node).containsNormalized(hint) }
            }
            val label = nodes.firstOrNull { node ->
                normalize(ownText(node)) == normalize(target.label)
            }
            (byId ?: byHint ?: label)
                ?.performAction(AccessibilityNodeInfo.ACTION_SHOW_ON_SCREEN)
                ?: false
        }

    fun showTextOnScreen(root: AccessibilityNodeInfo, text: String, exact: Boolean): Boolean =
        withFlattened(root) { nodes ->
            val wanted = normalize(text)
            nodes.firstOrNull { node ->
                val actual = normalize(ownText(node))
                if (exact) actual == wanted else actual.contains(wanted)
            }?.performAction(AccessibilityNodeInfo.ACTION_SHOW_ON_SCREEN) ?: false
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
}
