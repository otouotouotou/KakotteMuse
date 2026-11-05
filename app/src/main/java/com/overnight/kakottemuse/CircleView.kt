package com.overnight.kakottemuse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 撮影した画面を全画面に表示し、その上を指でなぞって囲める View。
 *
 * 囲み終わると、ストロークの外接矩形 (+余白) を切り抜き範囲として
 * [selection] (ビットマップ座標) に保持し、外側を薄暗く表示する。
 */
class CircleView(context: Context, val bitmap: Bitmap) : View(context) {

    /** 切り抜き範囲 (ビットマップ座標)。未選択なら null */
    var selection: Rect? = null
        private set

    /** 選択状態が変わったときの通知 (送信ボタンの有効/無効切り替え用) */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    /** 囲まずにタップした (点が少なすぎる) ときに呼ばれる */
    var onTapClose: (() -> Unit)? = null

    private val strokePath = Path()
    private val points = ArrayList<PointF>()
    private var drawing = false

    // ビットマップのフィット配置 (中央寄せ・等倍率)
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    /** 描画専用に表示サイズへ縮小したコピー (毎フレームの拡大描画を軽くする) */
    private var displayBitmap: Bitmap? = null

    // 囲み線の色は Muse アプリアイコンの青 (#2F6EE2、実アイコンから採色)。
    // 線にはグロー (にじみ) を重ね、指が触れている先端にも光の点を出す
    private val accentColor = Color.rgb(47, 110, 226)    // #2F6EE2

    private fun glowStroke(widthDp: Float, alpha: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(alpha, 47, 110, 226)
            strokeWidth = dp(widthDp)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private fun glowFill(alpha: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(alpha, 47, 110, 226)
    }

    private val glowOuterPaint = glowStroke(20f, 36)
    private val glowMidPaint = glowStroke(12f, 80)
    private val strokePaint = glowStroke(6f, 255)
    private val fingerGlowOuterPaint = glowFill(36)
    private val fingerGlowMidPaint = glowFill(80)
    private val fingerGlowCorePaint = glowFill(255)

    // 芯の外側に引く細い白縁 (背景が線と同色でも輪郭が立つように)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(235, 255, 255, 255)
        strokeWidth = dp(9.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fingerEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(235, 255, 255, 255)
    }
    private val dimPaint = Paint().apply {
        color = Color.argb(115, 0, 0, 0)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
        strokeWidth = dp(2.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(7f)), 0f)
    }
    private val boxEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = dp(4f)
        pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(7f)), 0f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
        scale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        offX = (w - bitmap.width * scale) / 2f
        offY = (h - bitmap.height * scale) / 2f
        val dw = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val dh = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val current = displayBitmap
        if (current == null || current.width != dw || current.height != dh) {
            displayBitmap = Bitmap.createScaledBitmap(bitmap, dw, dh, true)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val display = displayBitmap
        if (display != null) {
            canvas.drawBitmap(display, offX, offY, null)
        } else {
            val dst = RectF(offX, offY, offX + bitmap.width * scale, offY + bitmap.height * scale)
            canvas.drawBitmap(bitmap, null, dst, null)
        }

        if (points.isNotEmpty()) {
            canvas.drawPath(strokePath, glowOuterPaint)
            canvas.drawPath(strokePath, glowMidPaint)
            canvas.drawPath(strokePath, edgePaint)
            canvas.drawPath(strokePath, strokePaint)
            // 指が触れている先端に光の点を重ねる
            if (drawing && points.isNotEmpty()) {
                val tip = points.last()
                canvas.drawCircle(tip.x, tip.y, dp(16f), fingerGlowOuterPaint)
                canvas.drawCircle(tip.x, tip.y, dp(10f), fingerGlowMidPaint)
                canvas.drawCircle(tip.x, tip.y, dp(7f), fingerEdgePaint)
                canvas.drawCircle(tip.x, tip.y, dp(5f), fingerGlowCorePaint)
            }
        }

        selection?.let { sel ->
            val vr = toViewRect(sel)
            // 選択範囲の外側を薄暗くする
            canvas.drawRect(0f, 0f, width.toFloat(), vr.top, dimPaint)
            canvas.drawRect(0f, vr.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, vr.top, vr.left, vr.bottom, dimPaint)
            canvas.drawRect(vr.right, vr.top, width.toFloat(), vr.bottom, dimPaint)
            canvas.drawRect(vr, boxEdgePaint)
            canvas.drawRect(vr, boxPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                strokePath.reset()
                points.clear()
                points.add(PointF(event.x, event.y))
                strokePath.moveTo(event.x, event.y)
                selection = null
                onSelectionChanged?.invoke(false)
                drawing = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return true
                strokePath.lineTo(event.x, event.y)
                points.add(PointF(event.x, event.y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!drawing) return true
                drawing = false
                finishStroke()
            }
        }
        return true
    }

    private fun finishStroke() {
        // 点が少なすぎる (タップだけ等) 場合は囲みとして扱わない
        if (points.size < 8) {
            clearSelection()
            onTapClose?.invoke()
            return
        }
        val bounds = RectF()
        strokePath.computeBounds(bounds, true)
        val pad = dp(20f) / scale
        val left = ((bounds.left - offX) / scale - pad).coerceIn(0f, bitmap.width.toFloat())
        val top = ((bounds.top - offY) / scale - pad).coerceIn(0f, bitmap.height.toFloat())
        val right = ((bounds.right - offX) / scale + pad).coerceIn(0f, bitmap.width.toFloat())
        val bottom = ((bounds.bottom - offY) / scale + pad).coerceIn(0f, bitmap.height.toFloat())
        val rect = Rect(
            left.roundToInt(), top.roundToInt(),
            max(left.roundToInt() + 1, right.roundToInt()),
            max(top.roundToInt() + 1, bottom.roundToInt())
        )
        if (rect.width() < 16 || rect.height() < 16) {
            clearSelection()
            return
        }
        selection = rect
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    fun clearSelection() {
        strokePath.reset()
        points.clear()
        selection = null
        onSelectionChanged?.invoke(false)
        invalidate()
    }

    private fun toViewRect(r: Rect): RectF = RectF(
        offX + r.left * scale,
        offY + r.top * scale,
        offX + r.right * scale,
        offY + r.bottom * scale
    )
}
