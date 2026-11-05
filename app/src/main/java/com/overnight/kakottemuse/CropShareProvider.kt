package com.overnight.kakottemuse

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 切り抜き画像の受け渡し用プロバイダ。
 *
 * 実体は内部キャッシュの固定1ファイル (crop.png)。フック側
 * (SystemUI) がここへ書き込み、共有先 (Muse) がここから読む。
 * ギャラリーには出ず、増えもしない。
 */
class CropShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("no context")
        val file = cropFile(ctx)
        if (mode.contains("w")) {
            file.parentFile?.mkdirs()
        } else if (!file.isFile) {
            throw FileNotFoundException("crop not written yet")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun cropFile(ctx: android.content.Context): File = File(ctx.cacheDir, "crop.png")
}
