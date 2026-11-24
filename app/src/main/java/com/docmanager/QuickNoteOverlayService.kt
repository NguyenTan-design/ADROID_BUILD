package com.docmanager

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs

/** Mở PDF bằng Adobe Acrobat (nếu có cài), nếu không thì bằng ứng dụng PDF bất kỳ. */
fun openPdfExternal(ctx: Context, uri: String) {
    val base = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(Intent(base).setPackage("com.adobe.reader"))
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(base)
        } catch (_: Exception) {
            Toast.makeText(ctx, "Không có ứng dụng nào mở được PDF.", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Dịch vụ giữ nút "Quick Note" nổi trên mọi ứng dụng (kể cả Acrobat).
 * Bấm nút: ẩn nút → chụp màn hình → cho khoanh vùng → mở hộp thoại lưu.
 */
class QuickNoteOverlayService : Service() {
    companion object {
        const val ACTION_START = "com.docmanager.QN_START"
        const val ACTION_STOP = "com.docmanager.QN_STOP"
        private const val CHANNEL = "qn_overlay"
        private const val NOTIF_ID = 4711

        @Volatile var instance: QuickNoteOverlayService? = null

        /** Đang có phiên chụp màn hình hợp lệ hay không. */
        val active: Boolean get() = instance?.projection != null

        /** Mở PDF bằng Acrobat; nếu chưa có phiên nút nổi thì xin quyền chụp màn hình trước. */
        fun begin(ctx: Context, uri: String) {
            if (active) {
                openPdfExternal(ctx, uri)
            } else {
                ctx.startActivity(
                    Intent(ctx, QuickNoteConsentActivity::class.java)
                        .putExtra("uri", uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null
    private var lastImage: Image? = null
    private var rw = 0
    private var rh = 0

    private var button: View? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private var selector: View? = null
    private var menu: View? = null
    private var captureMode = "ocr"          // "copy" = dùng chữ đã Copy, "ocr" = nhận diện chữ trong ảnh
    private var started = 0                  // số Activity của app đang hiện
    private var capturing = false
    private var awaitingSaveUi = false

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START && projection == null) startSession(intent)
        return START_NOT_STICKY
    }

    // ============================================================ khởi tạo phiên
    private fun startSession(intent: Intent) {
        instance = this
        createChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return }
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { handler.post { stopSelf() } }
            }, handler)
            projection = p
            setupDisplay()
            createButton()
            application.registerActivityLifecycleCallbacks(lifecycleCb)
        } catch (e: Throwable) {
            Toast.makeText(this, "Không bật được nút Quick Note: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Quick Note", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, QuickNoteOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Quick Note đang bật")
            .setContentText("Mở PDF bằng Acrobat rồi bấm nút Quick Note nổi để ghi chú")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Dừng", stop)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private val frameListener = ImageReader.OnImageAvailableListener { r ->
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null }
        if (img != null) {
            lastImage?.close()
            lastImage = img
        }
    }

    private fun setupDisplay() {
        val dm = realMetrics()
        rw = dm.widthPixels
        rh = dm.heightPixels
        val r = ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener(frameListener, handler)
        reader = r
        vDisplay = projection!!.createVirtualDisplay(
            "QuickNote", rw, rh, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    /** Màn hình xoay → đổi kích thước vùng chụp. Trả về true nếu có đổi. */
    private fun ensureDisplaySize(): Boolean {
        val dm = realMetrics()
        if (dm.widthPixels == rw && dm.heightPixels == rh) return false
        lastImage?.close()
        lastImage = null
        reader?.close()
        rw = dm.widthPixels
        rh = dm.heightPixels
        val r = ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener(frameListener, handler)
        reader = r
        vDisplay?.surface = r.surface
        vDisplay?.resize(rw, rh, dm.densityDpi)
        return true
    }

    // ============================================================ nút nổi
    private fun createButton() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00796B"))
                cornerRadius = 24 * d
            }
            alpha = 0.4f                          // đủ mờ để không che nội dung PDF
        }
        fun label(t: String, l: Int, r: Int) = TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((l * d).toInt(), (10 * d).toInt(), (r * d).toInt(), (10 * d).toInt())
        }
        val main = label("📝 Quick Note", 14, 8)
        val close = label("✕", 8, 14)
        box.addView(main)
        box.addView(close)

        val dm = realMetrics()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - (190 * d).toInt()
            y = (dm.heightPixels * 0.72f).toInt()
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        box.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    v.alpha = 0.85f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 0.4f
                    if (!moved) {
                        if (e.x > v.width - close.width) stopSelf() else showModeMenu()
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.alpha = 0.4f
            }
            true
        }
        wm.addView(box, lp)
        button = box
        buttonLp = lp
        updateVisibility()
    }

    /** Nút chỉ hiện khi không có màn hình nào của app này đang mở (tức là đang ở Acrobat/ứng dụng khác). */
    private fun updateVisibility() {
        button?.visibility =
            if (started == 0 && !capturing && !awaitingSaveUi && menu == null) View.VISIBLE else View.GONE
    }

    private val lifecycleCb = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            started++
            awaitingSaveUi = false
            updateVisibility()
        }
        override fun onActivityStopped(activity: Activity) {
            started = maxOf(0, started - 1)
            updateVisibility()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    // ============================================================ menu chọn kiểu lấy chữ
    private fun showModeMenu() {
        if (menu != null || capturing) return
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 14 * d
                setStroke((1 * d).toInt(), Color.parseColor("#B0BEC5"))
            }
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
            elevation = 8 * d
        }
        fun item(title: String, sub: String, color: String, action: () -> Unit): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
                setOnClickListener { action() }
            }
            row.addView(TextView(this).apply {
                text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(color))
            })
            if (sub.isNotEmpty()) row.addView(TextView(this).apply {
                text = sub; textSize = 11f; setTextColor(Color.parseColor("#78909C"))
            })
            return row
        }
        col.addView(item("📋 Copy chữ", "Lấy chữ bạn vừa bôi chọn và Copy", "#3F51B5") { chooseMode("copy") })
        col.addView(item("🔍 Quét OCR", "Tự đọc chữ trong vùng khoanh", "#00796B") { chooseMode("ocr") })
        col.addView(item("✕ Đóng", "", "#C62828") { removeMenu(); updateVisibility() })

        val dm = realMetrics()
        val bl = buttonLp
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = minOf(bl?.x ?: 0, dm.widthPixels - (250 * d).toInt()).coerceAtLeast(0)
            y = ((bl?.y ?: 0) - (120 * d).toInt()).coerceAtLeast((40 * d).toInt())
        }
        col.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) { removeMenu(); updateVisibility() }
            false
        }
        try {
            wm.addView(col, lp)
            menu = col
            updateVisibility()
        } catch (_: Exception) {
        }
    }

    private fun removeMenu() {
        menu?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        menu = null
    }

    private fun chooseMode(mode: String) {
        captureMode = mode
        removeMenu()
        startCapture()
    }

    // ============================================================ chụp màn hình
    private fun startCapture() {
        if (capturing || projection == null) return
        capturing = true
        updateVisibility()                         // ẩn nút để không lọt vào ảnh chụp
        val resized = ensureDisplaySize()
        handler.postDelayed({ grabAndSelect(2) }, if (resized) 800L else 350L)
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val w = img.width
        val h = img.height
        val bmp = Bitmap.createBitmap(p.rowStride / p.pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(p.buffer)
        return if (bmp.width != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
    }

    private fun grabAndSelect(retries: Int) {
        val img = lastImage
        val bmp = try { img?.let { toBitmap(it) } } catch (_: Throwable) { null }
        if (bmp == null && retries > 0) {
            handler.postDelayed({ grabAndSelect(retries - 1) }, 400L)
            return
        }
        if (bmp == null) {
            capturing = false
            updateVisibility()
            Toast.makeText(this, "Không chụp được màn hình, hãy thử lại.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            showSelector(bmp)
        } catch (e: Throwable) {
            capturing = false
            updateVisibility()
            Toast.makeText(this, "Không mở được khung khoanh vùng: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSelector(shot: Bitmap) {
        val v = RegionSelectView(this, shot) { rect -> handler.post { onRegion(shot, rect) } }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        selector = v
    }

    private fun removeSelector() {
        selector?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        selector = null
    }

    private fun onRegion(shot: Bitmap, rect: Rect?) {
        removeSelector()
        if (rect == null) {
            capturing = false
            updateVisibility()
            return
        }
        try {
            val crop = Bitmap.createBitmap(shot, rect.left, rect.top, rect.width(), rect.height())
            val f = File(cacheDir, "qn_draft.png")
            f.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            awaitingSaveUi = true
            capturing = false
            startActivity(
                Intent(this, QuickNoteSaveActivity::class.java)
                    .putExtra("path", f.absolutePath).putExtra("mode", captureMode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            handler.postDelayed({ awaitingSaveUi = false; updateVisibility() }, 3000)
        } catch (e: Throwable) {
            capturing = false
            awaitingSaveUi = false
            Toast.makeText(this, "Không lưu được vùng ảnh: ${e.message}", Toast.LENGTH_LONG).show()
        }
        updateVisibility()
    }

    // ============================================================ dọn dẹp
    override fun onDestroy() {
        try { application.unregisterActivityLifecycleCallbacks(lifecycleCb) } catch (_: Exception) {}
        removeSelector()
        removeMenu()
        button?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        button = null
        lastImage?.close()
        lastImage = null
        try { vDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        instance = null
        super.onDestroy()
    }
}

/** Màn hình ảnh chụp + khung kéo để khoanh vùng cần ghi chú. */
class RegionSelectView(
    ctx: Context, private val shot: Bitmap, private val onResult: (Rect?) -> Unit
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800"); style = Paint.Style.STROKE; strokeWidth = 3 * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15 * d; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var hasRect = false
    private var down = 0                       // 1 = Lưu, 2 = Hủy, 3 = đang kéo
    private val okRect = RectF()
    private val cancelRect = RectF()

    private fun sel() = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))
    private fun valid() = hasRect && sel().width() > 24 * d && sel().height() > 12 * d

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawBitmap(shot, null, RectF(0f, 0f, w, h), bitmapPaint)
        if (hasRect) {
            val r = sel()
            c.drawRect(0f, 0f, w, r.top, dim)
            c.drawRect(0f, r.bottom, w, h, dim)
            c.drawRect(0f, r.top, r.left, r.bottom, dim)
            c.drawRect(r.right, r.top, w, r.bottom, dim)
            c.drawRect(r, border)
        } else {
            c.drawRect(0f, 0f, w, h, dim)
        }
        // dòng hướng dẫn
        val hint = if (valid()) "Thả tay rồi bấm \"Dùng vùng này\"" else "Kéo ngón tay để khoanh vùng cần lưu"
        val hw = text.measureText(hint) + 32 * d
        fill.color = Color.argb(200, 0, 0, 0)
        c.drawRoundRect(RectF(w / 2 - hw / 2, 48 * d, w / 2 + hw / 2, 88 * d), 20 * d, 20 * d, fill)
        c.drawText(hint, w / 2, 74 * d, text)
        // nút
        val bw = 150 * d
        val bh = 48 * d
        val gap = 16 * d
        val by = h - bh - 48 * d
        cancelRect.set(w / 2 - bw - gap / 2, by, w / 2 - gap / 2, by + bh)
        okRect.set(w / 2 + gap / 2, by, w / 2 + gap / 2 + bw, by + bh)
        fill.color = Color.parseColor("#455A64")
        c.drawRoundRect(cancelRect, 24 * d, 24 * d, fill)
        fill.color = if (valid()) Color.parseColor("#00796B") else Color.parseColor("#78909C")
        c.drawRoundRect(okRect, 24 * d, 24 * d, fill)
        c.drawText("✕ Hủy", cancelRect.centerX(), cancelRect.centerY() + text.textSize / 3, text)
        c.drawText("✔ Dùng vùng này", okRect.centerX(), okRect.centerY() + text.textSize / 3, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                down = when {
                    okRect.contains(e.x, e.y) -> 1
                    cancelRect.contains(e.x, e.y) -> 2
                    else -> { sx = e.x; sy = e.y; ex = e.x; ey = e.y; hasRect = false; 3 }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (down == 3) {
                ex = e.x; ey = e.y; hasRect = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                when (down) {
                    1 -> if (okRect.contains(e.x, e.y) && valid()) onResult(toBitmapRect())
                    2 -> if (cancelRect.contains(e.x, e.y)) onResult(null)
                    3 -> { ex = e.x; ey = e.y; invalidate() }
                }
                down = 0
            }
            MotionEvent.ACTION_CANCEL -> down = 0
        }
        return true
    }

    private fun toBitmapRect(): Rect {
        val r = sel()
        val kx = shot.width / width.toFloat()
        val ky = shot.height / height.toFloat()
        val l = (r.left * kx).toInt().coerceIn(0, shot.width - 2)
        val t = (r.top * ky).toInt().coerceIn(0, shot.height - 2)
        val rr = (r.right * kx).toInt().coerceIn(l + 1, shot.width)
        val b = (r.bottom * ky).toInt().coerceIn(t + 1, shot.height)
        return Rect(l, t, rr, b)
    }
}
