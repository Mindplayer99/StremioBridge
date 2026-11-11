import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // JitPack hosts the CloudStream Gradle plugin.
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools:r8:9.1.57")
        classpath("com.android.tools.build:gradle:8.7.3")
        // Pin an immutable CloudStream Gradle plugin revision whose POM and JAR
        // are present on JitPack. The current revision can be reported as built
        // while its artifacts are absent, and -SNAPSHOT is not reproducible.
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        // CloudStream pre-release is currently compiled with Kotlin 2.4 metadata.
        // Keep the plugin compiler on the latest compatible stable Kotlin line.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        val (owner, repo) = (System.getenv("GITHUB_REPOSITORY") ?: "Mindplayer99/StremioBridge").split("/", limit = 2)
        setRepo(owner, repo, "https://github.com/$owner/$repo", "https://raw.githubusercontent.com/$owner/$repo/%branch%/dist/%filename%")
        buildBranch = "main"
    }

    android {
        namespace = "com.mindplayer99.stremiobridge"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        testOptions.unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Required
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs for all cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        val sdk = project.extensions.getByType<CloudstreamExtension>().userCache.resolve("cloudstream/cloudstream.jar")
        add("testImplementation", files(sdk))
        add("testImplementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        add("testImplementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        add("testImplementation", "org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        add("testImplementation", "org.robolectric:robolectric:4.14.1")
        add("testImplementation", "junit:junit:4.13.2")
        add("testImplementation", "org.json:json:20240303")
        add("testImplementation", "com.squareup.okhttp3:mockwebserver:4.12.0")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
