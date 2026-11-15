// Hyper Eternity Relógios: extensão (outro módulo do LSPosed) com os relógios do HyperOS 4 e o
// patcher de bibliotecas. Compilada dentro do código do miuix pelo GitHub Actions, como o app principal.

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
    // os ganchos e o patcher (Java), compilados à parte contra a API completa do Android
    implementation(files("libs/relogios-hooks.jar"))
}

@Suppress("UnstableApiUsage")
android {
    namespace = "com.ilha.relogios.ui"
    buildToolsVersion = BuildConfig.BUILD_TOOLS_VERSION
    compileSdk {
        version =
            release(BuildConfig.COMPILE_SDK) {
                minorApiLevel = BuildConfig.COMPILE_SDK_MINOR
            }
    }
    defaultConfig {
        applicationId = "com.ilha.headsup.relogios"
        minSdk = 33
        targetSdk = BuildConfig.TARGET_SDK
        versionCode = 5
        versionName = "1.4"
    }
    signingConfigs {
        register("teste") {
            // a mesma chave do app principal
            storeFile = file("../ilha/teste.jks")
            storePassword = "ilhateste"
            keyAlias = "ilha"
            keyPassword = "ilhateste"
        }
    }
    buildTypes {
        release {
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
