package com.spendwise.app.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** ADR-002 rules that live in the manifest and build files — a regression here is a privacy bug, so it fails the build. */
class HardeningTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()

    private val internet = Regex("""<uses-permission[^>]*android:name="android\.permission\.INTERNET"""")

    @Test fun `no internet permission — in our manifest AND in what libraries merge in`() {
        // Control: the pattern really does catch the permission when present.
        assertThat(internet.containsMatchIn("<uses-permission android:name=\"android.permission.INTERNET\" />")).isTrue()
        assertThat(internet.containsMatchIn(manifest)).isFalse()
        // The merged manifests (when a build has produced them) are what actually ships.
        File("build/intermediates").walkTopDown()
            .filter { it.name == "AndroidManifest.xml" && "merged_manifest" in it.path }
            .forEach { assertThat(internet.containsMatchIn(it.readText())).isFalse() }
    }

    @Test fun `no backup, no device transfer`() {
        assertThat(manifest).contains("android:allowBackup=\"false\"")
        assertThat(manifest).contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val block = rules.substringAfter("<$section>").substringBefore("</$section>")
            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertThat(block).contains("<exclude domain=\"$domain\" />")
            }
        }
    }

    @Test fun `every exported component is protected, and only the expected ones exist`() {
        val exported = Regex("""<(activity|receiver|service|provider)\b[^>]*android:exported="true"[^>]*>""", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.value }.toList()
        assertThat(exported).hasSize(2) // the launcher activity and the SMS receiver
        val receiver = exported.single { it.contains("SmsReceiver") }
        assertThat(receiver).contains("android:permission=\"android.permission.BROADCAST_SMS\"")
    }

    @Test fun `release strips debug logging and is never debuggable`() {
        val proguard = File("proguard-rules.pro").readText()
        assertThat(proguard).contains("-assumenosideeffects class android.util.Log")
        val gradle = File("build.gradle.kts").readText()
        assertThat(gradle).doesNotContain("isDebuggable = true")
        assertThat(gradle).contains("applicationIdSuffix = \".debug\"")
    }
}
