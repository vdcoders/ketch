import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
version = "0.0.2"

android {
    namespace = "com.ketch"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.retrofit)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}
fun getVersionName(): String = project.version.toString()
fun getGroupId(): String = "io.github.vdcoders"

val sourceJar by tasks.registering(org.gradle.jvm.tasks.Jar::class) {
    archiveClassifier.set("sources")
    from(android.sourceSets["main"].java.srcDirs)
}

afterEvaluate {
    mavenPublishing {
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
        signAllPublications()

        coordinates(
            getGroupId(),
            "ketch",
            getVersionName()
        )

        pom {
            name.set("ketch Android library")
            description.set("The mpv library used by ketch.")
            inceptionYear.set("2025")
            url.set("https://github.com/vdcoders/ketch")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit/")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    id.set("vdcoders")
                    name.set("vCoderz")
                    url.set("https://github.com/vdcoders")
                }
            }

            scm {
                url.set("https://github.com/vdcoders/ketch")
                connection.set("scm:git:git://github.com/vdcoders/ketch.git")
                developerConnection.set("scm:git:ssh://git@github.com/vdcoders/ketch.git")
            }
        }
    }
}