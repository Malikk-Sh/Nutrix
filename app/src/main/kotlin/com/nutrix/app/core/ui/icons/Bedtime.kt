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

public val Icons.Rounded.Bedtime: ImageVector
    get() {
        if (_bedtime != null) {
            return _bedtime!!
        }
        _bedtime = materialIcon(name = "Rounded.Bedtime") {
            materialPath {
                moveTo(11.65f, 3.46f)
                curveToRelative(0.27f, -0.71f, -0.36f, -1.45f, -1.12f, -1.34f)
                curveToRelative(-5.52f, 0.8f, -9.47f, 6.07f, -8.34f, 11.88f)
                curveToRelative(0.78f, 4.02f, 4.09f, 7.21f, 8.14f, 7.87f)
                curveToRelative(3.74f, 0.61f, 7.16f, -0.87f, 9.32f, -3.44f)
                curveToRelative(0.48f, -0.57f, 0.19f, -1.48f, -0.55f, -1.62f)
                curveTo(13.08f, 15.66f, 9.42f, 9.27f, 11.65f, 3.46f)
                close()
            }
        }
        return _bedtime!!
    }

private var _bedtime: ImageVector? = null
