/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.test.utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.UIKitInstrumentedTest
import androidx.compose.ui.test.allSemanticsNodes
import androidx.compose.ui.test.findFocusedUITextInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRect
import platform.UIKit.NSWritingDirectionRightToLeft
import platform.UIKit.UITextSelectionRect
import platform.UIKit.UIView

/**
 * Mirror's foundation's `Handle`.
 * Reflects logical position of a handle.
 * Note: Cursor is omitted because iOS renders no "teardrop" handle.
 */
internal enum class TestHandle { SelectionStart, SelectionEnd }

/**
 * Mirrors foundation's internal `SelectionHandleAnchor` — how a handle is anchored to its position,
 * which on iOS also dictates how it is drawn (a vertical stem with a round "blob"):
 *  - Left:  leading handle — blob at the TOP of the line, stem hanging down. In a normal LTR
 *           selection this is the start handle.
 *  - Right: trailing handle — blob at the BOTTOM of the line, stem going up. In a normal LTR
 *           selection this is the end handle.
 *
 * Reflects the visual orientation of a handle.
 * RTL text or crossed handles can swap which side start/end map to.
 *
 * Note: Middle is omitted — it is only used by the cursor handle, which iOS does not render.
 */
internal enum class TestSelectionHandleAnchor { Left, Right }

/**
 * The contents of a selection handle, either parsed from foundation's internal `SelectionHandleInfo`
 * (Compose-rendered handles) or derived from the focused native `UITextInput` selection geometry.
 *
 * @property position The point the handle is anchored to. For Compose handles it is read verbatim
 *   from foundation (relative to the selectable content; may be [Offset.Unspecified]); for native
 *   handles it is the selection edge in window-space points.
 */
internal data class TestSelectionHandleInfo(
    val handle: TestHandle,
    val position: Offset,
    val anchor: TestSelectionHandleAnchor,
    val visible: Boolean,
)

/**
 * A selection handle located for a test.
 *
 * @property grabPoint The window-space point (Dp) to aim a touch at to grab this handle: the center
 *   of the popup for Compose handles, or the "blob" of the lollipop for native ones.
 * @property node The semantics node backing a Compose-rendered handle popup, or null for a native
 *   iOS handle (which has no Compose semantics).
 */
internal class TestSelectionHandle(
    val info: TestSelectionHandleInfo,
    val grabPoint: DpOffset,
    val node: SemanticsNode? = null,
)

/** The two selection handles present while text is selected. */
internal class SelectionHandlePair(
    val start: TestSelectionHandle,
    val end: TestSelectionHandle,
)

private const val SelectionHandleInfoKeyName = "SelectionHandleInfo" // `SelectionHandleInfoKey` semantics property

/**
 * The vertical distance (in points) from a native selection edge to the center of the lollipop
 * "blob" that a touch must land on to grab the handle. Tuned empirically against the native drag
 * tests.
 */
private const val NativeHandleKnobInset = 8.0

/**
 * How long UIKit keeps a finished tap open for one more tap of the same sequence. Touches sent
 * within this window continue the sequence instead of starting a new gesture.
 * 500ms should be enough
 *
 * TODO: CMP-10641
 */
private const val NativeTapSequenceWindowMillis = 500L

private val offsetRegex = Regex("""Offset\((-?[\d.]+),\s*(-?[\d.]+)\)""")

/**
 * Parses the `toString()` of foundation's internal `SelectionHandleInfo` data class, e.g.
 * `SelectionHandleInfo(handle=SelectionStart, position=Offset(12.3, 45.6), anchor=Left, visible=true)`.
 */
internal fun parseSelectionHandleInfo(raw: String): TestSelectionHandleInfo {
    fun field(name: String): String =
        Regex("""$name=(\w+)""").find(raw)?.groupValues?.get(1)
            ?: error("Could not parse '$name' from SelectionHandleInfo: $raw")

    val position = offsetRegex.find(raw)?.let { m ->
        Offset(m.groupValues[1].toFloat(), m.groupValues[2].toFloat())
    } ?: Offset.Unspecified

    return TestSelectionHandleInfo(
        handle = TestHandle.valueOf(field("handle")),
        position = position,
        anchor = TestSelectionHandleAnchor.valueOf(field("anchor")),
        visible = field("visible").toBooleanStrict(),
    )
}

/** The start and end selection handles if both are present; null otherwise. */
internal fun UIKitInstrumentedTest.selectionHandles(): SelectionHandlePair? =
    composeSelectionHandles() ?: nativeSelectionHandles()

/** Selection handles rendered by Compose as popups, read from their semantics. */
private fun UIKitInstrumentedTest.composeSelectionHandles(): SelectionHandlePair? {
    waitForIdle()
    val handles = allSemanticsNodes().mapNotNull { node ->
        val raw = node.config.firstOrNull { it.key.name == SelectionHandleInfoKeyName }?.value
            ?: return@mapNotNull null
        val info = parseSelectionHandleInfo(raw.toString())
        TestSelectionHandle(info = info, grabPoint = composeGrabPoint(node), node = node)
    }
    val start = handles.singleOrNull { it.info.handle == TestHandle.SelectionStart } ?: return null
    val end = handles.singleOrNull { it.info.handle == TestHandle.SelectionEnd } ?: return null
    return SelectionHandlePair(start, end)
}

/** The center of a Compose handle popup, in window-space Dp. */
private fun UIKitInstrumentedTest.composeGrabPoint(node: SemanticsNode): DpOffset {
    val bounds = node.boundsInWindow
    return with(density) { DpOffset(bounds.center.x.toDp(), bounds.center.y.toDp()) }
}

/**
 * Selection handles drawn by UIKit for a native text field. UIKit keeps the handle views private,
 * so a handle is derived from the edge of the focused `UITextInput`'s selection highlight rects.
 *
 * TODO: the whole derivation is guesswork — find the `_UITextSelectionLollipopView`s in the view
 *  tree and take the handle geometry from them instead of from the highlight rects.
 */
@OptIn(ExperimentalForeignApi::class)
private fun UIKitInstrumentedTest.nativeSelectionHandles(): SelectionHandlePair? {
    waitForIdle()
    val input = findFocusedUITextInput() ?: return null
    val view = input as? UIView ?: return null
    val range = input.selectedTextRange() ?: return null
    val rects = input.selectionRectsForRange(range).filterIsInstance<UITextSelectionRect>()
    // Ensure this is a ranged selection and not a caret
    if (rects.none { it.rect().useContents { size.width > 0.0 } }) return null
    val startRect = rects.firstOrNull { it.containsStart() } ?: return null
    val endRect = rects.firstOrNull { it.containsEnd() } ?: return null

    val start = nativeHandle(
        handle = TestHandle.SelectionStart,
        windowRect = view.convertRect(startRect.rect(), toView = null),
        rtl = startRect.writingDirection() == NSWritingDirectionRightToLeft,
    ) ?: return null
    val end = nativeHandle(
        handle = TestHandle.SelectionEnd,
        windowRect = view.convertRect(endRect.rect(), toView = null),
        rtl = endRect.writingDirection() == NSWritingDirectionRightToLeft,
    ) ?: return null
    return SelectionHandlePair(start, end)
}

/**
 * Builds a native [TestSelectionHandle] from a selection rect in window-space points. The start
 * handle (leading) sits at the top of the leading edge, the end handle (trailing) at the bottom of
 * the trailing edge; [rtl] flips which screen side is leading. Returns null for a degenerate
 * (collapsed) rect.
 */
@OptIn(ExperimentalForeignApi::class)
private fun nativeHandle(
    handle: TestHandle,
    windowRect: CValue<CGRect>,
    rtl: Boolean,
): TestSelectionHandle? = windowRect.useContents {
    if (size.width <= 0.0 && size.height <= 0.0) return@useContents null
    val minX = origin.x
    val minY = origin.y
    val maxX = origin.x + size.width
    val maxY = origin.y + size.height

    val (edgeX, edgeY, knobY, anchor) = when (handle) {
        TestHandle.SelectionStart ->
            NativeHandleGeometry(
                edgeX = if (rtl) maxX else minX,
                edgeY = minY,
                knobY = minY - NativeHandleKnobInset,
                anchor = TestSelectionHandleAnchor.Left,
            )
        TestHandle.SelectionEnd ->
            NativeHandleGeometry(
                edgeX = if (rtl) minX else maxX,
                edgeY = maxY,
                knobY = maxY + NativeHandleKnobInset,
                anchor = TestSelectionHandleAnchor.Right,
            )
    }

    TestSelectionHandle(
        info = TestSelectionHandleInfo(
            handle = handle,
            position = Offset(edgeX.toFloat(), edgeY.toFloat()),
            anchor = anchor,
            visible = true,
        ),
        grabPoint = DpOffset(edgeX.dp, knobY.dp),
    )
}

private data class NativeHandleGeometry(
    val edgeX: Double,
    val edgeY: Double,
    val knobY: Double,
    val anchor: TestSelectionHandleAnchor,
)

/**
 * Drags the [handle] selection handle to character [toOffset] of the text field tagged [tag].
 *
 * The handle is grabbed at its [grabPoint][TestSelectionHandle.grabPoint] (the popup center for a
 * Compose handle, or the lollipop blob for a native one) and dragged to the caret position for
 * [toOffset] (see [characterPosition]).
 *
 * The selection edge may not land exactly on [toOffset]: a dragged handle expands by word and
 * shrinks by character, so expanding a word selection can overshoot to the word boundary. UIKit
 * behaves the same way; for the Compose rules see `SelectionAdjustment.CharacterWithWordAccelerate`.
 */
internal fun UIKitInstrumentedTest.dragSelectionHandle(
    handle: TestHandle,
    tag: String,
    toOffset: Int,
    duration: Duration = 0.5.seconds,
) {
    val isNative = composeSelectionHandles() == null
    if (isNative) delay(NativeTapSequenceWindowMillis)

    val from = selectionHandle(handle).grabPoint
    val to = characterPosition(tag, toOffset)
    touchDown(from).dragTo(x = to.x, y = to.y, duration = duration).up()
    waitForIdle()

    if (isNative) {
        waitUntil("Selection loupe should hide after the drag") {
            findFirstDescendant { it.isLoupeView } == null
        }
    }
}

private fun UIKitInstrumentedTest.selectionHandle(handle: TestHandle): TestSelectionHandle =
    when (handle) {
        TestHandle.SelectionStart -> selectionHandles()?.start
        TestHandle.SelectionEnd -> selectionHandles()?.end
    } ?: error("No $handle selection handle present.")
