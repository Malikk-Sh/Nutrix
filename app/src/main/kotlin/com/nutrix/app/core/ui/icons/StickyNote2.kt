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
import kotlin.Deprecated

@Deprecated(
    "Use the AutoMirrored version at Icons.AutoMirrored.Rounded.StickyNote2",
    ReplaceWith( "Icons.AutoMirrored.Rounded.StickyNote2",
            "androidx.compose.material.icons.automirrored.rounded.StickyNote2"),
)
public val Icons.Rounded.StickyNote2: ImageVector
    get() {
        if (_stickyNote2 != null) {
            return _stickyNote2!!
        }
        _stickyNote2 = materialIcon(name = "Rounded.StickyNote2") {
            materialPath {
                moveTo(19.0f, 3.0f)
                horizontalLineTo(4.99f)
                curveTo(3.89f, 3.0f, 3.0f, 3.9f, 3.0f, 5.0f)
                lineToRelative(0.01f, 14.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
                horizontalLineToRelative(10.0f)
                lineToRelative(6.0f, -6.0f)
                verticalLineTo(5.0f)
                curveTo(21.0f, 3.9f, 20.1f, 3.0f, 19.0f, 3.0f)
                close()
                moveTo(8.0f, 8.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineTo(8.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveTo(7.0f, 8.45f, 7.45f, 8.0f, 8.0f, 8.0f)
                close()
                moveTo(11.0f, 14.0f)
                horizontalLineTo(8.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                curveTo(12.0f, 13.55f, 11.55f, 14.0f, 11.0f, 14.0f)
                close()
                moveTo(14.0f, 19.5f)
                verticalLineTo(15.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(4.5f)
                lineTo(14.0f, 19.5f)
                close()
            }
        }
        return _stickyNote2!!
    }

private var _stickyNote2: ImageVector? = null
