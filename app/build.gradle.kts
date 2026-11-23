@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

android {
    defaultConfig {
        // Версия переопределяется только если переданы -PappVersionName / -PappVersionCode
        // (release workflow). Иначе остаются значения из nb4a.properties (задаются в setupApp()).
        providers.gradleProperty("appVersionName").orNull?.let { versionName = it }
        providers.gradleProperty("appVersionCode").orNull?.let { versionCode = it.toInt() }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {

    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    implementation("com.google.android.material:material:1.8.0")
    implementation("com.google.code.gson:gson:2.9.0")

    implementation("com.github.jenly1314:zxing-lite:2.1.1")
    implementation("com.blacksquircle.ui:editorkit:2.6.0")
    implementation("com.blacksquircle.ui:language-base:2.6.0")
    implementation("com.blacksquircle.ui:language-json:2.6.0")

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.yaml:snakeyaml:1.30")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    implementation("com.jakewharton:process-phoenix:2.1.2")
    implementation("com.esotericsoftware:kryo:5.2.1")
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("org.ini4j:ini4j:0.5.4")

    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.github.MatrixDev.Roomigrant:RoomigrantLib:0.3.4")
    ksp("com.github.MatrixDev.Roomigrant:RoomigrantCompiler:0.3.4")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}


/**
 * Bundle the Default Route rule sets at build time so a fresh install works offline.
 * Fail the build rather than shipping a partially populated Default profile.
 */
val bundleDefaultRouteSrs by tasks.registering {
    val targetDir = layout.projectDirectory.dir("src/main/assets/sing-box/rule-set")
    outputs.dir(targetDir)
    doLast {
        val site = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/"
        val ip = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/"
        val compact = "https://github.com/raaad1on/geosite-geoip-compact/releases/download/latest/"
        val names = mapOf(
            "geosite-category-ads-all.srs" to listOf(site + "geosite-category-ads-all.srs"),
            "geoip-ru.srs" to listOf(ip + "geoip-ru.srs", compact + "geoip-ru.srs"),
            "geoip-private.srs" to listOf(compact + "geoip-private.srs"),
            "geosite-category-gov-ru.srs" to listOf(site + "geosite-category-gov-ru.srs"),
            "geosite-category-ru.srs" to listOf(site + "geosite-category-ru.srs", compact + "geosite-category-ru.srs"),
            "geosite-ru-suffixes.srs" to listOf(site + "geosite-ru-suffixes.srs"),
            "geosite-yandex.srs" to listOf(site + "geosite-yandex.srs"),
            "geosite-mailru.srs" to listOf(site + "geosite-mailru.srs"),
            "geosite-vk.srs" to listOf(site + "geosite-vk.srs"),
            "geosite-private.srs" to listOf(site + "geosite-private.srs", compact + "geosite-private.srs")
        )
        val dir = targetDir.asFile
        dir.mkdirs()
        names.forEach { (name, sources) ->
            val destination = dir.resolve(name)
            var failure: Exception? = null
            var selected: String? = null
            for (url in sources) {
                try {
                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 20000
                    connection.readTimeout = 60000
                    connection.instanceFollowRedirects = true
                    try {
                        check(connection.responseCode == 200) { "HTTP ${connection.responseCode}: $url" }
                        val temp = dir.resolve("$name.part")
                        try {
                            connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                            check(temp.length() > 5L) { "Empty rule set: $url" }
                            temp.inputStream().use { input ->
                                check(input.read() == 0x53 && input.read() == 0x52 && input.read() == 0x53) {
                                    "Invalid SRS header: $url"
                                }
                            }
                            check(temp.renameTo(destination)) { "Could not install $name" }
                            selected = url
                        } finally { temp.delete() }
                    } finally { connection.disconnect() }
                    break
                } catch (e: Exception) { failure = e }
            }
            check(selected != null) { "Missing required Default SRS $name: ${failure?.message}" }
            dir.resolve("${name.removeSuffix(".srs")}.source-url.txt").writeText(selected!! + "\n")
        }
    }
}
tasks.named("preBuild") { dependsOn(bundleDefaultRouteSrs) }
