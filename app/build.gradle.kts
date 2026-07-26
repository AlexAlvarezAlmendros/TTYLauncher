plugins {
    alias(libs.plugins.android.application)
    // Único plugin de Kotlin que se aplica a mano: AGP 9 trae el resto integrado.
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.tty"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.tty"
        minSdk = libs.versions.minSdk.get().toInt()
        // En AGP 9 targetSdk toma por defecto el valor de compileSdk: declararlo explícitamente.
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // proguard-android.txt está prohibido en AGP 9: usar la variante -optimize.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        // buildConfig viene desactivado por defecto en AGP 9. Activarlo solo si se usa de verdad.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // `src/main/kotlin` y `src/test/kotlin` ya son directorios de fuentes por defecto: no se
    // declaran. Y no hay bloque `kotlin { compilerOptions { jvmTarget } }` porque con el Kotlin
    // integrado de AGP 9 el jvmTarget deriva de `compileOptions.targetCompatibility`; declararlo
    // solo crea dos sitios que se pueden desincronizar.
    // Verificar ambas cosas al cerrar la tarea 0.1.
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    // Herramientas, no runtime: `ui-tooling` solo entra en debug.
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)

    // Sin material3 a propósito: BasicText y BasicTextField viven en foundation, y lo único que
    // aportaría material3 (ripple, tema, tipografía) está prohibido por la §4.8 del funcional.
}
