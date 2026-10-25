package com.nutrix.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrix.app.BuildConfig
import com.nutrix.app.R
import com.nutrix.app.core.model.ThemeMode
import com.nutrix.app.core.model.WeightUnit
import com.nutrix.app.core.ui.components.IconBadge
import com.nutrix.app.core.ui.components.InfoBanner
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.NutrixTextField
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.components.SectionHeader
import com.nutrix.app.core.ui.theme.LocalCategoryColors
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.util.Units
import com.nutrix.app.feature.lock.canUseBiometrics

data class ProfileActions(
    val onEditProfile: () -> Unit,
    val onFoodDiary: () -> Unit,
    val onWater: () -> Unit,
    val onWeight: () -> Unit,
    val onSymptoms: () -> Unit,
    val onSleepActivity: () -> Unit,
    val onIntegrations: () -> Unit,
    val onReminders: () -> Unit,
    val onSecurity: () -> Unit,
    val onBackup: () -> Unit,
    val onReports: () -> Unit,
    val onAbout: () -> Unit,
)

@Composable
fun ProfileScreen(contentPadding: PaddingValues, actions: ProfileActions, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalCategoryColors.current
    var deleteDialog by rememberSaveable { mutableStateOf(false) }
    val settings = s ?: return
    NutrixScreen(title = stringResource(R.string.profile_title), onBack = null) { padding ->
        ScreenList(PaddingValues(top = padding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())) {
            item {
                NutrixCard(onClick = actions.onEditProfile) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Person, MaterialTheme.colorScheme.primary, size = 48.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(settings.userName.ifBlank { stringResource(R.string.profile_edit) }, style = MaterialTheme.typography.titleLarge)
                            val unit = stringResource(if (settings.weightUnit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
                            val details = listOfNotNull(
                                settings.heightCm?.let { "$it ${stringResource(R.string.unit_cm)}" },
                                settings.targetWeightKg?.let { "${stringResource(R.string.weight_goal)}: ${Units.formatWeight(it, settings.weightUnit, unit)}" },
                            ).joinToString(" · ")
                            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.settings_section_diaries)) }
            item {
                NutrixCard(contentPadding = 6.dp) {
                    NavRow(Icons.Rounded.Restaurant, colors.meal, stringResource(R.string.settings_food_diary), actions.onFoodDiary)
                    NavRow(Icons.Rounded.WaterDrop, colors.water, stringResource(R.string.settings_water), actions.onWater)
                    NavRow(Icons.Rounded.MonitorWeight, colors.weight, stringResource(R.string.settings_weight), actions.onWeight)
                    NavRow(Icons.Rounded.Healing, colors.symptom, stringResource(R.string.settings_symptoms), actions.onSymptoms)
                    NavRow(Icons.Rounded.Bedtime, colors.sleep, stringResource(R.string.settings_sleep_activity), actions.onSleepActivity)
                }
            }
            item { SectionHeader(stringResource(R.string.settings_section_data)) }
            item {
                NutrixCard(contentPadding = 6.dp) {
                    NavRow(Icons.Rounded.Favorite, colors.heart, stringResource(R.string.settings_integrations), actions.onIntegrations)
                    NavRow(Icons.Rounded.Notifications, colors.activity, stringResource(R.string.settings_reminders), actions.onReminders)
                    NavRow(Icons.AutoMirrored.Rounded.Assignment, colors.water, stringResource(R.string.settings_reports), actions.onReports)
                    NavRow(Icons.Rounded.Backup, colors.weight, stringResource(R.string.settings_backup), actions.onBackup)
                    NavRow(Icons.Rounded.Lock, MaterialTheme.colorScheme.primary, stringResource(R.string.settings_security), actions.onSecurity)
                }
            }
            item { SectionHeader(stringResource(R.string.settings_section_app)) }
            item {
                NutrixCard {
                    Text(stringResource(R.string.settings_units), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(settings.weightUnit == WeightUnit.KG, { viewModel.setUnit(WeightUnit.KG) }, label = { Text(stringResource(R.string.settings_units_kg)) })
                        FilterChip(settings.weightUnit == WeightUnit.LB, { viewModel.setUnit(WeightUnit.LB) }, label = { Text(stringResource(R.string.settings_units_lb)) })
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ThemeMode.DARK to R.string.settings_theme_dark, ThemeMode.LIGHT to R.string.settings_theme_light, ThemeMode.SYSTEM to R.string.settings_theme_system).forEach { (m, l) ->
                            FilterChip(settings.themeMode == m, { viewModel.setTheme(m) }, label = { Text(stringResource(l)) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(stringResource(R.string.settings_smart), stringResource(R.string.settings_smart_desc), settings.smartSuggestions, viewModel::setSmart)
                    SwitchRow(stringResource(R.string.settings_kcal), stringResource(R.string.settings_kcal_desc), settings.calorieEstimates, viewModel::setKcal)
                }
            }
            item {
                NutrixCard {
                    Text(stringResource(R.string.settings_ai_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.settings_ai_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                NutrixCard(contentPadding = 6.dp) {
                    NavRow(Icons.Rounded.Info, MaterialTheme.colorScheme.tertiary, stringResource(R.string.settings_about), actions.onAbout)
                    NavRow(Icons.Rounded.DeleteForever, MaterialTheme.colorScheme.error, stringResource(R.string.settings_delete_all)) { deleteDialog = true }
                }
            }
            item { InfoBanner(stringResource(R.string.medical_disclaimer), Icons.Rounded.Info) }
        }
    }
    if (deleteDialog) {
        var word by remember { mutableStateOf("") }
        val required = stringResource(R.string.settings_delete_all_word)
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text(stringResource(R.string.settings_delete_all_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_delete_all_text))
                    OutlinedTextField(word, { word = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { deleteDialog = false; viewModel.deleteAll() }, enabled = word.trim().equals(required, ignoreCase = true)) {
                    Text(stringResource(R.string.settings_delete_all_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun NavRow(icon: ImageVector, tint: Color, title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint, size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
fun ProfileEditScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val settings = s ?: return
    val unitLabel = stringResource(if (settings.weightUnit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    fun fmt(kg: Double?) = kg?.let { Formatters.decimal(Units.toDisplay(it, settings.weightUnit)) }.orEmpty()
    var name by rememberSaveable { mutableStateOf(settings.userName) }
    var height by rememberSaveable { mutableStateOf(settings.heightCm?.toString().orEmpty()) }
    var start by rememberSaveable { mutableStateOf(fmt(settings.startWeightKg)) }
    var target by rememberSaveable { mutableStateOf(fmt(settings.targetWeightKg)) }
    fun parse(t: String) = Units.parseDecimal(t)?.let { Units.toKg(it, settings.weightUnit) }?.takeIf { Units.isPlausibleWeightKg(it) }
    val heightValue = height.toIntOrNull()?.takeIf { it in 50..260 }
    val valid = (height.isBlank() || heightValue != null) && (start.isBlank() || parse(start) != null) && (target.isBlank() || parse(target) != null)
    NutrixScreen(
        title = stringResource(R.string.profile_edit),
        onBack = onBack,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = { viewModel.saveProfile(name, heightValue, parse(start), parse(target)); onBack() },
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).height(54.dp),
                ) { Text(stringResource(R.string.action_save)) }
            }
        },
    ) { padding ->
        ScreenList(padding, bottomExtra = 24.dp) {
            item {
                NutrixCard {
                    NutrixTextField(name, { name = it }, stringResource(R.string.profile_name))
                    Spacer(Modifier.height(8.dp))
                    NutrixTextField(height, { height = it.filter(Char::isDigit).take(3) }, stringResource(R.string.profile_height), keyboardType = KeyboardType.Number, suffix = stringResource(R.string.unit_cm))
                    Spacer(Modifier.height(8.dp))
                    NutrixTextField(start, { start = it }, stringResource(R.string.profile_start_weight), keyboardType = KeyboardType.Decimal, suffix = unitLabel)
                    Spacer(Modifier.height(8.dp))
                    NutrixTextField(target, { target = it }, stringResource(R.string.profile_target_weight), keyboardType = KeyboardType.Decimal, suffix = unitLabel)
                }
            }
        }
    }
}

@Composable
fun SecurityScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val hasPin by viewModel.hasPin.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pinDialog by rememberSaveable { mutableStateOf(false) }
    val settings = s ?: return
    NutrixScreen(title = stringResource(R.string.security_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item {
                NutrixCard {
                    Text(stringResource(R.string.security_lock), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.security_lock_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pinDialog = true }) {
                            Text(stringResource(if (hasPin && settings.lockEnabled) R.string.security_change_pin else R.string.security_set_pin))
                        }
                        if (settings.lockEnabled) OutlinedButton(onClick = viewModel::disableLock) { Text(stringResource(R.string.security_disable)) }
                    }
                }
            }
            if (settings.lockEnabled && hasPin) {
                item {
                    NutrixCard {
                        val bioAvailable = canUseBiometrics(context)
                        SwitchRow(
                            stringResource(R.string.security_biometric),
                            stringResource(if (bioAvailable) R.string.security_biometric_desc else R.string.security_biometric_unavailable),
                            settings.biometricEnabled && bioAvailable,
                            viewModel::setBiometric,
                            enabled = bioAvailable,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.security_timeout), style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 30, 60, 300).forEach { sec ->
                                FilterChip(
                                    settings.lockTimeoutSeconds == sec,
                                    { viewModel.setTimeout(sec) },
                                    label = {
                                        Text(
                                            when {
                                                sec == 0 -> stringResource(R.string.security_timeout_immediately)
                                                sec < 60 -> stringResource(R.string.security_timeout_seconds, sec)
                                                else -> stringResource(R.string.security_timeout_minutes, sec / 60)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                NutrixCard {
                    SwitchRow(stringResource(R.string.security_secure_screen), stringResource(R.string.security_secure_screen_desc), settings.secureScreen, viewModel::setSecureScreen)
                }
            }
        }
    }
    if (pinDialog) {
        var pin by remember { mutableStateOf("") }
        var repeat by remember { mutableStateOf("") }
        val validPin = pin.length in 4..8 && pin.all(Char::isDigit)
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text(stringResource(R.string.security_set_pin)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.security_pin_new)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    OutlinedTextField(
                        repeat, { repeat = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.security_pin_repeat)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), isError = repeat.isNotEmpty() && repeat != pin,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    if (repeat.isNotEmpty() && repeat != pin) Text(stringResource(R.string.security_pin_mismatch), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.setPin(pin); pinDialog = false }, enabled = validPin && pin == repeat) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    NutrixScreen(title = stringResource(R.string.about_title), onBack = onBack) { padding ->
        ScreenList(padding) {
            item {
                NutrixCard {
                    Text("NUTRIX", style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.about_offline), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.about_samsung_sdk, stringResource(if (BuildConfig.SAMSUNG_SDK_BUNDLED) R.string.about_sdk_bundled else R.string.about_sdk_missing)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                NutrixCard {
                    Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { InfoBanner(stringResource(R.string.medical_disclaimer), Icons.Rounded.Info) }
        }
    }
}
