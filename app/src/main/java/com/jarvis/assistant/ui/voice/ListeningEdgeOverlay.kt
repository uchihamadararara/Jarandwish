package com.jarvis.assistant.ui.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Assistant aura: a glowing colour border around the whole screen plus a liquid orb at the bottom
 * that reacts to voice. It is shown while a live session is active, but ONLY while the home screen
 * is in front. Over any other app (including JARVIS itself) it is removed, and it comes back
 * when the user returns to the home screen. It never takes touches or focus.
 */
object ListeningEdgeOverlay {
    private const val TAG = "JarvisEdgeGlow"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var sessionActive = false
    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var auraView: AuraView? = null
    private var homePackages: Set<String> = emptySet()

    fun show(context: Context) {
        val app = context.applicationContext
        scope.launch {
            appContext = app
            sessionActive = true
            refreshHomePackages()
            if (watchJob?.isActive != true) {
                watchJob = scope.launch {
                    JarvisAccessibilityService.foregroundAppPackage.collect { applyVisibility() }
                }
            }
            applyVisibility()
        }
    }

    fun hide() {
        scope.launch {
            sessionActive = false
            watchJob?.cancel()
            watchJob = null
            detach()
        }
    }

    private fun applyVisibility() {
        if (!sessionActive) {
            detach()
            return
        }
        if (isHomeInFront()) attach() else detach()
    }

    /** If accessibility is not running we cannot tell which app is in front, so we keep the aura visible. */
    private fun isHomeInFront(): Boolean {
        if (!JarvisAccessibilityService.isRunning()) return true
        val pkg = JarvisAccessibilityService.foregroundAppPackage.value
        if (pkg.isEmpty()) return true
        return pkg in homePackages
    }

    private fun refreshHomePackages() {
        val ctx = appContext ?: return
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            homePackages = ctx.packageManager
                .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve home launcher packages: ${e.message}")
        }
    }

    private fun attach() {
        if (auraView != null) return
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            Log.i(TAG, "Overlay permission is not enabled; skipping aura")
            return
        }
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = AuraView(ctx)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "JARVIS assistant aura"
        }
        try {
            manager.addView(view, params)
            windowManager = manager
            auraView = view
            Log.i(TAG, "Aura shown (home screen)")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to show aura: ${e.message}")
            runCatching { manager.removeView(view) }
        }
    }

    private fun detach() {
        val view = auraView ?: return
        val manager = windowManager
        auraView = null
        windowManager = null
        view.animate().alpha(0f).setDuration(180L).withEndAction {
            try {
                manager?.removeViewImmediate(view)
            } catch (e: Exception) {
                Log.d(TAG, "Aura was already detached: ${e.message}")
            }
        }.start()
    }

    private class AuraView(context: Context) : View(context) {
        private val dp = resources.displayMetrics.density
        private val born = SystemClock.uptimeMillis()
        private var lastFrame = born
        private var level = 0f

        // JARVIS palette: electric cyan, deep blue and a restrained violet highlight.
        private val gColors = intArrayOf(
            Color.rgb(35, 220, 255), Color.rgb(35, 125, 255),
            Color.rgb(105, 75, 255), Color.rgb(35, 220, 255)
        )
        private val palette = intArrayOf(gColors[0], gColors[1], gColors[2], gColors[0])
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val frame = RectF()
        private val blob = Path()
        private val matrixTmp = Matrix()
        private var edgeSweep: SweepGradient? = null
        private var sweepW = 0
        private var sweepH = 0

        // Bottom wave / dots animation state
        private val wavePath = Path()
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val waveLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dotsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var waveShader: LinearGradient? = null
        private var glowShader: LinearGradient? = null
        private var bottomW = 0
        private var bottomH = 0
        private var dotsMix = 0f
        private var frameDt = 0.016f

        // Unit-space shaders (radius 1) so the orb can be scaled with the canvas, no per-frame allocation.
        private val rimSweep = SweepGradient(0f, 0f, palette, null)
        private val halo = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(170, 70, 190, 255), Color.argb(70, 120, 90, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        private val body = RadialGradient(
            -0.28f, -0.32f, 1.45f,
            intArrayOf(Color.rgb(205, 250, 255), Color.rgb(45, 175, 255), Color.rgb(100, 75, 235), Color.rgb(26, 20, 100)),
            floatArrayOf(0f, 0.32f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        @Suppress("DEPRECATION")
        private fun bottomInsetPx(): Float =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) (rootWindowInsets?.stableInsetBottom ?: 0).toFloat() else 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastFrame).coerceIn(1L, 64L)) / 1000f
            lastFrame = now
            frameDt = dt
            val t = (now - born) / 1000f
            val introRaw = ((now - born) / 450f).coerceIn(0f, 1f)
            val intro = 1f - (1f - introRaw) * (1f - introRaw) * (1f - introRaw)

            val state = JarvisConversationService.conversationState.value
            val raw = JarvisConversationService.audioLevel.value.coerceIn(0f, 1f)
            val shaped = (sqrt(raw) * 1.3f).coerceIn(0f, 1f)
            val target = when (state) {
                ConversationState.SPEAKING -> max(shaped, 0.22f)
                ConversationState.LISTENING -> max(shaped * 0.7f, 0.12f)
                ConversationState.THINKING -> 0.18f
                else -> 0.08f
            }
            level += (target - level) * min(1f, dt * (if (target > level) 14f else 5f))

            drawEdgeAura(canvas, w, h, t, intro)
            drawBottom(canvas, w, h, t, intro, state)
            drawJarvisOrbAndPanel(canvas, w, h, t, intro, state, level)

            postInvalidateOnAnimation()
        }

        private fun drawEdgeAura(canvas: Canvas, w: Float, h: Float, t: Float, intro: Float) {
            if (edgeSweep == null || sweepW != width || sweepH != height) {
                edgeSweep = SweepGradient(w / 2f, h / 2f, palette, null)
                sweepW = width
                sweepH = height
            }
            val sweep = edgeSweep ?: return
            matrixTmp.setRotate(t * 55f + level * 30f, w / 2f, h / 2f)
            sweep.setLocalMatrix(matrixTmp)
            edgePaint.shader = sweep

            val breathe = 0.86f + 0.14f * sin(t * 2.15f)
            val strength = (0.62f + 0.38f * level) * breathe * intro
            val boost = 1f + level * 0.55f
            frame.set(0f, 0f, w, h)
            val corner = 42f * dp

            // Multi-pass cyan HUD border. The transparent overlay keeps the launcher visible.
            val widths = floatArrayOf(66f, 42f, 25f, 12f, 4f)
            val alphas = floatArrayOf(0.08f, 0.14f, 0.24f, 0.48f, 0.96f)
            for (i in widths.indices) {
                edgePaint.strokeWidth = widths[i] * dp * boost
                edgePaint.alpha = (alphas[i] * 255f * strength).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(frame, corner, corner, edgePaint)
            }
        }

        /**
         * Google-assistant style bottom animation: layered, flowing four-colour waves that swell with
         * your voice, a soft colour haze above them, and (while thinking) four hopping Google dots.
         */
        private fun drawBottom(canvas: Canvas, w: Float, h: Float, t: Float, intro: Float, state: ConversationState) {
            if (waveShader == null || bottomW != width || bottomH != height) {
                waveShader = LinearGradient(0f, 0f, w * 1.2f, 0f, palette, null, Shader.TileMode.MIRROR)
                glowShader = LinearGradient(
                    0f, h - 240f * dp, 0f, h,
                    Color.TRANSPARENT, Color.argb(90, 66, 133, 244), Shader.TileMode.CLAMP
                )
                bottomW = width
                bottomH = height
            }
            val shader = waveShader ?: return
            val inset = bottomInsetPx()

            val thinking = state == ConversationState.THINKING
            dotsMix += ((if (thinking) 1f else 0f) - dotsMix) * min(1f, frameDt * 9f)
            val waveA = (1f - dotsMix) * intro

            // Soft haze rising from the bottom edge
            if (waveA > 0.01f) {
                glowPaint.shader = glowShader
                glowPaint.alpha = ((0.45f + 0.55f * level) * 255f * waveA).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, h - 240f * dp, w, h, glowPaint)
            }

            // Flowing waves
            if (waveA > 0.01f) {
                matrixTmp.setTranslate(-t * 140f * dp * 0.35f, 0f)
                shader.setLocalMatrix(matrixTmp)
                wavePaint.shader = shader
                waveLine.shader = shader

                val amp = (9f + level * 52f) * dp
                val layerAlpha = floatArrayOf(0.62f, 0.40f, 0.26f)
                val steps = 56
                for (k in 0..2) {
                    val phase = t * (1.25f + 0.55f * k) + k * 1.9f
                    val cycles = 1.15f + 0.45f * k
                    val base = h - inset - (14f + 7f * k) * dp
                    wavePath.rewind()
                    wavePath.moveTo(0f, h)
                    for (n in 0..steps) {
                        val x = w * n / steps
                        val u = n.toFloat() / steps
                        val envelope = sin(PI.toFloat() * u).coerceIn(0f, 1f)
                        val wob = sin(2f * PI.toFloat() * cycles * u + phase) * 0.62f +
                            sin(2f * PI.toFloat() * cycles * 1.8f * u - phase * 1.3f) * 0.38f
                        val y = base - amp * (0.35f + 0.65f * envelope) * (0.55f + 0.45f * k / 2f) * (0.5f + 0.5f * wob) - amp * 0.15f * envelope
                        wavePath.lineTo(x, y)
                    }
                    wavePath.lineTo(w, h)
                    wavePath.close()
                    wavePaint.alpha = (layerAlpha[k] * 255f * waveA).toInt().coerceIn(0, 255)
                    canvas.drawPath(wavePath, wavePaint)
                    if (k == 0) {
                        waveLine.strokeWidth = 2.6f * dp
                        waveLine.alpha = (235f * waveA).toInt().coerceIn(0, 255)
                        canvas.drawPath(wavePath, waveLine)
                    }
                }
            }

            // Thinking: four Google-coloured dots hopping in sequence
            if (dotsMix > 0.01f) {
                val gap = 28f * dp
                val r = 6.5f * dp
                val cy = h - inset - 46f * dp
                val startX = w / 2f - gap * 1.5f
                for (i in 0..3) {
                    val hop = abs(sin(t * 4.2f - i * 0.65f))
                    dotsPaint.color = gColors[i]
                    dotsPaint.alpha = (255f * dotsMix * intro).toInt().coerceIn(0, 255)
                    canvas.drawCircle(startX + gap * i, cy - hop * 16f * dp, r * (0.9f + 0.2f * hop), dotsPaint)
                }
            }
        }

        /**
         * Transparent JARVIS HUD: a glass energy orb, side audio ribbons and a live state panel.
         * It is drawn over the launcher; no wallpaper or launcher content is replaced.
         */
        private fun drawJarvisOrbAndPanel(
            canvas: Canvas, w: Float, h: Float, t: Float, intro: Float,
            state: ConversationState, audio: Float
        ) {
            val inset = bottomInsetPx()
            val cx = w * 0.5f
            val orbR = min(w * 0.125f, 66f * dp)
            val orbCy = h * 0.775f - inset * 0.12f
            val pulse = 1f + (0.025f + audio * 0.075f) * sin(t * (2.4f + audio * 3f))
            val r = orbR * pulse
            val alpha = (255f * intro).toInt().coerceIn(0, 255)

            // JARVIS label and thin luminous separators.
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 14f * dp
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
                this.alpha = alpha
            }
            val labelY = orbCy - orbR - 25f * dp
            canvas.drawText("J A R V I S", cx, labelY, labelPaint)
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f * dp
                shader = LinearGradient(cx - 125f * dp, labelY, cx + 125f * dp, labelY,
                    intArrayOf(Color.TRANSPARENT, Color.CYAN, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                this.alpha = alpha
            }
            canvas.drawLine(cx - 132f * dp, labelY - 4f * dp, cx - 58f * dp, labelY - 4f * dp, linePaint)
            canvas.drawLine(cx + 58f * dp, labelY - 4f * dp, cx + 132f * dp, labelY - 4f * dp, linePaint)

            // Orb halo and subtle concentric HUD rings.
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, orbCy, r * 1.9f,
                    intArrayOf(Color.argb((100f * intro).toInt(), 20, 170, 255),
                        Color.argb((38f * intro).toInt(), 45, 90, 255), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(cx, orbCy, r * 1.9f, haloPaint)

            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f * dp
                color = Color.argb((105f * intro).toInt(), 45, 190, 255)
            }
            canvas.drawCircle(cx, orbCy, r * 1.22f, ringPaint)
            ringPaint.alpha = (65f * intro).toInt()
            canvas.drawCircle(cx, orbCy, r * 1.34f, ringPaint)

            // Glass orb body: dark transparent core, cyan rim and violet inner reflection.
            val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx - r * 0.28f, orbCy - r * 0.36f, r * 1.55f,
                    intArrayOf(Color.rgb(95, 220, 255), Color.rgb(10, 112, 230),
                        Color.rgb(19, 35, 125), Color.rgb(8, 12, 48)),
                    floatArrayOf(0f, 0.34f, 0.72f, 1f), Shader.TileMode.CLAMP)
                alpha = alpha
            }
            canvas.drawCircle(cx, orbCy, r, orbPaint)

            // Moving liquid ribbons inside the orb.
            val innerWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = (2.2f + audio * 2.5f) * dp
                shader = LinearGradient(cx-r, orbCy, cx+r, orbCy,
                    intArrayOf(Color.TRANSPARENT, Color.rgb(40, 220, 255),
                        Color.rgb(105, 75, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                alpha = (220f * intro).toInt()
            }
            val orbClip = Path().apply { addCircle(cx, orbCy, r * 0.94f, Path.Direction.CW) }
            val save = canvas.save()
            canvas.clipPath(orbClip)
            for (layer in 0..1) {
                val p = Path()
                val baseY = orbCy + r * (0.35f + layer * 0.13f)
                p.moveTo(cx-r, baseY)
                for (i in 0..36) {
                    val x = cx-r + (2f*r*i/36f)
                    val u = i/36f
                    val wave = sin(u * PI.toFloat() * 2.2f + t * (1.8f + layer * 0.55f) + layer) *
                        (r * (0.12f + audio * 0.14f))
                    p.lineTo(x, baseY + wave)
                }
                innerWavePaint.alpha = ((190f - layer*55f) * intro).toInt().coerceIn(0,255)
                canvas.drawPath(p, innerWavePaint)
            }
            canvas.restoreToCount(save)

            // Bright glass rim and upper-left specular reflection.
            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * dp
                shader = SweepGradient(cx, orbCy, intArrayOf(
                    Color.rgb(30, 225, 255), Color.rgb(80, 130, 255),
                    Color.rgb(150, 80, 255), Color.rgb(30, 225, 255)), null)
            }
            canvas.drawCircle(cx, orbCy, r, rimPaint)
            val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = 5f * dp
                color = Color.argb((150f * intro).toInt(), 180, 245, 255)
            }
            canvas.drawArc(cx-r*0.78f, orbCy-r*0.78f, cx+r*0.78f, orbCy+r*0.78f,
                205f, 65f, false, shine)

            // Side audio ribbons that expand with microphone / speaker level.
            val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = 1.6f * dp
                color = Color.argb((145f * intro).toInt(), 30, 190, 255)
            }
            for (side in 0..1) {
                val p = Path()
                val start = if (side == 0) 0f else cx + r * 1.2f
                val end = if (side == 0) cx - r * 1.2f else w
                p.moveTo(start, orbCy + r * 0.65f)
                for (i in 0..32) {
                    val u = i / 32f
                    val x = start + (end-start)*u
                    val envelope = sin(PI.toFloat()*u).coerceAtLeast(0f)
                    val direction = if (side == 0) 1f else -1f
                    val y = orbCy + r*0.65f + sin(u*PI.toFloat()*4f+t*2.1f) *
                        (5f*dp + audio*24f*dp)*envelope*direction
                    p.lineTo(x,y)
                }
                canvas.drawPath(p, sidePaint)
            }

            // Bottom glass status pill.
            val panelTop = h - inset - 112f * dp
            val panelBottom = h - inset - 20f * dp
            val panel = RectF(18f*dp, panelTop, w-18f*dp, panelBottom)
            val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, panelTop, 0f, panelBottom,
                    intArrayOf(Color.argb(100, 4, 28, 60), Color.argb(145, 2, 13, 34)),
                    null, Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(panel, 42f*dp, 42f*dp, panelFill)
            val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f*dp
                color = Color.argb((170f*intro).toInt(), 25, 180, 255)
            }
            canvas.drawRoundRect(panel, 42f*dp, 42f*dp, panelStroke)

            val status = when (state) {
                ConversationState.LISTENING -> "Listening..."
                ConversationState.THINKING -> "Thinking..."
                ConversationState.SPEAKING -> "JARVIS is speaking..."
                else -> "Ready"
            }
            val transcript = JarvisConversationService.liveUserTranscript.value.trim()
            val primary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 16f*dp
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                this.alpha = alpha
            }
            val secondary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(150, 180, 210)
                textSize = 12f*dp
                textAlign = Paint.Align.CENTER
                this.alpha = (210f*intro).toInt()
            }
            val statusY = panelTop + 38f*dp
            canvas.drawText(status, cx, statusY, primary)
            val sub = when {
                transcript.isNotEmpty() -> transcript.take(54)
                state == ConversationState.LISTENING -> "Say something..."
                state == ConversationState.THINKING -> "Processing your request"
                state == ConversationState.SPEAKING -> "Responding"
                else -> "Tap the JARVIS orb to start"
            }
            canvas.drawText(sub, cx, panelTop + 66f*dp, secondary)

            // Tiny live waveform above the status label.
            val bars = 17
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 220, 255)
                strokeWidth = 2.4f*dp
                strokeCap = Paint.Cap.ROUND
                alpha = alpha
            }
            val baseX = cx - (bars-1)*4.2f*dp
            val baseY = statusY - 4f*dp
            for (i in 0 until bars) {
                val wave = abs(sin(t*5f + i*0.58f))
                val height = (4f + wave*(8f + audio*17f))*dp
                canvas.drawLine(baseX+i*4.2f*dp, baseY-height/2f,
                    baseX+i*4.2f*dp, baseY+height/2f, barPaint)
            }
        }

    }
}
