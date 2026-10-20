import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布签名：先看仓库根目录的 keystore.properties（不进 git），再看环境变量（GitHub Actions 用）。
// 都没有时 release 包退回 debug 密钥，只用于本机测试；Actions 发布时会校验，拒绝 debug 签名。
val releaseSigning: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties()
    if (file.exists()) file.inputStream().use { props.load(it) }
    fun value(prop: String, env: String) = props.getProperty(prop) ?: System.getenv(env)
    val storeFile = value("storeFile", "MOMODA_KEYSTORE_FILE") ?: return@run null
    mapOf(
        "storeFile" to storeFile,
        "storePassword" to (value("storePassword", "MOMODA_KEYSTORE_PASSWORD") ?: ""),
        "keyAlias" to (value("keyAlias", "MOMODA_KEY_ALIAS") ?: ""),
        "keyPassword" to (value("keyPassword", "MOMODA_KEY_PASSWORD") ?: ""),
    )
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
        // 检查更新用的 GitHub Releases 接口；留空则关闭检查更新
        buildConfigField("String", "UPDATE_API", "\"https://api.github.com/repos/zhixianio/momoda/releases/latest\"")
        // GitHub 同步登录用的 GitHub App 的 Client ID（公开信息，不是密钥）；留空则只能手动填 token
        // GitHub App：momoda-sync（公开，任何人都可以安装到自己的仓库）
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"Iv23liK9KsMUubPabWgP\"")
        buildConfigField("String", "GITHUB_APP_SLUG", "\"momoda-sync\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 注意：正式密钥和 debug 密钥签出来的包不能互相覆盖安装，换密钥前要先卸载
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // 单测里 android.util.Log 等桩方法返回默认值，不抛 "Stub!"（PasteServer 出错时会写日志）
        unitTests.isReturnDefaultValues = true
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
