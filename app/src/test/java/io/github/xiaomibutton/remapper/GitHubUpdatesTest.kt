package io.github.xiaomibutton.remapper

import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubUpdatesTest {
    @Test fun comparesVersionsNumerically() {
        assertEquals(ApkRelease("0.2.20", 3, hash), newerGitHubRelease(release("0.2.20"), "0.2.9"))
        assertEquals("1.0.0", newerGitHubRelease(release("1.0.0"), "0.9.50")?.version)
        assertNull(newerGitHubRelease(release("0.2.19"), "0.2.20"))
        assertNull(newerGitHubRelease(release("0.2.20"), "0.2.20"))
    }

    @Test fun ignoresDraftsPrereleasesAndInvalidTags() {
        assertNull(newerGitHubRelease(release("0.2.20").put("draft", true), "0.2.19"))
        assertNull(newerGitHubRelease(release("0.2.20").put("prerelease", true), "0.2.19"))
        assertNull(newerGitHubRelease(release("0.2.20-beta.1"), "0.2.19"))
        assertNull(newerGitHubRelease(release("0.2.999999999999999"), "0.2.19"))
    }

    @Test fun requiresThePublishedApkFromThisRepository() {
        val release = release("0.2.20")
        val asset = release.getJSONArray("assets").getJSONObject(0)
        asset.put("browser_download_url", "https://github.com/another/repo/releases/download/v0.2.20/Xiaomi-AI-Button-Remapper.apk")
        assertNull(newerGitHubRelease(release, "0.2.19"))
        asset.put("browser_download_url", releaseApkUrl("0.2.20")).put("state", "new")
        assertNull(newerGitHubRelease(release, "0.2.19"))
        release.put("assets", JSONArray())
        assertNull(newerGitHubRelease(release, "0.2.19"))
    }

    @Test fun requiresSizeAndSha256FromGitHub() {
        val release = release("0.2.20")
        val asset = release.getJSONArray("assets").getJSONObject(0)
        asset.remove("digest")
        assertNull(newerGitHubRelease(release, "0.2.19"))
        asset.put("digest", "sha256:invalid")
        assertNull(newerGitHubRelease(release, "0.2.19"))
        asset.put("digest", "sha256:$hash").put("size", 0)
        assertNull(newerGitHubRelease(release, "0.2.19"))
    }

    @Test fun verifiesBytesBeforeTheyCanBeCommittedToAndroid() {
        val release = ApkRelease("0.2.20", 3, hash)
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        copyVerifiedApk("abc".byteInputStream(), output, release, progress::add)
        assertEquals("abc", output.toString("UTF-8"))
        assertEquals(listOf(100), progress)
        for (invalid in listOf("ab", "abcd", "xyz")) {
            assertThrows(UpdateIntegrityException::class.java) {
                copyVerifiedApk(invalid.byteInputStream(), ByteArrayOutputStream(), release) {}
            }
        }
    }

    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun release(version: String) = JSONObject()
        .put("draft", false)
        .put("prerelease", false)
        .put("tag_name", "v$version")
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "Xiaomi-AI-Button-Remapper.apk")
            .put("state", "uploaded")
            .put("size", 3)
            .put("digest", "sha256:$hash")
            .put("browser_download_url", "https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases/download/v$version/Xiaomi-AI-Button-Remapper.apk")
        ))
}
