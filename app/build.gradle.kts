import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ANTHROPIC_API_KEY comes from local.properties (git-ignored) and is exposed via BuildConfig.
// Never commit a key. An empty value is allowed so the project still builds without one.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val anthropicApiKey: String = (localProps.getProperty("ANTHROPIC_API_KEY")
    ?: System.getenv("ANTHROPIC_API_KEY")
    ?: "")

android {
    namespace = "com.ioscastaway.crossappagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ioscastaway.crossappagent"
        // 30 = Android 11: AccessibilityService.takeScreenshot() and package visibility rules start here.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
        buildConfigField("String", "CLAUDE_MODEL", "\"claude-opus-5\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        // The Anthropic Java SDK pulls in Jackson + OkHttp; these META-INF entries collide on Android.
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/module-info.class",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Official Anthropic SDK (Java, used from Kotlin). Requires Java 8+; runs on Android with the
    // packaging excludes above. If it ever fails to load on device, swap ClaudeClient's implementation
    // for a raw OkHttp call — the agent only depends on the ClaudeClient interface.
    implementation(libs.anthropic.java)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
