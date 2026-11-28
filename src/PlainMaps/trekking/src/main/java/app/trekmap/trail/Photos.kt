package app.trekmap.trail

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/** A photo taken during a trail: the image file name (in the trail's photo folder) and where/when. */
class Photo(val file: String, val time: Long, val lat: Double, val lon: Double, val ele: Double, val media: String) {
    fun toJson(): JSONObject = JSONObject().put("file", file).put("time", time)
        .put("lat", if (lat.isNaN()) JSONObject.NULL else lat).put("lon", if (lon.isNaN()) JSONObject.NULL else lon)
        .put("ele", if (ele.isNaN()) JSONObject.NULL else ele).put("media", media)

    val hasPosition get() = !lat.isNaN() && !lon.isNaN()

    companion object {
        fun from(j: JSONObject) = Photo(
            j.getString("file"), j.optLong("time"), j.optDouble("lat", Double.NaN), j.optDouble("lon", Double.NaN),
            j.optDouble("ele", Double.NaN), j.optString("media", ""),
        )
    }
}

/**
 * Trail photos. While recording they live in files/rec/photos (crash-safe, like the track); on save they
 * move to tracks/ID_photos. Every photo is also copied into the phone's Gallery (Pictures/Trekmap Trail).
 */
object Photos {
    const val THUMB = 192
    private const val INDEX = "photos.json"

    fun recDir(ctx: Context) = File(ctx.filesDir, "rec/photos")
    fun trackDir(id: String) = File(Store.dir(Kind.TRACK), "${id}_photos")
    fun dirOf(e: Entry) = if (e.kind == Kind.TRACK) trackDir(e.id) else null
    fun thumbFile(dir: File, p: Photo) = File(dir, "thumb_${p.file}")

    fun list(dir: File?): List<Photo> {
        if (dir == null) return emptyList()
        val f = File(dir, INDEX)
        val indexed = try {
            // one bad entry must not cost the others their positions
            if (f.exists()) JSONArray(f.readText()).let { a -> (0 until a.length()).mapNotNull { try { Photo.from(a.getJSONObject(it)) } catch (_: Exception) { null } } } else emptyList()
        } catch (e: Exception) {
            emptyList() // corrupt index (power loss / full storage): photos are recovered from the files below
        }.filter { File(dir, it.file).exists() }
        // photos on disk but missing from the index still belong to the trail (time from the file, no position)
        val known = indexed.map { it.file }.toHashSet()
        val orphans = (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".jpg") && !it.name.startsWith("thumb_") && it.length() > 0 && it.name !in known }
            .map { Photo(it.name, it.lastModified(), Double.NaN, Double.NaN, Double.NaN, "") }
        return (indexed + orphans).sortedBy { it.time }
    }

    fun count(e: Entry): Int = dirOf(e)?.list()?.count { it.endsWith(".jpg") && !it.startsWith("thumb_") } ?: 0

    @Synchronized
    fun add(dir: File, p: Photo) {
        dir.mkdirs()
        writeIndex(dir, list(dir).filter { it.file != p.file } + p)
    }

    /**
     * Removes a photo from its trail (image, thumbnail and index entry). The copy in the phone's
     * Gallery is not touched. For a saved trail, rewrite its GPX afterwards (Store.rewriteGpx).
     */
    @Synchronized
    fun remove(dir: File, p: Photo) {
        val rest = list(dir).filter { it.file != p.file }
        writeIndex(dir, rest) // index first: a crash in between leaves an unlisted file, never a broken entry
        File(dir, p.file).delete()
        thumbFile(dir, p).delete()
    }

    // write-then-rename: a crash or full storage mid-write never leaves an empty index
    private fun writeIndex(dir: File, all: List<Photo>) {
        val tmp = File(dir, "$INDEX.tmp")
        tmp.writeText(JSONArray(all.map { it.toJson() }).toString())
        if (!tmp.renameTo(File(dir, INDEX))) { File(dir, INDEX).delete(); tmp.renameTo(File(dir, INDEX)) }
    }

    /** The small thumbnail if it's already on disk (fast enough for the UI thread), else null. */
    fun cachedThumb(dir: File, p: Photo): Bitmap? {
        val t = thumbFile(dir, p)
        return if (t.exists()) BitmapFactory.decodeFile(t.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) else null
    }

    /** Recording finished and saved as track [id]: move the photos with it. */
    fun moveRecToTrack(ctx: Context, id: String): Int {
        val src = recDir(ctx)
        // decide by the image files, not the index (which may be damaged)
        val hasPhotos = src.listFiles()?.any { it.name.endsWith(".jpg") && !it.name.startsWith("thumb_") && it.length() > 0 } == true
        if (!src.exists() || !hasPhotos) { src.deleteRecursively(); return 0 }
        val dst = trackDir(id)
        if (!src.renameTo(dst)) { src.copyRecursively(dst, overwrite = true); src.deleteRecursively() }
        return list(dst).size
    }

    /** Saving track [id] failed after [moveRecToTrack]: the photos go back to the recording that goes on. */
    fun moveTrackToRec(ctx: Context, id: String) {
        val src = trackDir(id)
        if (!src.exists()) return
        try {
            val dst = recDir(ctx)
            if (dst.exists() || !src.renameTo(dst)) { src.copyRecursively(dst, overwrite = true); src.deleteRecursively() }
        } catch (e: Exception) {
            Dev.event("photo", "could not move photos back to the recording: $e")
        }
    }

    fun deleteRec(ctx: Context) { recDir(ctx).deleteRecursively() }

    // ------------------------------------------------------------------ images
    /** Decodes [f] no larger than [maxPx] on its long side, upright according to its EXIF orientation. */
    fun decode(f: File, maxPx: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        if (b.outWidth <= 0) return null
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = config }) ?: return null
        val deg = try {
            when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        val scale = min(1f, maxPx.toFloat() / max(bmp.width, bmp.height))
        if (deg == 0f && scale >= 1f) return bmp
        val m = Matrix().apply { postScale(scale, scale); postRotate(deg) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    /** Square, centre-cropped thumbnail for the map pins. */
    fun makeThumb(src: File, dst: File) {
        val b = decode(src, THUMB * 2) ?: return
        val s = min(b.width, b.height)
        val sq = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
        val t = Bitmap.createScaledBitmap(sq, THUMB, THUMB, true)
        dst.outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 82, it) }
    }

    fun loadThumb(dir: File, p: Photo): Bitmap? {
        val t = thumbFile(dir, p)
        if (!t.exists()) try { makeThumb(File(dir, p.file), t) } catch (_: Exception) {}
        return BitmapFactory.decodeFile(t.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    /** Copies a photo into the Gallery (Pictures/Trekmap Trail). Returns its content URI, or "" before Android 10. */
    fun toGallery(ctx: Context, f: File, name: String, time: Long): String {
        if (Build.VERSION.SDK_INT < 29) return ""
        val cr = ctx.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Trekmap Trail")
            put(MediaStore.Images.Media.DATE_TAKEN, time)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv) ?: return ""
        try {
            cr.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri.toString()
    }

    fun galleryUri(p: Photo): Uri? = p.media.takeIf { it.isNotEmpty() }?.let(Uri::parse)

    // ------------------------------------------------------------------ export
    private fun shareFile(ctx: Context, name: String) =
        File(File(ctx.cacheDir, "share").also { it.mkdirs() }, name).also { f ->
            File(ctx.cacheDir, "share").listFiles()?.filter { it.name != f.name && !it.name.startsWith("trekmap-debug") }?.forEach { it.delete() }
        }

    private fun safe(name: String) = ShareProvider.fileName(name).removeSuffix(".gpx")

    /** ZIP: the GPX (photos as waypoints linking to photos/…) plus the original photos. */
    fun exportZip(ctx: Context, e: Entry): File {
        val out = shareFile(ctx, "${safe(e.name)}.zip")
        val dir = dirOf(e)
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("${safe(e.name)}.gpx"))
            Store.gpx(e.kind, e.id).inputStream().use { it.copyTo(z) }
            z.closeEntry()
            for (p in list(dir)) {
                z.putNextEntry(ZipEntry("photos/${p.file}"))
                File(dir, p.file).inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }

    /** KMZ for Google Earth: the track line plus a placemark per photo that shows the picture. */
    fun exportKmz(ctx: Context, e: Entry, route: Route): File {
        val out = shareFile(ctx, "${safe(e.name)}.kmz")
        val dir = dirOf(e)
        val photos = list(dir)
        fun f(v: Double) = String.format(Locale.US, "%.7f", v)
        fun esc(s: String) = GpxIo.esc(s) // also drops control characters XML forbids (a pasted name can hold them)
        val kml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
            append("<name>${esc(e.name)}</name>\n")
            append("<Style id=\"trk\"><LineStyle><color>fff6b629</color><width>4</width></LineStyle></Style>\n")
            append("<Style id=\"pic\"><IconStyle><scale>1.1</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/camera.png</href></Icon></IconStyle></Style>\n")
            append("<Placemark><name>${esc(e.name)}</name><styleUrl>#trk</styleUrl><MultiGeometry>\n")
            for (s in route.segs) {
                append("<LineString><tessellate>1</tessellate><coordinates>")
                for (i in 0 until s.size) {
                    append(f(s.lon[i])).append(',').append(f(s.lat[i]))
                    val el = s.ele?.get(i)
                    if (el != null && !el.isNaN()) append(',').append(String.format(Locale.US, "%.1f", el))
                    append(' ')
                }
                append("</coordinates></LineString>\n")
            }
            append("</MultiGeometry></Placemark>\n")
            for (p in photos) {
                if (!p.hasPosition) continue
                append("<Placemark><name>${esc(Fmt.date(p.time))}</name><styleUrl>#pic</styleUrl>")
                append("<description><![CDATA[<img src=\"photos/${p.file}\" width=\"480\"/>]]></description>")
                append("<Point><coordinates>${f(p.lon)},${f(p.lat)}</coordinates></Point></Placemark>\n")
            }
            append("</Document></kml>\n")
        }
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("doc.kml")); z.write(kml.toByteArray(Charsets.UTF_8)); z.closeEntry()
            for (p in photos) {
                // Google Earth doesn't need full resolution: 1600 px keeps the KMZ light
                val b = decode(File(dir, p.file), 1600) ?: continue
                z.putNextEntry(ZipEntry("photos/${p.file}"))
                b.compress(Bitmap.CompressFormat.JPEG, 85, z)
                z.closeEntry()
                b.recycle()
            }
        }
        return out
    }
}
