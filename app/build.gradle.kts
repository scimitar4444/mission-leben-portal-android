import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val authentikBaseUrl = providers.gradleProperty("ML_AUTHENTIK_BASE_URL")
    .orElse("https://id.mission-leben.de")
val oidcIssuer = providers.gradleProperty("ML_OIDC_ISSUER")
    .orElse("${authentikBaseUrl.get()}/application/o/mission-leben-portal/")
val oidcClientId = providers.gradleProperty("ML_OIDC_CLIENT_ID")
    .orElse("mission-leben-android")
val deviceServiceBaseUrl = providers.gradleProperty("ML_DEVICE_SERVICE_BASE_URL")
    .orElse("https://id.mission-leben.de/device-bridge")
val enrollmentServiceBaseUrl = providers.gradleProperty("ML_ENROLLMENT_SERVICE_BASE_URL")
    .orElse("https://geraete.mission-leben.de")
val selfEnrollmentUrl = providers.gradleProperty("ML_SELF_ENROLLMENT_URL")
    .orElse("${enrollmentServiceBaseUrl.get().trimEnd('/')}/self")
val newsFeedUrl = providers.gradleProperty("ML_NEWS_FEED_URL")
    .orElse("https://www.mission-leben.de/rss.xml")
val newsPageUrl = providers.gradleProperty("ML_NEWS_PAGE_URL")
    .orElse("https://www.mission-leben.de/mission-leben-darmstadt/aktuelles-archiv-nachrichten")
val announcementsPageUrl = providers.gradleProperty("ML_ANNOUNCEMENTS_PAGE_URL")
    .orElse("https://nextcloud.mission-leben.de/apps/announcementcenter/")
val webAllowedHostSuffixes = providers.gradleProperty("ML_WEB_ALLOWED_HOST_SUFFIXES")
    .orElse("mission-leben.de,akademie-mission-leben.de")
val authentikAuthenticationFlowSlugs = providers.gradleProperty("ML_AUTHENTIK_AUTHENTICATION_FLOW_SLUGS")
    .orElse(
        "mission-leben-android-authentication,mission-leben-browser-authentication," +
            "mission-leben-zimbra-authentication," +
            "default-authentication-flow,nextcloud-akademie-kerberos-sso",
    )
val ntfyPublicBaseUrl = providers.gradleProperty("ML_NTFY_PUBLIC_BASE_URL")
    .orElse("https://push.mission-leben.de")
val updateManifestUrl =
    "https://github.com/scimitar4444/mission-leben-portal-android/" +
        "releases/latest/download/update.json"

val releaseStoreFile = providers.environmentVariable("ML_ANDROID_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ML_ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ML_ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ML_ANDROID_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
if (releaseSigningValues.any { !it.isNullOrBlank() } && releaseSigningValues.any { it.isNullOrBlank() }) {
    throw GradleException("All ML_ANDROID_KEYSTORE_* signing variables must be set together")
}
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }

android {
    namespace = "de.missionleben.portal"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.missionleben.portal"
        minSdk = 33
        targetSdk = 36
        versionCode = 50
        versionName = "0.11.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Authorization stays inside PortalBrowserActivity; reserve AppAuth's receiver so it
        // cannot compete with the app's enrollment deep links.
        manifestPlaceholders["appAuthRedirectScheme"] = "de.missionleben.portal.appauth"
        manifestPlaceholders["enrollmentScheme"] = "de.missionleben.portal"

        buildConfigField("String", "AUTHENTIK_BASE_URL", authentikBaseUrl.get().asBuildConfigString())
        buildConfigField("String", "OIDC_ISSUER", oidcIssuer.get().asBuildConfigString())
        buildConfigField("String", "OIDC_CLIENT_ID", oidcClientId.get().asBuildConfigString())
        buildConfigField("String", "OIDC_REDIRECT_URI", "de.missionleben.portal:/oauth2redirect".asBuildConfigString())
        buildConfigField("String", "PORTAL_URL", "${authentikBaseUrl.get()}/if/user/".asBuildConfigString())
        buildConfigField("String", "DEVICE_SERVICE_BASE_URL", deviceServiceBaseUrl.get().asBuildConfigString())
        buildConfigField(
            "String",
            "ENROLLMENT_SERVICE_BASE_URL",
            enrollmentServiceBaseUrl.get().asBuildConfigString(),
        )
        buildConfigField("String", "SELF_ENROLLMENT_URL", selfEnrollmentUrl.get().asBuildConfigString())
        buildConfigField("String", "NEWS_FEED_URL", newsFeedUrl.get().asBuildConfigString())
        buildConfigField("String", "NEWS_PAGE_URL", newsPageUrl.get().asBuildConfigString())
        buildConfigField(
            "String",
            "ANNOUNCEMENTS_PAGE_URL",
            announcementsPageUrl.get().asBuildConfigString(),
        )
        buildConfigField("String", "WEB_ALLOWED_HOST_SUFFIXES", webAllowedHostSuffixes.get().asBuildConfigString())
        buildConfigField(
            "String",
            "AUTHENTIK_AUTHENTICATION_FLOW_SLUGS",
            authentikAuthenticationFlowSlugs.get().asBuildConfigString(),
        )
        buildConfigField("String", "NTFY_PUBLIC_BASE_URL", ntfyPublicBaseUrl.get().asBuildConfigString())
        buildConfigField("String", "UPDATE_MANIFEST_URL", updateManifestUrl.asBuildConfigString())
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appAuthRedirectScheme"] = "de.missionleben.portal.debug.appauth"
            manifestPlaceholders["enrollmentScheme"] = "de.missionleben.portal.debug"
            buildConfigField(
                "String",
                "OIDC_REDIRECT_URI",
                "de.missionleben.portal.debug:/oauth2redirect".asBuildConfigString(),
            )
        }
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose 1.10.x is the newest stable line compatible with AGP 8.13/API 36.
    val composeBom = platform("androidx.compose:compose-bom:2026.01.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.openid:appauth:0.11.1")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
