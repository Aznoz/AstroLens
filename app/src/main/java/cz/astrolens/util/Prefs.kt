package cz.astrolens.util

import android.content.Context
import android.content.SharedPreferences

/** Keys for persisted settings. */
object K {
    const val STARS = "layer_stars"
    const val STAR_NAMES = "layer_star_names"
    const val CONSTELLATIONS = "layer_constellations"
    const val CONSTELLATION_NAMES = "layer_constellation_names"
    const val PLANETS = "layer_planets"
    const val DSO = "layer_dso"
    const val DSO_NAMES = "layer_dso_names"
    const val MILKY_WAY = "layer_milky_way"
    const val HORIZON = "layer_horizon"
    const val ECLIPTIC = "layer_ecliptic"
    const val EQUATOR = "layer_equator"
    const val GRID_ALTAZ = "layer_grid_altaz"
    const val GRID_EQ = "layer_grid_eq"
    const val CROSSHAIR = "layer_crosshair"
    const val OVERLAY_ON = "overlay_on"
    const val MAG_AUTO = "mag_auto"
    const val MAG_LIMIT = "mag_limit"
    const val LABEL_DENSITY = "label_density"
    const val OVERLAY_ALPHA = "overlay_alpha"
    const val NIGHT_MODE = "night_mode"
    const val FOV_SCALE = "fov_scale"
    const val SMOOTHING = "smoothing"
    const val SENSOR_SOURCE = "sensor_source"
    const val USE_DECLINATION = "use_declination"
    const val LOC_AUTO = "loc_auto"
    const val LOC_LAT = "loc_lat"
    const val LOC_LON = "loc_lon"
    const val LENS_ID = "lens_id"
    const val ISO = "cam_iso"
    const val EXPOSURE_NS = "cam_exposure_ns"
    const val AUTO_EXPOSURE = "cam_auto_exposure"
    const val EV = "cam_ev"
    const val FOCUS_MANUAL = "cam_focus_manual"
    const val FOCUS_POS = "cam_focus_pos"
    const val AWB = "cam_awb"
    const val ZOOM = "cam_zoom"
    const val SAVE_JPEG = "cam_save_jpeg"
    const val SAVE_RAW = "cam_save_raw"
    const val JPEG_QUALITY = "cam_jpeg_quality"
    const val NOISE_REDUCTION = "cam_noise_reduction"
    const val OIS = "cam_ois"
    const val PREVIEW_MODE = "cam_preview_mode"
    const val TIMER = "cam_timer"
    const val SERIES_COUNT = "cam_series_count"
    const val SERIES_GAP = "cam_series_gap"
    const val ANNOTATED = "cam_annotated"
}

/** Thin typed wrapper over SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("astrolens", Context.MODE_PRIVATE)

    fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    fun int(key: String, def: Int): Int = sp.getInt(key, def)
    fun long(key: String, def: Long): Long = sp.getLong(key, def)
    fun float(key: String, def: Float): Float = sp.getFloat(key, def)
    fun string(key: String, def: String): String = sp.getString(key, def) ?: def

    fun put(key: String, v: Boolean) { sp.edit().putBoolean(key, v).apply() }
    fun put(key: String, v: Int) { sp.edit().putInt(key, v).apply() }
    fun put(key: String, v: Long) { sp.edit().putLong(key, v).apply() }
    fun put(key: String, v: Float) { sp.edit().putFloat(key, v).apply() }
    fun put(key: String, v: String) { sp.edit().putString(key, v).apply() }
}
