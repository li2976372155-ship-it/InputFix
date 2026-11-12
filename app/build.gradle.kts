plugins {
    id("com.android.application")
}

android {
    namespace = "com.dsh.inputfix"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.inputfix"
        minSdk = 26
        // targetSdk 保持在 28：Android 16 上这是唯一还能让 App 写
        // /sdcard/Download 的配置（legacy storage）。见 docs/ARCHITECTURE.md
        targetSdk = 28
        versionCode = 5
        versionName = "1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }
}

// 无第三方依赖：
//   HTTP  —— 手写 ServerSocket
//   JSON  —— android.jar 自带的 org.json
dependencies {
}
