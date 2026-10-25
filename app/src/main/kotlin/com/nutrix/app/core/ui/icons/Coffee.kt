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

public val Icons.Rounded.Coffee: ImageVector
    get() {
        if (_coffee != null) {
            return _coffee!!
        }
        _coffee = materialIcon(name = "Rounded.Coffee") {
            materialPath {
                moveTo(18.5f, 3.0f)
                horizontalLineTo(6.0f)
                curveTo(4.9f, 3.0f, 4.0f, 3.9f, 4.0f, 5.0f)
                verticalLineToRelative(5.71f)
                curveToRelative(0.0f, 3.83f, 2.95f, 7.18f, 6.78f, 7.29f)
                curveToRelative(3.96f, 0.12f, 7.22f, -3.06f, 7.22f, -7.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(0.5f)
                curveToRelative(1.93f, 0.0f, 3.5f, -1.57f, 3.5f, -3.5f)
                reflectiveCurveTo(20.43f, 3.0f, 18.5f, 3.0f)
                close()
                moveTo(16.0f, 5.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(6.0f)
                verticalLineTo(5.0f)
                horizontalLineTo(16.0f)
                close()
                moveTo(18.5f, 8.0f)
                horizontalLineTo(18.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(0.5f)
                curveTo(19.33f, 5.0f, 20.0f, 5.67f, 20.0f, 6.5f)
                reflectiveCurveTo(19.33f, 8.0f, 18.5f, 8.0f)
                close()
                moveTo(5.0f, 19.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineTo(5.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveTo(4.0f, 19.45f, 4.45f, 19.0f, 5.0f, 19.0f)
                close()
            }
        }
        return _coffee!!
    }

private var _coffee: ImageVector? = null
