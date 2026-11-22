package io.github.xiaomibutton.remapper

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubUpdatesTest {
    @Test fun comparesVersionsNumerically() {
        assertEquals("0.2.20", newerGitHubRelease(release("0.2.20"), "0.2.9"))
        assertEquals("1.0.0", newerGitHubRelease(release("1.0.0"), "0.9.50"))
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

    private fun release(version: String) = JSONObject()
        .put("draft", false)
        .put("prerelease", false)
        .put("tag_name", "v$version")
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "Xiaomi-AI-Button-Remapper.apk")
            .put("state", "uploaded")
            .put("browser_download_url", "https://github.com/migwynkriid13/Xiaomi-AI-Button-Remapper/releases/download/v$version/Xiaomi-AI-Button-Remapper.apk")
        ))
}
