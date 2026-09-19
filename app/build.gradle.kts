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
val webAllowedHostSuffixes = providers.gradleProperty("ML_WEB_ALLOWED_HOST_SUFFIXES")
    .orElse("mission-leben.de,akademie-mission-leben.de")
val authentikAuthenticationFlowSlugs = providers.gradleProperty("ML_AUTHENTIK_AUTHENTICATION_FLOW_SLUGS")
    .orElse(
        "mission-leben-browser-authentication,mission-leben-zimbra-authentication," +
            "default-authentication-flow,nextcloud-akademie-kerberos-sso",
    )
val firebaseApplicationId = providers.gradleProperty("ML_FIREBASE_APPLICATION_ID").orElse("")
val firebaseApiKey = providers.gradleProperty("ML_FIREBASE_API_KEY").orElse("")
val firebaseProjectId = providers.gradleProperty("ML_FIREBASE_PROJECT_ID").orElse("")
val firebaseSenderId = providers.gradleProperty("ML_FIREBASE_SENDER_ID").orElse("")

android {
    namespace = "de.missionleben.portal"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.missionleben.portal"
        minSdk = 33
        targetSdk = 36
        versionCode = 20
        versionName = "0.7.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Authorization stays inside PortalBrowserActivity; reserve AppAuth's receiver so it
        // cannot compete with the app's enrollment deep links.
        manifestPlaceholders["appAuthRedirectScheme"] = "de.missionleben.portal.appauth"

        buildConfigField("String", "AUTHENTIK_BASE_URL", authentikBaseUrl.get().asBuildConfigString())
        buildConfigField("String", "OIDC_ISSUER", oidcIssuer.get().asBuildConfigString())
        buildConfigField("String", "OIDC_CLIENT_ID", oidcClientId.get().asBuildConfigString())
        buildConfigField("String", "OIDC_REDIRECT_URI", "de.missionleben.portal:/oauth2redirect".asBuildConfigString())
        buildConfigField("String", "PORTAL_URL", "${authentikBaseUrl.get()}/if/user/".asBuildConfigString())
        buildConfigField("String", "DEVICE_SERVICE_BASE_URL", deviceServiceBaseUrl.get().asBuildConfigString())
        buildConfigField("String", "WEB_ALLOWED_HOST_SUFFIXES", webAllowedHostSuffixes.get().asBuildConfigString())
        buildConfigField(
            "String",
            "AUTHENTIK_AUTHENTICATION_FLOW_SLUGS",
            authentikAuthenticationFlowSlugs.get().asBuildConfigString(),
        )
        buildConfigField("String", "FIREBASE_APPLICATION_ID", firebaseApplicationId.get().asBuildConfigString())
        buildConfigField("String", "FIREBASE_API_KEY", firebaseApiKey.get().asBuildConfigString())
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseProjectId.get().asBuildConfigString())
        buildConfigField("String", "FIREBASE_SENDER_ID", firebaseSenderId.get().asBuildConfigString())
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

    val firebaseBom = platform("com.google.firebase:firebase-bom:34.19.0")
    implementation(firebaseBom)
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
