package com.novelreader.app

import com.novelreader.app.utils.AdBlocker
import org.junit.Assert.*
import org.junit.Test

class AdBlockerTest {
    @Test
    fun blocksAdDomainsAndSubdomains() {
        assertTrue(AdBlocker.isBlockedUrl("https://bauval.org/22/dbd0b824027f0bd49885d86c34a9ae88"))
        assertTrue(AdBlocker.isBlockedUrl("//rz.fulahunslack.com/x".let { "https:$it" }))
        assertTrue(AdBlocker.isBlockedUrl("https://pagead2.googlesyndication.com/pagead/js"))
        assertFalse(AdBlocker.isBlockedUrl("https://xszj.org/b/103995"))
        assertFalse(AdBlocker.isBlockedUrl("https://czbooks.net/"))
    }

    @Test
    fun blocksExternalSchemes() {
        assertTrue(AdBlocker.isExternalScheme("intent://scan/#Intent;scheme=zxing;end"))
        assertTrue(AdBlocker.isExternalScheme("market://details?id=x"))
        assertFalse(AdBlocker.isExternalScheme("https://xszj.org/"))
    }

    @Test
    fun navigationRules() {
        // 使用者點擊同站連結可通過
        assertFalse(AdBlocker.shouldBlockNavigation("https://xszj.org/b/1", "https://m.xszj.org/", true))
        // 無手勢的同站跳轉 (m. -> 主站) 可通過
        assertFalse(AdBlocker.shouldBlockNavigation("https://xszj.org/b/1", "https://m.xszj.org/b/1", false))
        // 無手勢的跨站跳轉被擋（典型彈出頁 / 劫持）
        assertTrue(AdBlocker.shouldBlockNavigation("https://evil.example.com/", "https://m.xszj.org/b/1", false))
        // 使用者主動點擊跨站連結可通過
        assertFalse(AdBlocker.shouldBlockNavigation("https://evil.example.com/", "https://m.xszj.org/b/1", true))
        // 黑名單網域一律擋
        assertTrue(AdBlocker.shouldBlockNavigation("https://bauval.org/x", "https://m.xszj.org/", true))
    }
}
