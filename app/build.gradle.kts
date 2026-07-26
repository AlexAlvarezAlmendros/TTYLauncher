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

    // Se declaran explícitamente aunque AGP los traiga por defecto. El motivo es el modo de fallo:
    // si `src/main/kotlin` no estuviera incluido el build falla a gritos, pero si no lo estuviera
    // `src/test/kotlin` los tests simplemente no existirían y todo pasaría en verde. Un aviso de
    // deprecación es preferible a 107 tests invisibles.
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")

    // No hay bloque `kotlin { compilerOptions { jvmTarget } }`: con el Kotlin integrado de AGP 9 el
    // jvmTarget deriva de `compileOptions.targetCompatibility`, y declararlo crearía dos sitios que
    // se pueden desincronizar.
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.graphics)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)

    // Herramientas, no runtime: `ui-tooling` solo entra en debug.
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)

    // Sin material3 a propósito: BasicText y BasicTextField viven en foundation, y lo único que
    // aportaría material3 (ripple, tema, tipografía) está prohibido por la §4.8 del funcional.
}
