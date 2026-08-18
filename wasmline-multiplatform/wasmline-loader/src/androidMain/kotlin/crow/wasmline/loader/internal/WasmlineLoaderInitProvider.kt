package crow.wasmline.loader.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/** Initializes the loader cache path before [android.app.Application.onCreate]. */
class WasmlineLoaderInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val applicationCacheDirectory = context?.cacheDir
        if (applicationCacheDirectory == null) {
            Log.w(LOG_TAG, "Wasmline loader could not resolve the application cache directory.")
            return false
        }
        AndroidCacheResolver.initialize(applicationCacheDirectory)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val LOG_TAG: String = "WasmlineLoader"
    }
}
