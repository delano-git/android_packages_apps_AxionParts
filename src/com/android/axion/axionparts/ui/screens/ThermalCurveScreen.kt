/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.axion.axionparts.ui.screens

import android.os.SystemProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.axion.axionparts.R
import com.android.axion.compose.preferences.ListPreference
import com.android.axion.compose.preferences.MainSwitchPreference
import com.android.axion.compose.preferences.PreferenceGroup
import com.android.axion.compose.preferences.SliderPreference
import com.android.axion.compose.scaffold.AxionScaffold

/**
 * User-defined throttling curve. The vendor daemon (ax_thermal_curve) reads these properties every
 * couple of seconds, so changes apply live; while it is enabled it replaces mi_thermald.
 *
 * Curves are "temp:percent,..." (degC, percent of the maximum frequency), sorted by temperature.
 * Here the temperatures are fixed anchors and the percentages are what the user sets.
 */
const val THERMAL_CURVE_SUPPORTED_PROPERTY = "ro.ax.thermal_curve.supported"
private const val SWITCH_PROPERTY = "persist.sys.ax.thermal_curve"
private const val CPU_PROPERTY = "persist.sys.ax.thermal_curve.cpu"
private const val GPU_PROPERTY = "persist.sys.ax.thermal_curve.gpu"

private val ANCHOR_TEMPS = listOf(40, 45, 50, 55, 60)
private const val MIN_PERCENT = 10
private const val MAX_PERCENT = 100
private const val PERCENT_STEP = 5

private const val PRESET_DEFAULT = "default"
private const val PRESET_COOLER = "cooler"
private const val PRESET_PERFORMANCE = "performance"
private const val PRESET_CUSTOM = "custom"

private val PRESETS =
    mapOf(
        PRESET_DEFAULT to listOf(100, 100, 90, 75, 55),
        PRESET_COOLER to listOf(100, 90, 75, 55, 40),
        PRESET_PERFORMANCE to listOf(100, 100, 100, 90, 75),
    )

@Composable
fun ThermalCurveScreen(onBackClick: (() -> Unit)? = null, showTopBar: Boolean = true) {
    if (showTopBar) {
        AxionScaffold(
            title = stringResource(R.string.thermal_curve),
            onBackClick = { onBackClick?.invoke() },
        ) { innerPadding ->
            ThermalCurveContent(modifier = Modifier.padding(innerPadding))
        }
    } else {
        ThermalCurveContent()
    }
}

@Composable
private fun ThermalCurveContent(modifier: Modifier = Modifier) {
    var enabled by remember { mutableStateOf(SystemProperties.get(SWITCH_PROPERTY, "0") == "1") }

    Column(
        modifier =
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        MainSwitchPreference(
            title = stringResource(R.string.thermal_curve_enable),
            summary = stringResource(R.string.thermal_curve_enable_summary),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                SystemProperties.set(SWITCH_PROPERTY, if (it) "1" else "0")
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        CurveGroup(
            title = stringResource(R.string.thermal_curve_cpu),
            property = CPU_PROPERTY,
            enabled = enabled,
        )

        Spacer(modifier = Modifier.height(12.dp))

        CurveGroup(
            title = stringResource(R.string.thermal_curve_gpu),
            property = GPU_PROPERTY,
            enabled = enabled,
        )

        Text(
            text = stringResource(R.string.thermal_curve_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        )

        Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

@Composable
private fun CurveGroup(title: String, property: String, enabled: Boolean) {
    val percents = remember { mutableStateListOf<Int>().apply { addAll(readCurve(property)) } }
    val preset = presetOf(percents)

    val presetOptions =
        listOf(
            PRESET_DEFAULT to stringResource(R.string.thermal_curve_preset_default),
            PRESET_COOLER to stringResource(R.string.thermal_curve_preset_cooler),
            PRESET_PERFORMANCE to stringResource(R.string.thermal_curve_preset_performance),
            PRESET_CUSTOM to stringResource(R.string.thermal_curve_preset_custom),
        )

    PreferenceGroup(title = title) {
        item {
            ListPreference(
                title = stringResource(R.string.thermal_curve_preset),
                summary = stringResource(R.string.thermal_curve_preset_summary),
                options = presetOptions,
                value = preset,
                enabled = enabled,
                onValueChange = { key ->
                    PRESETS[key]?.let { values ->
                        percents.clear()
                        percents.addAll(values)
                        writeCurve(property, percents)
                    }
                },
            )
        }
        ANCHOR_TEMPS.forEachIndexed { index, temp ->
            item {
                SliderPreference(
                    title = stringResource(R.string.thermal_curve_point, temp),
                    summary = stringResource(R.string.thermal_curve_point_summary),
                    value = percents[index].toFloat(),
                    onValueChange = { percents[index] = snap(it) },
                    onValueChangeFinished = { writeCurve(property, percents) },
                    valueRange = MIN_PERCENT.toFloat()..MAX_PERCENT.toFloat(),
                    steps = (MAX_PERCENT - MIN_PERCENT) / PERCENT_STEP - 1,
                    displayValue = "${percents[index]}%",
                    enabled = enabled,
                )
            }
        }
    }
}

private fun snap(value: Float): Int =
    (Math.round(value / PERCENT_STEP) * PERCENT_STEP).coerceIn(MIN_PERCENT, MAX_PERCENT)

/** The saved percentages for the anchor temperatures; the default curve when unset or unreadable. */
private fun readCurve(property: String): List<Int> {
    val raw = SystemProperties.get(property, "")
    if (raw.isEmpty()) return PRESETS.getValue(PRESET_DEFAULT)
    val byTemp =
        raw.split(",").mapNotNull {
            val parts = it.split(":")
            val t = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val p = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (t != null && p != null) t to p else null
        }
    if (byTemp.map { it.first } != ANCHOR_TEMPS) return PRESETS.getValue(PRESET_DEFAULT)
    return byTemp.map { it.second.coerceIn(MIN_PERCENT, MAX_PERCENT) }
}

private fun writeCurve(property: String, percents: List<Int>) {
    // Always writes the full list, in temperature order, as the daemon expects
    SystemProperties.set(
        property,
        ANCHOR_TEMPS.zip(percents).joinToString(",") { (t, p) -> "$t:$p" },
    )
}

private fun presetOf(percents: List<Int>): String =
    PRESETS.entries.firstOrNull { it.value == percents }?.key ?: PRESET_CUSTOM
