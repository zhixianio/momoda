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
        // CI 打 tag 发布时可以用 -PversionCode=… -PversionName=… 覆盖
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.0.1-m0"
        // 检查更新用的 GitHub Releases 接口，例如 https://api.github.com/repos/<用户>/momoda/releases/latest
        // 留空表示还没有发布地址，检查更新功能自动关闭
        buildConfigField("String", "UPDATE_API", "\"\"")
        // GitHub 同步登录用的 GitHub App 的 Client ID（公开信息，不是密钥）；留空则只能手动填 token
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"Iv23liK9KsMUubPabWgP\"") // GitHub App: momoda-sync-zhixianio
    }

    buildFeatures {
        buildConfig = true
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
    // 单测在 JVM 上跑，android.jar 里的 org.json 只是空壳，换成真实实现
    testImplementation("org.json:json:20240303")
}
