package com.drishtisaathi.profile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.ui.DS
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

/** QR encoding with ZXing core (Apache-2.0). */
object Qr {
    /** The QR modules of [text] (UTF-8, error correction M, a 2-module quiet zone). */
    fun matrix(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
    )

    fun bitmap(text: String, sizePx: Int): Bitmap {
        val m = matrix(text)
        val scale = maxOf(1, sizePx / m.width)
        val bmp = Bitmap.createBitmap(m.width * scale, m.height * scale, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val p = Paint().apply { color = android.graphics.Color.BLACK }
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) {
            c.drawRect((x * scale).toFloat(), (y * scale).toFloat(), ((x + 1) * scale).toFloat(), ((y + 1) * scale).toFloat(), p)
        }
        return bmp
    }
}

/** The QR card on the result screen: the code (always black on white) and one line saying what it holds. */
@Composable
fun QrCard(lang: Lang, content: String, isLink: Boolean) {
    val c = DS.colors
    val m = remember(content) { runCatching { Qr.matrix(content) }.getOrNull() }
    SoftCard {
        Text(SProfile.qrTitle[lang], style = MaterialTheme.typography.titleLarge, color = c.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (m != null) Box(Modifier.background(Color.White, DS.button).padding(8.dp)) {
                Canvas(Modifier.size(148.dp).semantics { contentDescription = SProfile.qrTitle[lang] }) {
                    val s = size.width / m.width
                    for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) drawRect(Color.Black, Offset(x * s, y * s), Size(s + 0.5f, s + 0.5f))
                }
            }
            Text((if (isLink) SProfile.qrLink else SProfile.qrOffline)[lang], Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.inkSecondary)
        }
    }
}

/** The result summary as a picture (white card: title, lines, QR), for the share sheet. */
object ShareImage {
    fun render(context: Context, title: String, lines: List<String>, qr: String): Bitmap {
        val w = 1080; val pad = 64f
        val regular = ResourcesCompat.getFont(context, com.drishtisaathi.R.font.inter_deva_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, com.drishtisaathi.R.font.inter_deva_bold) ?: Typeface.DEFAULT_BOLD
        fun paint(size: Float, face: Typeface, colour: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; color = colour }
        fun layout(t: String, p: TextPaint) = StaticLayout.Builder.obtain(t, 0, t.length, p, (w - 2 * pad).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(6f, 1f).build()
        val ink = android.graphics.Color.rgb(11, 27, 43)
        val head = layout(title, paint(56f, bold, ink))
        val body = lines.map { layout(it, paint(34f, regular, ink)) }
        val code = Qr.bitmap(qr, 520)
        val h = (pad + head.height + 32 + body.sumOf { it.height + 14 } + 40 + code.height + pad).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        var y = pad
        c.save(); c.translate(pad, y); head.draw(c); c.restore(); y += head.height + 32
        for (l in body) { c.save(); c.translate(pad, y); l.draw(c); c.restore(); y += l.height + 14 }
        c.drawBitmap(code, (w - code.width) / 2f, y + 40, null)
        return bmp
    }

    /** Saves the picture next to the session's PDFs (deleted with the session) and opens the share sheet, WhatsApp first. */
    fun share(context: Context, bmp: Bitmap, sessionId: Long?, title: String) {
        val file = File(com.drishtisaathi.pdf.PdfExporter.dir(context), com.drishtisaathi.pdf.PdfExporter.prefix(sessionId) + "summary.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        fun send(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pkg?.let(::setPackage)
        }
        // WhatsApp directly when it is installed; else the phone's share sheet
        runCatching { context.startActivity(send("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { context.startActivity(Intent.createChooser(send(null), title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** "Share on WhatsApp" is only possible after the customer agrees: a checkbox, then Share. */
@Composable
fun ShareConsentDialog(lang: Lang, onShare: () -> Unit, onDismiss: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SProfile.shareTitle[lang]) },
        text = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                Text(SProfile.shareConsent[lang], style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = { TextButton(onClick = onShare, enabled = agreed) { Text(SProfile.shareNow[lang]) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(com.drishtisaathi.i18n.S.cancel[lang]) } },
    )
}
