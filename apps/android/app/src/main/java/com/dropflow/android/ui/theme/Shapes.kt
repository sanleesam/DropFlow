package com.dropflow.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Concentric geometry: a child's radius is its parent's radius minus the inset
 * between them, so nested corners share one centre.
 */
object Radii {
    val Sheet = 30.dp
    val Inset = 6.dp
    val Card = 20.dp
    val Row = Card - Inset
    val Field = 10.dp
    val Tile = 9.dp

    fun concentric(parent: Dp, inset: Dp): Dp = (parent - inset).coerceAtLeast(0.dp)
}

object Shapes {
    val Capsule = RoundedCornerShape(percent = 50)
    val Card = RoundedCornerShape(Radii.Card)
    val Row = RoundedCornerShape(Radii.Row)
    val Field = RoundedCornerShape(Radii.Field)
    val Tile = RoundedCornerShape(Radii.Tile)
    val Sheet = RoundedCornerShape(topStart = Radii.Sheet, topEnd = Radii.Sheet)
}
