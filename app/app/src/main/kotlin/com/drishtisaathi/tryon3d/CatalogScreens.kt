package com.drishtisaathi.tryon3d

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drishtisaathi.engine.tryon3d.CatalogFilter
import com.drishtisaathi.engine.tryon3d.CatalogOrder
import com.drishtisaathi.engine.tryon3d.CatalogSort
import com.drishtisaathi.engine.tryon3d.Frame3D
import com.drishtisaathi.engine.tryon3d.Mat4
import com.drishtisaathi.i18n.Lang
import com.drishtisaathi.i18n.S3D
import com.drishtisaathi.ui.AppScaffold
import com.drishtisaathi.ui.BigButton
import com.drishtisaathi.ui.Teal
import java.util.Locale

fun rupees(p: Int) = "₹" + String.format(Locale.US, "%,d", p)

@Composable
fun FrameThumb(frame: Frame3D, thumb: suspend (Frame3D) -> Bitmap?, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, frame.id) { value = runCatching { thumb(frame) }.getOrNull() }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), frame.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FacetChip(label: String, current: String?, options: List<String>, l: Lang, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != null, onClick = { open = true },
            label = { Text(if (current == null) "$label: " + S3D.all[l] else S3D.label(current)[l] + " ×") },
        )
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(S3D.all[l]) }, onClick = { onPick(null); open = false })
            options.forEach { o -> DropdownMenuItem(text = { Text(S3D.label(o)[l]) }, onClick = { onPick(o); open = false }) }
        }
    }
}

/**
 * Frame catalogue: search, category, filters (shape, rim, colour, material, price, size) and sort
 * ("recommended for you" uses the existing ranking engine; then price).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Catalog3DScreen(
    lang: Lang,
    onLang: (Lang) -> Unit,
    frames: List<Frame3D>,
    online: Boolean,
    recommendedIds: List<String>,
    thumb: suspend (Frame3D) -> Bitmap?,
    onOpen: (Frame3D) -> Unit,
    onBack: () -> Unit,
) {
    val l = lang
    var filter by remember { mutableStateOf(CatalogFilter(category = Frame3D.CATEGORY_EYEGLASSES)) }
    var sort by remember { mutableStateOf(if (recommendedIds.isEmpty()) CatalogSort.PRICE_LOW else CatalogSort.RECOMMENDED) }
    val shown = remember(frames, filter, sort, recommendedIds) { CatalogOrder.sort(filter.apply(frames), sort, recommendedIds) }
    fun facet(sel: (Frame3D) -> String) = frames.map(sel).distinct().sorted()

    AppScaffold(l, onLang, title = S3D.catalogTitle[l], onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                filter.query, { filter = filter.copy(query = it) }, singleLine = true,
                placeholder = { Text(S3D.search[l]) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(filter.category == Frame3D.CATEGORY_EYEGLASSES, { filter = filter.copy(category = Frame3D.CATEGORY_EYEGLASSES) }, { Text(S3D.eyeglasses[l]) })
                }
                item {
                    FilterChip(filter.category == Frame3D.CATEGORY_SUNGLASSES, { filter = filter.copy(category = Frame3D.CATEGORY_SUNGLASSES) }, { Text(S3D.sunglasses[l]) })
                }
                item { FilterChip(filter.category == null, { filter = filter.copy(category = null) }, { Text(S3D.all[l]) }) }
                item {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        FilterChip(true, { open = true }, {
                            Text(when (sort) {
                                CatalogSort.RECOMMENDED -> S3D.recommended[l]
                                CatalogSort.PRICE_LOW -> S3D.priceLow[l]
                                CatalogSort.PRICE_HIGH -> S3D.priceHigh[l]
                            })
                        })
                        DropdownMenu(open, { open = false }) {
                            if (recommendedIds.isNotEmpty()) DropdownMenuItem({ Text(S3D.recommended[l]) }, { sort = CatalogSort.RECOMMENDED; open = false })
                            DropdownMenuItem({ Text(S3D.priceLow[l]) }, { sort = CatalogSort.PRICE_LOW; open = false })
                            DropdownMenuItem({ Text(S3D.priceHigh[l]) }, { sort = CatalogSort.PRICE_HIGH; open = false })
                        }
                    }
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FacetChip(S3D.shape[l], filter.shape, facet { it.shape }, l) { filter = filter.copy(shape = it) } }
                item { FacetChip(S3D.rim[l], filter.rim, facet { it.rim }, l) { filter = filter.copy(rim = it) } }
                item { FacetChip(S3D.colour[l], filter.colour, facet { it.colour }, l) { filter = filter.copy(colour = it) } }
                item { FacetChip(S3D.material[l], filter.material, facet { it.material }, l) { filter = filter.copy(material = it) } }
                item { FacetChip(S3D.price[l], filter.priceBand, listOf("budget", "mid", "premium"), l) { filter = filter.copy(priceBand = it) } }
                item { FacetChip(S3D.size[l], filter.sizeBand, listOf("small", "medium", "large"), l) { filter = filter.copy(sizeBand = it) } }
                item { TextButton({ filter = CatalogFilter(category = filter.category) }) { Text(S3D.clearFilters[l]) } }
            }
            Text(
                S3D.count.format(shown.size)[l] + " · " + (if (online) S3D.online[l] else S3D.offline[l]),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
            )
            if (shown.isEmpty()) Text(S3D.noMatch[l], Modifier.padding(16.dp))
            LazyVerticalGrid(
                GridCells.Adaptive(150.dp), Modifier.weight(1f),
                contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.id }) { f ->
                    Card(
                        Modifier.clickable { onOpen(f) }, shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            FrameThumb(f, thumb, Modifier.fillMaxWidth().aspectRatio(2.4f))
                            Text(f.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(rupees(f.priceInr) + " · " + f.sizes.label + if (f.sizesEstimated) "*" else "",
                                style = MaterialTheme.typography.bodySmall, color = Teal, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Text("* " + S3D.demoCatalog[l], Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 3D frame viewer: rotate 360° by touch, zoom, sizes / material / colour with an estimated or measured label. */
@Composable
fun FrameViewer3DScreen(
    lang: Lang,
    onLang: (Lang) -> Unit,
    frame: Frame3D,
    glb: suspend (Frame3D) -> ByteArray,
    thumb: suspend (Frame3D) -> Bitmap?,
    favourite: Boolean,
    onFavourite: () -> Unit,
    onLive: () -> Unit,
    onAvatar: () -> Unit,
    onBack: () -> Unit,
) {
    val l = lang
    val render = rememberRender3D(transparent = false)
    val orbit = remember { Orbit().also { it.yawDeg = 25f } }
    var failed by remember { mutableStateOf(render == null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(frame.id, render) {
        val r = render ?: return@LaunchedEffect
        loaded = false
        failed = runCatching {
            r.load("frame", glb(frame))
            r.setNodeVisible("frame", "contact_shadow", false)
            r.setSunDirection(-0.25f, -0.6f, 0.75f)
        }.isFailure
        loaded = !failed
    }
    DisposableEffect(render) {
        render?.onFrame = {
            render.perspectiveFront(330.0 / orbit.zoom)
            // turn about a point between the front and the temples so the frame stays centred
            render.setRoot("frame", Mat4.mul(
                Mat4.rotX(Math.toRadians(orbit.pitchDeg.toDouble())), Mat4.rotY(Math.toRadians(orbit.yawDeg.toDouble())),
                Mat4.translate(0.0, 8.0, -35.0),
            ))
        }
        onDispose { render?.onFrame = null }
    }
    AppScaffold(l, onLang, title = frame.name, onBack = onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(300.dp).background(Color(0xFFEEF2F2))) {
                if (render != null && !failed) {
                    Render3DView(render, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().orbitGestures(orbit))
                    if (!loaded) Text(S3D.loading[l], Modifier.align(Alignment.Center))
                    Text(S3D.viewerHint[l], Modifier.align(Alignment.BottomCenter).padding(6.dp), style = MaterialTheme.typography.bodySmall)
                } else {
                    FrameThumb(frame, thumb, Modifier.fillMaxSize().padding(16.dp))
                    Text(S3D.downloadFailed[l], Modifier.align(Alignment.BottomCenter).padding(6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rupees(frame.priceInr), style = MaterialTheme.typography.titleLarge, color = Teal, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onFavourite) { Text(if (favourite) S3D.favourited[l] else S3D.favourite[l]) }
                }
                Text(S3D.sizeLine.format(frame.sizes.label)[l], style = MaterialTheme.typography.bodyLarge)
                Text(if (frame.sizesEstimated) S3D.sizesEstimated[l] else S3D.sizesMeasured[l],
                    style = MaterialTheme.typography.bodySmall, color = if (frame.sizesEstimated) Color(0xFF8A5A00) else Teal)
                val est = S3D.estimated[l]
                Text(S3D.materialLine.format(S3D.label(frame.material)[l], est)[l])
                Text(S3D.colourLine.format(S3D.label(frame.colour)[l], est)[l])
                Text(S3D.label(frame.shape)[l] + " · " + S3D.label(frame.rim)[l], style = MaterialTheme.typography.bodyMedium)
                Text(S3D.demoCatalog[l], style = MaterialTheme.typography.bodySmall)
            }
            BigButton(S3D.tryLive[l], onLive, Modifier.padding(horizontal = 16.dp), container = Teal)
            BigButton(S3D.tryAvatar[l], onAvatar, Modifier.padding(16.dp))
        }
    }
}
