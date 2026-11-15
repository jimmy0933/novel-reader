package com.novelreader.app.utils

import java.net.URI

/**
 * 內建瀏覽器的廣告 / 彈出頁阻擋：
 * - 依網域黑名單攔截廣告腳本與請求
 * - 擋掉未經點擊的跨站自動跳轉、外部 App 協定（intent:// market:// 等）
 * - 注入 CSS 隱藏常見廣告版位
 */
object AdBlocker {

    /** 網域（含子網域）黑名單 */
    private val blockedDomains = listOf(
        // 小說之家 (xszj.org) 使用的廣告 / 彈出頁來源
        "bauval.org", "fulahunslack.com",
        // 常見廣告網路與彈出式廣告
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com",
        "exoclick.com", "exosrv.com", "juicyads.com", "adsterra.com", "highperformanceformat.com",
        "effectivegatecpm.com", "profitablegatecpm.com", "trafficjunky.com", "onclickads.net",
        "adnxs.com", "taboola.com", "outbrain.com", "mgid.com", "revcontent.com",
        "clickadu.com", "hilltopads.net", "richpush.co", "adcash.com", "a-ads.com"
    )

    /** 隱藏廣告版位的 CSS */
    const val HIDE_CSS = "ins.adsbygoogle,iframe[src*=\"bauval.org\"],iframe[src*=\"doubleclick\"]," +
        "iframe[src*=\"googlesyndication\"],[id^=\"container-\"]:empty,[id*=\"google_ads\"]," +
        "[class*=\"adsbygoogle\"],[data-ad-slot]{display:none!important;height:0!important;}"

    fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    fun isBlockedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return blockedDomains.any { host == it || host.endsWith(".$it") }
    }

    fun isBlockedUrl(url: String): Boolean = isBlockedHost(hostOf(url))

    /** 是否為外部 App 協定（intent:// market:// 等），不應由內建瀏覽器開啟 */
    fun isExternalScheme(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme.isNotEmpty() && scheme !in setOf("http", "https", "about", "data", "blob", "javascript", "file")
    }

    /** 簡易取得主網域（最後兩段），用於判斷是否同站 */
    fun siteOf(host: String): String {
        val parts = host.split('.')
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }

    fun isSameSite(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return true
        return siteOf(a) == siteOf(b)
    }

    /**
     * 是否阻擋這次主框架導航：黑名單網域、外部協定，或沒有使用者手勢的跨站跳轉。
     */
    fun shouldBlockNavigation(targetUrl: String, currentUrl: String?, hasGesture: Boolean): Boolean {
        if (isExternalScheme(targetUrl)) return true
        val targetHost = hostOf(targetUrl)
        if (isBlockedHost(targetHost)) return true
        if (!hasGesture && !isSameSite(targetHost, currentUrl?.let { hostOf(it) })) return true
        return false
    }
}
