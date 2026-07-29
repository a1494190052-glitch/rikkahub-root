package me.rerere.rikkahub.ui.components.webview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import kotlin.math.abs

/**
 * WebView with Android nested scrolling support (NestedScrollingChild3).
 *
 * Uses the standard AndroidX [NestedScrollingChildHelper] — it only coordinates
 * the nested scroll *protocol* (start/stop/dispatch) and does NOT scroll the
 * WebView itself. The actual scrolling is done by [WebView.onTouchEvent] via
 * [super.onTouchEvent]. This avoids the double-scroll bug that occurred when
 * a custom NestedScrollView helper also called scrollTo().
 *
 * Flow when the user drags inside the preview:
 *  1. ACTION_DOWN → startNestedScroll (registers with AndroidView's ViewFactoryHolder,
 *     which bridges to Compose's nested scroll chain → LazyColumn)
 *  2. ACTION_MOVE → super.onTouchEvent scrolls the WebView; dispatchNestedScroll
 *     forwards the *unconsumed* delta (overflow when WebView is at boundary) to
 *     the parent LazyColumn
 *  3. ACTION_UP/CANCEL → stopNestedScroll
 *
 * This works with Compose BOM 2026.06+ where AndroidView automatically bridges
 * NestedScrollingChild3 ↔ Compose NestedScrollConnection.
 */
class NestedScrollWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr), NestedScrollingChild3 {

    private val childHelper = NestedScrollingChildHelper(this)
    private val scrollConsumed = IntArray(2)
    private val scrollOffset = IntArray(2)
    private var lastY = 0
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null

    init {
        overScrollMode = OVER_SCROLL_NEVER
        isNestedScrollingEnabled = true
    }

    private fun ensureVelocityTracker(): VelocityTracker {
        var tracker = velocityTracker
        if (tracker == null) {
            tracker = VelocityTracker.obtain()
            velocityTracker = tracker
        }
        return tracker
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        ensureVelocityTracker().addMovement(event)
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.rawY.toInt()
                isDragging = false
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
                return super.onTouchEvent(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val y = event.rawY.toInt()

                if (!isDragging) {
                    val slopDelta = lastY - y
                    if (abs(slopDelta) > touchSlop) {
                        isDragging = true
                        // Reset so subsequent deltas start from here (no touch-slop leak)
                        lastY = y
                        // Prevent LazyColumn from stealing the touch stream
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    // Before drag threshold, let WebView handle normally (links, taps…)
                    return super.onTouchEvent(event)
                }

                val deltaY = lastY - y
                lastY = y

                // Let WebView scroll natively — it has priority
                val oldScrollY = scrollY
                val result = super.onTouchEvent(event)
                val scrolledByMe = scrollY - oldScrollY

                // Report consumed/unconsumed to parent for nested scroll coordination.
                // unconsumed > 0 means the WebView hit its boundary → parent takes over.
                val unconsumed = (deltaY - scrolledByMe).coerceAtLeast(0)
                scrollConsumed[1] = 0
                dispatchNestedScroll(
                    0, scrolledByMe,
                    0, unconsumed,
                    scrollOffset,
                    ViewCompat.TYPE_TOUCH,
                    scrollConsumed
                )

                return result
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    val tracker = velocityTracker
                    if (tracker != null) {
                        tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                        val velocityY = tracker.getYVelocity().toInt()
                        if (abs(velocityY) >= minFlingVelocity) {
                            // Notify parent a fling is about to happen
                            dispatchNestedPreFling(0f, velocityY.toFloat())
                        }
                    }
                }
                isDragging = false
                recycleVelocityTracker()
                stopNestedScroll(ViewCompat.TYPE_TOUCH)
                return super.onTouchEvent(event)
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                recycleVelocityTracker()
                stopNestedScroll(ViewCompat.TYPE_TOUCH)
                return super.onTouchEvent(event)
            }
        }

        return super.onTouchEvent(event)
    }

    // ── NestedScrollingChild3 ──────────────────────────────────────────

    override fun startNestedScroll(axes: Int, type: Int): Boolean =
        childHelper.startNestedScroll(axes, type)

    override fun stopNestedScroll(type: Int) =
        childHelper.stopNestedScroll(type)

    override fun hasNestedScrollingParent(type: Int): Boolean =
        childHelper.hasNestedScrollingParent(type)

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int, consumed: IntArray
    ) = childHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
        offsetInWindow, type, consumed
    )

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int,
        consumed: IntArray?, offsetInWindow: IntArray?, type: Int
    ): Boolean = childHelper.dispatchNestedPreScroll(
        dx, dy, consumed, offsetInWindow, type
    )

    // ── NestedScrollingChild2 ──────────────────────────────────────────

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int
    ): Boolean = childHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
        offsetInWindow, type
    )

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int,
        consumed: IntArray?, offsetInWindow: IntArray?
    ): Boolean = childHelper.dispatchNestedPreScroll(
        dx, dy, consumed, offsetInWindow, ViewCompat.TYPE_TOUCH
    )

    // ── NestedScrollingChild (legacy) ──────────────────────────────────

    override fun setNestedScrollingEnabled(enabled: Boolean) =
        childHelper.setNestedScrollingEnabled(enabled)

    override fun isNestedScrollingEnabled(): Boolean =
        childHelper.isNestedScrollingEnabled

    override fun startNestedScroll(axes: Int): Boolean =
        childHelper.startNestedScroll(axes, ViewCompat.TYPE_TOUCH)

    override fun stopNestedScroll() =
        childHelper.stopNestedScroll(ViewCompat.TYPE_TOUCH)

    override fun hasNestedScrollingParent(): Boolean =
        childHelper.hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ): Boolean = childHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
        offsetInWindow, ViewCompat.TYPE_TOUCH
    )

    override fun dispatchNestedFling(
        velocityX: Float, velocityY: Float, consumed: Boolean
    ): Boolean = childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(
        velocityX: Float, velocityY: Float
    ): Boolean = childHelper.dispatchNestedPreFling(velocityX, velocityY)
}
