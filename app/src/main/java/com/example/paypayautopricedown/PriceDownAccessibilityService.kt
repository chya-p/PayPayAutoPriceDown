package com.example.paypayautopricedown

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.*
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

class PriceDownAccessibilityService : AccessibilityService() {

    enum class Phase {
        NONE,
        WAIT_EDIT,
        FORCE_SCROLL,
        FIND_PRICE_INPUT,
        INPUT_PRICE,
        CLICK_SAVE,
        FINISHED
    }

    companion object {
        private const val TAG = "PayPayAuto"
        private const val PRICE_DOWN = 200
    }

    private val handler = Handler(Looper.getMainLooper())

    private var phase = Phase.NONE
    private var currentPrice = 0
    private var scrollCount = 0
    private var targetPrice = 0
    private var waitEditScheduled = false
    private var priceInputNode: AccessibilityNodeInfo? = null

    /* ========================= */

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        Log.d(TAG, "✅ onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "jp.co.yahoo.android.paypayfleamarket") return
        if (phase == Phase.FINISHED) return

        val root = rootInActiveWindow ?: return
        Log.d(TAG, "📣 EVENT phase=$phase")

        when (phase) {

            Phase.NONE -> {
                val price = findPrice(root) ?: return
                currentPrice = price
                targetPrice = price - PRICE_DOWN
                Log.d(TAG, "💰 現在価格: $price")

                if (clickEditButton(root)) {
                    phase = Phase.WAIT_EDIT
                }
            }

            Phase.WAIT_EDIT -> {
                if (!waitEditScheduled) {
                    waitEditScheduled = true
                    handler.postDelayed({
                        Log.d(TAG, "⏱ 編集画面安定 → FORCE_SCROLL")
                        scrollCount = 0
                        phase = Phase.FORCE_SCROLL
                        startForceScrollLoop()
                    }, 1500)
                }
            }

            Phase.FORCE_SCROLL -> {
                val r = rootInActiveWindow ?: return

                val priceInput = findPriceInput(r)
                if (priceInput != null) {
                    Log.d(TAG, "🛑 STOP scroll → INPUT_PRICE")
                    phase = Phase.INPUT_PRICE
                    return
                }

                performScroll(r)
            }

            Phase.FIND_PRICE_INPUT -> {
                val priceInput = findPriceInput(root)
                if (priceInput != null) {
                    phase = Phase.INPUT_PRICE
                } else {
                    Log.w(TAG, "⚠️ PRICE_INPUT not found yet")
                }
            }

            Phase.INPUT_PRICE -> {
                val node = priceInputNode ?: findPriceInput(root) ?: return

                // フォーカス
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                // テキストセット
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        targetPrice.toString()
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                Log.d(TAG, "✏️ PRICE set to $targetPrice")

                // ★ 重要：フォーカスを外して確定させる
                handler.postDelayed({
                    node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                    Log.d(TAG, "✅ PRICE focus cleared")
                    phase = Phase.CLICK_SAVE
                }, 300)
            }

            Phase.CLICK_SAVE -> {
                handler.postDelayed({
                    val r = rootInActiveWindow ?: return@postDelayed

                    // 保存ボタンが見えるまで軽くスクロール
                    if (findSaveButton(r) == null) {
                        performScroll(r)
                        Log.d(TAG, "📜 scroll to SAVE")
                        return@postDelayed
                    }

                    if (clickSaveButton(r)) {
                        Log.d(TAG, "✅ 保存押下")
                        phase = Phase.FINISHED

                        // ★ 一覧に戻る
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            Log.d(TAG, "↩️ BACK to list")
                        }, 800)
                    }
                }, 500)
            }

            else -> {}
        }
    }

    override fun onInterrupt() {}

    /* ========================= */
    /* FORCE SCROLL LOOP */
    /* ========================= */

    private fun startForceScrollLoop() {
        handler.post(object : Runnable {
            override fun run() {
                if (phase != Phase.FORCE_SCROLL) return

                val root = rootInActiveWindow ?: return

                // ★ 最優先：価格入力欄が見えたら即停止
                val input = findPriceInput(root)
                if (input != null) {
                    priceInputNode = input
                    logRect("PRICE_INPUT_VISIBLE", input)
                    phase = Phase.INPUT_PRICE
                    return
                }

                // まだ見えていなければ下へスクロール
                performScroll(root)
                Log.d(TAG, "📜 FORCE_SCROLL count=$scrollCount")

                scrollCount++
                if (scrollCount < 15) {
                    handler.postDelayed(this, 400)
                } else {
                    Log.w(TAG, "⛔ FORCE_SCROLL timeout")
                    phase = Phase.FINISHED
                }
            }
        })
    }

    /* ========================= */
    /* SCROLL */
    /* ========================= */

    private fun performScroll(root: AccessibilityNodeInfo): Boolean {
        val container = findScrollable(root) ?: return false
        logRect("SCROLL_CONTAINER", container)
        return container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestHeight = 0

        if (node.isScrollable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.height() > bestHeight) {
                best = node
                bestHeight = r.height()
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                val c = findScrollable(it)
                if (c != null) {
                    val r = Rect()
                    c.getBoundsInScreen(r)
                    if (r.height() > bestHeight) {
                        best = c
                        bestHeight = r.height()
                    }
                }
            }
        }
        return best
    }

    /* ========================= */
    /* FINDERS */
    /* ========================= */

    private fun findPriceInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        return nodes.firstOrNull { node ->
            node.className == "android.widget.EditText" &&
                    node.text?.toString()?.replace(",", "")?.toIntOrNull() != null
        }
    }

    private fun clickEditButton(node: AccessibilityNodeInfo): Boolean {
        if (node.text?.toString()?.contains("編集") == true && node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "✏️ 編集クリック")
            return true
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { if (clickEditButton(it)) return true }
        }
        return false
    }

    private fun clickSaveButton(node: AccessibilityNodeInfo): Boolean {
        val t = node.text?.toString()?.trim()
        if (t == "変更する" || t == "保存" || t == "完了") {
            (if (node.isClickable) node else node.parent)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { if (clickSaveButton(it)) return true }
        }
        return false
    }

    private fun findPrice(node: AccessibilityNodeInfo): Int? {
        val t = node.text?.toString() ?: ""
        if (t.matches(Regex("\\d{1,3}(,\\d{3})+"))) {
            return t.replace(",", "").toInt()
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                val r = findPrice(it)
                if (r != null) return r
            }
        }
        return null
    }


    private fun findSaveButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val t = node.text?.toString()?.trim()

        if (t == "変更する" || t == "保存" || t == "完了") {
            Log.d(TAG, "💾 SAVE_BUTTON visible: $t")
            return node
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                val r = findSaveButton(it)
                if (r != null) return r
            }
        }
        return null
    }

    /* ========================= */
    /* INPUT (SET_TEXT) */
    /* ========================= */


    /* ========================= */

    private fun logRect(tag: String, node: AccessibilityNodeInfo) {
        val r = Rect()
        node.getBoundsInScreen(r)
        Log.d(TAG, "📐 $tag top=${r.top} bottom=${r.bottom} h=${r.height()}")
    }
    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        out.add(node)
        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), out)
        }
    }
}
