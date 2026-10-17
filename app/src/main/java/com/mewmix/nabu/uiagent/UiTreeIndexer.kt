package com.mewmix.nabu.uiagent

import org.w3c.dom.Element
import java.io.StringReader
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

import com.mewmix.nabu.accessibility.UiSnapshot
import com.mewmix.nabu.accessibility.UiNode
import com.mewmix.nabu.accessibility.ObservedNodeIdentity

object UiTreeIndexer {
    private val boundsPattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun build(snapshot: UiSnapshot): UiScreenState {
        val indexed = mutableListOf<UiElement>()

        fun visit(node: UiNode, parentId: String?) {
            val bounds = UiBounds(
                node.boundsInScreen.left,
                node.boundsInScreen.top,
                node.boundsInScreen.right,
                node.boundsInScreen.bottom
            )
            val id = ObservedNodeIdentity.compute(
                node.packageName,
                node.resourceId.takeIf { it.isNotBlank() },
                node.text.takeIf { it.isNotBlank() },
                node.contentDescription.takeIf { it.isNotBlank() },
                node.className.takeIf { it.isNotBlank() },
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                node.treePath
            )
            indexed += UiElement(
                id = id,
                text = node.text.takeIf { it.isNotBlank() },
                contentDescription = node.contentDescription.takeIf { it.isNotBlank() },
                resourceId = node.resourceId.takeIf { it.isNotBlank() },
                className = node.className.takeIf { it.isNotBlank() },
                packageName = node.packageName.takeIf { it.isNotBlank() },
                bounds = bounds,
                clickable = node.isClickable,
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                editable = node.isEditable || node.className == "android.widget.EditText",
                scrollable = node.isScrollable,
                longClickable = node.isLongClickable,
                checkable = node.isCheckable,
                checked = node.isChecked,
                password = node.isPassword,
                parentId = parentId,
                treePath = node.treePath,
                hintText = node.hintText.takeIf(String::isNotBlank),
                focusable = node.isFocusable,
                focused = node.isFocused,
                selected = node.isSelected,
                standardActions = node.standardActions.mapTo(linkedSetOf()) { it.token },
                customActions = node.customActions.mapIndexed { index, action ->
                    UiCustomActionRef("ca$index", action.label, action.actionId)
                },
                movementGranularities = node.movementGranularities,
                range = node.rangeInfo?.let { UiRange(it.type, it.min, it.max, it.current) },
                accessibilityFocused = node.isAccessibilityFocused,
                contextClickable = node.isContextClickable,
                selectionStart = node.textSelectionStart,
                selectionEnd = node.textSelectionEnd,
                collection = node.collectionInfo?.let {
                    UiCollection(it.rowCount, it.columnCount, it.hierarchical, it.selectionMode)
                },
                collectionItem = node.collectionItemInfo?.let {
                    UiCollectionItem(
                        it.rowIndex,
                        it.rowSpan,
                        it.columnIndex,
                        it.columnSpan,
                        it.heading,
                        it.selected
                    )
                }
            )
            for (child in node.children) {
                visit(child, id)
            }
        }

        snapshot.rootNode?.let { visit(it, null) }

        val resolvedPackage = snapshot.packageName
        return UiScreenState(
            screenId = snapshot.stateFingerprint,
            packageName = resolvedPackage,
            activityName = null,
            elements = indexed,
            systemActions = snapshot.systemActions
        )
    }

    fun parse(
        xml: String,
        packageName: String? = null,
        activityName: String? = null
    ): UiScreenState {
        require(xml.isNotBlank()) { "UI hierarchy XML is blank." }
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed in UI hierarchy XML." }
        require(!xml.contains("<!ENTITY", ignoreCase = true)) { "Entities are not allowed in UI hierarchy XML." }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val indexed = mutableListOf<UiElement>()

        fun visit(element: Element, path: String, parentId: String?) {
            val nodePackage = element.attributeOrNull("package") ?: packageName
            val bounds = parseBounds(element.attributeOrNull("bounds"))
            val text = element.attributeOrNull("text")
            val contentDescription = element.attributeOrNull("content-desc")
                ?: element.attributeOrNull("contentDescription")
            val hintText = element.attributeOrNull("hint")
            val resourceId = element.attributeOrNull("resource-id")
                ?: element.attributeOrNull("view-id")
            val className = element.attributeOrNull("class")
            val id = ObservedNodeIdentity.compute(
                nodePackage,
                resourceId,
                text,
                contentDescription,
                className,
                bounds?.left,
                bounds?.top,
                bounds?.right,
                bounds?.bottom,
                path
            )
            indexed += UiElement(
                id = id,
                text = text,
                contentDescription = contentDescription,
                resourceId = resourceId,
                className = className,
                packageName = nodePackage,
                bounds = bounds,
                clickable = element.booleanAttribute("clickable"),
                enabled = element.booleanAttribute("enabled", default = true),
                visible = element.booleanAttribute("visible", default = true),
                editable = element.booleanAttribute("editable") ||
                    className == "android.widget.EditText",
                scrollable = element.booleanAttribute("scrollable"),
                longClickable = element.booleanAttribute("long-clickable"),
                checkable = element.booleanAttribute("checkable"),
                checked = element.booleanAttribute("checked"),
                password = element.booleanAttribute("password"),
                parentId = parentId,
                treePath = path,
                hintText = hintText,
                focusable = element.booleanAttribute("focusable"),
                focused = element.booleanAttribute("focused"),
                selected = element.booleanAttribute("selected")
            )

            var childIndex = 0
            val children = element.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child is Element && child.tagName == "node") {
                    visit(child, "$path/$childIndex", id)
                    childIndex++
                }
            }
        }

        val root = document.documentElement
        var rootIndex = 0
        val rootChildren = root.childNodes
        for (index in 0 until rootChildren.length) {
            val child = rootChildren.item(index)
            if (child is Element && child.tagName == "node") {
                visit(child, rootIndex.toString(), null)
                rootIndex++
            }
        }

        val resolvedPackage = packageName
            ?: indexed.firstNotNullOfOrNull { it.packageName?.takeIf(String::isNotBlank) }
        val screenId = stableHash(
            listOf(
                resolvedPackage.orEmpty(),
                activityName.orEmpty(),
                indexed.joinToString("|") {
                    "${it.id}:${it.enabled}:${it.visible}:${it.checked}:${it.text.orEmpty()}"
                }
            ).joinToString("\u001f")
        )
        return UiScreenState(screenId, resolvedPackage, activityName, indexed)
    }

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun parseBounds(raw: String?): UiBounds? {
        val match = raw?.let(boundsPattern::matchEntire) ?: return null
        val values = match.groupValues.drop(1).map(String::toInt)
        return UiBounds(values[0], values[1], values[2], values[3]).takeIf { it.isValid }
    }

    private fun Element.attributeOrNull(name: String): String? =
        getAttribute(name).trim().takeIf { it.isNotEmpty() }

    private fun Element.booleanAttribute(name: String, default: Boolean = false): Boolean =
        attributeOrNull(name)?.toBooleanStrictOrNull() ?: default

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }
}
