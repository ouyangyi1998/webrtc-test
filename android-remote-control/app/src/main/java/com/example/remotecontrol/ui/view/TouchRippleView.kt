package com.example.remotecontrol.ui.view

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 触控波纹反馈 View
 * 用于在屏幕上显示点击特效
 */
class TouchRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#4000FFFF") // 浅蓝色半透明
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#8000FFFF") // 浅蓝色半透明填充
    }

    private data class Ripple(
        var x: Float,
        var y: Float,
        var radius: Float,
        var alpha: Int
    )

    private val ripples = mutableListOf<Ripple>()

    override fun onDraw(canvas: Canvas) { // Use default non-nullable Canvas
        super.onDraw(canvas)

        val iterator = ripples.iterator()
        while (iterator.hasNext()) {
            val ripple = iterator.next()
            
            // 绘制中心点
            fillPaint.alpha = ripple.alpha
            canvas.drawCircle(ripple.x, ripple.y, 20f, fillPaint)

            // 绘制波纹
            paint.alpha = ripple.alpha
            paint.strokeWidth = 5f * (ripple.alpha / 255f)
            canvas.drawCircle(ripple.x, ripple.y, ripple.radius, paint)
        }
    }

    /**
     * 在指定位置显示波纹动画
     */
    fun showRipple(x: Float, y: Float) {
        val ripple = Ripple(x, y, 0f, 255)
        ripples.add(ripple)
        invalidate()

        val animator = ValueAnimator.ofFloat(0f, 100f)
        animator.duration = 400
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            ripple.radius = value
            ripple.alpha = (255 * (1 - value / 100)).toInt()
            invalidate()
        }
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                ripples.remove(ripple)
                invalidate()
            }
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
        })
        animator.start()
    }
}
