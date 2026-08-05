package studio.koeda.norrklang.media

import android.content.Context
import android.net.Uri

/**
 * `android.resource://` URI for a bundled drawable, usable wherever a car
 * media host fetches icons by URI (tab artwork, browse-action icons).
 *
 * Must be the `package/type/name` form: hosts resolve icon URIs through
 * `Resources.getIdentifier`, which never matches the numeric `package/resId`
 * form.
 */
internal fun Context.resourceUri(resId: Int): Uri =
    Uri.parse(
        "android.resource://$packageName/" +
            "${resources.getResourceTypeName(resId)}/${resources.getResourceEntryName(resId)}",
    )
