/*
 * Adapted from KernelSU (https://github.com/tiann/KernelSU),
 * licensed under the Apache License, Version 2.0.
 */
package com.os4.musiccover.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A row in a menu.
 *
 * miuix's own DropdownImpl is built for choosing between options - it reserves room for a tick
 * and takes an isSelected. A menu of actions has nothing to select, so this is the same row
 * without that: the first and last items get more room against the popup's rounded ends, and the
 * ones between them are tighter.
 */
@Composable
fun DropdownItem(
    modifier: Modifier = Modifier,
    text: String,
    optionSize: Int,
    index: Int,
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    onSelectedIndexChange: (Int) -> Unit,
) {
    val currentOnSelectedIndexChange = rememberUpdatedState(onSelectedIndexChange)
    val additionalTopPadding = if (index == 0) 20f.dp else 12f.dp
    val additionalBottomPadding = if (index == optionSize - 1) 20f.dp else 12f.dp

    Row(
        modifier = modifier
            .clickable { currentOnSelectedIndexChange.value(index) }
            .background(dropdownColors.containerColor)
            .padding(horizontal = 20.dp)
            .padding(
                top = additionalTopPadding,
                bottom = additionalBottomPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            fontWeight = FontWeight.Medium,
            color = dropdownColors.contentColor,
        )
    }
}
