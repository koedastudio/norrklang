package studio.koeda.norrklang.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import java.io.File
import java.io.IOException
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * Renders the Home and Library tabs' square button images — the static
 * [HomeTile] buttons and the dynamic catalog mix tiles alike: a collage of the covers actually
 * behind each button (2×2 when four distinct covers exist, a single
 * full-bleed cover otherwise), under a bottom scrim and a dark icon badge
 * that says what the button contains. Buttons with no covers — icon-only
 * [HomeTile]s (the Library tab) and collage sections with no content yet —
 * get an accent gradient with a large centered icon instead.
 *
 * Composed on demand by [ArtworkProvider] (paths `home/<key>` and
 * `home/<kind>/<key>`) and cached next to the plain cover files.
 */
internal object HomeButtonArtwork {

    /**
     * Cover-art ids for [tile]'s collage: the section's leading items, deduped
     * (several tracks can share one album cover), at most [COLLAGE_TILES].
     */
    suspend fun coverIds(
        tile: HomeTile,
        repository: MusicRepository,
        randomMix: RandomMixSession,
    ): List<String> = coverIds(tile.coverUrls(repository, randomMix))

    /**
     * Cover-art ids behind the given provider URIs, deduped, at most
     * [COLLAGE_TILES]. Ids are recovered from the domain models' provider
     * URIs, so everything returned here is already registered as fetchable.
     */
    fun coverIds(coverUrls: List<String>): List<String> =
        coverUrls
            .mapNotNull { Uri.parse(it).lastPathSegment }
            .distinct()
            .take(COLLAGE_TILES)

    /** Composes the button image from the given cover files into [target] (PNG). */
    fun render(
        context: Context,
        iconRes: Int,
        accentColor: Int,
        covers: List<File>,
        target: File,
    ) {
        val tiles = covers.mapNotNull { decode(it) }
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            when {
                tiles.size >= COLLAGE_TILES -> drawCollage(canvas, tiles)
                tiles.isNotEmpty() -> drawCover(canvas, tiles.first(), Rect(0, 0, SIZE, SIZE))
                else -> drawFallback(canvas, accentColor)
            }
            if (tiles.isEmpty()) {
                drawCenteredIcon(context, canvas, iconRes)
            } else {
                drawScrim(canvas)
                drawBadge(context, canvas, iconRes)
            }
            writeAtomically(bitmap, target)
        } finally {
            bitmap.recycle()
            tiles.forEach(Bitmap::recycle)
        }
    }

    private fun drawCollage(canvas: Canvas, tiles: List<Bitmap>) {
        val half = SIZE / 2
        tiles.take(COLLAGE_TILES).forEachIndexed { index, tile ->
            val left = (index % 2) * half
            val top = (index / 2) * half
            drawCover(canvas, tile, Rect(left, top, left + half, top + half))
        }
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap, dst: Rect) {
        val edge = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - edge) / 2
        val top = (bitmap.height - edge) / 2
        canvas.drawBitmap(bitmap, Rect(left, top, left + edge, top + edge), dst, coverPaint)
    }

    /** Diagonal accent gradient for sections that are still empty. */
    private fun drawFallback(canvas: Canvas, accentColor: Int) {
        val paint = Paint().apply {
            shader = LinearGradient(
                0f,
                0f,
                SIZE.toFloat(),
                SIZE.toFloat(),
                accentColor,
                darken(accentColor),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint)
    }

    /** Bottom gradient keeping the badge legible on busy cover art. */
    private fun drawScrim(canvas: Canvas) {
        val paint = Paint().apply {
            shader = LinearGradient(
                0f,
                SIZE * SCRIM_START,
                0f,
                SIZE.toFloat(),
                Color.TRANSPARENT,
                SCRIM_COLOR,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, SIZE * SCRIM_START, SIZE.toFloat(), SIZE.toFloat(), paint)
    }

    private fun drawBadge(context: Context, canvas: Canvas, iconRes: Int) {
        val cx = BADGE_MARGIN + BADGE_RADIUS
        val cy = SIZE - BADGE_MARGIN - BADGE_RADIUS
        canvas.drawCircle(
            cx.toFloat(),
            cy.toFloat(),
            BADGE_RADIUS.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_COLOR },
        )
        drawIcon(context, canvas, iconRes, cx, cy, (BADGE_RADIUS * 2 * BADGE_ICON_FRACTION).toInt())
    }

    private fun drawCenteredIcon(context: Context, canvas: Canvas, iconRes: Int) {
        drawIcon(context, canvas, iconRes, SIZE / 2, SIZE / 2, EMPTY_ICON_SIZE)
    }

    private fun drawIcon(context: Context, canvas: Canvas, iconRes: Int, cx: Int, cy: Int, size: Int) {
        val icon = context.getDrawable(iconRes) ?: return
        val half = size / 2
        icon.setBounds(cx - half, cy - half, cx + half, cy + half)
        icon.draw(canvas)
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / SIZE)
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun writeAtomically(bitmap: Bitmap, target: File) {
        val tmp = File.createTempFile("home-", ".part", target.parentFile)
        try {
            tmp.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Could not encode button image")
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Could not store button image")
        } finally {
            tmp.delete()
        }
    }

    private fun darken(color: Int): Int = Color.rgb(
        (Color.red(color) * DARKEN_FACTOR).toInt(),
        (Color.green(color) * DARKEN_FACTOR).toInt(),
        (Color.blue(color) * DARKEN_FACTOR).toInt(),
    )

    private val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private const val SIZE = 512

    /** Covers in a full collage; fewer means a single full-bleed cover. */
    const val COLLAGE_TILES = 4

    private const val SCRIM_START = 0.45f
    private const val SCRIM_COLOR = 0xB3000000.toInt()

    // Near-black with slight translucency, so the cover art still reads
    // through the badge edge while the white icon stays high-contrast.
    private const val BADGE_COLOR = 0xE61C1C1E.toInt()

    private const val BADGE_MARGIN = 28
    private const val BADGE_RADIUS = 56
    private const val BADGE_ICON_FRACTION = 0.58f
    private const val EMPTY_ICON_SIZE = 224
    private const val DARKEN_FACTOR = 0.45f
}
