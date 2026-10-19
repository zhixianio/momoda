plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.momoda"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.momoda"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-m0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // 正式发布前改成自己的发布密钥；现在先用 debug 密钥，方便在真机上直接覆盖安装测试性能
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // 字体已经是压缩格式，不再压缩可以直接 mmap，加载更快
        noCompress += "ttf"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
