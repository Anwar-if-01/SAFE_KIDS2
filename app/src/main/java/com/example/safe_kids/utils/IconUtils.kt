package com.example.safe_kids.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream

object IconUtils {

    /** Convert Drawable to compressed Base64 string for Firestore storage. */
    fun drawableToBase64(drawable: Drawable): String? {
        return try {
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val b = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(b)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                b
            }
            
            // Resize to 96x96 for list display (keep it small for Firestore)
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
            
            val outputStream = ByteArrayOutputStream()
            // Use WEBP or PNG with compression
            scaledBitmap.compress(Bitmap.CompressFormat.WEBP, 70, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** Convert Base64 string back to Bitmap for display. */
    fun base64ToBitmap(base64Str: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}
