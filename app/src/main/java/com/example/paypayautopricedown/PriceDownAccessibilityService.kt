package com.example.paypayautopricedown

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.*
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

/**
 * PayPay フリマアプリに対して価格自動値下げを行う AccessibilityService
 *
 * 【概要】
 * 1. 商品詳細画面を検知
 * 2. 現在価格を取得し、指定額値下げした価格を算出
 * 3. 編集画面へ遷移
 * 4. 編集画面描画完了を待機
 * 5. 価格入力欄を検知（見つからなければスクロール）
 * 6. 価格を入力
 * 7. 保存ボタンを押下
 * 8. 一覧画面へ戻る
 *
 * AccessibilityService は UI 描画と完全に同期しないため、
 * 「待機」「再探索」「強制スクロール」を組み合わせて安定動作を実現している。
 */
class PriceDownAccessibilityService : AccessibilityService() {
    /**処理状態（状態遷移ベースで制御）*/
    enum class Phase {
        NONE,                      // 商品詳細画面待ち
        WAIT_EDIT,              // 編集画面への遷移待ち
        FORCE_SCROLL,        // 価格欄探索のための強制スクロール
        FIND_PRICE_INPUT,   // スクロール後の価格欄探索
        INPUT_PRICE,           // 価格入力処理
        CLICK_SAVE,             // 保存ボタン押下
        FINISHED                  // 処理完了
    }

    companion object {
        private const val TAG = "PayPayAuto"
        private const val PRICE_DOWN = 200  //値下げ額（必要に応じて変更）
    }
    /** UI 操作タイミング調整用 */
    private val handler = Handler(Looper.getMainLooper())
    /** 現在の処理フェーズ */
    private var phase = Phase.NONE
    /** 現在価格 / 目標価格 */
    private var currentPrice = 0
    private var targetPrice = 0
    /** 価格入力欄ノード（再取得コスト削減用） */
    private var priceInputNode: AccessibilityNodeInfo? = null
    /** スクロール回数制御 */
    private var scrollCount = 0
    private var forceScrollActive = false
    /** 編集画面遷移待ち開始時刻 */
    private var waitEditStartTime = 0L
    private var enteredWaitEdit = false
    private var priceInputDone = false



    /* ========================= */
    /* Service lifecycle */
    /* ========================= */

    override fun onServiceConnected() {
        // AccessibilityEvent を最小限に抑え、安定性を優先
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        Log.d(TAG, "✅ onServiceConnected")
    }

    /* ========================= */
    /* Main event handler */
    /* ========================= */

    /**
     * AccessibilityEvent を受け取り、Phase に応じて処理を進める
     *
     * ※ AccessibilityEvent は大量に発火するため、
     *   Phase 管理をしないと誤動作しやすい
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "jp.co.yahoo.android.paypayfleamarket") return  // 対象アプリ以外は無視
        if (phase == Phase.FINISHED) return  // 完了後は何もしない

        val root = rootInActiveWindow ?: return
        Log.d(TAG, "📣 EVENT phase=$phase")

        when (phase) {

            /* 商品詳細画面で編集ボタンを探す */
            Phase.NONE -> {

                // ★ 商品詳細画面以外では何もしない
                if (!isProductDetailPage(root)) {
                    return
                }

                // 表示中価格を取得
                val price = findPrice(root)
                if (price != null) {
                    currentPrice = price
                    targetPrice = price - PRICE_DOWN
                    Log.d(TAG, "💰 現在価格: $price")
                }

                // 編集ボタン押下
                if (clickEditButton(root)) {
                    phase = Phase.WAIT_EDIT
                    enteredWaitEdit = false
                    scrollCount = 0
                    priceInputDone = false
                    waitEditStartTime = SystemClock.uptimeMillis()
                }
            }

            /* 編集画面遷移後、UI 描画完了を待つ */
            Phase.WAIT_EDIT -> {

                // 編集画面で価格入力欄が見えたら即入力へ
                val priceInput = findPriceInput(root)
                if (priceInput != null) {
                    priceInputNode = priceInput
                    Log.d(TAG, "🟢 Edit screen detected → INPUT_PRICE")
                    phase = Phase.INPUT_PRICE
                    return
                }

                val elapsed = SystemClock.uptimeMillis() - waitEditStartTime

                // ★ 5秒経過したら強制スクロール開始
                if (elapsed > 5000) {
                    Log.d(TAG, "⏬ FORCE_SCROLL by timeout (${elapsed}ms)")
                    forceScrollActive = true
                    phase = Phase.FORCE_SCROLL
                    startForceScrollLoop()
                }
            }

            /* スクロール中はループ処理に委任 */
            Phase.FORCE_SCROLL -> {
                // 何もしない
            }

            /* スクロール後の再探索 */
            Phase.FIND_PRICE_INPUT -> {
                val priceInput = findPriceInput(root)
                if (priceInput != null) {
                    phase = Phase.INPUT_PRICE
                } else {
                    Log.w(TAG, "⚠️ PRICE_INPUT not found yet")
                }
            }

            /* 価格入力処理 */
            Phase.INPUT_PRICE -> {

                val node = priceInputNode ?: findPriceInput(root) ?: return

                // ★ クリック必須（手動タップと同じ状態を作る）
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "✏️ PRICE click")

                //価格入力処理
                handler.postDelayed({

                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            targetPrice.toString()
                        )
                    }

                    val success = node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        args
                    )

                    Log.d(TAG, "💴 PRICE set result=$success")

                    if (success) {
                        // フォーカス解除で確定
                        handler.postDelayed({
                            node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                            Log.d(TAG, "✅ PRICE focus cleared")
                            phase = Phase.CLICK_SAVE
                        }, 300)
                    }

                }, 250)
            }

            /* 保存処理 */
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
    /* Price input */
    /* ========================= */

    /* 価格欄をタップ → 値を入力 → フォーカス解除 */
    private fun executeInputPrice() {
        val node = priceInputNode ?: return

        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "✏️ PRICE click")

        handler.postDelayed({
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    targetPrice.toString()
                )
            }

            val success = node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                args
            )

            Log.d(TAG, "💴 PRICE set result=$success")

            if (success) {
                handler.postDelayed({
                    node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                    Log.d(TAG, "✅ PRICE focus cleared")
                    phase = Phase.CLICK_SAVE
                }, 300)
            }
        }, 300)
    }

    /* ========================= */
    /* SCROLL */
    /* ========================= */

    private fun startForceScrollLoop() {
        handler.post(object : Runnable {
            override fun run() {

                if (!forceScrollActive) return
                if (phase != Phase.FORCE_SCROLL) return

                val root = rootInActiveWindow ?: return

                val input = findPriceInput(root)
                //FORCE_SCROLL 停止時に直接 INPUT_PRICE 処理を呼ぶ
                if (input != null) {
                    handler.postDelayed({
                        priceInputNode = input
                        forceScrollActive = false
                        phase = Phase.INPUT_PRICE
                        Log.d(TAG, "🟢 FORCE_SCROLL stopped → INPUT_PRICE")
                        executeInputPrice()
                    }, 150)
                    return
                }

                performScroll(root)
                scrollCount++

                if (scrollCount < 10) {
                    handler.postDelayed(this, 400)
                } else {
                    forceScrollActive = false
                    phase = Phase.FIND_PRICE_INPUT
                }
            }
        })
    }

    /**
     * 画面を強制的にスクロールする処理
     * ・初期表示では、画面外にある UI 要素は取得できない
     * ・端末解像度やフォントサイズにより、価格入力欄が初期表示で画面外に存在するケースがある
     */
    private fun performScroll(root: AccessibilityNodeInfo): Boolean {
        val container = findScrollable(root)
        if (container == null) {
            Log.w(TAG, "❌ scrollable container not found")
            return false
        }
        Log.d(TAG, "📜 SCROLL action")
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
    /* Find / click helpers */
    /* ========================= */

    private fun findPriceInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        return nodes.firstOrNull { node ->
            node.className == "android.widget.EditText"
                    && node.isEnabled
                    && node.isVisibleToUser
                    && (
                    // ① 既に価格が入っているケース
                    node.text?.toString()?.replace(",", "")?.toIntOrNull() != null
                            ||
                            // ② hint が価格系のケース（端末差対策）
                            node.hintText?.toString()?.contains("価格") == true
                    )
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
    /* Utils */
    /* ========================= */

    /**
     * ルートノード配下に存在する AccessibilityNodeInfo を再帰的に収集する
     *
     * AccessibilityService には「画面上の全ノード一覧」を直接取得する API が存在しないため、
     * root → child → child… とツリーを手動で辿る必要がある。
     */
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

    //商品詳細画面かを判定
    private fun isProductDetailPage(root: AccessibilityNodeInfo): Boolean {
        return findNodeByText(root, "編集") != null
    }

/**
 * 指定したテキストを含むノードを画面全体から検索する
 * ・「編集」「保存」など、ID が取得できない UI 要素を検知するために使用
 *
 * 【注意点】
 * ・UI テキストはアプリ側の文言変更に影響されやすい
 * ・完全一致ではなく「含む」で検索しているため、誤検知防止には呼び出し側で用途を限定する
 */
    private fun findNodeByText(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text) == true) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                val r = findNodeByText(it, text)
                if (r != null) return r
            }
        }
        return null
    }
}
