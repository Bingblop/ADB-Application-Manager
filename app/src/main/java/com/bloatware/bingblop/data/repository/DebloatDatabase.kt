package com.bloatware.bingblop.data.repository

import com.bloatware.bingblop.data.model.DebloatCategory
import com.bloatware.bingblop.data.model.DebloatInfo
import com.bloatware.bingblop.data.model.DebloatSafetyTier

object DebloatDatabase {

    private val presetList: List<DebloatInfo> = listOf(
        // ==========================================
        // SAMSUNG ONE UI
        // ==========================================
        DebloatInfo(
            packageName = "com.samsung.android.bixby.agent",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Bixby Voice Assistant Service",
            description = "Main Bixby voice agent service. Safe to disable if you do not use Samsung Bixby voice commands."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.bixby.wakeup",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Bixby Voice Wakeup Listener",
            description = "Always-listening background service for 'Hi Bixby' hotword triggers. Disabling saves battery."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.app.spage",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Samsung Free / Bixby Home Side Panel",
            description = "Left-most screen feed on One UI home screen containing sponsored news and games."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.game.gamehome",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Samsung Gaming Hub",
            description = "Game launcher and promotion portal with ad feeds and telemetry. Disabling does not affect normal gaming."
        ),
        DebloatInfo(
            packageName = "com.sec.android.app.sbrowser",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Samsung Internet Browser",
            description = "Stock One UI browser. Safe to remove if using Chrome, Firefox, or Brave."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.voc",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Samsung Members Diagnostics",
            description = "Customer community and telemetry app used for hardware self-diagnostics and promotional coupons."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.ardrawing",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "AR Doodle",
            description = "Augmented reality camera doodling tool. Safe to disable with no impact on core camera features."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.aremoji",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "AR Emoji Studio",
            description = "Generates cartoon avatars from camera photos. Bloatware for users not using AR avatars."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.livestickers",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Camera Live Stickers",
            description = "Face filters and sticker overlays in the Samsung Camera app."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.kidsinstaller",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Samsung Kids Installer",
            description = "Installer stub for Samsung Kids sandbox mode. Safe to disable if you do not use Kids Mode."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.dynamiclock",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Glance Dynamic Lock Screen Ads",
            description = "Cycles online promotional wallpapers on lock screen. Disabling eliminates background data usage."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.knox.analytics.uploader",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Knox Analytics Uploader",
            description = "Collects hardware usage telemetrics and uploads them to Samsung telemetry servers."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.beaconmanager",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.ADVANCED,
            summary = "SmartThings Find Beacon Manager",
            description = "Background BLE scanner for Galaxy SmartTags and SmartThings offline location tracking."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.themestore",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.ADVANCED,
            summary = "Galaxy Themes Store",
            description = "Theme, icon, and AOD store. Disabling prevents browsing or purchasing new Galaxy themes."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.samsungpass",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.ADVANCED,
            summary = "Samsung Pass Password Manager",
            description = "Biometric credentials vault. Safe if using Google Autofill, Bitwarden, or 1Password."
        ),
        DebloatInfo(
            packageName = "com.samsung.android.spay",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.ADVANCED,
            summary = "Samsung Wallet & Pay",
            description = "Contactless payment and digital boarding passes. Safe if you use Google Wallet instead."
        ),
        DebloatInfo(
            packageName = "com.sec.android.app.launcher",
            category = DebloatCategory.SAMSUNG,
            safetyTier = DebloatSafetyTier.UNSAFE,
            summary = "One UI Home Launcher",
            description = "Primary home screen and recents app switcher. DO NOT disable without a 3rd party launcher installed."
        ),

        // ==========================================
        // XIAOMI MIUI / HYPEROS
        // ==========================================
        DebloatInfo(
            packageName = "com.miui.analytics",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "MIUI Analytics & Telemetry",
            description = "Xiaomi tracking service sending user interaction logs to analytics servers. Highly recommended to disable."
        ),
        DebloatInfo(
            packageName = "com.miui.msa.global",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "MIUI System Ads (MSA)",
            description = "Background ad-serving daemon injecting advertisements into system apps and notifications."
        ),
        DebloatInfo(
            packageName = "com.miui.yellowpage",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Xiaomi Yellow Pages",
            description = "Spam caller lookup and business directory suggestions in phone dialer. Useless outside China/India."
        ),
        DebloatInfo(
            packageName = "com.xiaomi.mipicks",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "GetApps Store",
            description = "Xiaomi proprietary third-party app store pushing unsolicited app download suggestions."
        ),
        DebloatInfo(
            packageName = "com.miui.bugreport",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "MIUI Feedback & Bug Reporter",
            description = "Automated crash dump and user feedback uploader. Safe to disable."
        ),
        DebloatInfo(
            packageName = "com.miui.cleanmaster",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Cheetah Mobile Cleaner Engine",
            description = "Third-party bundled junk cleaner module inside Security app with telemetry tracking."
        ),
        DebloatInfo(
            packageName = "com.miui.hybrid",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Quick Apps Platform",
            description = "WeChat-style mini-program engine. Noted for aggressive background data requests."
        ),
        DebloatInfo(
            packageName = "com.miui.videoplayer",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Mi Video Player",
            description = "Default video player integrated with promotional TikTok-like short video feed."
        ),
        DebloatInfo(
            packageName = "com.miui.player",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Mi Music Player",
            description = "Stock music player integrated with streaming service upsells. Safe to replace with VLC or Spotify."
        ),
        DebloatInfo(
            packageName = "com.xiaomi.joyose",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.ADVANCED,
            summary = "Joyose Game Optimization",
            description = "Throttling service that limits CPU/GPU clock speeds under thermal loads. Gamers often disable it."
        ),
        DebloatInfo(
            packageName = "com.miui.home",
            category = DebloatCategory.XIAOMI,
            safetyTier = DebloatSafetyTier.UNSAFE,
            summary = "MIUI System Launcher",
            description = "Core launcher and gesture navigation engine. Disabling causes black screen / navigation failure."
        ),

        // ==========================================
        // GOOGLE / AOSP
        // ==========================================
        DebloatInfo(
            packageName = "com.google.android.apps.tachyon",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Google Meet",
            description = "Preloaded video calling and conference app. Safe to disable if you do not use Meet."
        ),
        DebloatInfo(
            packageName = "com.google.android.videos",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Google TV (Play Movies)",
            description = "Movie rental and streaming guide app. Safe to disable."
        ),
        DebloatInfo(
            packageName = "com.google.android.music",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Google Play Music Stub",
            description = "Discontinued Google Play Music redirect stub."
        ),
        DebloatInfo(
            packageName = "com.google.android.apps.docs",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Google Drive / Docs",
            description = "Google cloud office and file storage viewer."
        ),
        DebloatInfo(
            packageName = "com.google.android.apps.magazines",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Google News",
            description = "News aggregation and magazine subscriptions."
        ),
        DebloatInfo(
            packageName = "com.google.android.apps.subscriptions.red",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Google One Storage Management",
            description = "Storage upgrade prompts and device backup manager for Google One subscribers."
        ),
        DebloatInfo(
            packageName = "com.google.android.apps.photos",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.ADVANCED,
            summary = "Google Photos",
            description = "Cloud photo backup and gallery. Safe if using local Gallery or another cloud sync."
        ),
        DebloatInfo(
            packageName = "com.google.android.googlequicksearchbox",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.ADVANCED,
            summary = "Google App & Assistant",
            description = "Powers the Google search bar widget, Discover feed, and Google Assistant voice recognition."
        ),
        DebloatInfo(
            packageName = "com.google.android.feedback",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Google Market Feedback Agent",
            description = "Crash and ANR diagnostics reporter for Google Play apps."
        ),
        DebloatInfo(
            packageName = "com.google.android.youtube",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "YouTube",
            description = "Official YouTube client. Often debloated when using NewPipe or Revanced."
        ),
        DebloatInfo(
            packageName = "com.google.android.gms",
            category = DebloatCategory.GOOGLE,
            safetyTier = DebloatSafetyTier.UNSAFE,
            summary = "Google Play Services",
            description = "CRITICAL: Provides push notifications, location APIs, SafetyNet, and in-app purchases. DO NOT REMOVE."
        ),

        // ==========================================
        // CARRIER BLOAT
        // ==========================================
        DebloatInfo(
            packageName = "com.vzw.hss.myverizon",
            category = DebloatCategory.CARRIER,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "My Verizon Account",
            description = "Verizon carrier billing, promotional alerts, and usage tracking app."
        ),
        DebloatInfo(
            packageName = "com.verizon.mips.services",
            category = DebloatCategory.CARRIER,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Verizon Telemetry & Location Services",
            description = "Carrier diagnostic logging daemon collecting telemetry on network handoffs."
        ),
        DebloatInfo(
            packageName = "com.att.myWireless",
            category = DebloatCategory.CARRIER,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "myAT&T Portal",
            description = "AT&T carrier account manager with push notifications for data plans and upgrades."
        ),
        DebloatInfo(
            packageName = "com.att.dh",
            category = DebloatCategory.CARRIER,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "AT&T Device Help",
            description = "Carrier diagnostic and support agent with background telemetry."
        ),
        DebloatInfo(
            packageName = "com.asurion.android.mobilerecovery.att",
            category = DebloatCategory.CARRIER,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "AT&T Mobile Insurance Stub",
            description = "Preloaded insurance marketing app from Asurion."
        ),
        DebloatInfo(
            packageName = "com.tmobile.pr.mytmobile",
            category = DebloatCategory.CARRIER,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "T-Mobile Account",
            description = "T-Mobile carrier app pushing plan notifications and device upgrade promos."
        ),
        DebloatInfo(
            packageName = "com.tmobile.pr.adapt",
            category = DebloatCategory.CARRIER,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "T-Mobile Diagnostics (Adapt)",
            description = "Diagnostic data collector sending device health and network logs to T-Mobile."
        ),
        DebloatInfo(
            packageName = "com.sprint.ms.smf",
            category = DebloatCategory.CARRIER,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Sprint Mobile Framework",
            description = "Legacy Sprint carrier monitoring service."
        ),

        // ==========================================
        // ANALYTICS & TELEMETRY
        // ==========================================
        DebloatInfo(
            packageName = "com.facebook.katana",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Facebook Preloaded App",
            description = "Pre-installed social app. Safe to disable or uninstall."
        ),
        DebloatInfo(
            packageName = "com.facebook.services",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Facebook Background Services",
            description = "Silent background telemetry sync for Meta apps. Highly recommended to freeze."
        ),
        DebloatInfo(
            packageName = "com.facebook.system",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Facebook System App Installer",
            description = "Privileged installer stub capable of updating Meta apps outside Google Play."
        ),
        DebloatInfo(
            packageName = "com.facebook.appmanager",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Facebook App Manager",
            description = "Background daemon coordinating Meta tracking and asset caching."
        ),
        DebloatInfo(
            packageName = "com.amazon.appmanager",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Amazon App Manager",
            description = "Background service managing preloaded Amazon shopping apps."
        ),
        DebloatInfo(
            packageName = "com.amazon.mShop.android.shopping",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Amazon Shopping",
            description = "Preloaded retail shopping client."
        ),
        DebloatInfo(
            packageName = "com.microsoft.appmanager",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.ADVANCED,
            summary = "Link to Windows Service",
            description = "Bridges phone notifications, photos, and calls to Windows PC Phone Link."
        ),
        DebloatInfo(
            packageName = "com.microsoft.skydrive",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Microsoft OneDrive",
            description = "Cloud storage sync preloaded on Samsung devices. Safe if you do not use OneDrive."
        ),
        DebloatInfo(
            packageName = "com.linkedin.android",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "LinkedIn Client",
            description = "Pre-installed professional network client."
        ),
        DebloatInfo(
            packageName = "com.netflix.partner.activation",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Netflix Partner Activation",
            description = "OEM carrier partnership stub verifying Netflix subscription privileges."
        ),
        DebloatInfo(
            packageName = "com.spotify.music",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Spotify Music",
            description = "Pre-installed audio streaming app."
        ),
        DebloatInfo(
            packageName = "com.booking",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "Booking.com Travel",
            description = "Preloaded commercial travel booking app."
        ),
        DebloatInfo(
            packageName = "com.ebay.carrier",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "eBay Carrier Stub",
            description = "Preloaded auction shopping stub."
        ),
        DebloatInfo(
            packageName = "com.ss.android.ugc.trill",
            category = DebloatCategory.TELEMETRY,
            safetyTier = DebloatSafetyTier.SAFE,
            summary = "TikTok OEM Preload",
            description = "Preloaded short-video client."
        )
    )

    private val presetMap: Map<String, DebloatInfo> = presetList.associateBy { it.packageName }

    fun find(packageName: String): DebloatInfo? = presetMap[packageName]

    fun getAllPresets(): List<DebloatInfo> = presetList

    fun getCategoryPresets(category: DebloatCategory): List<DebloatInfo> {
        if (category == DebloatCategory.ALL) return presetList
        return presetList.filter { it.category == category }
    }
}
