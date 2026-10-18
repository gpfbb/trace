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
internal fun SettingsMenuCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 彩色圆角方块里放白色图标：分组列表视觉，比裸图标更容易扫读
        com.trackmaster.app.ui.design.IconTile(
            icon = icon,
            containerColor = tint,
            tint = Color.White,
            size = 29.dp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                color = currentPalette.label
            )
            Text(
                description,
                fontSize = 13.sp,
                color = currentPalette.labelSecondary
            )
        }
        Icon(
            imageVector = AppIcons.chevronRight,
            contentDescription = null,
            tint = currentPalette.labelTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// SettingsScreen 保持向后兼容 - 作为对话框

@Composable
fun InfoSection() {
    val palette = currentPalette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 这是说明性文字，不是可点的操作项。用次级灰而不是强调色——
        // 强调色留给真正能点、需要吸引注意的东西。
        Text(
            stringResource(R.string.usage_instructions),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = palette.labelSecondary
        )
        Text(
            "1. ${stringResource(R.string.usage_tip_1)}\n2. ${stringResource(R.string.usage_tip_2)}\n3. ${stringResource(R.string.usage_tip_3)}\n4. ${stringResource(R.string.usage_tip_4)}",
            fontSize = 13.sp,
            color = palette.labelSecondary,
            lineHeight = 18.sp
        )
    }
}

