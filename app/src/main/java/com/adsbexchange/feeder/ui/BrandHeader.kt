package com.adsbexchange.feeder.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsbexchange.feeder.BuildConfig
import com.adsbexchange.feeder.R
import com.adsbexchange.feeder.ui.theme.Brand
import com.adsbexchange.feeder.ui.theme.DmSans

/** The adsbexchange.com header: logo mark and wordmark on black, with the product name in green. */
@Composable
fun BrandHeader(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Brand.Black).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painterResource(R.drawable.ic_adsbx_logo), contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Column {
                Text(
                    stringResource(R.string.brand_name), color = Brand.Text, fontFamily = DmSans,
                    fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 26.sp,
                )
                Text(
                    stringResource(R.string.brand_product), color = Brand.Green, fontFamily = DmSans,
                    fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 15.sp,
                )
            }
            if (BuildConfig.BETA) {
                // An outlined tag beside the name, in the product's green, until the full release.
                Text(
                    stringResource(R.string.beta_tag), color = Brand.Green, fontFamily = DmSans,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.border(1.5.dp, Brand.Green, RoundedCornerShape(5.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        HorizontalDivider(color = Brand.Panel)
    }
}
