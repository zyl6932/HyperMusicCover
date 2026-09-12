package com.os4.musiccover.ui.component.markdown

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.os4.musiccover.ui.util.isInDarkTheme
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private const val TAG = "MarkdownContent"

/** The longest a changelog may grow before it scrolls inside itself rather than the dialog. */
private val MAX_HEIGHT = 360.dp

/**
 * A release's notes, rendered as Markdown.
 *
 * The pipeline is KernelSU's, and so is the way it appears. CommonMark turns the notes into HTML
 * and a `WebView` renders that against the `github-markdown-css` sheets in `assets/webview/`.
 * Building that `WebView` costs a frame or two, and sizing it costs another, so the content is
 * held at zero alpha behind an indeterminate indicator until the page reports itself painted -
 * otherwise the dialog opens at the wrong height, paints a blank block, and then jumps. The
 * container animates its size for the same reason.
 *
 * Left out of KernelSU's version is everything that had no work to do here: the
 * horizontal-scroll-indicator Javascript, the OkHttp interceptor that proxies remote images, and
 * the two-UI-mode colour plumbing.
 */
@Composable
fun MarkdownContent(content: String, modifier: Modifier = Modifier) {
    var loaded by remember(content) { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "markdownContentAlpha",
    )
    val placeholderAlpha by animateFloatAsState(
        targetValue = if (loaded) 0f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "markdownPlaceholderAlpha",
    )

    Box(modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 300))) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = MAX_HEIGHT)
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .graphicsLayer { alpha = contentAlpha },
        ) {
            MarkdownWebView(content = content, onPainted = { loaded = true })
        }
        if (placeholderAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .graphicsLayer { alpha = placeholderAlpha },
                contentAlignment = Alignment.Center,
            ) {
                InfiniteProgressIndicator()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownWebView(content: String, onPainted: () -> Unit) {
    val context = LocalContext.current
    val isDark = isInDarkTheme()
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "rtl" else "ltr"

    // The WebView has its own text scaling on top of the system's; matching the two keeps the
    // notes the same size as the rest of the dialog under a non-default font scale.
    val density = LocalDensity.current
    val systemDensity = LocalResources.current.displayMetrics.density
    val zoom = (90 * (density.density / systemDensity) * density.fontScale).toInt()

    val template = remember(isDark) {
        val name = if (isDark) "webview/template_dark.html" else "webview/template.html"
        context.assets.open(name).bufferedReader().use { it.readText() }
    }

    val style = styleSheet()
    val html = remember(content, template, dir, style) {
        template
            .replace("@dir@", dir)
            .replace("@style@", style)
            .replace("@body@", renderMarkdown(content))
    }

    AndroidView(
        // WRAP_CONTENT so the WebView lays the whole changelog out at its natural height and the
        // Compose scroll above is what moves it; a WebView scrolling inside a scrolling dialog
        // fights the dialog for every drag.
        modifier = Modifier.fillMaxWidth(),
        factory = { viewContext ->
            WebView(viewContext).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
                settings.apply {
                    offscreenPreRaster = true
                    allowContentAccess = false
                    allowFileAccess = false
                    setSupportZoom(false)
                    textZoom = zoom
                    cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
                webViewClient = object : WebViewClient() {
                    private val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler(
                            "/assets/",
                            WebViewAssetLoader.AssetsPathHandler(viewContext),
                        )
                        .build()

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ) = assetLoader.shouldInterceptRequest(request.url)

                    // Called once the page has painted, which is the first moment the WebView has
                    // a height worth showing.
                    override fun onPageCommitVisible(view: WebView, url: String) {
                        onPainted()
                    }

                    // A changelog is full of links - the "Full Changelog" compare URL at the very
                    // least - and none of them should navigate this WebView away from the notes.
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        try {
                            viewContext.startActivity(
                                Intent(Intent.ACTION_VIEW, request.url)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: ActivityNotFoundException) {
                            Log.w(TAG, "no activity for ${request.url}")
                        }
                        return true
                    }
                }
                loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/assets/webview/template.html",
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

private val extensions = listOf(
    TablesExtension.create(),
    StrikethroughExtension.builder().requireTwoTildes(true).build(),
    AutolinkExtension.create(),
    TaskListItemsExtension.create(),
)

private val parser = Parser.builder().extensions(extensions).build()
private val renderer = HtmlRenderer.builder().extensions(extensions).build()

private fun renderMarkdown(content: String): String = renderer.render(parser.parse(content))

/**
 * The theme, as CSS variables.
 *
 * `github-markdown-css` reads all of its colours from these names, so setting them is the whole
 * of what it takes to make the rendered notes match the dialog around them. The background is
 * transparent on purpose: the dialog's own surface is what should show through.
 */
@Composable
private fun styleSheet(): String = """
    :root {
        --background: transparent;
        --pre-background: ${colorScheme.surfaceContainer.toCss()};
        --code-background: ${colorScheme.surfaceContainer.toCss()};
        --tr-alt-background: ${colorScheme.surfaceContainer.toCss()};
        --thead-background: ${colorScheme.surfaceContainer.toCss()};
        --textPrimary: ${colorScheme.onSurface.toCss()};
        --link: ${colorScheme.primary.toCss()};
    }
    html, body { margin: 0; padding: 0; background: transparent; }
    .markdown-body { padding: 0; background: transparent; }
    .markdown-body > :first-child { margin-top: 0; }
    .markdown-body > :last-child { margin-bottom: 0; }
    img, video { max-width: 100%; height: auto; }
""".trimIndent()

private fun Color.toCss(): String {
    val argb = toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val a = (argb ushr 24) / 255f
    return "rgba($r, $g, $b, $a)"
}
