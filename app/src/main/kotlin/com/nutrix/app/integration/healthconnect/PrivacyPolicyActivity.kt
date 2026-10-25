package com.nutrix.app.integration.healthconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nutrix.app.R
import com.nutrix.app.core.model.ThemeMode
import com.nutrix.app.core.ui.components.NutrixCard
import com.nutrix.app.core.ui.components.NutrixScreen
import com.nutrix.app.core.ui.components.ScreenList
import com.nutrix.app.core.ui.theme.NutrixTheme

/**
 * Shown by Health Connect when the user asks why NUTRIX needs health permissions
 * (ACTION_SHOW_PERMISSIONS_RATIONALE / VIEW_PERMISSION_USAGE). Required for the permission dialog.
 */
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NutrixTheme(ThemeMode.SYSTEM) {
                NutrixScreen(title = stringResource(R.string.privacy_title), onBack = { finish() }) { padding ->
                    ScreenList(padding) {
                        item {
                            NutrixCard {
                                Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}
