// Hyper Eternity: o módulo inteiro (hooks em libs/hooks.jar) com a tela de ajustes em miuix.
// Este módulo é copiado para dentro do código do miuix pelo GitHub Actions
// (veja .github/workflows/build.yml) e usa as versões e plugins de lá.

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    id("module.kotlin-jvm-toolchain")
}

dependencies {
    implementation(project(":miuix-ui"))
    implementation(project(":miuix-preference"))
    implementation(project(":miuix-blur"))
    implementation(project(":miuix-icons"))
    implementation(libs.androidx.activity)
    // API do LSPosed: quem fornece é o próprio LSPosed em tempo de execução
    compileOnly(files("libs/apistubs.jar"))
    // os hooks (Java), compilados à parte contra a API completa do Android
    implementation(files("libs/hooks.jar"))
}

@Suppress("UnstableApiUsage")
android {
    namespace = "com.ilha.headsup.miuix"
    buildToolsVersion = BuildConfig.BUILD_TOOLS_VERSION
    compileSdk {
        version =
            release(BuildConfig.COMPILE_SDK) {
                minorApiLevel = BuildConfig.COMPILE_SDK_MINOR
            }
    }
    defaultConfig {
        applicationId = "com.ilha.headsup"
        minSdk = 33
        targetSdk = BuildConfig.TARGET_SDK
        versionCode = 864
        versionName = "7.9.55"
    }
    signingConfigs {
        register("teste") {
            storeFile = file("teste.jks")
            storePassword = "ilhateste"
            keyAlias = "ilha"
            keyPassword = "ilhateste"
        }
    }
    buildTypes {
        release {
            // R8 encolhe o miuix; os hooks ficam intactos (proguard-rules.pro)
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
            vcsInfo.include = false
            signingConfig = signingConfigs.getByName("teste")
        }
        debug {
            signingConfig = signingConfigs.getByName("teste")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        jniLibs {
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
    }
}
