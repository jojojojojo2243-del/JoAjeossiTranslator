plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.joajeossi.translator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.joajeossi.translator"
        minSdk = 23
        targetSdk = 35
        // GitHub Actions 가 빌드할 때마다 번호를 1씩 올려서 넣어 준다 (앱 자동 업데이트용)
        val buildNumber = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // 항상 같은 열쇠(keystore)로 서명해야 휴대폰에서 덮어쓰기 업데이트가 된다.
    // 열쇠는 GitHub 저장소 Secrets 에 넣어 두고, Actions 가 빌드 때 파일로 꺼내 쓴다.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                // 열쇠가 없을 때(PR 검사 등)는 임시 debug 열쇠로라도 빌드가 되게 한다.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}
