package com.trackmaster.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.trackmaster.app.core.PowerMode
import com.trackmaster.app.utils.LanguageManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.R
import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.ui.design.currentPalette
import androidx.compose.foundation.layout.statusBarsPadding
import com.trackmaster.app.ui.design.LargeTitle
import com.trackmaster.app.ui.design.AppIcons
import com.trackmaster.app.ui.design.IconTile
import com.trackmaster.app.settings.FenceRelationshipRecalculator
import com.trackmaster.app.settings.PermissionSectionContent
import com.trackmaster.app.settings.TrackingToggleCard
import com.trackmaster.app.settings.LocationModeSection
import com.trackmaster.app.ui.design.DesignSecondaryButton

@Composable
internal fun RecalculateRelationshipsDialog(
    result: FenceRelationshipRecalculator.Result?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = currentPalette
    val hasResult = result != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (hasResult) R.string.recalculate_title else R.string.recalculate_relationships
                ),
                style = com.trackmaster.app.ui.design.AppType.title2
            )
        },
        text = {
            Column {
                when (result) {
                    null -> Text(
                        text = stringResource(R.string.recalculate_confirm),
                        style = com.trackmaster.app.ui.design.AppType.body
                    )

                    is FenceRelationshipRecalculator.Result.HasIntersections -> Text(
                        text = stringResource(
                            R.string.intersection_warning,
                            result.pairCount
                        ),
                        style = com.trackmaster.app.ui.design.AppType.body,
                        color = palette.danger
                    )

                    is FenceRelationshipRecalculator.Result.Success -> Text(
                        text = stringResource(
                            R.string.recalculate_success,
                            result.relationshipCount
                        ),
                        style = com.trackmaster.app.ui.design.AppType.body,
                        color = palette.success
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(
                        if (hasResult) R.string.ok else R.string.confirm
                    ),
                    color = palette.accent
                )
            }
        },
        dismissButton = if (hasResult) {
            null
        } else {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel), color = palette.labelSecondary)
                }
            }
        }
    )
}
