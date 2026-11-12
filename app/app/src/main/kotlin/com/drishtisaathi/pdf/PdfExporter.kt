package com.drishtisaathi.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/** Draws [Block]s onto A4 pages with Android's PdfDocument. StaticLayout handles Devanagari shaping and wrapping. */
object PdfExporter {
    private const val PAGE_W = 595 // A4 at 72 dpi
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val CONTENT_W = (PAGE_W - 2 * MARGIN).toInt()

    fun dir(context: Context) = File(context.cacheDir, "pdf").apply { mkdirs() }

    /** All exported files for one session share this prefix, so "delete session" can remove them. */
    fun prefix(sessionId: Long?) = "drishti_${sessionId ?: 0}_"

    fun deleteFor(context: Context, sessionId: Long) {
        dir(context).listFiles { f -> f.name.startsWith(prefix(sessionId)) }?.forEach { it.delete() }
    }

    fun write(context: Context, blocks: List<Block>, file: File): File {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN
            // footer page number
            val p = Paint().apply { color = Color.GRAY; textSize = 9f }
            page!!.canvas.drawText("$pageNo", PAGE_W - MARGIN, PAGE_H - 20f, p)
        }

        fun paint(size: Float, bold: Boolean, tone: Tone) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            color = when (tone) {
                Tone.NORMAL -> Color.rgb(33, 33, 33)
                Tone.MUTED -> Color.rgb(97, 97, 97)
                Tone.URGENT -> Color.rgb(183, 28, 28)
                Tone.ROUTINE -> Color.rgb(141, 110, 0)
                Tone.ACCENT -> Color.rgb(0, 105, 92)
            }
        }

        fun draw(text: String, p: TextPaint, indent: Float = 0f, spaceBefore: Float = 0f, spaceAfter: Float = 4f) {
            val width = (CONTENT_W - indent).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
            if (page == null || y + spaceBefore + layout.height > PAGE_H - MARGIN) newPage() else y += spaceBefore
            val c = page!!.canvas
            c.save(); c.translate(MARGIN + indent, y); layout.draw(c); c.restore()
            y += layout.height + spaceAfter
        }

        newPage()
        for (b in blocks) when (b) {
            is Block.Title -> draw(b.text, paint(18f, true, Tone.ACCENT), spaceAfter = 6f)
            is Block.Heading -> draw(b.text, paint(13f, true, Tone.NORMAL), spaceBefore = 10f)
            is Block.Para -> draw(b.text, paint(10.5f, b.bold, b.tone))
            is Block.Bullet -> draw("•  " + b.text, paint(10.5f, false, b.tone), indent = 10f, spaceAfter = 2f)
            Block.Rule -> {
                if (y + 12 > PAGE_H - MARGIN) newPage()
                page!!.canvas.drawLine(MARGIN, y + 4, PAGE_W - MARGIN, y + 4, Paint().apply { color = Color.LTGRAY; strokeWidth = 1f })
                y += 12f
            }
        }
        page?.let { doc.finishPage(it) }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Share sheet (WhatsApp, email, print...). The file is exposed only through FileProvider with a read grant. */
    fun share(context: Context, file: File, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
