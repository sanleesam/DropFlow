package com.dropflow.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.R
import com.dropflow.android.BuildConfig
import com.dropflow.android.core.model.releaseLabel
import com.dropflow.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(modifier: Modifier = Modifier, onRefresh: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    androidx.compose.material3.TopAppBar(modifier = modifier, title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.ic_dropflow_logo), null, Modifier.size(32.dp))
            Text("DropFlow", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
            StatusPill(releaseLabel(BuildConfig.VERSION_NAME, false).uppercase())
        }
    }, navigationIcon = {
        if (onBack != null) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Settings", tint = IcyBlue)
        }
    }, actions = {
        if (onRefresh != null) IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, "Rescan devices", tint = IcyBlue)
        }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = TextPrimary))
}
