package ftl.android.app.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The website's single FTL design (web/styles.css, daisyUI theme "ftl"): dark teal surfaces on the honeycomb
 * texture, chamfered frames for panels and buttons (web/ftl/panel-frame.svg, button*.svg), the pale-yellow accent,
 * Chakra Petch for headings and buttons. Images come from web/ftl (assets/site).
 */
object Ftl {
    val siteBg = Color(0xFF0B1012)
    val surface = Color(0xFF172124)
    val base100 = Color(0xFF182326)
    val base200 = Color(0xFF0D1518)
    val base300 = Color(0xFF52676B)
    val ink = Color(0xFFE6EEEA)
    val muted = Color(0xFFAFC1C3)
    val accent = Color(0xFFE4EF87)
    val accentContent = Color(0xFF152020)
    val accentSoft = Color(0xFF2B3930)
    val line = Color(0xFF61777B)
    val header = Color(0xFF192023)
    val headerLine = Color(0xFF819B9E)
    val secondary = Color(0xFFA9C7C9)
    val success = Color(0xFF91E76C)
    val info = Color(0xFF9AD7E0)
    val error = Color(0xFFFF9C8C)
    val neutral = Color(0xFF26373B)

    /** Chakra Petch (assets/site/ChakraPetch-*.ttf), else the system sans. */
    var display: FontFamily = FontFamily.SansSerif
        private set

    fun loadFonts(context: android.content.Context) {
        val am = context.assets
        val files = runCatching { am.list("site").orEmpty().toSet() }.getOrDefault(emptySet())
        if ("ChakraPetch-Regular.ttf" in files && "ChakraPetch-SemiBold.ttf" in files) {
            display = FontFamily(
                Font("site/ChakraPetch-Regular.ttf", am, FontWeight.Normal),
                Font("site/ChakraPetch-SemiBold.ttf", am, FontWeight.SemiBold),
            )
        }
    }

    val h1 get() = TextStyle(color = ink, fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 29.sp)
    val h2 get() = TextStyle(color = ink, fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp)
    val body = TextStyle(color = ink, fontSize = 15.sp, lineHeight = 24.sp)
    val small = TextStyle(color = muted, fontSize = 13.sp, lineHeight = 19.sp)
    val label get() = TextStyle(color = ink, fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
fun siteImage(name: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(name) { runCatching { context.assets.open("site/$name").use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull() }
}

/** The page: the website's background colour with its honeycomb texture. */
@Composable
fun SitePage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val honeycomb = siteImage("honeycomb.png")
    val brush = remember(honeycomb) { honeycomb?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) } }
    Box(modifier.fillMaxSize().background(Ftl.siteBg).then(if (brush != null) Modifier.background(brush) else Modifier), content = content)
}

@Composable
fun Txt(text: String, style: TextStyle = Ftl.body, modifier: Modifier = Modifier) = BasicText(text, modifier, style)

enum class ButtonKind { PRIMARY, NORMAL, DANGER, GHOST }

/** An octagon inset by `inset` with corners cut by `cut` (the website's button frame geometry, in px). */
private fun octagon(w: Float, h: Float, inset: Float, cut: Float) = Path().apply {
    moveTo(inset + cut, inset); lineTo(w - inset - cut, inset); lineTo(w - inset, inset + cut); lineTo(w - inset, h - inset - cut)
    lineTo(w - inset - cut, h - inset); lineTo(inset + cut, h - inset); lineTo(inset, h - inset - cut); lineTo(inset, inset + cut); close()
}

private data class Frame(val fill: Color, val light: Color, val dark: Color, val text: Color)

private fun frame(kind: ButtonKind) = when (kind) {
    ButtonKind.PRIMARY -> Frame(Color(0xFFE4EF87), Color(0xFFFBFFD4), Color(0xFF3D4B27), Color(0xFF142125))
    ButtonKind.NORMAL -> Frame(Color(0xFFD5E2DF), Color(0xFFEFF8F4), Color(0xFF27383C), Color(0xFF142125))
    ButtonKind.GHOST -> Frame(Color(0xFF2C3E42), Color(0xFFA9C2C4), Color(0xFF17272B), Color(0xFFE1ECE8))
    ButtonKind.DANGER -> Frame(Color(0xFFE5A296), Color(0xFFFFE2D8), Color(0xFF673C37), Color(0xFF142125))
}

private fun DrawScope.buttonFrame(f: Frame) {
    val px = density
    val w = size.width
    val h = size.height
    drawPath(octagon(w, h, 0.5f * px, 8.5f * px), f.fill)
    drawPath(octagon(w, h, 0.5f * px, 8.5f * px), f.light, style = Stroke(px))
    drawPath(octagon(w, h, 3.5f * px, 6.9f * px), f.dark, style = Stroke(px))
    drawPath(octagon(w, h, 5.5f * px, 5.9f * px), f.light, style = Stroke(px))
}

@Composable
fun FtlButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.NORMAL, enabled: Boolean = true, large: Boolean = false, description: String? = null) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val f = frame(kind)
    Box(
        modifier
            .alpha(if (enabled) (if (pressed) 0.85f else 1f) else 0.5f)
            .defaultMinSize(minHeight = if (large) 56.dp else 42.dp)
            .drawBehind { buttonFrame(f) }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier)
            .padding(horizontal = if (large) 28.dp else 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Ftl.label.copy(color = f.text, fontSize = if (large) 18.sp else 14.sp, textAlign = TextAlign.Center))
    }
}

/** A square button with a drawn picture instead of text. */
@Composable
fun FtlIconButton(description: String, onClick: () -> Unit, kind: ButtonKind = ButtonKind.GHOST, picture: DrawScope.(Color) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val f = frame(kind)
    Box(
        Modifier
            .alpha(if (pressed) 0.85f else 1f)
            .size(42.dp)
            .drawBehind { buttonFrame(f); picture(f.text) }
            .semantics { contentDescription = description }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
    )
}

/** The mark of what is chosen, so that it does not rest on colour alone. */
@Composable
fun Tick(modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp).background(Ftl.accent).clearAndSetSemantics {}.drawBehind {
        val u = size.width / 18f
        val line = Path().apply { moveTo(4.5f * u, 9.5f * u); lineTo(7.8f * u, 12.8f * u); lineTo(13.5f * u, 5.8f * u) }
        drawPath(line, Ftl.accentContent, style = Stroke(2.2f * u))
    })
}

/** One line of a list where one thing is chosen. */
@Composable
fun ChoiceRow(text: String, chosen: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, if (chosen) Ftl.accent else Ftl.line)
        .selectable(chosen, role = Role.RadioButton, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Ftl.label.copy(color = if (chosen) Ftl.accent else Ftl.ink), Modifier.weight(1f))
        if (chosen) Tick()
    }
}

/** Something is under way and how long it takes is not known. */
@Composable
fun Working(modifier: Modifier = Modifier) {
    val at by rememberInfiniteTransition(label = "working").animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "at")
    Box(modifier.fillMaxWidth().height(4.dp).background(Ftl.base200).clearAndSetSemantics {}.drawBehind {
        val block = size.width / 4
        drawRect(Ftl.accent, Offset((size.width + block) * at - block, 0f), androidx.compose.ui.geometry.Size(block, size.height))
    })
}

/** The cog of the settings button. */
fun DrawScope.gear(color: Color) {
    val c = center
    val r = 9f * density
    val tooth = Stroke(3.2f * density)
    for (i in 0 until 8) {
        val a = i * Math.PI / 4
        val cos = Math.cos(a).toFloat()
        val sin = Math.sin(a).toFloat()
        drawLine(color, Offset(c.x + cos * r * 0.75f, c.y + sin * r * 0.75f), Offset(c.x + cos * r * 1.3f, c.y + sin * r * 1.3f), tooth.width)
    }
    drawCircle(color, r * 0.82f, c, style = Stroke(3f * density))
}

/** On or off, square-cut like the website's other controls. It does not take the touch: its row does. */
@Composable
fun FtlSwitch(on: Boolean) {
    Box(
        Modifier.size(width = 48.dp, height = 26.dp).background(if (on) Ftl.accent else Ftl.base200).border(1.dp, if (on) Ftl.accent else Ftl.line).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).background(if (on) Ftl.accentContent else Ftl.muted))
    }
}

/** A panel in the website's frame (corners cut top-right and bottom-left), with an optional tab label. */
@Composable
fun Panel(modifier: Modifier = Modifier, tab: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(top = if (tab != null) 10.dp else 0.dp).drawBehind {
                val px = density
                val w = size.width
                val h = size.height
                val cut = 12f * px
                val outer = Path().apply {
                    moveTo(0.5f * px, 0.5f * px); lineTo(w - cut, 0.5f * px); lineTo(w - 0.5f * px, cut); lineTo(w - 0.5f * px, h - 0.5f * px)
                    lineTo(cut, h - 0.5f * px); lineTo(0.5f * px, h - cut); close()
                }
                drawPath(outer, Ftl.surface)
                drawPath(outer, Color(0xFFB0C7C9), style = Stroke(px))
                val i = 3.5f * px
                val c2 = 10.3f * px
                val inner = Path().apply {
                    moveTo(i, i); lineTo(w - i - c2, i); lineTo(w - i, i + c2); lineTo(w - i, h - i); lineTo(i + c2, h - i); lineTo(i, h - i - c2); close()
                }
                drawPath(inner, Color(0xFF536C70), style = Stroke(px))
            }.padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        if (tab != null) Box(
            Modifier.padding(start = 20.dp).background(Color(0xFFC5D9D7)).border(1.dp, Color(0xFFE0EDE8)).padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            BasicText(tab.uppercase(), style = TextStyle(color = Color(0xFF172124), fontFamily = Ftl.display, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.6.sp))
        }
    }
}

@Composable
fun Badge(text: String, color: Color = Color(0xFFBACCCE), modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier.background(if (filled) color else Color(0xFF101B1E)).border(1.dp, if (filled) color else Color(0xFF6D8689))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        BasicText(text, style = Ftl.small.copy(color = if (filled) Color(0xFF152020) else color, fontSize = 11.sp, lineHeight = 15.sp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, modifier: Modifier = Modifier) {
    // The keyboard opens after the field took focus; once it has, scroll the box being typed in above it. In landscape
    // there may be room for that box alone.
    val bring = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboard) { if (focused && keyboard > 0) bring.bringIntoView() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Txt(label, Ftl.small.copy(color = Ftl.ink, fontSize = 14.sp))
        BasicTextField(
            value, onChange,
            singleLine = true,
            textStyle = Ftl.body,
            cursorBrush = SolidColor(Ftl.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bring).onFocusChanged { focused = it.isFocused }.background(Ftl.base200).border(1.dp, Ftl.base300)
                .padding(horizontal = 12.dp, vertical = 11.dp),
        )
    }
}

/** The site header: logo, caption, tools on the right, the double rule below. */
@Composable
fun Header(caption: String, right: @Composable () -> Unit = {}) {
    val logo = siteImage("logo.png")
    Column {
        Row(
            Modifier.fillMaxWidth().background(Ftl.header).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (logo != null) Image(logo, "FTL", Modifier.height(34.dp), contentScale = ContentScale.Fit)
            else BasicText("FTL", style = TextStyle(color = Color(0xFFF0F7F2), fontFamily = Ftl.display, fontWeight = FontWeight.SemiBold, fontSize = 30.sp))
            // Given what the tools on the right leave: on a narrow phone the caption goes, not the buttons.
            var captionFits by remember { mutableStateOf(true) }
            Box(Modifier.weight(1f)) {
                Box(Modifier.alpha(if (captionFits) 1f else 0f).drawBehind {
                    drawLine(Color(0xFF73898C), Offset(0f, 0f), Offset(0f, size.height), density)
                }.padding(start = 10.dp)) {
                    BasicText(caption.uppercase(), maxLines = 2, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                        onTextLayout = { captionFits = !it.hasVisualOverflow },
                        style = TextStyle(color = Color(0xFFB5C9CB), fontFamily = Ftl.display, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, letterSpacing = 1.5.sp, lineHeight = 13.sp))
                }
            }
            right()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF819B9E)))
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF070B0D)))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3C4B4E)))
    }
}

/** The save list's ship: a save names a stock ship by its id, unless the player renamed it. A mod's ship keeps what it has. */
fun shipLabel(ship: String?): String? = when (ship?.removeSuffix("_2")?.removeSuffix("_3")) {
    "PLAYER_SHIP_HARD" -> "The Kestrel"
    "PLAYER_SHIP_STEALTH" -> "Stealth Cruiser"
    "PLAYER_SHIP_MANTIS" -> "Mantis Cruiser"
    "PLAYER_SHIP_CIRCLE" -> "Engi Cruiser"
    "PLAYER_SHIP_FED" -> "Federation Cruiser"
    "PLAYER_SHIP_JELLY" -> "Slug Cruiser"
    "PLAYER_SHIP_ROCK" -> "Rock Cruiser"
    "PLAYER_SHIP_ENERGY" -> "Zoltan Cruiser"
    "PLAYER_SHIP_CRYSTAL" -> "Crystal Cruiser"
    "PLAYER_SHIP_ANAEROBIC" -> "Lanius Cruiser"
    else -> ship
}

/** The space banner of the sign-in and Play pages. */
@Composable
fun Banner(height: Int = 190) {
    val space = siteImage("space.png")
    val logo = siteImage("logo.png")
    Box(Modifier.fillMaxWidth().height(height.dp), contentAlignment = Alignment.Center) {
        if (space != null) Image(space, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.Center)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xD9080E12))))
        if (logo != null) Image(logo, "FTL", Modifier.height((height * 0.42f).dp), contentScale = ContentScale.Fit)
    }
}
