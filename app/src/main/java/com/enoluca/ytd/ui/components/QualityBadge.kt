package com.enoluca.ytd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enoluca.ytd.data.model.QualityTier
import com.enoluca.ytd.ui.theme.TierBest
import com.enoluca.ytd.ui.theme.TierHigh
import com.enoluca.ytd.ui.theme.TierLow
import com.enoluca.ytd.ui.theme.TierMedium

@Composable
fun QualityBadge(tier: QualityTier, modifier: Modifier = Modifier) {
    val (color, label) = when (tier) {
        QualityTier.BEST -> TierBest to "BEST"
        QualityTier.HIGH -> TierHigh to "HIGH"
        QualityTier.MEDIUM -> TierMedium to "MEDIUM"
        QualityTier.LOW -> TierLow to "LOW"
    }
    Text(
        text = label,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun EstimatedTag(modifier: Modifier = Modifier) {
    Text(
        text = "Estimated",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
