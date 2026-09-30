package com.capyreader.app.common

import android.content.Context
import android.content.Intent
import com.capyreader.app.ui.digest.digestShareText
import com.jocmp.capy.Article

fun Context.shareArticle(article: Article) {
    val text = digestShareText(article) ?: article.url?.toString() ?: return

    val share = Intent.createChooser(Intent().apply {
        type = "text/plain"
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_TITLE, article.title)
    }, null)
    startActivity(share)
}
