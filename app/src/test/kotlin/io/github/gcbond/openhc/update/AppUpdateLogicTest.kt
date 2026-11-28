package io.github.gcbond.openhc.update

import io.github.gcbond.openhc.update.AppUpdateLogic.DownloadRoute
import io.github.gcbond.openhc.update.AppUpdateLogic.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateLogicTest {

    @Test
    fun parsesAndComparesSemanticVersions() {
        val v312 = SemanticVersion.parse("v0.3.12")
        val v313 = SemanticVersion.parse("0.3.13")
        val v400 = SemanticVersion.parse("0.4.0")
        assertNotNull(v312)
        assertNotNull(v313)
        assertNotNull(v400)
        assertTrue(v313!! > v312!!)
        assertTrue(v400!! > v313)
        assertEquals(SemanticVersion(0, 3, 12), v312)
        assertEquals(SemanticVersion(1, 2, 0), SemanticVersion.parse("1.2"))
        assertNull(SemanticVersion.parse("invalid"))
    }

    @Test
    fun downloadCandidatesTryDirectFirstThenGhfastProxy() {
        val raw = "https://github.com/DongShull/OpenHC/releases/download/v0.3.13/OpenHC-0.3.13.apk"
        val candidates = AppUpdateLogic.downloadCandidates(raw)
        assertEquals(2, candidates.size)
        assertEquals(DownloadRoute.DIRECT to raw, candidates[0])
        assertEquals(
            DownloadRoute.PROXY to "https://ghfast.top/https://github.com/DongShull/OpenHC/releases/download/v0.3.13/OpenHC-0.3.13.apk",
            candidates[1],
        )
        // 不重复拼接代理前缀
        assertEquals(candidates[1].second, AppUpdateLogic.proxiedUrl(candidates[1].second))
    }

    @Test
    fun parsesLatestReleaseJsonIgnoringDraftAndDebugApk() {
        val json = """
            [
              {
                "tag_name": "v0.3.14",
                "name": "Draft Release",
                "draft": true,
                "prerelease": true,
                "html_url": "https://github.com/DongShull/OpenHC/releases/tag/v0.3.14",
                "body": "draft",
                "assets": [
                  {"name": "OpenHC-0.3.14.apk", "size": 1800000, "browser_download_url": "https://github.com/DongShull/OpenHC/releases/download/v0.3.14/OpenHC-0.3.14.apk"}
                ]
              },
              {
                "tag_name": "v0.3.13",
                "name": "OpenHC 0.3.13",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/DongShull/OpenHC/releases/tag/v0.3.13",
                "body": "### 本版新增\n- 优化侧边栏版本更新卡片\n- 支持 ghfast.top 代理回退下载",
                "assets": [
                  {"name": "OpenHC-0.3.13-debug.apk", "size": 14000000, "browser_download_url": "https://github.com/DongShull/OpenHC/releases/download/v0.3.13/OpenHC-0.3.13-debug.apk"},
                  {"name": "OpenHC-0.3.13.apk", "size": 1850000, "browser_download_url": "https://github.com/DongShull/OpenHC/releases/download/v0.3.13/OpenHC-0.3.13.apk"},
                  {"name": "checksums.txt", "size": 170, "browser_download_url": "https://github.com/DongShull/OpenHC/releases/download/v0.3.13/checksums.txt"}
                ]
              },
              {
                "tag_name": "v0.3.12",
                "name": "OpenHC 0.3.12",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/DongShull/OpenHC/releases/tag/v0.3.12",
                "body": "",
                "assets": [
                  {"name": "OpenHC-0.3.12.apk", "size": 1840000, "browser_download_url": "https://github.com/DongShull/OpenHC/releases/download/v0.3.12/OpenHC-0.3.12.apk"}
                ]
              }
            ]
        """.trimIndent()

        val latest = AppUpdateLogic.parseLatestReleaseJson(json)
        assertNotNull(latest)
        assertEquals("0.3.13", latest!!.versionName)
        assertEquals("OpenHC-0.3.13.apk", latest.apkName)
        assertEquals(1850000L, latest.apkSizeBytes)
        assertEquals("https://github.com/DongShull/OpenHC/releases/download/v0.3.13/checksums.txt", latest.checksumUrl)
        assertTrue(AppUpdateLogic.isUpdateAvailable("0.3.12", latest))
        assertFalse(AppUpdateLogic.isUpdateAvailable("0.3.13", latest))
        assertTrue(latest.notes.contains("优化侧边栏版本更新卡片"))
    }

    @Test
    fun parsesAtomFeedFallback() {
        val atom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>OpenHC 0.3.15（测试预发布版）</title>
                <link rel="alternate" type="text/html" href="https://github.com/DongShull/OpenHC/releases/tag/v0.3.15"/>
              </entry>
              <entry>
                <title>OpenHC 0.3.12</title>
                <link rel="alternate" type="text/html" href="https://github.com/DongShull/OpenHC/releases/tag/v0.3.12"/>
              </entry>
            </feed>
        """.trimIndent()

        val latest = AppUpdateLogic.parseLatestReleaseAtom(atom)
        assertNotNull(latest)
        assertEquals("0.3.15", latest!!.versionName)
        assertEquals("OpenHC-0.3.15.apk", latest.apkName)
        assertEquals(
            "https://github.com/DongShull/OpenHC/releases/download/v0.3.15/OpenHC-0.3.15.apk",
            latest.apkUrl,
        )
        assertTrue(AppUpdateLogic.isUpdateAvailable("0.3.12", latest))
    }

    @Test
    fun parsesSha256ChecksumLine() {
        val hash = "c7ad4bb7b0a49a091234567890abcdef1234567890abcdef1234567890abcdef"
        val txt = """
            a036398be89c37781234567890abcdef1234567890abcdef1234567890abcdef  OpenHC-0.3.12-debug.apk
            $hash  OpenHC-0.3.12.apk
        """.trimIndent()
        assertEquals(hash, AppUpdateLogic.parseChecksumFor(txt, "OpenHC-0.3.12.apk"))
        assertNull(AppUpdateLogic.parseChecksumFor(txt, "missing.apk"))
    }
}
