import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "1.9.25"
}

// ---------------------------------------------------------------------------------------------
// 365 Push Alert – native Android app, built WITHOUT the Android Gradle Plugin.
//
// Why: this project's build environment cannot reach Google's Maven repository or SDK downloads,
// so AGP/AndroidX/Compose are unavailable. Instead Gradle compiles the Kotlin sources against the
// Android framework API jar and then drives the standard Android command-line tools:
//   kotlinc (Gradle) -> dx -> aapt2 compile/link -> zip -> zipalign -> apksigner
// See README.md ("Build") for details and for the tools required.
// ---------------------------------------------------------------------------------------------

val appId = "com.dhruvtilva.pushalert"
val appVersionName = "1.0.5"
val appVersionCode = 6
val minSdk = 26
val targetSdk = 34

group = appId
version = appVersionName

val sdkRoot = file((findProperty("androidSdk") as String?) ?: System.getenv("ANDROID_HOME") ?: "/usr/lib/android-sdk")
val buildToolsDir: File = (findProperty("buildTools") as String?)?.let { sdkRoot.resolve("build-tools/$it") }
    ?: (sdkRoot.resolve("build-tools").listFiles { f -> f.isDirectory && f.name.firstOrNull()?.isDigit() == true }
        ?.maxByOrNull { it.name }
        ?: sdkRoot.resolve("build-tools/missing"))
val platformJar: File = sdkRoot.resolve("platforms").listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
    ?.sortedByDescending { it.name.removePrefix("android-").toIntOrNull() ?: 0 }
    ?.map { it.resolve("android.jar") }?.firstOrNull { it.exists() }
    ?: sdkRoot.resolve("platforms/missing/android.jar")

val frameworkApiJar: Configuration by configurations.creating
val robolectricRuntime: Configuration by configurations.creating { isTransitive = false }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // dx cannot translate invokedynamic, so compile lambdas/SAMs to classes; restrict to Java 8 APIs.
        freeCompilerArgs.addAll(
            "-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline", "-Xjdk-release=1.8",
        )
        allWarningsAsErrors.set(true)
    }
}
tasks.compileJava { options.release.set(8) }
tasks.compileTestJava { options.release.set(17) }
// Only the shipped app must be Java-8 bytecode (for dx); tests run on the JDK and may use newer libraries.
tasks.compileTestKotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); freeCompilerArgs.set(emptyList()) } }
configurations.matching { it.name.startsWith("test") }.configureEach {
    attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
}

dependencies {
    // Compile against the full Android API (API 34). Not packaged: the phone provides it at runtime.
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
    frameworkApiJar("org.robolectric:android-all:14-robolectric-10818077")
    robolectricRuntime("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:android-all:14-robolectric-10818077")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // Robolectric smoke tests (launch the real Application/Activity). Its androidx.test dependencies live only on
    // Google Maven (unreachable here), so they are excluded and replaced by tiny stubs under src/test.
    testImplementation("org.robolectric:robolectric:4.17") {
        listOf("androidx.test", "androidx.test.espresso", "androidx.test.ext", "androidx.test.services", "androidx.annotation")
            .forEach { exclude(group = it) }
    }
    // The real Android classes are not needed by unit tests (the domain code is Android-free), but the
    // android-all jar is on the compile classpath only, so tests cannot accidentally depend on it.
}

val robolectricDeps = layout.buildDirectory.dir("robolectric-deps")
val prepareRobolectric by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricDeps)
}

tasks.test {
    useJUnit()
    forkEvery = 1 // Robolectric must not share a JVM with tests that load android.* classes directly
    dependsOn(prepareRobolectric)
    // Offline: the framework jar comes from Gradle's cache (Maven Central), not a runtime download.
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDeps.get().asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ------------------------------------------------------------------ APK pipeline

val apkWork = layout.buildDirectory.dir("apk")
val outputApk = layout.projectDirectory.file("artifacts/365-push-alert-debug.apk")

fun tool(name: String): String = buildToolsDir.resolve(name).also {
    require(it.exists()) { "Android build tool '$name' not found in $buildToolsDir. Set -PandroidSdk=/path/to/sdk." }
}.absolutePath

// dx cannot read multi-release metadata (META-INF/versions/9/module-info.class), so flatten the runtime
// libraries into one plain jar first.
val libsJar by tasks.registering(Jar::class) {
    archiveFileName.set("libs.jar")
    destinationDirectory.set(apkWork)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.runtimeClasspath.get().map { zipTree(it) } }) {
        // Keep META-INF/services: kotlinx-coroutines-android finds Dispatchers.Main through ServiceLoader.
        exclude("META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/*.version", "META-INF/MANIFEST.MF",
            "META-INF/proguard/**", "META-INF/com.android.tools/**", "META-INF/maven/**", "META-INF/native-image/**",
            "module-info.class", "**/module-info.class", "DebugProbesKt.bin")
    }
}

val dexDebug by tasks.registering(Exec::class) {
    group = "build"
    description = "Converts compiled classes and runtime libraries to classes.dex using dx."
    dependsOn(tasks.classes, libsJar)
    val out = apkWork.map { it.file("classes.dex") }
    inputs.files(tasks.compileKotlin.map { it.destinationDirectory }, libsJar)
    outputs.file(out)
    doFirst {
        apkWork.get().asFile.mkdirs()
        val classDirs = tasks.compileKotlin.get().destinationDirectory.get().asFile.absolutePath
        val libs = listOf(libsJar.get().archiveFile.get().asFile.absolutePath)
        commandLine(
            listOf(tool("dx"), "-JXmx2g", "--dex", "--min-sdk-version=$minSdk", "--output=${out.get().asFile.absolutePath}", classDirs) + libs,
        )
    }
}

val compileResources by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles res/ with aapt2."
    val outZip = apkWork.map { it.file("res.zip") }
    inputs.dir("src/main/res")
    outputs.file(outZip)
    doFirst {
        apkWork.get().asFile.mkdirs()
        commandLine(tool("aapt2"), "compile", "--dir", "src/main/res", "-o", outZip.get().asFile.absolutePath)
    }
}

val linkResources by tasks.registering(Exec::class) {
    group = "build"
    description = "Links manifest and resources into a base APK with aapt2."
    dependsOn(compileResources)
    val base = apkWork.map { it.file("base.apk") }
    inputs.file("src/main/AndroidManifest.xml")
    inputs.file(apkWork.map { it.file("res.zip") })
    outputs.file(base)
    doFirst {
        // Prefer the framework jar with API 34 resources so newer manifest attributes resolve.
        val framework = frameworkApiJar.singleFile
        commandLine(
            tool("aapt2"), "link", "-o", base.get().asFile.absolutePath,
            "-I", framework.absolutePath,
            "--manifest", "src/main/AndroidManifest.xml",
            "-R", apkWork.get().file("res.zip").asFile.absolutePath,
            "--min-sdk-version", "$minSdk", "--target-sdk-version", "$targetSdk",
            "--version-code", "$appVersionCode", "--version-name", appVersionName,
            "--auto-add-overlay",
        )
    }
}

val assembleUnsigned by tasks.registering {
    group = "build"
    description = "Adds classes.dex to the base APK."
    dependsOn(dexDebug, linkResources)
    val unsigned = apkWork.map { it.file("unsigned.apk") }
    outputs.file(unsigned)
    doLast {
        val dir = apkWork.get().asFile
        val target = unsigned.get().asFile
        dir.resolve("base.apk").copyTo(target, overwrite = true)
        // `zip` appends without recompressing existing entries (resources.arsc must stay stored).
        val p = ProcessBuilder("zip", "-q", "-j", target.absolutePath, dir.resolve("classes.dex").absolutePath)
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "zip failed: $text" }

        // Non-class resources from libraries (ServiceLoader registrations) must be packaged as-is.
        val extra = dir.resolve("extra").also { it.deleteRecursively(); it.mkdirs() }
        copy {
            from(zipTree(libsJar.get().archiveFile)) { include("META-INF/services/**") }
            into(extra)
        }
        if (extra.resolve("META-INF").exists()) {
            val z = ProcessBuilder("zip", "-q", "-r", target.absolutePath, "META-INF")
                .directory(extra).redirectErrorStream(true).start()
            val zt = z.inputStream.bufferedReader().readText()
            check(z.waitFor() == 0) { "zip (services) failed: $zt" }
        }
    }
}

val signDebugApk by tasks.registering {
    group = "build"
    description = "zipalign + apksigner (debug keystore in keystore/)."
    dependsOn(assembleUnsigned)
    val signed = apkWork.map { it.file("signed.apk") }
    outputs.file(signed)
    doLast {
        fun run(vararg cmd: String) {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "${cmd.first()} failed:\n$out" }
        }
        val dir = apkWork.get().asFile
        val aligned = dir.resolve("aligned.apk").also { it.delete() }
        signed.get().asFile.delete()
        run(tool("zipalign"), "-f", "-p", "4", dir.resolve("unsigned.apk").absolutePath, aligned.absolutePath)
        run(
            tool("apksigner"), "sign",
            "--ks", file("keystore/debug.keystore").absolutePath,
            "--ks-pass", "pass:android", "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey",
            "--min-sdk-version", "$minSdk",
            "--out", signed.get().asFile.absolutePath, aligned.absolutePath,
        )
        run(tool("apksigner"), "verify", "--min-sdk-version", "$minSdk", signed.get().asFile.absolutePath)
    }
}

val assembleDebug by tasks.registering(Copy::class) {
    group = "build"
    description = "Builds the signed debug APK and copies it to artifacts/365-push-alert-debug.apk."
    dependsOn(signDebugApk)
    from(apkWork.map { it.file("signed.apk") })
    into(outputApk.asFile.parentFile)
    rename { outputApk.asFile.name }
}

// ----------------------------------------------------------- checks / aliases

/** The Android Lint tool needs AGP, which is unavailable here; these are the project's own static checks. */
val lintDebug by tasks.registering {
    group = "verification"
    description = "Static checks: Kotlin warnings-as-errors (compileKotlin), no embedded secrets, permission audit."
    dependsOn(tasks.compileKotlin, tasks.compileTestKotlin)
    doLast {
        val sources = fileTree("src") { include("**/*.kt", "**/*.xml") }.files
        val secret = Regex("discord(app)?\\.com/api/webhooks/\\d{15,}/[A-Za-z0-9_-]{30,}")
        val badLog = Regex("Log\\.[dviwe]\\([^)]*(currentText|originalText|\\.text|webhook|fullUrl|token)", RegexOption.IGNORE_CASE)
        val problems = mutableListOf<String>()
        sources.forEach { f ->
            val text = f.readText()
            val isMain = f.relativeTo(projectDir).path.startsWith("src/main")
            if (isMain && secret.containsMatchIn(text)) problems += "${f.relativeTo(projectDir)}: embedded webhook URL"
            if (isMain && badLog.containsMatchIn(text)) problems += "${f.relativeTo(projectDir)}: log call may include sensitive data"
        }
        val manifest = file("src/main/AndroidManifest.xml").readText()
        val perms = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        if (perms != setOf("android.permission.INTERNET")) problems += "unexpected permissions: $perms"
        if (!manifest.contains("android:allowBackup=\"false\"")) problems += "allowBackup must be false"
        if (problems.isNotEmpty()) throw GradleException("Static checks failed:\n" + problems.joinToString("\n"))
        println("Static checks passed (permissions: $perms)")
    }
}

val testDebugUnitTest by tasks.registering {
    group = "verification"
    description = "Alias for the JVM unit tests."
    dependsOn(tasks.test)
}

val verifyApk by tasks.registering(Exec::class) {
    group = "verification"
    description = "Prints application id, version, permissions and signature of the built APK."
    dependsOn(assembleDebug)
    doFirst {
        commandLine("bash", "-c", "${tool("aapt")} dump badging '${outputApk.asFile}' | grep -E \"^package|uses-permission|sdkVersion|targetSdk|launchable\"; " +
            "${tool("apksigner")} verify --verbose --print-certs '${outputApk.asFile}' | head -8; ls -l '${outputApk.asFile}'")
    }
}
