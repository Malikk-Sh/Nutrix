/*
 * Vendored from androidx.compose.material:material-icons-extended 1.7.8 (only the icons NUTRIX uses).
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nutrix.app.core.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Rounded.FlashOff: ImageVector
    get() {
        if (_flashOff != null) {
            return _flashOff!!
        }
        _flashOff = materialIcon(name = "Rounded.FlashOff") {
            materialPath {
                moveTo(16.12f, 11.5f)
                curveToRelative(0.39f, -0.67f, -0.09f, -1.5f, -0.86f, -1.5f)
                horizontalLineToRelative(-1.87f)
                lineToRelative(2.28f, 2.28f)
                lineToRelative(0.45f, -0.78f)
                close()
                moveTo(16.28f, 3.45f)
                curveToRelative(0.33f, -0.67f, -0.15f, -1.45f, -0.9f, -1.45f)
                lineTo(8.0f, 2.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(0.61f)
                lineToRelative(6.13f, 6.13f)
                lineToRelative(3.15f, -6.29f)
                close()
                moveTo(18.44f, 17.88f)
                lineTo(4.12f, 3.56f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                lineTo(7.0f, 9.27f)
                lineTo(7.0f, 12.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(7.15f)
                curveToRelative(0.0f, 0.51f, 0.67f, 0.69f, 0.93f, 0.25f)
                lineToRelative(2.65f, -4.55f)
                lineToRelative(3.44f, 3.44f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                curveToRelative(0.4f, -0.39f, 0.4f, -1.02f, 0.01f, -1.41f)
                close()
            }
        }
        return _flashOff!!
    }

private var _flashOff: ImageVector? = null
