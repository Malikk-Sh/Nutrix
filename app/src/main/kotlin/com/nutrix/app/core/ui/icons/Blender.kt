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

public val Icons.Rounded.Blender: ImageVector
    get() {
        if (_blender != null) {
            return _blender!!
        }
        _blender = materialIcon(name = "Rounded.Blender") {
            materialPath {
                moveTo(16.13f, 15.13f)
                lineToRelative(1.69f, -10.98f)
                curveTo(17.92f, 3.55f, 17.45f, 3.0f, 16.83f, 3.0f)
                horizontalLineTo(14.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-2.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                horizontalLineTo(5.0f)
                curveTo(3.9f, 3.0f, 3.0f, 3.9f, 3.0f, 5.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(2.23f)
                lineToRelative(0.64f, 4.13f)
                curveTo(6.74f, 16.05f, 6.0f, 17.43f, 6.0f, 19.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-1.0f)
                curveTo(18.0f, 17.43f, 17.26f, 16.05f, 16.13f, 15.13f)
                close()
                moveTo(5.0f, 9.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(1.31f)
                lineToRelative(0.62f, 4.0f)
                horizontalLineTo(5.0f)
                close()
                moveTo(12.0f, 19.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                reflectiveCurveTo(12.55f, 19.0f, 12.0f, 19.0f)
                close()
                moveTo(14.29f, 14.0f)
                horizontalLineTo(9.72f)
                lineTo(8.33f, 5.0f)
                horizontalLineToRelative(7.34f)
                lineTo(14.29f, 14.0f)
                close()
            }
        }
        return _blender!!
    }

private var _blender: ImageVector? = null
