package com.trevornk.ramblr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/** A square crop region: the largest centered square that fits inside a [width]x[height] image. */
data class CropRegion(val x: Int, val y: Int, val size: Int)

/** Pure image math, no Bitmap/Context dependency -- centers a square crop inside whatever
 *  rectangle the picked image happens to be, so [OverlayIconStore.save] never stretches it. */
fun centerCropRegion(width: Int, height: Int): CropRegion {
    val size = minOf(width, height)
    return CropRegion(x = (width - size) / 2, y = (height - size) / 2, size = size)
}

/**
 * Largest power-of-two `inSampleSize` that still keeps the decoded image at least [targetPx] on its
 * shorter side, so [OverlayIconStore.save] never fully decodes a 50-100MP photo into hundreds of MB
 * just to produce a small square icon -- a full-resolution decode is an uncatchable
 * [OutOfMemoryError] ([Error], not [Exception]), so it must be avoided, not caught (M15).
 */
fun overlayIconSampleSize(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    var sample = 1
    val shorter = minOf(width, height)
    while (shorter / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/**
 * Persists a user-chosen custom overlay icon (#43/#53) into app-private storage rather than
 * holding onto the photo picker's content:// [Uri] long-term -- that read grant is scoped to the
 * picker call and can't be relied on to survive a process restart, so the image is decoded and
 * copied to a local file exactly once, at pick time.
 */
object OverlayIconStore {
    private const val FILE_NAME = "overlay_icon.png"

    /** Ring sizes are clamped to [OverlayAppearancePrefs.MAX_RING_DP]; storing a fixed size well
     *  above the largest realistic on-screen density keeps the file small without needing to
     *  re-encode every time the user changes the icon size slider. */
    private const val STORED_SIZE_PX = 256

    fun file(context: Context) = context.filesDir.resolve(FILE_NAME)

    fun exists(context: Context): Boolean = file(context).exists()

    /**
     * Decodes [uri] and writes a center-cropped, fixed-size copy into app-private storage.
     * Returns false -- leaving any previously saved icon untouched -- if [uri] can't be read or
     * decoded as an image, so a bad pick never leaves the overlay with no icon at all.
     */
    fun save(context: Context, uri: Uri): Boolean {
        // First pass: read only the image's dimensions (inJustDecodeBounds allocates no pixels),
        // then decode subsampled so a huge photo never lands full-resolution in the heap (M15).
        val bounds = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    .also { BitmapFactory.decodeStream(input, null, it) }
            }
        } catch (e: Exception) {
            null
        } ?: return false
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val source = try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = overlayIconSampleSize(bounds.outWidth, bounds.outHeight, STORED_SIZE_PX)
            }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        } ?: return false

        return try {
            val crop = centerCropRegion(source.width, source.height)
            val square = Bitmap.createBitmap(source, crop.x, crop.y, crop.size, crop.size)
            val scaled = Bitmap.createScaledBitmap(square, STORED_SIZE_PX, STORED_SIZE_PX, true)
            file(context).outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(context: Context) {
        file(context).delete()
    }

    /** The persisted custom icon, or null if none was ever saved or the file's gone unreadable --
     *  callers fall back to the default mic glyph in either case (#43). */
    fun load(context: Context): Bitmap? =
        if (!exists(context)) null else try { BitmapFactory.decodeFile(file(context).path) } catch (e: Exception) { null }
}
