package app.gamenative.ui.theme

import androidx.compose.ui.graphics.Color
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults

// Your custom color scheme
val customBackground = Color(0xFF09090B)
val customForeground = Color(0xFFFAFAFA)
val customCard = Color(0xFF09090B)
val customCardForeground = Color(0xFFFAFAFA)
val customPrimary = Color(0xFFA21CAF)
val customPrimaryForeground = Color(0xFFFAFAFA)
val customSecondary = Color(0xFF27272A)
val customSecondaryForeground = Color(0xFFFAFAFA)
val customMuted = Color(0xFF27272A)
val customMutedForeground = Color(0xFF94969C)
val customAccent = Color(0xFF06B6D4)
val customAccentForeground = Color(0xFFFAFAFA)
val customDestructive = Color(0xFF7F1D1D)

val pluviaSeedColor = Color(0x284561FF)

/**
 * Raw color primitives for the Pluvia app.
 * These are the base colors used to construct theme palettes.
 */

// Brand
val PluviaPrimary = Color(0xFF98A9C4)
val PluviaSeed = Color(0x284561FF)

// Backgrounds
val PluviaBackground = Color(0xFF111522)
val PluviaSurface = Color(0xFF1E263B)
val PluviaSurfaceElevated = Color(0xFF303D59)
val PluviaCard = Color(0xFF111522)

// Foregrounds
val PluviaForeground = Color(0xFFDCE3EF)
val PluviaForegroundMuted = Color(0xFF98A9C4)

// Secondary
val PluviaSecondary = Color(0xFF303D59)

// Accents
val PluviaCyan = Color(0xFF00D4FF)
val PluviaPurple = Color(0xFF8B5CF6)
val PluviaPink = Color(0xFFEC4899)

// Semantic
val PluviaSuccess = Color(0xFF10B981)
val PluviaWarning = Color(0xFFF59E0B)
val PluviaDanger = Color(0xFFEF4444)
val PluviaDestructive = Color(0xFF7F1D1D)

// Border
val PluviaBorder = Color(0xFF303D59)

// Status - Installed/Download states
val StatusInstalled = Color(0xFF4CAF50)
val StatusDownloading = Color(0xFF00BCD4)
val StatusAvailable = Color(0xFF2196F3)
val StatusAway = Color(0xFFFF9800)
val StatusOffline = Color(0xFF9E9E9E)

// Friend states
val FriendOnline = Color(0xFF6DCFF6)
val FriendOffline = Color(0xFF7A7A7A)
val FriendInGame = Color(0xFF90BA3C)
val FriendAwayOrSnooze = Color(0x806DCFF6)
val FriendInGameAwayOrSnooze = Color(0x8090BA3C)
val FriendBlocked = Color(0xFF983D3D)

// WoW Forever: a six-step navy palette, darkest to lightest.
val PaletteInk = Color(0xFF111522)
val PaletteNight = Color(0xFF1E263B)
val PaletteSlate = Color(0xFF303D59)
val PaletteSteel = Color(0xFF536684)
val PaletteMist = Color(0xFF98A9C4)
val PaletteFrost = Color(0xFFDCE3EF)

val WowBackgroundGradient = listOf(PaletteInk, PaletteNight, PaletteInk)
/** Headings, links and highlights (the old gold). */
val WowGold = PaletteFrost
/** Filled action buttons (the old bronze). */
val WowBronze = PaletteSteel
val WowMuted = PaletteMist
val WowSubtle = Color(0xFF8394B1)
val WowError = Color(0xFFFC8181)

// Compatibility
val CompatibilityGood = Color(0xFF4CAF50)
val CompatibilityGoodBg = Color(0xFF1B5E20)
val CompatibilityPartial = Color(0xFF8BC34A)
val CompatibilityPartialBg = Color(0xFF33691E)
val CompatibilityUnknown = Color(0xFF9E9E9E)
val CompatibilityUnknownBg = Color(0xFF424242)
val CompatibilityBad = Color(0xFFEF5350)
val CompatibilityBadBg = Color(0xFFB71C1C)
