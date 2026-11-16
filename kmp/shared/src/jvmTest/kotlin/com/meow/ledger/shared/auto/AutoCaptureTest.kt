package com.meow.ledger.shared.auto

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meow.ledger.shared.db.MeowDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 20 条真实感支付文本：识别率 ≥ 90%、重复 0（04 §4.3 去重是自动记账的生命线）。 */
class AutoCaptureTest {
    private val db = MeowDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).apply { MeowDatabase.Schema.create(this) })
    private val repo = AutoRepository(db)

    private val samples = listOf(
        "【微信支付】支付成功 ¥23.50 商户：瑞幸咖啡",
        "在 盒马鲜生 消费 ¥128.40",
        "向 美团外卖 付款 ¥36.80 完成",
        "商户：永辉超市 交易金额：￥89.20",
        "支付宝支出 45.00元 打车出行",
        "扣款 ¥12.00 顺丰速运",
        "收款 ¥200.00 商户：闲鱼买家张三",
        "到账 1500.00元 工资代发",
        "消费 ¥9.90 711便利店",
        "支付 ¥66.60 商户：肯德基",
        "退款 ¥18.50 商户：京东自营",
        "红包已领 88.88元",
        "转入 300.00 招商银行",
        "付款给 老王水果店 ¥35.70",
        "店铺：优衣库 消费 ¥299.00",
        "在 老娘舅 消费26.00元",
        "交易对方：滴滴出行 金额 ¥21.30",
        "已付款 ¥108.00 山姆会员店",
        "支出 320.00元 国网电费",
        "扫码支付 ¥4.50 商户：地铁便利",
    )

    @Test
    fun recognitionRateAtLeast90Percent() {
        var created = 0
        for (text in samples) {
            val r = repo.capture(text, "notification")
            created += r.created
        }
        assertTrue(created * 10 >= samples.size * 9, "识别 $created / ${samples.size}")
        assertEquals(created, repo.pending().size)
    }

    @Test
    fun duplicateNeverReachesQueue() {
        val first = repo.capture(samples[0], "notification")
        assertEquals(1, first.created)
        val again = repo.capture(samples[0], "notification")
        assertEquals(0, again.created)
        assertEquals(1, again.duplicate)
        assertEquals(1, repo.pending().size)
    }

    @Test
    fun sameSourceSameMinuteIsDuplicateNextMinuteIsNot() {
        val t = "在 瑞幸咖啡 消费 ¥23.50"
        val base = 1_700_000_000_000L
        repo.capture(t, "notification", occurredAt = base)
        val sameMinute = repo.capture(t, "notification", occurredAt = base + 30_000)
        assertEquals(0, sameMinute.created)
        assertEquals(1, sameMinute.duplicate)
        val nextMinute = repo.capture(t, "notification", occurredAt = base + 90_000)
        assertEquals(1, nextMinute.created)
        assertEquals(0, nextMinute.duplicate)
    }

    @Test
    fun garbageIsUnsupportedNotQueued() {
        val r = repo.capture("今天天气不错", "notification")
        assertEquals(0, r.created)
        assertEquals(1, r.unsupported)
        assertTrue(repo.pending().isEmpty())
    }

    /**
     * 截屏 OCR 会把浏览器地址栏一起读进来。`127.0.0.1:8765` 里的 127 曾被当成
     * 127.00 元入队，域名 `pay.html` 曾被当成商户名。
     */
    @Test
    fun browserAddressBarIsNotMoneyOrMerchant() {
        val text = "127.0.0.1:8765/pay.html\n微信支付\n支付成功\n¥88.66\n星巴克咖啡"
        val r = repo.capture(text, "screenshot")
        assertEquals(1, r.created)
        val event = repo.pending().single()
        assertEquals(8866L, event.money)
        assertEquals("星巴克咖啡", event.merchant)
    }

    @Test
    fun urlOnlyTextProducesNothing() {
        val r = repo.capture("https://example.com/v1.2/order?id=127.0.0.1", "screenshot")
        assertEquals(0, r.created)
        assertTrue(repo.pending().isEmpty())
    }

    /** 无障碍取到的是「应用名 + 整窗文案」拼在一起的一行，商户在金额后面。 */
    @Test
    fun accessibilityWindowTextKeepsMerchantAfterMoney() {
        val text = "FakeWX 微信支付 支付成功 ¥77.80 商户：麦当劳"
        val r = repo.capture(text, "accessibility", sourceApp = "com.tencent.mm")
        assertEquals(1, r.created)
        val event = repo.pending().single()
        assertEquals(7780L, event.money)
        assertEquals("麦当劳", event.merchant)
        assertEquals("com.tencent.mm", event.source_app)
    }

    /** 通知标题与正文分两段送进来，商户在第二段。 */
    @Test
    fun notificationTitleAndBodySplitAcrossLines() {
        val text = "微信支付\n支付成功 ¥66.60 商户：肯德基"
        val r = repo.capture(text, "notification", sourceApp = "com.tencent.mm")
        assertEquals(1, r.created)
        val event = repo.pending().single()
        assertEquals(6660L, event.money)
        assertEquals("肯德基", event.merchant)
    }
}

/** 票据整图：ML Kit 多行文本一次解析，日期数字不误判成金额，商户取全段。 */
class AutoTicketTest {
    private val db = MeowDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).apply { MeowDatabase.Schema.create(this) })
    private val repo = AutoRepository(db)

    @Test
    fun ticketTextProducesOneEventWithMerchant() {
        val text = "微信支付\n¥42.50\n瑞幸咖啡\n2026年11月12日 01:45"
        val result = repo.captureTicket(text, "ocr")
        assertEquals(1, result.created)
        assertEquals(0, result.unsupported)
        val events = repo.pending()
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals(4250L, event.money)
        assertEquals("瑞幸咖啡", event.merchant)
        assertEquals(9L, event.member_cate_id)
    }

    @Test
    fun plainDateTextIsNotMoney() {
        val result = repo.captureTicket("2026年11月12日 01:45", "ocr")
        assertEquals(0, result.created)
        assertEquals(1, result.unsupported)
    }

    @Test
    fun duplicateTicketIsFolded() {
        val text = "支付成功 ¥15.90 美团外卖"
        repo.captureTicket(text, "ocr")
        val again = repo.captureTicket(text, "ocr")
        assertEquals(0, again.created)
        assertEquals(1, again.duplicate)
    }

    @Test
    fun captureTicketWithImageWritesTicketImageAndConfirmFillsBillImages() {
        val text = "微信支付\n¥12.00\n星巴克\n2026年11月15日 12:00"
        val path = "/tmp/tickets/starbucks.jpg"
        val result = repo.captureTicket(text, "ocr", imagePath = path, thumbPath = "/tmp/tickets/starbucks_thumb.jpg")
        assertEquals(1, result.created)
        val event = repo.pending().single()
        val images = repo.imagesOf(event.id)
        assertEquals(1, images.size)
        assertEquals(path, images.single().path)
        assertEquals("/tmp/tickets/starbucks_thumb.jpg", images.single().thumb_path)

        val bill = repo.confirm(event)
        assertEquals(path, bill.images)
    }

    @Test
    fun duplicateTicketCanAttachImageLater() {
        val text = "支付成功 ¥8.80 便利店"
        repo.captureTicket(text, "ocr")
        val again = repo.captureTicket(text, "ocr", imagePath = "/tmp/tickets/store.jpg")
        assertEquals(1, again.duplicate)
        val event = repo.pending().single()
        assertEquals(1, repo.imagesOf(event.id).size)
        assertEquals("/tmp/tickets/store.jpg", repo.imagesOf(event.id).single().path)
    }
}
