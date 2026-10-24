package com.jarvis.app.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.core.brain.SearchSource

/**
 * Grounding with Google Search requires the Search Suggestions HTML to be shown
 * unchanged, and the source links to be shown as well. Speech never reads the URLs.
 * The shelf is short, so the widget is height-capped. The HTML itself is not edited.
 * A tap opens the link directly. There is no page between the tap and the browser.
 */
@Composable
fun SearchGroundingBlock(
    sources: List<SearchSource>,
    html: String?,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty() && html.isNullOrBlank()) return
    val context = LocalContext.current
    Column(modifier) {
        sources.take(4).forEach { source ->
            Text(
                text = source.title,
                modifier = Modifier.clickable { openHttp(context, source.uri) },
                color = color,
                fontSize = fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!html.isNullOrBlank()) {
            AndroidView(
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        isVerticalScrollBarEnabled = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                openHttp(viewContext, request.url?.toString().orEmpty())
                                return true
                            }
                        }
                    }
                },
                update = { web ->
                    if (web.tag != html) {
                        web.tag = html
                        web.loadDataWithBaseURL(
                            "https://www.google.com",
                            html,
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            )
        }
    }
}

private fun openHttp(context: android.content.Context, uri: String) {
    val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return
    val scheme = parsed.scheme?.lowercase() ?: return
    if (scheme != "http" && scheme != "https") return
    val intent = Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
