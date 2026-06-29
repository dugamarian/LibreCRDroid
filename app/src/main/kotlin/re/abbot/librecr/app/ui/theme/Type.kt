package re.abbot.librecr.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import re.abbot.librecr.app.R

/**
 * Google Sans Rounded  — the two static TTFs
 * live in res/font. Compose synthesises the in-between weights (Medium/SemiBold)
 * from these two anchors.
 */
val GoogleSansRounded = FontFamily(
    Font(R.font.google_sans_rounded_regular, FontWeight.Normal),
    Font(R.font.google_sans_rounded_regular, FontWeight.Medium),
    Font(R.font.google_sans_rounded_bold, FontWeight.SemiBold),
    Font(R.font.google_sans_rounded_bold, FontWeight.Bold),
)

/** Material 3 typography with every standard role rendered in Google Sans Rounded. */
val AppTypography: Typography = Typography().run {
    fun TextStyle.rounded() = copy(fontFamily = GoogleSansRounded)
    Typography(
        displayLarge = displayLarge.rounded(),
        displayMedium = displayMedium.rounded(),
        displaySmall = displaySmall.rounded(),
        headlineLarge = headlineLarge.rounded(),
        headlineMedium = headlineMedium.rounded(),
        headlineSmall = headlineSmall.rounded(),
        titleLarge = titleLarge.rounded(),
        titleMedium = titleMedium.rounded(),
        titleSmall = titleSmall.rounded(),
        bodyLarge = bodyLarge.rounded(),
        bodyMedium = bodyMedium.rounded(),
        bodySmall = bodySmall.rounded(),
        labelLarge = labelLarge.rounded(),
        labelMedium = labelMedium.rounded(),
        labelSmall = labelSmall.rounded(),
    )
}
