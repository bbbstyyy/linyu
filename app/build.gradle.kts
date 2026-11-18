import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
android {
    namespace = "local.rea.shower"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "local.rea.shower"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        testInstrumentationRunner = "local.rea.shower.WidgetSmokeInstrumentation"
    }
    buildTypes {
        getByName("debug") {
            // Isolated, empty-data installation used only for offline UI checks on a connected phone.
            if (providers.gradleProperty("uiPreview").orNull == "true") applicationIdSuffix = ".preview"
        }
        getByName("release") {
            isDebuggable = false
            // Signing happens after verification, outside Gradle, using apksigner.
            isMinifyEnabled = false
        }
    }
    signingConfigs {
        getByName("debug") {
            // Optional local override preserves existing development installations.
            providers.environmentVariable("SHOWER_DEBUG_KEYSTORE").orNull?.let {
                storeFile = file(it)
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            providers.environmentVariable("SHOWER_TEST_MAVEN_REPO").orNull?.let { repo ->
                it.systemProperty("maven.repo.local", repo)
            }
            // API 36's shared-memory setup needs the JDK file-descriptor bridge in Robolectric.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/legalAssets"))
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    implementation("com.google.code.gson:gson:2.15.0") {
        // Compile-time annotations only; Gson does not require these at runtime.
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
}
// Runs the real protocol and state machine with scripted offline transports.
tasks.register<JavaExec>("verifyMvp") {
    dependsOn("compileDebugUnitTestKotlin")
    classpath = files(layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")) +
        configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("local.rea.shower.core.ProtocolChecksKt")
}
val prepareLegalAssets by tasks.registering(Sync::class) {
    from(rootProject.projectDir) {
        include("LICENSE", "THIRD_PARTY_NOTICES.md", "licenses/**")
    }
    into(layout.buildDirectory.dir("generated/legalAssets/legal"))
}
tasks.named("preBuild") { dependsOn(prepareLegalAssets) }
