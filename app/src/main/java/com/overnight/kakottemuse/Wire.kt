package com.overnight.kakottemuse

/** アプリ内共有の定数。 */
object Wire {
    /** 切り抜き画像の受け渡し用プロバイダ (内部キャッシュの1ファイル) */
    const val SHARE_AUTHORITY = "com.overnight.kakottemuse.share"
    const val SHARE_URI = "content://$SHARE_AUTHORITY/crop.png"
}
