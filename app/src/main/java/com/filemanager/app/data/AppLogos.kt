package com.filemanager.app.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.filemanager.app.MainActivity
import com.filemanager.app.R

/**
 * One of the icons the app can show on the home screen.
 *
 * [background] and [foreground] are the adaptive icon's two layers, for
 * drawing it inside the app; the launcher is given the whole icon through
 * the logo's activity-alias.
 */
data class AppLogo(
    val number: Int,
    val name: String,
    @DrawableRes val background: Int,
    @DrawableRes val foreground: Int,
)

/**
 * The logos, and switching between them.
 *
 * Android has no call for changing an app's icon. What it does allow is an
 * app with several launcher entries, each with its own icon, turning them on
 * and off - so the manifest has one activity-alias per logo, all opening the
 * same activity, and exactly one is on at a time. The shapes themselves are
 * drawn by tools/app_logos.py, in this order.
 */
object AppLogos {

    val all: List<AppLogo> = listOf(
        logo(1, "Classic", R.drawable.ic_logo_01_background, R.drawable.ic_logo_01_foreground),
        logo(2, "Midnight", R.drawable.ic_logo_02_background, R.drawable.ic_logo_02_foreground),
        logo(3, "Paper", R.drawable.ic_logo_03_background, R.drawable.ic_logo_03_foreground),
        logo(4, "Sunset", R.drawable.ic_logo_04_background, R.drawable.ic_logo_04_foreground),
        logo(5, "Aurora", R.drawable.ic_logo_05_background, R.drawable.ic_logo_05_foreground),
        logo(6, "Mint", R.drawable.ic_logo_06_background, R.drawable.ic_logo_06_foreground),
        logo(7, "Grape", R.drawable.ic_logo_07_background, R.drawable.ic_logo_07_foreground),
        logo(8, "Coral", R.drawable.ic_logo_08_background, R.drawable.ic_logo_08_foreground),
        logo(9, "Favourite", R.drawable.ic_logo_09_background, R.drawable.ic_logo_09_foreground),
        logo(10, "Gold", R.drawable.ic_logo_10_background, R.drawable.ic_logo_10_foreground),
        logo(11, "Documents", R.drawable.ic_logo_11_background, R.drawable.ic_logo_11_foreground),
        logo(12, "Archive", R.drawable.ic_logo_12_background, R.drawable.ic_logo_12_foreground),
        logo(13, "Cloud", R.drawable.ic_logo_13_background, R.drawable.ic_logo_13_foreground),
        logo(14, "Tiles", R.drawable.ic_logo_14_background, R.drawable.ic_logo_14_foreground),
        logo(15, "Emerald", R.drawable.ic_logo_15_background, R.drawable.ic_logo_15_foreground),
        logo(16, "Letter", R.drawable.ic_logo_16_background, R.drawable.ic_logo_16_foreground),
        logo(17, "Ink", R.drawable.ic_logo_17_background, R.drawable.ic_logo_17_foreground),
        logo(18, "Explorer", R.drawable.ic_logo_18_background, R.drawable.ic_logo_18_foreground),
        logo(19, "Charge", R.drawable.ic_logo_19_background, R.drawable.ic_logo_19_foreground),
        logo(20, "Storage", R.drawable.ic_logo_20_background, R.drawable.ic_logo_20_foreground),
    )

    private fun logo(number: Int, name: String, background: Int, foreground: Int) =
        AppLogo(number, name, background, foreground)

    /** The logo the home screen shows now. */
    fun current(context: Context): AppLogo {
        val pm = context.packageManager
        return all.firstOrNull { logo ->
            when (pm.getComponentEnabledSetting(component(context, logo))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                // Never switched: the manifest's own choice, which is logo 1.
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> logo.number == 1
                else -> false
            }
        } ?: all.first()
    }

    /**
     * Show [logo] on the home screen instead.
     *
     * The new entry is turned on before the old ones are turned off, so the
     * app is never left with no way to open it. The launcher redraws in its
     * own time - a few seconds on some - and a shortcut pinned to the home
     * screen may need putting back, since it pointed at the old entry.
     */
    fun select(context: Context, logo: AppLogo) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component(context, logo),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        all.filter { it != logo }.forEach {
            pm.setComponentEnabledSetting(
                component(context, it),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /**
     * The alias for [logo]. Named after the code's package, not the installed
     * one, because that is what the manifest's ".LogoNN" is relative to.
     */
    private fun component(context: Context, logo: AppLogo): ComponentName {
        val codePackage = MainActivity::class.java.name.substringBeforeLast('.')
        return ComponentName(context, "$codePackage.Logo%02d".format(logo.number))
    }
}
