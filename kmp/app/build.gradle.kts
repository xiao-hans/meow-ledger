plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
            }
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":ui"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.androidx.core.ktx)
            // 截屏采集服务复用 ui 模块同一套本地中文 OCR 引擎。
            implementation(libs.mlkit.text.recognition.chinese)
        }

        // 真机仪器测试：启动、数据库迁移与深链解析，补上「真机手工路径不能替代自动化」的缺口。
        androidInstrumentedTest.dependencies {
            implementation("androidx.test.ext:junit:1.2.1")
            implementation("androidx.test:runner:1.6.2")
            implementation("androidx.test:core-ktx:1.6.1")
            implementation(libs.sqldelight.android.driver)
        }
    }
}

android {
    namespace = "com.meow.ledger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meow.ledger"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.8.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
