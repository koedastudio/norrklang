package studio.koeda.norrklang.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import java.io.File
import java.io.IOException
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * Renders the Home and Library tabs' square button images — the static
 * [HomeTile] buttons and the dynamic mix tiles alike: a collage of the covers actually
 * behind each button (2×2 when four distinct covers exist, a single
 * full-bleed cover otherwise), under a dark icon badge that says what the
 * button contains. Buttons with no covers — icon-only
 * [HomeTile]s (the Library tab) and collage sections with no content yet —
 * get a uniform near-black fill with only an oversized faded icon bleeding
 * off the bottom-right corner — one quiet look for the whole Library list.
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
                else -> drawFallback(canvas)
            }
            if (tiles.isEmpty()) {
                drawMotif(context, canvas, iconRes)
            } else {
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

    /**
     * Uniform near-black fill behind icon-only tiles — deliberately close
     * to the car UIs' dark backgrounds, so the Library reads as quiet icon
     * rows rather than a stack of colour swatches.
     */
    private fun drawFallback(canvas: Canvas) {
        canvas.drawColor(EMPTY_TILE_COLOR)
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

    /**
     * The icon-only tiles' sole glyph: an oversized, faded copy of the icon
     * bleeding off the tile's bottom-right corner. Asymmetric on purpose:
     * some hosts show list artwork in a not-quite-square view with an
     * off-center crop (seen on the Polestar 2) — with no centered content
     * and no symmetric frame, there is nothing such a crop can visibly
     * misplace.
     */
    private fun drawMotif(context: Context, canvas: Canvas, iconRes: Int) {
        // mutate() before alpha: getDrawable instances share constant state,
        // and the collage badge's copy of the icon must stay opaque.
        val icon = context.getDrawable(iconRes)?.mutate() ?: return
        icon.alpha = MOTIF_ALPHA
        val center = (SIZE * MOTIF_CENTER).toInt()
        val half = MOTIF_SIZE / 2
        icon.setBounds(center - half, center - half, center + half, center + half)
        icon.draw(canvas)
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

    private val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private const val SIZE = 512

    /** Covers in a full collage; fewer means a single full-bleed cover. */
    const val COLLAGE_TILES = 4

    // Near-black with slight translucency, so the cover art still reads
    // through the badge edge while the white icon stays high-contrast.
    private const val BADGE_COLOR = 0xE61C1C1E.toInt()

    private const val BADGE_MARGIN = 28
    private const val BADGE_RADIUS = 56
    private const val BADGE_ICON_FRACTION = 0.58f

    // A shade off pure black, so the tile still separates (just) from a
    // true-black host background.
    private const val EMPTY_TILE_COLOR = 0xFF111113.toInt()

    // Motif square, its center as a fraction of both axes (past 0.5 so it
    // bleeds off the bottom-right edges), and its opacity out of 255.
    private const val MOTIF_SIZE = 460
    private const val MOTIF_CENTER = 0.8f
    private const val MOTIF_ALPHA = 46
}
