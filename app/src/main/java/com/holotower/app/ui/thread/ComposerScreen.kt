package com.holotower.app.ui.thread

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val COMPOSER_CLOSE_THRESHOLD = 90f
private const val SUCCESS_POLL_MS = 800L
private const val SUCCESS_POLL_LIMIT_MS = 60_000L
private val threadPageRegex = Regex("""/[^/]+/res/(\d+)\.html""", RegexOption.IGNORE_CASE)

@Composable
fun NewThreadComposerScreen(
    board: String,
    onBack: () -> Unit,
    onPosted: (newThreadNo: Long?) -> Unit = { onBack() }
) {
    ReplyComposerOverlay(
        board = board,
        threadNo = null,
        replyToNo = null,
        onClose = onBack,
        onPosted = onPosted
    )
}

/**
 * The site's own post form, loaded in a WebView and restyled. Post images on the page
 * are not downloaded. When a post goes through, [onPosted] gets the thread number
 * (the new thread for a new-thread post, when the site says which).
 */
@Composable
internal fun ReplyComposerOverlay(
    board: String,
    threadNo: Long?,
    replyToNo: Long?,
    onClose: () -> Unit,
    onPosted: (threadNo: Long?) -> Unit
) {
    val context = LocalContext.current
    val currentOnPosted by rememberUpdatedState(onPosted)
    val pageUrl = remember(board, threadNo) {
        if (threadNo != null && threadNo > 0L) {
            "https://holotower.org/$board/res/$threadNo.html"
        } else {
            "https://holotower.org/$board/index.html"
        }
    }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = 0f
        }
    }
    var isComposerReady by remember(pageUrl, replyToNo) { mutableStateOf(false) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    // Set when the form is sent (seen as a POST to post.php), cleared on success.
    val submitted = remember { AtomicBoolean(false) }
    var posted by remember { mutableStateOf(false) }
    var submitToken by remember { mutableIntStateOf(0) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    fun reportPosted(postedThreadNo: Long?) {
        if (posted) return
        posted = true
        submitted.set(false)
        Toast.makeText(context, "Posted", Toast.LENGTH_SHORT).show()
        currentOnPosted(postedThreadNo ?: threadNo)
    }

    BackHandler(onBack = onClose)

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    DisposableEffect(pageUrl, replyToNo) {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
                if (intent == null) {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = null
                    return false
                }
                filePickerLauncher.launch(intent)
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val path = request?.url?.path.orEmpty()
                if (request?.method.equals("POST", ignoreCase = true) && path.endsWith("/post.php")) {
                    submitted.set(true)
                    view?.post { submitToken += 1 }
                    return null
                }
                // Post images and thumbnails are hidden by the restyle anyway; don't download them.
                if (path.startsWith("/$board/thumb/") || path.startsWith("/$board/src/")) {
                    return WebResourceResponse("image/png", null, ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val raw = request?.url?.toString().orEmpty()
                if (submitted.get()) {
                    // After a post the site sends us to the thread (or the board index).
                    threadPageRegex.find(raw)?.let {
                        reportPosted(it.groupValues[1].toLongOrNull())
                        return true
                    }
                }
                val normalized = raw.lowercase()
                val boardRoot = "https://holotower.org/$board/"
                val boardIndex = "https://holotower.org/$board/index.html"
                val boardCatalog = "https://holotower.org/$board/catalog.html"
                if (normalized == boardRoot || normalized.startsWith(boardIndex) || normalized.startsWith(boardCatalog)) {
                    if (submitted.get()) {
                        reportPosted(null)
                    } else {
                        view?.loadUrl(pageUrl)
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (submitted.get() && url != null) {
                    // Server redirects after the POST land here without shouldOverrideUrlLoading.
                    threadPageRegex.find(url)?.let {
                        view?.stopLoading()
                        reportPosted(it.groupValues[1].toLongOrNull())
                        return
                    }
                }
                isComposerReady = false
                view?.alpha = 0f
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (posted) return
                view.evaluateJavascript(composerScript(replyToNo)) {
                    isComposerReady = true
                    view.alpha = 1f
                }
            }
        }

        webView.loadUrl(pageUrl)
        onDispose {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            webView.stopLoading()
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = WebViewClient()
        }
    }

    // The site may post in the background (no page change). Its script then clears the
    // form; seeing the form go from filled to empty after a send means it worked.
    LaunchedEffect(submitToken) {
        if (submitToken == 0) return@LaunchedEffect
        var waited = 0L
        while (!posted && submitted.get() && waited < SUCCESS_POLL_LIMIT_MS) {
            delay(SUCCESS_POLL_MS)
            waited += SUCCESS_POLL_MS
            if (readFormState(webView) == "cleared") reportPosted(threadNo)
        }
    }

    HorizontalSwipeLayer(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE000000)),
        enabled = true,
        threshold = COMPOSER_CLOSE_THRESHOLD,
        allowPositive = true,
        allowNegative = false,
        positiveCueLabel = null,
        negativeCueLabel = null,
        cueStartFraction = 0.88f,
        onCommit = { onClose() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val title = when {
                    threadNo == null || threadNo <= 0L -> "New Thread"
                    replyToNo != null -> "Reply >>$replyToNo"
                    else -> "Reply"
                }
                Text(text = title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.weight(1f))
                BottomOverlayButton(text = "Close", onClick = onClose)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { webView },
                    update = { }
                )
                if (!isComposerReady) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0A0A0A)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = LoadingGreen)
                    }
                }
            }
        }
    }
}

/** "cleared" once a form that was sent with content has been emptied by the site. */
private suspend fun readFormState(webView: WebView): String = suspendCancellableCoroutine { cont ->
    webView.evaluateJavascript(
        """
        (function() {
          var f = document.querySelector("form[name='post']");
          if (!f || !window.__htSent) return 'unknown';
          var ta = f.querySelector('textarea');
          var file = f.querySelector("input[type='file']");
          var sub = f.querySelector("input[type='submit']");
          var busy = sub && sub.disabled;
          var empty = (!ta || ta.value.trim() === '') && (!file || !file.value);
          return (!busy && empty) ? 'cleared' : 'filled';
        })();
        """.trimIndent()
    ) { raw ->
        if (cont.isActive) cont.resume(raw?.trim('"').orEmpty())
    }
}

private fun composerScript(replyToNo: Long?): String {
    val quoteScript = if (replyToNo != null) {
        """
          var quote = ">>$replyToNo\n";
          if (!ta.value || ta.value.indexOf(quote.trim()) !== 0) {
            ta.value = quote + ta.value;
          }
        """.trimIndent()
    } else {
        ""
    }
    return """
        (function() {
          document.querySelectorAll("link[rel='stylesheet'], style").forEach(function(el) {
            el.remove();
          });

          var form = document.querySelector("form[name='post']");
          if (!form) return "no-form";

          document.body.innerHTML = "";
          document.body.appendChild(form);
          document.documentElement.style.background = "#0A0A0A";
          document.body.style.background = "#0A0A0A";
          document.body.style.color = "#EAEAEA";
          document.body.style.margin = "0";
          document.body.style.padding = "10px";
          document.body.style.fontFamily = "sans-serif";

          var style = document.createElement("style");
          style.textContent = `
            * { box-sizing: border-box !important; }
            form[name="post"] { max-width: 900px !important; margin: 0 auto !important; }
            form[name="post"] table { width: 100% !important; border-collapse: collapse !important; }
            form[name="post"] tr { display: block !important; margin: 0 0 10px 0 !important; background: transparent !important; }
            form[name="post"] th, form[name="post"] td {
              display: block !important;
              width: 100% !important;
              margin: 0 !important;
              padding: 0 !important;
              border: none !important;
              background: transparent !important;
            }
            form[name="post"] th {
              color: #9CA3AF !important;
              font-size: 12px !important;
              font-weight: 600 !important;
              margin-bottom: 6px !important;
              text-align: left !important;
            }
            form[name="post"] input[type="text"],
            form[name="post"] input[type="password"],
            form[name="post"] input[type="file"],
            form[name="post"] textarea {
              width: 100% !important;
              background: #16181B !important;
              color: #EAEAEA !important;
              border: 1px solid #2A2F35 !important;
              border-radius: 10px !important;
              padding: 10px 12px !important;
              outline: none !important;
              font-size: 14px !important;
            }
            form[name="post"] textarea { min-height: 220px !important; line-height: 1.4 !important; }
            form[name="post"] input[type="submit"] {
              background: #00FF9F !important;
              color: #03281A !important;
              border: none !important;
              border-radius: 999px !important;
              padding: 9px 16px !important;
              font-weight: 700 !important;
              margin-top: 8px !important;
              cursor: pointer !important;
            }
            form[name="post"] .unimportant { color: #7B8088 !important; font-size: 11px !important; }
            #upload_settings label, #upload_settings { color: #C8CDD4 !important; }
          `;
          document.head.appendChild(style);

          // Remember that something was sent, so a cleared form later means success.
          form.addEventListener("submit", function() {
            var t = form.querySelector("textarea");
            var f = form.querySelector("input[type='file']");
            window.__htSent = !!((t && t.value.trim()) || (f && f.value));
          }, true);

          var ta = form.querySelector("textarea[name='body'], textarea[name='com'], textarea#body, textarea#com, textarea");
          if (!ta) return "no-textarea";
          $quoteScript
          ta.focus();
          if (ta.setSelectionRange) {
            var len = ta.value.length;
            ta.setSelectionRange(len, len);
          }
          return "ok";
        })();
    """.trimIndent()
}
