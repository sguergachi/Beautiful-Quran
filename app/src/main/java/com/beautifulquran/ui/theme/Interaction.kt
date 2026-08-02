package com.beautifulquran.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize

/**
 * The app-wide tap: no ripple, no Material ink — content answers with motion
 * instead, preserving the paper feel (docs/DESIGN.md). Every tappable element
 * on a sheet should use this rather than a raw [clickable].
 *
 * Pass [onLongClick] to handle press-and-hold; the long-press carries the
 * same quiet treatment (no ink ripple).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.quietClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier =
    if (onLongClick == null) {
        clickable(
            interactionSource = null,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
    } else {
        combinedClickable(
            interactionSource = null,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }

/**
 * Consumes every touch that lands on this element, so content beneath never
 * reacts — used by full-sheet overlays and dismiss scrims. [onFirstDown]
 * fires once per gesture as it begins (e.g. to request a dismissal); it is
 * captured when the element enters composition, so it should only mutate
 * stable state objects, not close over per-recomposition values.
 */
fun Modifier.absorbPointerEvents(onFirstDown: (() -> Unit)? = null): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onFirstDown?.invoke()
            down.consume()
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

/** Consumes a gesture only when its first contact satisfies [shouldAbsorb]. */
fun Modifier.absorbPointerEventsWhere(
    shouldAbsorb: (Offset) -> Boolean,
): Modifier = pointerInput(shouldAbsorb) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val absorb = shouldAbsorb(down.position)
        if (absorb) down.consume()
        do {
            val event = awaitPointerEvent()
            if (absorb) event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * A quiet action that owns its touch before shared siblings can consume it.
 * Contextual surfaces use this for controls drawn over otherwise live paper.
 */
fun Modifier.ownedQuietClickable(
    role: Role? = null,
    onClick: () -> Unit,
): Modifier =
    semantics {
        role?.let { this.role = it }
        onClick {
            onClick()
            true
        }
    }.pointerInput(onClick) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            down.consume()
            var inside = true
            var released = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                inside = inside &&
                    change.position.x in 0f..size.width.toFloat() &&
                    change.position.y in 0f..size.height.toFloat()
                change.consume()
                released = !change.pressed
            } while (change.pressed)
            if (released && inside) onClick()
        }
    }

/** Keeps lower paper siblings on the hit path beneath a contextual surface. */
fun Modifier.sharePointerInputWithSiblings(): Modifier = this then SharedPointerElement

private data object SharedPointerElement : ModifierNodeElement<SharedPointerNode>() {
    override fun create() = SharedPointerNode()
    override fun update(node: SharedPointerNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "sharePointerInputWithSiblings"
    }
}

private class SharedPointerNode : Modifier.Node(), PointerInputModifierNode {
    override fun sharePointerInputWithSiblings() = true
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
        Unit

    override fun onCancelPointerInput() = Unit
}
