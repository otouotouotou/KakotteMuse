package com.overnight.kakottemuse

import android.app.Activity
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * かこってみゅぜの本体。起動 = トリガー。
 *
 * 透明な Activity として前面に出て、root の screencap で背後の
 * 画面を撮影し (標準出力経由でメモリに取り込む。実ファイルなし)、
 * その画像をこのレイヤー上に表示して指で囲ませる。
 * 「みゅぜに送る」で囲んだ部分を切り抜き、内部キャッシュの
 * 固定1ファイル経由で Muse へ共有する。
 *
 * root (KernelSU / Magisk 等で本アプリへの許可) が必要。
 */
class CaptureActivity : Activity() {

    /** 撮影が進行中か (二重起動防止) */
    @Volatile
    private var captureRunning = false

    /** 送信処理が進行中か (二重送信防止) */
    @Volatile
    private var sending = false

    /** Muse の共有受け口とアイコン (撮影と並行して裏で解決しておく) */
    @Volatile
    private var museTarget: Pair<String, String>? = null

    @Volatile
    private var museIcon: Drawable? = null

    /** 受け口解決が進行中か (二重解決防止) */
    @Volatile
    private var resolving = false

    /** 表示中の Muse ボタン (アイコン解決が遅れたら後から差し替える) */
    private var museButton: ImageButton? = null

    /** 起動時刻 (所要時間の計測用) */
    @Volatile
    private var launchStartMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ステータスバー/ナビバー領域までウィンドウを広げて描画する。
        // これがないとバーが黒帯になり、撮影画像の上端もバーの下から始まってしまう。
        // 撮影画像は画面全体なのでバー領域も画像で覆われ、バーのアイコンは
        // システムが重ねる分と撮影に写った分がほぼ一致して自然に見える
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        startCapture()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 既存インスタンスが再利用されても、前回の画面を残さず撮り直す
        startCapture()
    }

    private fun startCapture() {
        if (captureRunning) return
        captureRunning = true
        launchStartMs = android.os.SystemClock.elapsedRealtime()
        // 受け口とアイコンの解決は撮影と完全並行に走らせる
        // (以前は撮影の後ろに直列で付いていて、その分表示が遅れた)
        resolveTargetAsync()
        Thread { capture() }.start()
    }

    /** Muse の受け口とアイコンを裏スレッドで解決する */
    private fun resolveTargetAsync() {
        if (museTarget != null || resolving) return
        resolving = true
        Thread {
            // 撮影と CPU を食い合わないよう優先度を下げておく
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND
                )
            } catch (t: Throwable) {
                // 下げられなくても続行する
            }
            val target = findMuseTarget()
            if (target != null) {
                museTarget = target
                museIcon = try {
                    packageManager.getApplicationIcon(target.first)
                } catch (t: Throwable) {
                    null
                }
                val icon = museIcon
                // ボタンが先に表示されていたら、アイコンを差し替える
                if (icon != null) {
                    runOnUiThread {
                        val button = museButton
                        if (!isFinishing && button != null) {
                            button.setImageDrawable(icon)
                            button.background = null
                        }
                    }
                }
            }
            resolving = false
        }.start()
    }

    /** root の screencap で背後の画面を撮る */
    private fun capture() {
        val result = runScreencap()
        runOnUiThread {
            captureRunning = false
            if (isFinishing) return@runOnUiThread
            val bitmap = result.bitmap
            if (bitmap == null) {
                Toast.makeText(this, result.error ?: "撮影に失敗しました", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Log.i(
                    TAG,
                    "captured ${bitmap.width}x${bitmap.height}" +
                        " total=${android.os.SystemClock.elapsedRealtime() - launchStartMs}ms"
                )
                showEditor(bitmap)
            }
        }
    }

    private class CaptureResult(val bitmap: Bitmap?, val error: String?)

    /** まず raw (無圧縮) で撮り、ダメなら PNG で撮り直す */
    private fun runScreencap(): CaptureResult {
        val raw = runScreencapOnce(usePng = false)
        if (raw.bitmap != null) return raw
        Log.i(TAG, "raw capture failed (${raw.error}); falling back to png")
        return runScreencapOnce(usePng = true)
    }

    private fun runScreencapOnce(usePng: Boolean): CaptureResult {
        val mode = if (usePng) "png" else "raw"
        val t0 = android.os.SystemClock.elapsedRealtime()
        val process = try {
            // -p を付けないと PNG ではなく生の RGBA が出てくる
            ProcessBuilder("su", "-c", if (usePng) "screencap -p" else "screencap")
                .redirectErrorStream(false)
                .start()
        } catch (t: Throwable) {
            Log.e(TAG, "su start failed", t)
            return CaptureResult(null, "su が見つかりません (root 環境が必要です)")
        }
        // 標準エラーは詰まらないよう別スレッドで読み捨てる
        Thread {
            try {
                process.errorStream.bufferedReader().forEachLine {
                    Log.i(TAG, "screencap stderr: $it")
                }
            } catch (t: Throwable) {
                // 読み捨ての失敗は無視
            }
        }.start()
        val baos = ByteArrayOutputStream()
        val reader = Thread {
            try {
                process.inputStream.use { it.copyTo(baos) }
            } catch (t: Throwable) {
                Log.e(TAG, "stdout read failed", t)
            }
        }
        reader.start()
        val finished = try {
            process.waitFor(CAPTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            false
        }
        if (!finished) {
            process.destroy()
            return CaptureResult(
                null,
                "撮影がタイムアウトしました (KernelSU で root 許可を確認してください)"
            )
        }
        reader.join(2_000)
        val exit = try {
            process.exitValue()
        } catch (t: Throwable) {
            -1
        }
        val bytes = baos.toByteArray()
        if (exit != 0 || bytes.isEmpty()) {
            Log.e(TAG, "screencap failed: exit=$exit bytes=${bytes.size}")
            return CaptureResult(
                null,
                "撮影に失敗しました (root 許可が必要です: KernelSU / Magisk で本アプリを許可してください)"
            )
        }
        val bitmap = decodeScreenshot(bytes)
            ?: return CaptureResult(null, "撮影画像の読み込みに失敗しました")
        Log.i(
            TAG,
            "screencap done [$mode]: process+read=" +
                "${android.os.SystemClock.elapsedRealtime() - t0}ms bytes=${bytes.size}"
        )
        return CaptureResult(bitmap, null)
    }

    /**
     * screencap の出力を Bitmap にする。通常は PNG (-p 指定)。
     * PNG でなければ生フォーマット (12バイトヘッダ + RGBA) として
     * 読むフォールバックも用意する。
     */
    private fun decodeScreenshot(bytes: ByteArray): Bitmap? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
            Log.i(TAG, "decode png: ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            return it
        }
        return decodeRaw(bytes)
    }

    /**
     * screencap の生出力 (ヘッダ + RGBA) を読む。ヘッダ長は 12 / 16 の
     * 両方を試し、寸法が実画面と一致するものだけを採用する
     * (形式を取り違えて化けた画像を掴まないための保険)。
     * 行ごとの詰め物 (ストライド) がある場合は詰め直して読む。
     */
    private fun decodeRaw(bytes: ByteArray): Bitmap? {
        val dm = resources.displayMetrics
        val dispW = dm.widthPixels
        val dispH = dm.heightPixels
        for (headerSize in intArrayOf(12, 16)) {
            try {
                if (bytes.size < headerSize) continue
                val header = java.nio.ByteBuffer.wrap(bytes, 0, headerSize)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val w = header.getInt(0)
                val h = header.getInt(4)
                val format = header.getInt(8)
                if (w <= 0 || h <= 0 || format != 1) continue
                // 画面寸法と一致しないものは形式違いとして捨てる
                // (回転時は縦横の入れ替わりを許す)
                if (!((w == dispW && h == dispH) || (w == dispH && h == dispW))) {
                    continue
                }
                val rowBytes = w * 4
                val payload = bytes.size - headerSize
                if (payload < rowBytes * h) continue
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                if (payload == rowBytes * h) {
                    bitmap.copyPixelsFromBuffer(
                        java.nio.ByteBuffer.wrap(bytes, headerSize, rowBytes * h)
                    )
                } else if (payload % h == 0 && payload / h >= rowBytes) {
                    val stride = payload / h
                    val packed = java.nio.ByteBuffer.allocate(rowBytes * h)
                    for (y in 0 until h) {
                        packed.put(bytes, headerSize + y * stride, rowBytes)
                    }
                    packed.flip()
                    bitmap.copyPixelsFromBuffer(packed)
                } else {
                    continue
                }
                Log.i(TAG, "decode raw: ${w}x$h header=$headerSize")
                return bitmap
            } catch (t: Throwable) {
                Log.e(TAG, "raw decode failed (header=$headerSize)", t)
            }
        }
        return null
    }

    /** 撮影した画像をこのレイヤー上に表示し、囲み UI を出す */
    private fun showEditor(bitmap: Bitmap) {
        val circleView = CircleView(this, bitmap)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                circleView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        // 指を離して囲みが確定した時点で、そのまま送信する
        circleView.onSelectionChanged = { has ->
            if (has) send(circleView, bitmap)
        }
        // 囲まずにタップしたら閉じる
        circleView.onTapClose = { finish() }

        // 下部中央に2つのボタン: 左 = Muse (全体を「これ調べて」)、
        // 右 = Aあ (全体を「翻訳して」)
        fun iconButton(icon: Drawable?, onClick: () -> Unit): ImageButton {
            return ImageButton(this).apply {
                if (icon != null) {
                    setImageDrawable(icon)
                    background = null
                } else {
                    setImageResource(android.R.drawable.ic_menu_send)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { onClick() }
            }
        }
        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val museButton = iconButton(museIcon) {
            sendFull(bitmap, DEFAULT_QUESTION)
        }
        this.museButton = museButton
        val translateIcon = try {
            packageManager.getApplicationIcon(packageName)
        } catch (t: Throwable) {
            null
        }
        val translateButton = iconButton(translateIcon) {
            sendFull(bitmap, TRANSLATE_QUESTION)
        }
        buttonBar.addView(
            museButton,
            LinearLayout.LayoutParams(px(64), px(64))
        )
        buttonBar.addView(
            translateButton,
            LinearLayout.LayoutParams(px(64), px(64)).apply { leftMargin = px(16) }
        )
        root.addView(
            buttonBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = px(24)
            }
        )
        setContentView(root)
    }

    /** 囲んだ部分を切り抜いて Muse へ送る */
    private fun send(circleView: CircleView, bitmap: Bitmap) {
        val rect = circleView.selection
        if (rect == null) {
            finish()
            return
        }
        dispatch(rect, bitmap, DEFAULT_QUESTION)
    }

    /** 画面全体をそのまま送る (質問文はボタンごとに変える) */
    private fun sendFull(bitmap: Bitmap, question: String) {
        dispatch(Rect(0, 0, bitmap.width, bitmap.height), bitmap, question)
    }

    /** 指定範囲を切り抜いて送信する本体 (重い処理は裏スレッド) */
    private fun dispatch(rect: Rect, bitmap: Bitmap, question: String) {
        if (sending) return
        sending = true
        Thread {
            var uri: Uri? = null
            try {
                val crop = Bitmap.createBitmap(
                    bitmap, rect.left, rect.top, rect.width(), rect.height()
                )
                // 受け渡しは MediaStore の「ファイル」コレクションを試す。
                // 正規の MediaStore URI のまま、ギャラリーの画像一覧には
                // 分類されないことを狙う (v0.30 で実機確認)。
                // 自前プロバイダ URI は直接送信でも HyperOS が権限を落とし、
                // 受け側 (Muse) が読めない (v0.28 で実機確認済み)。
                // ファイル保存に失敗したら画像コレクション→プロバイダの順に倒す
                uri = saveCropToFiles(crop)
                    ?: saveCropToMediaStore(crop)
                    ?: saveCropToProvider(crop)
            } catch (t: Throwable) {
                Log.e(TAG, "send failed", t)
            }
            val resultUri = uri
            runOnUiThread {
                if (resultUri == null) {
                    Toast.makeText(this, "送信に失敗しました", Toast.LENGTH_LONG).show()
                } else {
                    // パネル経由だと HyperOS が権限を落とすことがあるため、
                    // Muse 宛が見つかれば直接送る (パネル自体をスキップ)
                    startSend(resultUri, question)
                }
                finish()
            }
        }.start()
    }

    /** Muse の共有受け口を探して直接送る。無ければ従来のパネル。 */
    private fun startSend(uri: Uri, question: String) {
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // 画像だけだと意図が曖昧なので、質問文を添える
            putExtra(Intent.EXTRA_TEXT, question)
            clipData = ClipData.newUri(contentResolver, "かこってみゅぜ", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val target = museTarget ?: findMuseTarget()
        if (target != null) {
            val direct = Intent(base).setClassName(target.first, target.second)
            try {
                // 小窓 (フリーフォーム) で開く指定を試す。ROM が
                // 受け付けなければ通常起動になる
                startActivity(direct, freeformOptions())
                Log.i(TAG, "direct send (freeform requested) to ${target.first}/${target.second}")
                return
            } catch (t: Throwable) {
                Log.e(TAG, "freeform send failed; retrying plain", t)
            }
            try {
                startActivity(direct)
                Log.i(TAG, "direct send to ${target.first}/${target.second}")
                return
            } catch (t: Throwable) {
                Log.e(TAG, "direct send failed; falling back to chooser", t)
            }
        }
        startActivity(Intent.createChooser(base, "みゅぜに送る"))
    }

    /** 小窓 (WINDOWING_MODE_FREEFORM = 5) で開くための起動オプション */
    private fun freeformOptions(): Bundle? {
        return try {
            val dm = resources.displayMetrics
            val w = (dm.widthPixels * 0.88f).toInt()
            val h = (dm.heightPixels * 0.6f).toInt()
            // 実機では指定 (中央寄せ) より右に寄って表示されたため、
            // 左端から置く指定にして補正を試す
            val left = 0
            val top = (dm.heightPixels - h) / 2
            val options = ActivityOptions.makeBasic()
            // setLaunchWindowingMode は公開 SDK に無い (非公開 API) ため
            // 反射で試す。取れなくても範囲指定だけで続行する
            try {
                ActivityOptions::class.java
                    .getMethod(
                        "setLaunchWindowingMode",
                        Int::class.javaPrimitiveType
                    )
                    .invoke(options, 5)
            } catch (t: Throwable) {
                Log.i(TAG, "setLaunchWindowingMode unavailable: ${t.javaClass.simpleName}")
            }
            options.setLaunchBounds(Rect(left, top, left + w, top + h))
            options.toBundle()
        } catch (t: Throwable) {
            Log.e(TAG, "freeform options failed", t)
            null
        }
    }

    /** 共有先候補から Muse (このアプリ) の受け口を探す */
    private fun findMuseTarget(): Pair<String, String>? {
        val probe = Intent(Intent.ACTION_SEND).setType("image/png")
        val list = try {
            packageManager.queryIntentActivities(
                probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
        } catch (t: Throwable) {
            Log.e(TAG, "query share targets failed", t)
            emptyList()
        }
        for (ri in list) {
            Log.i(
                TAG,
                "share target: ${ri.loadLabel(packageManager)} " +
                    "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
            )
        }
        val candidates = list.filter { it.activityInfo.packageName != packageName }
        val exact = candidates.firstOrNull {
            it.loadLabel(packageManager).toString().equals("Muse", ignoreCase = true)
        }
        val fuzzy = candidates.firstOrNull {
            it.loadLabel(packageManager).toString().contains("muse", ignoreCase = true) ||
                it.activityInfo.packageName.contains("muse", ignoreCase = true)
        }
        val chosen = exact ?: fuzzy ?: return null
        return chosen.activityInfo.packageName to chosen.activityInfo.name
    }

    /**
     * 切り抜きを MediaStore の「ファイル」コレクションに固定名で保存する。
     * URI は正規の MediaStore のものになるが、画像として分類されなければ
     * ギャラリーの画像一覧には出てこない (はず、という実験)。
     */
    private fun saveCropToFiles(crop: Bitmap): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val resolver = contentResolver
            val collection = MediaStore.Files.getContentUri("external")
            // 前回の切り抜きを消してから書く (常に1枚だけ残す)。
            // 画像コレクション側に残っている分も名前で消しておく
            try {
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(CROP_DISPLAY_NAME)
                )
            } catch (t: Throwable) {
                // 消せなくても続行する
            }
            try {
                resolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                    arrayOf(CROP_DISPLAY_NAME)
                )
            } catch (t: Throwable) {
                // 消せなくても続行する
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, CROP_DISPLAY_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/KakotteMuse")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            val out = resolver.openOutputStream(uri, "wt")
            if (out == null) {
                resolver.delete(uri, null, null)
                return null
            }
            out.use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "crop saved to MediaStore Files: $uri")
            uri
        } catch (t: Throwable) {
            Log.e(TAG, "Files save failed", t)
            null
        }
    }

    /** 切り抜きを MediaStore (Pictures/KakotteMuse) に固定名で保存する */
    private fun saveCropToMediaStore(crop: Bitmap): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val resolver = contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            // 前回の切り抜きを消してから書く (常に1枚だけ残す)
            try {
                resolver.delete(
                    collection,
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                    arrayOf(CROP_DISPLAY_NAME)
                )
            } catch (t: Throwable) {
                // 消せなくても続行する
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, CROP_DISPLAY_NAME)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KakotteMuse")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            val out = resolver.openOutputStream(uri, "wt")
            if (out == null) {
                resolver.delete(uri, null, null)
                return null
            }
            out.use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "crop saved to MediaStore: $uri")
            uri
        } catch (t: Throwable) {
            Log.e(TAG, "MediaStore save failed", t)
            null
        }
    }

    /** フォールバック: 内部キャッシュ + 自前プロバイダ経由 */
    private fun saveCropToProvider(crop: Bitmap): Uri? {
        return try {
            val uri = Uri.parse(Wire.SHARE_URI)
            val out = contentResolver.openOutputStream(uri, "wt") ?: return null
            out.use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "crop saved via provider: $uri")
            uri
        } catch (t: Throwable) {
            Log.e(TAG, "provider save failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "KakotteMuse"
        private const val CAPTURE_TIMEOUT_SEC = 15L
        private const val CROP_DISPLAY_NAME = "kakotte_crop.png"

        /** 画像に添える定型の質問 */
        private const val DEFAULT_QUESTION = "これ調べて"

        /** Aあボタン (翻訳) 用の質問 */
        private const val TRANSLATE_QUESTION = "翻訳して"
    }
}
