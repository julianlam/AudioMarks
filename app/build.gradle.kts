import java.util.Properties

plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
	id("org.jetbrains.kotlin.plugin.compose")
}

// Release keystore password lives in local.properties (gitignored)
val localProps = Properties().apply {
	rootProject.file("local.properties").inputStream().use { load(it) }
}
val releaseStorePassword = localProps.getProperty("RELEASE_STORE_PASSWORD", "")

android {
	namespace = "com.example.audiomarks"
	compileSdk = 36

	defaultConfig {
		applicationId = "com.example.audiomarks"
		minSdk = 36
		targetSdk = 36
		versionCode = 2
		versionName = "1.1.0"
	}

	// Debug keystore kept in .tools/ so it survives container resets
	// (~/.android, where Gradle would auto-generate one, is wiped)
	signingConfigs {
		create("projectDebug") {
			storeFile = rootProject.file(".tools/debug.keystore")
			storePassword = "android"
			keyAlias = "androiddebugkey"
			keyPassword = "android"
		}
		create("release") {
			storeFile = rootProject.file(".tools/release.keystore")
			storePassword = releaseStorePassword
			keyAlias = "audiomarks"
			keyPassword = releaseStorePassword
		}
	}

	buildTypes {
		debug {
			signingConfig = signingConfigs.getByName("projectDebug")
		}
		release {
			isMinifyEnabled = false
			signingConfig = signingConfigs.getByName("release")
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	kotlinOptions {
		jvmTarget = "17"
	}
	buildFeatures {
		compose = true
	}
}

dependencies {
	val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
	implementation(composeBom)
	implementation("androidx.compose.ui:ui")
	implementation("androidx.compose.ui:ui-tooling-preview")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.compose.material:material-icons-extended")
	implementation("androidx.activity:activity-compose:1.10.1")
	implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
	implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
	implementation("androidx.documentfile:documentfile:1.0.1")
	debugImplementation("androidx.compose.ui:ui-tooling")
}
