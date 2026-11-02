import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.impl.VariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息全部来自仓库根目录的 keystore.properties（不入版本库）。
// 该文件缺失时 release 不签名，由 scripts/release.sh 负责校验并给出指引。
val keystoreProps: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "com.tvboard.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvboard.ime"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 不覆盖 applicationId，避免出现两个输入法条目干扰真机调试
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        // API 21：java.time / java.util.stream / Optional 必须靠脱糖补齐，见设计文档 6.3
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.12.0")

    // 二维码编码：纯 Java，无 Android 依赖
    implementation("com.google.zxing:core:3.5.3")

    // 极简 HTTP 服务：内含 multipart 解析
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

// ---------------------------------------------------------------- 产物命名
//
// <applicationId>_<构建类型><versionName>_<yyMMddHHmm>.apk
// 例：com.tvboard.ime_debug0.1.0_2610310945.apk
//     com.tvboard.ime_release1.0.0_2610311213.apk
//
// 放在 Gradle 侧而不是打包脚本里：Android Studio、命令行、CI 三条路产出的
// 文件名必须一致。只在脚本里重命名的话，Studio 出来的仍然是 app-debug.apk，
// 就会出现两套命名。
//
// 代价：时间戳在配置阶段求值，每次构建的产物路径都不同，package 任务会
// 重新执行（几秒），换来的是产物名可追溯。这个取舍对本地构建是可接受的。
val buildStamp: String = SimpleDateFormat("yyMMddHHmm", Locale.ROOT).format(Date())
val apkVersionName: String = android.defaultConfig.versionName ?: "0.0.0"
// applicationId 在建变体时可能被 flavor 改写，这里取的是 defaultConfig 的值；
// 本项目没有 flavor，与最终包名一致。取不到时退回 namespace，保证文件名不残缺。
val apkPackageName: String = android.defaultConfig.applicationId
    ?: android.namespace
    ?: "app"

androidComponents {
    onVariants { variant ->
        val type = variant.buildType ?: "debug"
        val fileName = "${apkPackageName}_${type}${apkVersionName}_${buildStamp}.apk"
        // outputs 只有 ApplicationVariant 才有；而 outputFileName 至今只暴露在
        // 内部实现类 VariantOutputImpl 上，公开接口 VariantOutput 里没有
        // （AGP 8.5.2 的 gradle-api-8.5.2.jar 已用 javap 核对过）。
        // 因此这里必须做双重类型判断，任一步不成立就静默跳过，退回默认文件名。
        (variant as? ApplicationVariant)?.outputs?.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set(fileName)
        }
    }
}
