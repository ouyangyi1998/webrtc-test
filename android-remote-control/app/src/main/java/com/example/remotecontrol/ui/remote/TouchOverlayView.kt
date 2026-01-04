package com.example.remotecontrol.ui.remote

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 触摸覆盖层
 * 处理用户触摸并转换为控制消息
 */
class TouchOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "TouchOverlayView"
        private const val LONG_PRESS_TIMEOUT = 500L
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val SCROLL_THRESHOLD = 10f
        private const val WHEEL_SENSITIVITY = 3f
    }

    interface Listener {
        fun onMouseMove(xRatio: Float, yRatio: Float)
        fun onMouseDown(xRatio: Float, yRatio: Float, button: Int)
        fun onMouseUp(xRatio: Float, yRatio: Float, button: Int)
        fun onMouseDoubleClick(xRatio: Float, yRatio: Float, button: Int)
        fun onMouseWheel(xRatio: Float, yRatio: Float, deltaY: Float)
        fun onToolbarToggle()  // 三指轻触切换工具栏
    }

    var listener: Listener? = null

    // 视频显示区域（用于坐标转换）
    private var videoWidth: Float = 1920f
    private var videoHeight: Float = 1080f
    private var videoOffsetX: Float = 0f
    private var videoOffsetY: Float = 0f
    private var videoDisplayWidth: Float = 0f
    private var videoDisplayHeight: Float = 0f

    // 触摸状态
    private var isTouching = false
    private var isLongPress = false
    private var isTwoFingerMode = false
    private var isThreeFingerTap = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastTwoFingerY = 0f

    // 手势检测器
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val ratio = calculateRatio(e.x, e.y)
            if (ratio != null) {
                Log.d(TAG, "Single tap at (${ratio.first}, ${ratio.second})")
                listener?.onMouseDown(ratio.first, ratio.second, 0)
                listener?.onMouseUp(ratio.first, ratio.second, 0)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val ratio = calculateRatio(e.x, e.y)
            if (ratio != null) {
                Log.d(TAG, "Double tap at (${ratio.first}, ${ratio.second})")
                listener?.onMouseDoubleClick(ratio.first, ratio.second, 0)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            isLongPress = true
            val ratio = calculateRatio(e.x, e.y)
            if (ratio != null) {
                Log.d(TAG, "Long press (right click) at (${ratio.first}, ${ratio.second})")
                // 右键点击
                listener?.onMouseDown(ratio.first, ratio.second, 2)
                listener?.onMouseUp(ratio.first, ratio.second, 2)
            }
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (isTwoFingerMode) {
                return false  // 双指滚动在 onTouchEvent 中处理
            }

            // 单指滑动 = 鼠标移动
            val ratio = calculateRatio(e2.x, e2.y)
            if (ratio != null) {
                listener?.onMouseMove(ratio.first, ratio.second)
            }
            return true
        }
    })

    init {
        // 允许长按
        gestureDetector.setIsLongpressEnabled(true)
    }

    /**
     * 设置视频分辨率
     */
    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width.toFloat()
        videoHeight = height.toFloat()
        updateVideoDisplayArea()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateVideoDisplayArea()
    }

    private fun updateVideoDisplayArea() {
        if (width == 0 || height == 0 || videoWidth == 0f || videoHeight == 0f) {
            return
        }

        val viewAspect = width.toFloat() / height
        val videoAspect = videoWidth / videoHeight

        if (videoAspect > viewAspect) {
            // 视频更宽，上下有黑边
            videoDisplayWidth = width.toFloat()
            videoDisplayHeight = videoDisplayWidth / videoAspect
            videoOffsetX = 0f
            videoOffsetY = (height - videoDisplayHeight) / 2
        } else {
            // 视频更高，左右有黑边
            videoDisplayHeight = height.toFloat()
            videoDisplayWidth = videoDisplayHeight * videoAspect
            videoOffsetX = (width - videoDisplayWidth) / 2
            videoOffsetY = 0f
        }

        Log.d(TAG, "Video display area: ${videoDisplayWidth}x${videoDisplayHeight} at ($videoOffsetX, $videoOffsetY)")
    }

    /**
     * 将触摸坐标转换为相对于视频的比例值 (0-1)
     */
    private fun calculateRatio(x: Float, y: Float): Pair<Float, Float>? {
        if (videoDisplayWidth == 0f || videoDisplayHeight == 0f) {
            return null
        }

        val relativeX = (x - videoOffsetX) / videoDisplayWidth
        val relativeY = (y - videoOffsetY) / videoDisplayHeight

        // 确保坐标在有效范围内
        if (relativeX < 0 || relativeX > 1 || relativeY < 0 || relativeY > 1) {
            return null  // 坐标在视频区域外
        }

        return Pair(relativeX, relativeY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount

        // 处理三指手势（切换工具栏）
        if (pointerCount >= 3) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !isThreeFingerTap) {
                isThreeFingerTap = true
                Log.d(TAG, "Three finger tap - toggle toolbar")
                listener?.onToolbarToggle()
            }
            return true
        }

        // 处理双指手势（滚轮）
        if (pointerCount >= 2) {
            isThreeFingerTap = false
            handleTwoFingerGesture(event)
            return true
        }

        // 如果刚从双指切换到单指，忽略这次事件
        if (isTwoFingerMode && event.action == MotionEvent.ACTION_MOVE) {
            return true
        }

        isTwoFingerMode = false
        isThreeFingerTap = false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                isLongPress = false
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isLongPress) {
                    val ratio = calculateRatio(event.x, event.y)
                    if (ratio != null) {
                        listener?.onMouseMove(ratio.first, ratio.second)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                isLongPress = false
            }
        }

        // 交给手势检测器处理点击和长按
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun handleTwoFingerGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                isTwoFingerMode = true
                // 计算两指中心点
                lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isTwoFingerMode) return

                val currentY = (event.getY(0) + event.getY(1)) / 2
                val deltaY = currentY - lastTwoFingerY

                if (abs(deltaY) > SCROLL_THRESHOLD) {
                    // 计算中心点作为鼠标位置
                    val centerX = (event.getX(0) + event.getX(1)) / 2
                    val centerY = (event.getY(0) + event.getY(1)) / 2
                    val ratio = calculateRatio(centerX, centerY)

                    if (ratio != null) {
                        // 滚轮方向：向下滑动 = 向下滚动（正值）
                        val wheelDelta = deltaY * WHEEL_SENSITIVITY
                        Log.d(TAG, "Mouse wheel: delta=$wheelDelta")
                        listener?.onMouseWheel(ratio.first, ratio.second, wheelDelta)
                    }

                    lastTwoFingerY = currentY
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 当一个手指抬起时，准备切换回单指模式
                isTwoFingerMode = false
            }
        }
    }
}
