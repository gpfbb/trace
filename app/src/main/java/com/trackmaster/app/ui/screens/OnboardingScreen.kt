package com.trackmaster.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.trackmaster.app.AppLanguage
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.R
import com.trackmaster.app.MapProvider
import com.trackmaster.app.ui.theme.GlassButton
import com.trackmaster.app.ui.theme.Success
import com.trackmaster.app.ui.theme.primaryColor
import com.trackmaster.app.ui.theme.surfaceColor
import com.trackmaster.app.ui.theme.backgroundColor
import com.trackmaster.app.ui.theme.NeonAmber
import kotlinx.coroutines.launch

/**
 * Onboarding: 5 pages
 * 0: Language selection (3 languages)
 * 1: Map SDK recommendation
 * 2: Welcome
 * 3: Create fences
 * 4: Start tracking
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()

    // 5 pages
    val pagerState = rememberPagerState(pageCount = { 5 })

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(colors = listOf(surfaceColor(), backgroundColor())))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Skip button
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    prefs.isOnboardingCompleted = true
                    onComplete()
                }) {
                    Text(text = stringResource(R.string.skip), color = Color.Gray)
                }
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> LanguageSelectionPage(
                        prefs = prefs,
                        onSelected = { scope.launch { pagerState.animateScrollToPage(1) } }
                    )
                    1 -> MapSdkRecommendationPage(prefs = prefs)
                    2 -> OnboardingPage(
                        icon = Icons.Default.MyLocation,
                        title = stringResource(R.string.welcome_title),
                        description = stringResource(R.string.welcome_desc),
                        accentColor = primaryColor()
                    )
                    3 -> OnboardingPage(
                        icon = Icons.Default.Map,
                        title = stringResource(R.string.create_fence_title),
                        description = stringResource(R.string.create_fence_desc),
                        accentColor = NeonAmber
                    )
                    4 -> OnboardingPage(
                        icon = Icons.Default.Timeline,
                        title = stringResource(R.string.start_tracking_title),
                        description = stringResource(R.string.start_tracking_desc),
                        accentColor = Success
                    )
                }
            }

            // Page indicators (5 dots)
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(5) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (pagerState.currentPage == index) 24.dp else 8.dp, 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == index) primaryColor()
                                else primaryColor().copy(alpha = 0.3f)
                            )
                    )
                }
            }

            // Action buttons
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (pagerState.currentPage) {
                    0 -> {
                        // Language page: next button
                        GlassButton(
                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.next), color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White)
                        }
                    }
                    1 -> {
                        GlassButton(
                            onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.next), color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White)
                        }
                    }
                    2 -> {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        GlassButton(
                            onClick = {
                                if (hasPermission) {
                                    scope.launch { pagerState.animateScrollToPage(3) }
                                } else {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.grant_permission), color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(3) } }) {
                            Text(stringResource(R.string.grant_later), color = Color.Gray)
                        }
                    }
                    3 -> {
                        GlassButton(
                            onClick = { scope.launch { pagerState.animateScrollToPage(4) } },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.next), color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White)
                        }
                    }
                    4 -> {
                        GlassButton(
                            onClick = {
                                prefs.isOnboardingCompleted = true
                                onComplete()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.start_using), color = Color.White)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LanguageSelectionPage(prefs: AppPreferences, onSelected: () -> Unit = {}) {
    var selectedLang by remember { mutableStateOf(prefs.appLanguage) }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(primaryColor().copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Language, contentDescription = null, tint = primaryColor(), modifier = Modifier.size(64.dp))
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.select_language_title),
            fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.select_language_desc),
            fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center, lineHeight = 24.sp
        )

        Spacer(modifier = Modifier.height(48.dp))

        // 3 language options
        listOf(
            AppLanguage.CHINESE to R.string.chinese,
            AppLanguage.ENGLISH to R.string.english,
            AppLanguage.MALAY to R.string.malay
        ).forEach { (lang, labelRes) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedLang = lang
                        prefs.appLanguage = lang
                        onSelected()
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedLang == lang) Color(0x4DFF7F50) else Color(0x4D000000)
                ),
                shape = RoundedCornerShape(16.dp),
                border = if (selectedLang == lang) androidx.compose.foundation.BorderStroke(2.dp, primaryColor()) else null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (selectedLang == lang) Success else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        stringResource(labelRes),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MapSdkRecommendationPage(prefs: AppPreferences) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(NeonAmber.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Recommend, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(64.dp))
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.map_sdk_recommendation_title),
            fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0x4D000000)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Map, contentDescription = null, tint = Success, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.amap_recommendation),
                    fontSize = 15.sp, color = Color(0xFFE0E0E0), lineHeight = 22.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0x4D000000)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Public, contentDescription = null, tint = Color(0xFF4285F4), modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.google_recommendation),
                    fontSize = 15.sp, color = Color(0xFFE0E0E0), lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
private fun OnboardingPage(
    icon: ImageVector,
    title: String,
    description: String,
    accentColor: Color
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(64.dp))
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = title,
            fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = description,
            fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center, lineHeight = 24.sp
        )
    }
}
