package com.dananz.window

import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.bold.Gear
import com.adamglin.phosphoricons.bold.SquaresFour
import com.adamglin.phosphoricons.fill.Cloud as CloudFill
import com.adamglin.phosphoricons.fill.CloudRain as CloudRainFill
import com.adamglin.phosphoricons.fill.MapPin
import com.adamglin.phosphoricons.fill.Sun as SunFill
import com.adamglin.phosphoricons.regular.Cloud
import com.adamglin.phosphoricons.regular.CloudRain
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.bold.Check
import com.adamglin.phosphoricons.regular.Sun
import com.adamglin.phosphoricons.fill.Lightbulb as LightbulbFill
import com.adamglin.phosphoricons.fill.LightbulbFilament as LightbulbFilamentFill
import com.adamglin.phosphoricons.fill.Snowflake as SnowflakeFill
import com.adamglin.phosphoricons.fill.Fire as FireFill
import com.adamglin.phosphoricons.regular.Lightbulb
import com.adamglin.phosphoricons.regular.LightbulbFilament
import com.adamglin.phosphoricons.regular.Snowflake
import com.adamglin.phosphoricons.regular.Fire
import com.adamglin.phosphoricons.regular.Fan
import com.adamglin.phosphoricons.fill.House as HouseFill
import com.adamglin.phosphoricons.fill.Television as TelevisionFill
import com.adamglin.phosphoricons.bold.CaretUp
import com.adamglin.phosphoricons.bold.CaretDown as CaretDownBold

// Phosphor, the icon set the prototype used
object Glyphs {
    val Gear = PhosphorIcons.Bold.Gear
    val Grid = PhosphorIcons.Bold.SquaresFour
    val Pin = PhosphorIcons.Fill.MapPin
    val Shuffle = PhosphorIcons.Regular.Shuffle
    val Repeat = PhosphorIcons.Regular.Repeat
    val Check = PhosphorIcons.Bold.Check

    val Up = PhosphorIcons.Bold.CaretUp
    val Down = PhosphorIcons.Bold.CaretDownBold
    val Fan = PhosphorIcons.Regular.Fan
    val Home = PhosphorIcons.Fill.HouseFill
    val Tv = PhosphorIcons.Fill.TelevisionFill

    fun bulb(on: Boolean, all: Boolean): ImageVector = when {
        all && on -> PhosphorIcons.Fill.LightbulbFilamentFill
        all -> PhosphorIcons.Regular.LightbulbFilament
        on -> PhosphorIcons.Fill.LightbulbFill
        else -> PhosphorIcons.Regular.Lightbulb
    }

    fun mode(mode: AcMode, fill: Boolean): ImageVector = when (mode) {
        AcMode.Cool -> if (fill) PhosphorIcons.Fill.SnowflakeFill else PhosphorIcons.Regular.Snowflake
        AcMode.Heat -> if (fill) PhosphorIcons.Fill.FireFill else PhosphorIcons.Regular.Fire
    }

    fun sky(sky: Sky, fill: Boolean): ImageVector = when (sky) {
        Sky.Clear -> if (fill) PhosphorIcons.Fill.SunFill else PhosphorIcons.Regular.Sun
        Sky.Cloudy -> if (fill) PhosphorIcons.Fill.CloudFill else PhosphorIcons.Regular.Cloud
        Sky.Rain -> if (fill) PhosphorIcons.Fill.CloudRainFill else PhosphorIcons.Regular.CloudRain
    }
}
