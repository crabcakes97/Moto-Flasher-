package com.cronch.motoflasher

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Moto Flasher — phonk / cyberpunk bleak look for the programmatic UI.
 *
 * 2.0.11 GUI revamp: near-black gritty background, angular buttons
 * (sharp corners, dark fill, thin neon border, uppercase labels,
 * pressed = solid accent fill), sharp-cornered cards with thin
 * borders, solid neon headings instead of rainbow everywhere. Phonk
 * palette: deep purple/black base, blood-red + magenta accents.
 * Exo 2 font (bundled in assets/fonts/Exo2.ttf, loaded with a system
 * fallback), used hard: uppercase, letter-spaced headers. A gradient
 * stays on the main logo banner only. Styling only: no behavior
 * lives here, no gates, no flashing logic. Buttons are colored by
 * role — magenta = action, red = destructive, purple = query,
 * cyan = BROM. No emojis anywhere in the app (Kyle's order 2026-11-27) —
 * waiting states are plain words.
 *
 * App shell: a top app bar (logo banner + version + root status,
 * name shown ONCE) and a bottom tab bar (Home / Flash / BROM / Val /
 * ADB / Extras / More). All dialogs, checkboxes and the log row are
 * themed here too, so nothing is left stock.
 */
object UiStyle {
    // Palette (2.0.11 phonk/cyberpunk: near-black base, magenta + blood-red
    // accents, cyan secondary). Colours are vars, not vals — applyTheme()
    // swaps the whole palette at startup (persisted pick) and every UI
    // builder below reads them at call time, so a theme change is a
    // recreate away. MAGENTA is the primary action colour in every theme,
    // even when the theme's accent is not literally magenta (Ember =
    // orange-red, Frost = blue). CYAN is the secondary accent.
    var BG: Int = 0xFF07040C.toInt()
    var CARD: Int = 0xFF110A1D.toInt()
    var CARD_STROKE: Int = 0xFF7B2FFF.toInt()
    var TEXT: Int = 0xFFF5EFFF.toInt()
    var TEXT_DIM: Int = 0xFFB9A8D6.toInt()
    var MAGENTA: Int = 0xFFFF2E88.toInt()
    var MAGENTA_PRESSED: Int = 0xFFD11B6E.toInt()
    var PURPLE: Int = 0xFF7B2FFF.toInt()
    var CYAN: Int = 0xFF00E5FF.toInt()
    var CYAN_DIM: Int = 0xFF00B8D4.toInt()
    var SUN_TOP: Int = 0xFFFF2E88.toInt()
    var SUN_MID: Int = 0xFFFF7A18.toInt()
    var SUN_BOTTOM: Int = 0xFFFFD600.toInt()
    var DANGER: Int = 0xFFE8112D.toInt()
    var DANGER_PRESSED: Int = 0xFFA30D20.toInt()
    var GHOST: Int = 0xFF1B1230.toInt()
    var GHOST_PRESSED: Int = 0xFF2A1B4A.toInt()
    var GREEN: Int = 0xFF69F0AE.toInt()
    var AMBER: Int = 0xFFFFD600.toInt()
    var RED: Int = 0xFFFF5252.toInt()
    var LOG_BG: Int = 0xFF050208.toInt()
    var LOG_TEXT: Int = 0xFF4DFF88.toInt()
    // Input-field fill + hint (follow the card colour per theme).
    var INPUT_BG: Int = 0xFF160C28.toInt()
    var INPUT_HINT: Int = 0xFF9A86C2.toInt()

    /** Themes. "neon" is the default — the 2.0.11 phonk/cyberpunk look. */
    val THEMES = listOf("neon", "ember", "frost")

    fun applyTheme(name: String) {
        when (name) {
            "ember" -> {
                BG = 0xFF0D0503.toInt(); CARD = 0xFF1E0D07.toInt(); CARD_STROKE = 0xFFFF5722.toInt()
                TEXT = 0xFFFFF1E6.toInt(); TEXT_DIM = 0xFFD8A48F.toInt()
                MAGENTA = 0xFFFF5722.toInt(); MAGENTA_PRESSED = 0xFFD84315.toInt()
                PURPLE = 0xFFFF8A50.toInt(); CYAN = 0xFFFFD600.toInt(); CYAN_DIM = 0xFFFFAB00.toInt()
                SUN_TOP = 0xFFFF5722.toInt(); SUN_MID = 0xFFFF7A18.toInt(); SUN_BOTTOM = 0xFFFFD600.toInt()
                DANGER = 0xFFE8112D.toInt(); DANGER_PRESSED = 0xFFA30D20.toInt()
                GHOST = 0xFF2A130B.toInt(); GHOST_PRESSED = 0xFF3D1C10.toInt()
                GREEN = 0xFF69F0AE.toInt(); AMBER = 0xFFFFD600.toInt(); RED = 0xFFFF5252.toInt()
                LOG_BG = 0xFF080302.toInt(); LOG_TEXT = 0xFFFFAB91.toInt()
                INPUT_BG = 0xFF241005.toInt(); INPUT_HINT = 0xFFC08A6E.toInt()
            }
            "frost" -> {
                BG = 0xFF030A10.toInt(); CARD = 0xFF0A1A28.toInt(); CARD_STROKE = 0xFF00B0FF.toInt()
                TEXT = 0xFFEAF6FF.toInt(); TEXT_DIM = 0xFF9DBED6.toInt()
                MAGENTA = 0xFF00B0FF.toInt(); MAGENTA_PRESSED = 0xFF0077C2.toInt()
                PURPLE = 0xFF2979FF.toInt(); CYAN = 0xFF40C4FF.toInt(); CYAN_DIM = 0xFF0091EA.toInt()
                SUN_TOP = 0xFF00B0FF.toInt(); SUN_MID = 0xFF2979FF.toInt(); SUN_BOTTOM = 0xFF7C4DFF.toInt()
                DANGER = 0xFFE8112D.toInt(); DANGER_PRESSED = 0xFFA30D20.toInt()
                GHOST = 0xFF0B1B29.toInt(); GHOST_PRESSED = 0xFF12293D.toInt()
                GREEN = 0xFF69F0AE.toInt(); AMBER = 0xFFFFD600.toInt(); RED = 0xFFFF5252.toInt()
                LOG_BG = 0xFF02080F.toInt(); LOG_TEXT = 0xFF4DFF88.toInt()
                INPUT_BG = 0xFF0A1F33.toInt(); INPUT_HINT = 0xFF6E93B0.toInt()
            }
            else -> { // neon (default): phonk / cyberpunk bleak
                BG = 0xFF07040C.toInt(); CARD = 0xFF110A1D.toInt(); CARD_STROKE = 0xFF7B2FFF.toInt()
                TEXT = 0xFFF5EFFF.toInt(); TEXT_DIM = 0xFFB9A8D6.toInt()
                MAGENTA = 0xFFFF2E88.toInt(); MAGENTA_PRESSED = 0xFFD11B6E.toInt()
                PURPLE = 0xFF7B2FFF.toInt(); CYAN = 0xFF00E5FF.toInt(); CYAN_DIM = 0xFF00B8D4.toInt()
                SUN_TOP = 0xFFFF2E88.toInt(); SUN_MID = 0xFFFF7A18.toInt(); SUN_BOTTOM = 0xFFFFD600.toInt()
                DANGER = 0xFFE8112D.toInt(); DANGER_PRESSED = 0xFFA30D20.toInt()
                GHOST = 0xFF1B1230.toInt(); GHOST_PRESSED = 0xFF2A1B4A.toInt()
                GREEN = 0xFF69F0AE.toInt(); AMBER = 0xFFFFD600.toInt(); RED = 0xFFFF5252.toInt()
                LOG_BG = 0xFF050208.toInt(); LOG_TEXT = 0xFF4DFF88.toInt()
                INPUT_BG = 0xFF160C28.toInt(); INPUT_HINT = 0xFF9A86C2.toInt()
            }
        }
    }

    /** Rainbow ramp for banner / section-title text (magenta → orange → yellow → green → cyan → purple). */
    val RAINBOW: IntArray = intArrayOf(
        0xFFFF2E88.toInt(), 0xFFFF7A18.toInt(), 0xFFFFD600.toInt(),
        0xFF69F0AE.toInt(), 0xFF00E5FF.toInt(), 0xFF7B2FFF.toInt(),
        0xFFFF2E88.toInt(),
    )

    enum class Status { OK, WAIT, FAIL, INFO }

    private var exo2: Typeface? = null
    private var exo2Tried = false

    /** Exo 2 from assets/fonts/Exo2.ttf; null when the asset is missing (callers fall back). */
    fun exo(ctx: Context): Typeface? {
        if (!exo2Tried) {
            exo2Tried = true
            exo2 = try {
                Typeface.createFromAsset(ctx.assets, "fonts/Exo2.ttf")
            } catch (_: Exception) {
                null
            }
        }
        return exo2
    }

    /** Apply Exo 2 (or bold sans fallback) to a TextView. */
    fun applyFont(ctx: Context, v: TextView, bold: Boolean = false) {
        val f = exo(ctx)
        if (f != null) v.typeface = if (bold) Typeface.create(f, Typeface.BOLD) else f
        else v.setTypeface(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Rainbow per-character span, like the neon LK banner text. */
    fun rainbow(text: String): CharSequence {
        if (text.isEmpty()) return text
        val span = SpannableString(text)
        for (i in text.indices) {
            val pos = i.toFloat() / maxOf(1, text.length - 1).toFloat() * (RAINBOW.size - 1)
            val idx = pos.toInt().coerceIn(0, RAINBOW.size - 2)
            val frac = pos - idx
            val c = blend(RAINBOW[idx], RAINBOW[idx + 1], frac)
            span.setSpan(ForegroundColorSpan(c), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(c: Int, shift: Int): Int = (c shr shift) and 0xFF
        val r = (ch(a, 16) + (ch(b, 16) - ch(a, 16)) * f).roundToInt()
        val g = (ch(a, 8) + (ch(b, 8) - ch(a, 8)) * f).roundToInt()
        val bl = (ch(a, 0) + (ch(b, 0) - ch(a, 0)) * f).roundToInt()
        return Color.rgb(r, g, bl)
    }

    /**
     * 2.0.11: angular button background. Sharp corners (2dp), dark fill
     * (accent blended toward black), thin 1dp neon border in the accent
     * colour. Pressed = solid accent fill (invert). No soft pills.
     */
    private fun angular(accent: Int, pressed: Int, ctx: Context): StateListDrawable {
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(2).toFloat()
            setColor(blend(accent, Color.BLACK, 0.78f))
            setStroke(ctx.dp(1), accent)
        }
        val down = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(2).toFloat()
            setColor(pressed)
            setStroke(ctx.dp(1), accent)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(), normal)
        }
    }

    /** Magenta sun gradient: the app banner background (logo only). */
    fun bannerBackground(ctx: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF2A0A4A.toInt(), SUN_TOP, SUN_MID, SUN_BOTTOM),
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = ctx.dp(2).toFloat()
    }

    /** App banner: rainbow title + cyan subtitle on the sun gradient. */
    fun banner(ctx: Context, title: String, subtitle: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bannerBackground(ctx)
            setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = ctx.dp(12) }
            addView(TextView(ctx).apply {
                text = rainbow(title)
                textSize = 30f
                setTypeface(null, Typeface.BOLD)
                applyFont(ctx, this, bold = true)
                gravity = Gravity.CENTER
                setShadowLayer(6f, 0f, 0f, 0xFF000000.toInt())
            })
            addView(TextView(ctx).apply {
                text = subtitle
                textSize = 14f
                setTextColor(Color.WHITE)
                applyFont(ctx, this)
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(4), 0, 0)
                setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            })
        }

    /** One section card: sharp-cornered panel with a thin neon border. */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(2).toFloat()
            setColor(CARD)
            setStroke(ctx.dp(1), CARD_STROKE)
        }
        setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = ctx.dp(14) }
    }

    /** Section header inside a card — solid neon, uppercase, letter-spaced. */
    fun sectionTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        isAllCaps = true
        letterSpacing = 0.08f
        textSize = 17f
        setTextColor(MAGENTA)
        applyFont(ctx, this, bold = true)
        setPadding(0, 0, 0, ctx.dp(4))
    }

    /** Thin cyan divider under a section header. */
    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(CYAN)
        alpha = 0.7f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(2),
        ).apply { bottomMargin = ctx.dp(10) }
    }

    /** Body/description text inside a card. */
    fun body(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTextColor(TEXT_DIM)
        applyFont(ctx, this)
        setPadding(0, 0, 0, ctx.dp(8))
    }

    /** Status line (device / gate / connection readouts). */
    fun status(ctx: Context, initial: String): TextView = TextView(ctx).apply {
        text = initial
        textSize = 14f
        setTextColor(TEXT)
        applyFont(ctx, this)
        setPadding(0, ctx.dp(4), 0, ctx.dp(4))
    }

    private fun styledButton(
        ctx: Context, text: String, accent: Int, pressed: Int, fn: () -> Unit, row: Boolean = false,
    ): Button = Button(ctx).apply {
        this.text = text
        isAllCaps = true
        letterSpacing = 0.06f
        background = angular(accent, pressed, ctx)
        setTextColor(Color.WHITE)
        applyFont(ctx, this, bold = true)
        textSize = 15f
        minHeight = ctx.dp(52)
        // 2.0.10: vertical padding. Was 0 — wrapped text in half-width
        // row buttons touched the edges and read as clipped on the S26 FE.
        setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
        layoutParams = if (row) {
            // Shares a horizontal row evenly with its siblings.
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = ctx.dp(4)
                rightMargin = ctx.dp(4)
                topMargin = ctx.dp(4)
                bottomMargin = ctx.dp(4)
            }
        } else {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = ctx.dp(4)
                bottomMargin = ctx.dp(4)
            }
        }
        setOnClickListener { fn() }
    }

    /** Primary action button (flash, pick, connect, read) — magenta. */
    fun actionButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, MAGENTA, MAGENTA_PRESSED, fn)

    /** Destructive button (erase, relock, unlock, write) — red. */
    fun dangerButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, DANGER, DANGER_PRESSED, fn)

    /** Secondary button (refresh, status checks) — dark violet, purple border. */
    fun ghostButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, PURPLE, GHOST_PRESSED, fn)

    /** Cyan accent button (BROM connect / blankflash runs). */
    fun bromButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, CYAN_DIM, 0xFF00838F.toInt(), fn)

    /** Secondary button sized to share a horizontal row evenly. */
    fun rowButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, PURPLE, GHOST_PRESSED, fn, row = true)

    /** Primary button sized to share a horizontal row evenly. */
    fun rowActionButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, MAGENTA, MAGENTA_PRESSED, fn, row = true)

    /** Destructive button sized to share a horizontal row evenly. */
    fun rowDangerButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, DANGER, DANGER_PRESSED, fn, row = true)

    /** Secondary button sized to share a horizontal row evenly. */
    fun rowGhostButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, PURPLE, GHOST_PRESSED, fn, row = true)

    /**
     * 2.0.10: button for dense grids (Extras OEM commands, preset rows).
     * Smaller text (13sp) + tight horizontal padding so long one-word
     * labels (MRDUMP_FALLOCATE, PRINTK-RATELIMIT) fit on one line in a
     * half-width cell instead of breaking mid-word; MATCH_PARENT height
     * so both buttons in a row are the same height even when one wraps.
     * Wraps only at spaces, never mid-word at this size on the S26 FE.
     */
    fun gridButton(ctx: Context, text: String, fn: () -> Unit): Button = Button(ctx).apply {
        this.text = text
        isAllCaps = true
        letterSpacing = 0.04f
        background = angular(PURPLE, GHOST_PRESSED, ctx)
        setTextColor(Color.WHITE)
        applyFont(ctx, this, bold = true)
        textSize = 13f
        minHeight = ctx.dp(48)
        setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(10))
        layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f,
        ).apply {
            leftMargin = ctx.dp(4)
            rightMargin = ctx.dp(4)
            topMargin = ctx.dp(4)
            bottomMargin = ctx.dp(4)
        }
        setOnClickListener { fn() }
    }

    /** Themed checkbox: light Exo 2 text on the dark card, magenta tick. */
    fun styledCheckBox(ctx: Context, text: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox =
        CheckBox(ctx).apply {
            this.text = text
            isChecked = checked
            setTextColor(TEXT)
            applyFont(ctx, this)
            buttonTintList = ColorStateList.valueOf(MAGENTA)
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }

    /** Dark sharp input with a border. Fill + hint follow the theme. */
    fun input(ctx: Context, hint: String, initial: String? = null): EditText =
        EditText(ctx).apply {
            this.hint = hint
            if (initial != null) setText(initial)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(2).toFloat()
                setColor(INPUT_BG)
                setStroke(ctx.dp(1), PURPLE)
            }
            setTextColor(TEXT)
            setHintTextColor(INPUT_HINT)
            applyFont(ctx, this)
            textSize = 15f
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = ctx.dp(4)
                bottomMargin = ctx.dp(4)
            }
        }

    // ---------- 2.0.1 app shell: logo, app bar, tab bar, dialogs ----------

    private var logoBanner: Bitmap? = null
    private var logoBannerTried = false

    /** Top-bar logo (assets/logo/logo_banner.png); null when the asset
     *  is absent — callers fall back to the gradient tile, never crash. */
    fun logoBanner(ctx: Context): Bitmap? {
        if (!logoBannerTried) {
            logoBannerTried = true
            logoBanner = try {
                BitmapFactory.decodeStream(ctx.assets.open("logo/logo_banner.png"))
            } catch (_: Exception) {
                null
            }
        }
        return logoBanner
    }

    /** The top app bar: logo banner (carries the name — shown ONCE) +
     *  small version + live root status. No second title. */
    data class AppBar(val bar: LinearLayout, val statusText: TextView)

    fun appBar(ctx: Context, versionName: String): AppBar {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CARD)
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        }
        val logo = logoBanner(ctx)
        if (logo != null) {
            row.addView(ImageView(ctx).apply {
                setImageBitmap(logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(ctx.dp(208), ctx.dp(52)).apply {
                    rightMargin = ctx.dp(12)
                }
            })
        } else {
            // Logo asset absent (re-package pending): gradient tile + "MF".
            row.addView(TextView(ctx).apply {
                text = "MF"
                textSize = 30f
                gravity = Gravity.CENTER
                background = bannerBackground(ctx)
                layoutParams = LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)).apply {
                    rightMargin = ctx.dp(12)
                }
            })
        }
        val sideCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sideCol.addView(TextView(ctx).apply {
            text = "$versionName • Penumbra engine"
            textSize = 12f
            letterSpacing = 0.04f
            setTextColor(TEXT_DIM)
            applyFont(ctx, this)
        })
        val status = TextView(ctx).apply {
            text = "Starting…"
            textSize = 12f
            letterSpacing = 0.04f
            setTextColor(AMBER)
            applyFont(ctx, this, bold = true)
        }
        sideCol.addView(status)
        row.addView(sideCol)
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(View(ctx).apply {
                setBackgroundColor(MAGENTA)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(2),
                )
            })
        }
        return AppBar(bar, status)
    }

    /** Bottom navigation bar for the app shell.
     *  2.0.11: angular cells (sharp corners), active cell = dark fill +
     *  magenta border + solid magenta underline bar, uppercase labels. */
    class TabBar(
        val view: LinearLayout,
        private val labels: List<TextView>,
        private val underlines: List<View>,
        private val cells: List<LinearLayout>,
    ) {
        fun select(index: Int) {
            labels.forEachIndexed { i, label ->
                label.setTextColor(if (i == index) MAGENTA else TEXT_DIM)
            }
            underlines.forEachIndexed { i, line ->
                line.background = if (i == index) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(MAGENTA)
                    }
                } else {
                    ColorDrawable(BG)
                }
            }
            cells.forEachIndexed { i, cell ->
                cell.background = if (i == index) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = view.context.dp(2).toFloat()
                        setColor(GHOST)
                        setStroke(view.context.dp(1), MAGENTA)
                    }
                } else {
                    ColorDrawable(Color.TRANSPARENT)
                }
            }
        }
    }

    fun tabBar(ctx: Context, titles: List<String>, onSelect: (Int) -> Unit): TabBar {
        val labels = mutableListOf<TextView>()
        val underlines = mutableListOf<View>()
        val cells = mutableListOf<LinearLayout>()
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(CARD)
                setStroke(ctx.dp(1), CARD_STROKE)
            }
            setPadding(ctx.dp(4), ctx.dp(10), ctx.dp(4), ctx.dp(10))
        }
        titles.forEachIndexed { i, title ->
            val label = TextView(ctx).apply {
                text = title
                isAllCaps = true
                textSize = 13f
                letterSpacing = 0.05f
                applyFont(ctx, this, bold = true)
                gravity = Gravity.CENTER
                setTextColor(if (i == 0) MAGENTA else TEXT_DIM)
            }
            val underline = View(ctx).apply {
                background = if (i == 0) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(MAGENTA)
                    }
                } else {
                    ColorDrawable(BG)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(3),
                ).apply { topMargin = ctx.dp(4) }
            }
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, ctx.dp(4), 0, ctx.dp(2))
                // First tab is active at build time: give it the filled
                // cell background now; select() takes over after that.
                background = if (i == 0) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = ctx.dp(2).toFloat()
                        setColor(GHOST)
                        setStroke(ctx.dp(1), MAGENTA)
                    }
                } else {
                    ColorDrawable(Color.TRANSPARENT)
                }
                setOnClickListener { onSelect(i) }
                addView(label)
                addView(underline)
            }
            labels.add(label)
            underlines.add(underline)
            cells.add(cell)
            bar.addView(cell)
        }
        return TabBar(bar, labels, underlines, cells)
    }

    /** Themed confirm dialog: dark sharp card, solid neon title, Exo 2,
     *  role-coloured buttons. Replaces the stock AlertDialog everywhere. */
    fun styledDialog(
        ctx: Context, title: String, message: String,
        positiveLabel: String, negativeLabel: String, onYes: () -> Unit,
    ) {
        val dialog = AlertDialog.Builder(ctx).create()
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(2).toFloat()
                setColor(CARD)
                setStroke(ctx.dp(1), CARD_STROKE)
            }
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(16))
        }
        body.addView(TextView(ctx).apply {
            text = title
            isAllCaps = true
            letterSpacing = 0.06f
            textSize = 17f
            setTextColor(MAGENTA)
            applyFont(ctx, this, bold = true)
            setPadding(0, 0, 0, ctx.dp(8))
        })
        body.addView(TextView(ctx).apply {
            text = message
            textSize = 15f
            setTextColor(TEXT)
            applyFont(ctx, this)
            setPadding(0, 0, 0, ctx.dp(16))
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(rowGhostButton(ctx, negativeLabel) { dialog.dismiss() })
        row.addView(rowActionButton(ctx, positiveLabel) { dialog.dismiss(); onYes() })
        body.addView(row)
        dialog.setView(body)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
}

/** Density-independent pixels, for any Context. */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Color a status line: green = ready/ok, amber = waiting, red = failed/stopped. */
fun TextView.setStatus(kind: UiStyle.Status) {
    setTextColor(
        when (kind) {
            UiStyle.Status.OK -> UiStyle.GREEN
            UiStyle.Status.WAIT -> UiStyle.AMBER
            UiStyle.Status.FAIL -> UiStyle.RED
            UiStyle.Status.INFO -> UiStyle.TEXT
        },
    )
}
