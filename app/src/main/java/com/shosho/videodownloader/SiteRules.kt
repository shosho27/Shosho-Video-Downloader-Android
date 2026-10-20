package com.shosho.videodownloader

import java.net.URI

/**
 * Port of video_downloader.py's SUPPORTED_HOSTS / validate_url / selector logic
 * from the macOS Video Frame Capture app, so both apps apply the same rules.
 */
object SiteRules {

    private val SUPPORTED_HOSTS = setOf(
        "youtube.com", "youtu.be", "x.com", "twitter.com",
        "facebook.com", "fb.watch", "instagram.com", "tiktok.com", "tiktokv.com",
        "linkedin.com",
    )

    class InvalidUrlException(message: String) : Exception(message)

    private fun host(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()

    private fun isLinkedIn(url: String): Boolean {
        val h = host(url) ?: return false
        return h == "linkedin.com" || h.endsWith(".linkedin.com")
    }

    /** Throws InvalidUrlException on an unsupported link; otherwise returns a normalized URL. */
    fun validateUrl(raw: String): String {
        val value = raw.trim()
        val uri = runCatching { URI(value) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val h = uri?.host?.lowercase()
        val userInfo = uri?.userInfo

        val hostSupported = h != null && SUPPORTED_HOSTS.any { domain ->
            h == domain || h.endsWith(".$domain")
        }

        if (scheme !in setOf("http", "https") || userInfo != null || !hostSupported) {
            throw InvalidUrlException(
                "Paste a YouTube, X, Facebook, Instagram, TikTok, or LinkedIn video link."
            )
        }

        // yt-dlp's LinkedIn extractor matches linkedin.com and www.linkedin.com only.
        // A country host such as pt.linkedin.com is the same post with a different host.
        if (isLinkedIn(value) && h != "www.linkedin.com") {
            val fixed = URI(uri!!.scheme, "www.linkedin.com", uri.path, uri.query, uri.fragment)
            return fixed.toString()
        }
        return value
    }

    /** Mirrors download_command()'s -f selector string. */
    fun formatSelector(heightCap: Int?): String {
        val limit = if (heightCap != null) "[height<=$heightCap]" else ""
        return "bv*$limit+ba/b$limit"
    }

    /**
     * LinkedIn's MP4s omit width and height, so a 720p/1080p filter matches
     * nothing - the Mac app adds an unconstrained "/b" fallback for it.
     * Facebook hits the same "Requested format is not available" failure
     * (its formats don't reliably expose separate video+audio streams under
     * a height cap either), so the fallback applies to every site: it's a
     * harmless last resort that only gets used when the normal selector
     * fails to match anything.
     */
    fun formatSelectorForUrl(url: String, heightCap: Int?): String {
        return "${formatSelector(heightCap)}/b"
    }

    /** Pulls the first http(s) URL out of shared text (e.g. "Check this out: https://...") */
    fun extractUrl(sharedText: String): String? {
        val match = Regex("""https?://\S+""").find(sharedText) ?: return null
        return match.value.trimEnd('.', ',', ')', ']', '"', '\'')
    }
}
