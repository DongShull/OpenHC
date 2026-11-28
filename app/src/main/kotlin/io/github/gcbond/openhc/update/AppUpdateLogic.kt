package io.github.gcbond.openhc.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * GitHub 版本检查、直连优先 + ghfast.top 代理回退、Release JSON / Atom 解析与 SHA-256 校验纯逻辑（JVM 可单测）。
 */
internal object AppUpdateLogic {
    const val REPO_OWNER = "DongShull"
    const val REPO_NAME = "OpenHC"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"
    const val RELEASES_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=10"
    const val RELEASES_ATOM_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases.atom"
    const val PROXY_BASE = "https://ghfast.top"

    enum class DownloadRoute { DIRECT, PROXY }

    enum class CheckFormat { JSON_API, ATOM_FEED }

    data class CheckCandidate(
        val route: DownloadRoute,
        val url: String,
        val format: CheckFormat,
    )

    data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            return patch.compareTo(other.patch)
        }

        override fun toString(): String = "$major.$minor.$patch"

        companion object {
            private val VERSION_REGEX = Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?""")

            fun parse(raw: String): SemanticVersion? {
                val match = VERSION_REGEX.find(raw.trim()) ?: return null
                val major = match.groupValues[1].toIntOrNull() ?: return null
                val minor = match.groupValues[2].toIntOrNull() ?: return null
                val patch = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
                return SemanticVersion(major, minor, patch)
            }
        }
    }

    data class ReleaseInfo(
        val version: SemanticVersion,
        val versionName: String,
        val tagName: String,
        val title: String,
        val notes: String,
        val htmlUrl: String,
        val apkName: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val checksumUrl: String?,
        val prerelease: Boolean,
    )

    /** 判断 [remote] 是否严格高于本地 [currentVersionName]。 */
    fun isUpdateAvailable(currentVersionName: String, remote: ReleaseInfo): Boolean {
        val current = SemanticVersion.parse(currentVersionName) ?: return false
        return remote.version > current
    }

    /** 拼接 ghfast.top 代理加速地址；已包含代理前缀时不重复拼接。 */
    fun proxiedUrl(originalUrl: String, proxyBase: String = PROXY_BASE): String {
        val cleanBase = proxyBase.trimEnd('/')
        if (originalUrl.startsWith("$cleanBase/")) return originalUrl
        return "$cleanBase/$originalUrl"
    }

    /**
     * 下载候选地址：始终先测原始 GitHub 地址，连接不上再回退到 https://ghfast.top 代理加速地址。
     */
    fun downloadCandidates(originalUrl: String, proxyBase: String = PROXY_BASE): List<Pair<DownloadRoute, String>> =
        listOf(
            DownloadRoute.DIRECT to originalUrl,
            DownloadRoute.PROXY to proxiedUrl(originalUrl, proxyBase),
        )

    /**
     * 检查更新候选链路：先测 GitHub 原始 API / Atom，不通再切 https://ghfast.top 代理。
     * 注意多数代理仅加速 github.com（releases.atom），因此代理段优先 Atom、其次 API。
     */
    fun checkCandidates(proxyBase: String = PROXY_BASE): List<CheckCandidate> = listOf(
        CheckCandidate(DownloadRoute.DIRECT, RELEASES_API_URL, CheckFormat.JSON_API),
        CheckCandidate(DownloadRoute.DIRECT, RELEASES_ATOM_URL, CheckFormat.ATOM_FEED),
        CheckCandidate(DownloadRoute.PROXY, proxiedUrl(RELEASES_ATOM_URL, proxyBase), CheckFormat.ATOM_FEED),
        CheckCandidate(DownloadRoute.PROXY, proxiedUrl(RELEASES_API_URL, proxyBase), CheckFormat.JSON_API),
    )

    /** 解析 GitHub Releases API JSON 数组，跳过 draft，选出带正式签名 APK 且版本号最高的一条。 */
    fun parseLatestReleaseJson(jsonText: String): ReleaseInfo? {
        val array: JsonArray = try {
            Json.parseToJsonElement(jsonText).jsonArray
        } catch (_: Exception) {
            return null
        }
        var best: ReleaseInfo? = null
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            if (obj["draft"]?.jsonPrimitive?.booleanOrNull == true) continue
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val version = SemanticVersion.parse(tag) ?: continue
            val assets = (obj["assets"] as? JsonArray) ?: continue

            var apkName: String? = null
            var apkUrl: String? = null
            var apkSize = 0L
            var checksumUrl: String? = null

            for (assetEl in assets) {
                val asset = assetEl as? JsonObject ?: continue
                val name = asset["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isEmpty() || url.isEmpty()) continue
                val lower = name.lowercase()
                when {
                    lower.endsWith(".apk") && !lower.contains("debug") -> {
                        apkName = name
                        apkUrl = url
                        apkSize = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
                    }
                    lower == "checksums.txt" || lower.endsWith(".sha256") -> {
                        checksumUrl = url
                    }
                }
            }
            if (apkName == null || apkUrl == null) continue

            val title = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "OpenHC $version"
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: "$RELEASES_PAGE_URL/tag/$tag"
            val body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val prerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull == true

            val candidate = ReleaseInfo(
                version = version,
                versionName = version.toString(),
                tagName = tag,
                title = title,
                notes = summarizeReleaseNotes(body),
                htmlUrl = htmlUrl,
                apkName = apkName,
                apkUrl = apkUrl,
                apkSizeBytes = apkSize,
                checksumUrl = checksumUrl,
                prerelease = prerelease,
            )
            if (best == null || candidate.version > best.version) best = candidate
        }
        return best
    }

    /**
     * 解析 GitHub `releases.atom` XML（当 api.github.com 直连不可达且经 ghfast.top 回退时使用）。
     * 按仓库固定发布规范拼接正式签名包 `OpenHC-<version>.apk` 与 `checksums.txt` 下载地址。
     */
    fun parseLatestReleaseAtom(atomXml: String): ReleaseInfo? {
        val entryRegex = Regex("""<entry>([\s\S]*?)</entry>""")
        val linkTagRegex = Regex("""/releases/tag/([^"'<\s]+)""")
        val titleRegex = Regex("""<title[^>]*>(.*?)</title>""")
        var best: ReleaseInfo? = null

        for (match in entryRegex.findAll(atomXml)) {
            val block = match.groupValues[1]
            val tag = linkTagRegex.find(block)?.groupValues?.get(1)?.trim() ?: continue
            val version = SemanticVersion.parse(tag) ?: continue
            val rawTitle = titleRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val title = decodeXmlEntities(rawTitle).ifEmpty { "OpenHC $version" }
            val apkName = "OpenHC-$version.apk"
            val baseDownload = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tag"
            val candidate = ReleaseInfo(
                version = version,
                versionName = version.toString(),
                tagName = tag,
                title = title,
                notes = "",
                htmlUrl = "$RELEASES_PAGE_URL/tag/$tag",
                apkName = apkName,
                apkUrl = "$baseDownload/$apkName",
                apkSizeBytes = 0L,
                checksumUrl = "$baseDownload/checksums.txt",
                prerelease = version.patch > 0,
            )
            if (best == null || candidate.version > best.version) best = candidate
        }
        return best
    }

    /** 从 `checksums.txt`（`sha256  filename` 格式）中提取指定文件名的 64 位小写十六进制 SHA-256。 */
    fun parseChecksumFor(checksumText: String, fileName: String): String? {
        for (rawLine in checksumText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("""\s+"""), limit = 2)
            if (parts.size != 2) continue
            val hash = parts[0].lowercase()
            val name = parts[1].removePrefix("*").trim()
            if (name == fileName && hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }) {
                return hash
            }
        }
        return null
    }

    /** 精简 Release Markdown 说明为车机弹窗可读的几行纯文本摘要。 */
    fun summarizeReleaseNotes(rawMarkdown: String): String {
        if (rawMarkdown.isBlank()) return ""
        val withoutDetails = rawMarkdown.replace(Regex("""<details>[\s\S]*?</details>"""), "")
        val lines = ArrayList<String>()
        for (raw in withoutDetails.lineSequence()) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith(">") || trimmed.startsWith("|") || trimmed.startsWith("#")) continue
            if (trimmed.startsWith("**完整代码对比**") || trimmed.startsWith("**测试预发布版")) continue
            val cleaned = trimmed
                .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
                .replace("`", "")
                .replace("**", "")
                .replace(Regex("""（[0-9a-f]{7,}）$"""), "")
                .trim()
            if (cleaned.isNotEmpty()) {
                lines += cleaned
                if (lines.size >= 5) break
            }
        }
        return lines.joinToString("\n").take(320)
    }

    private fun decodeXmlEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
