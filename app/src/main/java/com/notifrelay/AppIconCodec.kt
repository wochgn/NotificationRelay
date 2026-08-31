package com.notifrelay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream

object AppIconCodec {
    private const val ICON_SIZE = 64
    private const val MAX_ENCODED_LENGTH = 12_000

    fun encode(context: Context, packageName: String): String? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawable.toBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, output)) null
            else Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                .takeIf { it.length <= MAX_ENCODED_LENGTH }
        } catch (_: Exception) {
            null
        }
    }

    fun decode(value: String): Bitmap? {
        if (value.length > MAX_ENCODED_LENGTH) return null
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
}
