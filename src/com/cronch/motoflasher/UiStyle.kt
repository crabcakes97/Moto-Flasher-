package com.cronch.motoflasher

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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
 *
 * 2.0.12 GUI overhaul (Kyle: 2.0.11 "looks cheap like ai made it"):
 * one accent per theme used sparingly (active tab, section headers,
 * primary buttons), everything else desaturated grays; chamfered
 * buttons (cut corners, custom drawable, accent left bar on primary
 * actions); layered panels (gradient page bg, panel + 1px border +
 * top highlight); condensed uppercase headings; rebuilt bottom tab
 * bar (64dp+, Canvas-drawn geometric icons, solid accent active
 * block, 2dp accent top line); status LEDs beside key status lines.
 * Skin only — no behavior lives here.
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
    // 2.0.12: borders/secondary are desaturated grays now — the accent
    // (MAGENTA var, theme-swapped) is the only saturated color besides
    // semantic red/green/amber and the logo.
    var CARD_STROKE: Int = 0xFF4A4458.toInt()
    var TEXT: Int = 0xFFF5EFFF.toInt()
    var TEXT_DIM: Int = 0xFFA79FB5.toInt()
    var MAGENTA: Int = 0xFFFF2E88.toInt()
    var MAGENTA_PRESSED: Int = 0xFFD11B6E.toInt()
    var PURPLE: Int = 0xFF9A93A8.toInt()
    var CYAN: Int = 0xFF00E5FF.toInt()
    var CYAN_DIM: Int = 0xFF00B8D4.toInt()
    var SUN_TOP: Int = 0xFFFF2E88.toInt()
    var SUN_MID: Int = 0xFFFF7A18.toInt()
    var SUN_BOTTOM: Int = 0xFFFFD600.toInt()
    var DANGER: Int = 0xFFE8112D.toInt()
    var DANGER_PRESSED: Int = 0xFFA30D20.toInt()
    var GHOST: Int = 0xFF1E1B26.toInt()
    var GHOST_PRESSED: Int = 0xFF2E2A3A.toInt()
    var GREEN: Int = 0xFF69F0AE.toInt()
    var AMBER: Int = 0xFFFFD600.toInt()
    var RED: Int = 0xFFFF5252.toInt()
    // 2.0.12: status LED "unknown" gray + dark text/icon on solid accent.
    var LED_OFF: Int = 0xFF6B6B6B.toInt()
    var ON_ACCENT: Int = 0xFF0A0A0E.toInt()
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
                BG = 0xFF0D0503.toInt(); CARD = 0xFF1E0D07.toInt(); CARD_STROKE = 0xFF57463E.toInt()
                TEXT = 0xFFFFF1E6.toInt(); TEXT_DIM = 0xFFB3A196.toInt()
                MAGENTA = 0xFFFF5722.toInt(); MAGENTA_PRESSED = 0xFFD84315.toInt()
                PURPLE = 0xFFA89890.toInt(); CYAN = 0xFFFFD600.toInt(); CYAN_DIM = 0xFFFFAB00.toInt()
                SUN_TOP = 0xFFFF5722.toInt(); SUN_MID = 0xFFFF7A18.toInt(); SUN_BOTTOM = 0xFFFFD600.toInt()
                DANGER = 0xFFE8112D.toInt(); DANGER_PRESSED = 0xFFA30D20.toInt()
                GHOST = 0xFF241B16.toInt(); GHOST_PRESSED = 0xFF38291F.toInt()
                GREEN = 0xFF69F0AE.toInt(); AMBER = 0xFFFFD600.toInt(); RED = 0xFFFF5252.toInt()
                LOG_BG = 0xFF080302.toInt(); LOG_TEXT = 0xFFFFAB91.toInt()
                INPUT_BG = 0xFF241005.toInt(); INPUT_HINT = 0xFFC08A6E.toInt()
            }
            "frost" -> {
                BG = 0xFF030A10.toInt(); CARD = 0xFF0A1A28.toInt(); CARD_STROKE = 0xFF3D5266.toInt()
                TEXT = 0xFFEAF6FF.toInt(); TEXT_DIM = 0xFF9FB2C2.toInt()
                MAGENTA = 0xFF00B0FF.toInt(); MAGENTA_PRESSED = 0xFF0077C2.toInt()
                PURPLE = 0xFF8FA3B5.toInt(); CYAN = 0xFF40C4FF.toInt(); CYAN_DIM = 0xFF0091EA.toInt()
                SUN_TOP = 0xFF00B0FF.toInt(); SUN_MID = 0xFF2979FF.toInt(); SUN_BOTTOM = 0xFF7C4DFF.toInt()
                DANGER = 0xFFE8112D.toInt(); DANGER_PRESSED = 0xFFA30D20.toInt()
                GHOST = 0xFF16222E.toInt(); GHOST_PRESSED = 0xFF22364A.toInt()
                GREEN = 0xFF69F0AE.toInt(); AMBER = 0xFFFFD600.toInt(); RED = 0xFFFF5252.toInt()
                LOG_BG = 0xFF02080F.toInt(); LOG_TEXT = 0xFF4DFF88.toInt()
                INPUT_BG = 0xFF0A1F33.toInt(); INPUT_HINT = 0xFF6E93B0.toInt()
            }
            else -> { // neon (default): phonk / cyberpunk bleak
                BG = 0xFF07040C.toInt(); CARD = 0xFF110A1D.toInt(); CARD_STROKE = 0xFF4A4458.toInt()
                TEXT = 0xFFF5EFFF.toInt(); TEXT_DIM = 0xFFA79FB5.toInt()
                MAGENTA = 0xFFFF2E88.toInt(); MAGENTA_PRESSED = 0xFFD11B6E.toInt()
                PURPLE = 0xFF9A93A8.toInt(); CYAN = 0xFF00E5FF.toInt(); CYAN_DIM = 0xFF00B8D4.toInt()
                SUN_TOP = 0xFFFF2E88.toInt(); SUN_MID = 0xFFFF7A18.toInt(); SUN_BOTTOM = 0xFFFFD600.toInt()
                DANGER = 0xFFE8112D.toInt(); DANGER_PRESSED = 0xFFA30D20.toInt()
                GHOST = 0xFF1E1B26.toInt(); GHOST_PRESSED = 0xFF2E2A3A.toInt()
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

    /** 2.0.12: headings get a condensed feel — sans-serif-condensed
     *  bold where the device has it (Typeface.create falls back to the
     *  default sans when the family is missing, never throws). */
    fun applyHeadingFont(v: TextView) {
        v.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
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
     * 2.0.12: chamfered button background — cut corners (~6dp), dark
     * fill (accent blended toward black), 1.5dp border in the accent
     * colour, optional 3dp accent bar on the left edge (primary
     * actions). Pressed = solid accent fill (invert). Custom drawable:
     * GradientDrawable cannot cut corners.
     */
    class ChamferDrawable(
        private val fill: Int,
        private val border: Int,
        private val borderPx: Float,
        private val chamferPx: Float,
        private val leftBar: Int = 0,
        private val leftBarPx: Float = 0f,
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = border; style = Paint.Style.STROKE; strokeWidth = borderPx }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = leftBar; style = Paint.Style.FILL }
        private val path = Path()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val l = bounds.left + borderPx / 2
            val t = bounds.top + borderPx / 2
            val r = bounds.right - borderPx / 2
            val b = bounds.bottom - borderPx / 2
            val c = chamferPx
            path.reset()
            path.moveTo(l + c, t)
            path.lineTo(r - c, t); path.lineTo(r, t + c)
            path.lineTo(r, b - c); path.lineTo(r - c, b)
            path.lineTo(l + c, b); path.lineTo(l, b - c)
            path.lineTo(l, t + c)
            path.close()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(path, fillPaint)
            if (leftBarPx > 0f) {
                canvas.save()
                canvas.clipPath(path)
                canvas.drawRect(
                    bounds.left.toFloat(), bounds.top.toFloat(),
                    bounds.left + leftBarPx, bounds.bottom.toFloat(), barPaint,
                )
                canvas.restore()
            }
            canvas.drawPath(path, borderPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha; borderPaint.alpha = alpha; barPaint.alpha = alpha
        }

        override fun setColorFilter(cf: ColorFilter?) {
            fillPaint.colorFilter = cf; borderPaint.colorFilter = cf; barPaint.colorFilter = cf
        }

        @Deprecated("deprecated in API 29")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    /**
     * 2.0.12: layered panel — fill one step lighter than the page,
     * 1px border, lighter 1px highlight along the top edge for depth.
     */
    class PanelDrawable(
        private val fill: Int,
        private val border: Int,
        private val highlight: Int,
        private val radiusPx: Float,
        private val borderPx: Float,
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = border; style = Paint.Style.STROKE; strokeWidth = borderPx }
        private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = highlight; style = Paint.Style.STROKE; strokeWidth = borderPx }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = RectF(
                b.left + borderPx / 2, b.top + borderPx / 2,
                b.right - borderPx / 2, b.bottom - borderPx / 2,
            )
            canvas.drawRoundRect(r, radiusPx, radiusPx, fillPaint)
            canvas.drawRoundRect(r, radiusPx, radiusPx, borderPaint)
            canvas.drawLine(r.left + radiusPx, r.top + borderPx, r.right - radiusPx, r.top + borderPx, hlPaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha; borderPaint.alpha = alpha; hlPaint.alpha = alpha
        }

        override fun setColorFilter(cf: ColorFilter?) {
            fillPaint.colorFilter = cf; borderPaint.colorFilter = cf; hlPaint.colorFilter = cf
        }

        @Deprecated("deprecated in API 29")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    /** Chamfered button background, normal + pressed (invert) states. */
    private fun chamfered(accent: Int, pressed: Int, ctx: Context, primary: Boolean = false): StateListDrawable {
        val density = ctx.resources.displayMetrics.density
        val borderPx = 1.5f * density
        val chamferPx = ctx.dp(6).toFloat()
        val barPx = if (primary) ctx.dp(3).toFloat() else 0f
        val normal = ChamferDrawable(blend(accent, Color.BLACK, 0.78f), accent, borderPx, chamferPx, accent, barPx)
        val down = ChamferDrawable(pressed, accent, borderPx, chamferPx, accent, barPx)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(), normal)
        }
    }

    /** Page background: near-black with a subtle vertical gradient,
     *  darker toward the bottom. Panels sit one step lighter on top. */
    fun pageBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(BG, blend(BG, Color.BLACK, 0.55f)),
    ).apply { shape = GradientDrawable.RECTANGLE }

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

    /** One section card: layered panel (fill + 1px border + top
     *  highlight), 16dp padding, 12dp gap below. */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = PanelDrawable(
            CARD, CARD_STROKE, blend(CARD, Color.WHITE, 0.16f),
            ctx.dp(2).toFloat(), ctx.dp(1).toFloat(),
        )
        setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = ctx.dp(12) }
    }

    /** Section header inside a card — condensed bold, accent colour,
     *  uppercase, letter-spaced. One pattern everywhere: accent text
     *  + full-width accent rule (divider below). */
    fun sectionTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        isAllCaps = true
        letterSpacing = 0.08f
        textSize = 18f
        setTextColor(MAGENTA)
        applyHeadingFont(this)
        setPadding(0, 0, 0, ctx.dp(4))
    }

    /** Full-width accent rule under a section header. */
    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(MAGENTA)
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

    /** Status line with a small square LED on the left (gray =
     *  unknown until setLed/setStatus says otherwise). The LED is a
     *  compound drawable, so every existing text set keeps working. */
    fun ledStatus(ctx: Context, initial: String): TextView = status(ctx, initial).apply {
        val led = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(LED_OFF)
            setBounds(0, 0, ctx.dp(10), ctx.dp(10))
        }
        setCompoundDrawables(led, null, null, null)
        compoundDrawablePadding = ctx.dp(8)
    }

    private fun styledButton(
        ctx: Context, text: String, accent: Int, pressed: Int, fn: () -> Unit,
        row: Boolean = false, primary: Boolean = false,
    ): Button = Button(ctx).apply {
        this.text = text
        isAllCaps = true
        letterSpacing = 0.06f
        background = chamfered(accent, pressed, ctx, primary)
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

    /** Primary action button (flash, pick, connect, read) — theme accent, left bar. */
    fun actionButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, MAGENTA, MAGENTA_PRESSED, fn, primary = true)

    /** Destructive button (erase, relock, unlock, write) — red, left bar. */
    fun dangerButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, DANGER, DANGER_PRESSED, fn, primary = true)

    /** Secondary button (refresh, status checks) — gray border, no bar. */
    fun ghostButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, PURPLE, GHOST_PRESSED, fn)

    /** BROM primary button (connect / blankflash runs) — theme accent, left bar. */
    fun bromButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, MAGENTA, MAGENTA_PRESSED, fn, primary = true)

    /** Secondary button sized to share a horizontal row evenly. */
    fun rowButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, PURPLE, GHOST_PRESSED, fn, row = true)

    /** Primary button sized to share a horizontal row evenly. */
    fun rowActionButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, MAGENTA, MAGENTA_PRESSED, fn, row = true, primary = true)

    /** Destructive button sized to share a horizontal row evenly. */
    fun rowDangerButton(ctx: Context, text: String, fn: () -> Unit): Button =
        styledButton(ctx, text, DANGER, DANGER_PRESSED, fn, row = true, primary = true)

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
        background = chamfered(PURPLE, GHOST_PRESSED, ctx)
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

    /** Dark chamfered input with a gray border. Fill + hint follow the theme. */
    fun input(ctx: Context, hint: String, initial: String? = null): EditText =
        EditText(ctx).apply {
            this.hint = hint
            if (initial != null) setText(initial)
            background = ChamferDrawable(
                INPUT_BG, PURPLE, ctx.dp(1).toFloat(), ctx.dp(6).toFloat(),
            )
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

    /** The top app bar: logo banner only (carries the name — shown ONCE).
     *  2.0.12: the right side (version + root status) is gone — Kyle's
     *  order. Root state still shows in the Home Status card. */
    data class AppBar(val bar: LinearLayout)

    fun appBar(ctx: Context): AppBar {
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
        return AppBar(bar)
    }

    /** Geometric tab icon drawn with Canvas — no emoji, no assets.
     *  Kinds by tab position: 0 house, 1 bolt, 2 chip, 3 key,
     *  4 terminal </>, 5 2x2 grid, 6 three dots. Simple stroke
     *  shapes (~1.8dp) on a 24-unit design grid. */
    class TabIconView(ctx: Context, private val kind: Int) : View(ctx) {
        var iconColor: Int = TEXT_DIM
            set(v) { field = v; invalidate() }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val s = minOf(w, h) / 24f
            val ox = (w - 24f * s) / 2f
            val oy = (h - 24f * s) / 2f
            fun x(v: Float) = ox + v * s
            fun y(v: Float) = oy + v * s
            strokePaint.color = iconColor
            strokePaint.strokeWidth = 1.8f * resources.displayMetrics.density
            fillPaint.color = iconColor
            path.reset()
            when (kind) {
                0 -> { // house: roof + walls
                    path.moveTo(x(4f), y(11f)); path.lineTo(x(12f), y(4f)); path.lineTo(x(20f), y(11f))
                    path.moveTo(x(6f), y(9.5f)); path.lineTo(x(6f), y(20f)); path.lineTo(x(18f), y(20f)); path.lineTo(x(18f), y(9.5f))
                    canvas.drawPath(path, strokePaint)
                }
                1 -> { // bolt
                    path.moveTo(x(13f), y(2f)); path.lineTo(x(5f), y(13f)); path.lineTo(x(11f), y(13f))
                    path.lineTo(x(9f), y(22f)); path.lineTo(x(19f), y(10f)); path.lineTo(x(13f), y(10f))
                    path.close()
                    canvas.drawPath(path, strokePaint)
                }
                2 -> { // chip: square + pins + inner square
                    canvas.drawRect(x(7f), y(7f), x(17f), y(17f), strokePaint)
                    canvas.drawRect(x(10.5f), y(10.5f), x(13.5f), y(13.5f), strokePaint)
                    for (p in listOf(9.5f, 12f, 14.5f)) {
                        canvas.drawLine(x(p), y(3.5f), x(p), y(7f), strokePaint)
                        canvas.drawLine(x(p), y(17f), x(p), y(20.5f), strokePaint)
                        canvas.drawLine(x(3.5f), y(p), x(7f), y(p), strokePaint)
                        canvas.drawLine(x(17f), y(p), x(20.5f), y(p), strokePaint)
                    }
                }
                3 -> { // key: ring + shaft + teeth
                    canvas.drawCircle(x(8f), y(12f), 4f * s, strokePaint)
                    canvas.drawLine(x(12f), y(12f), x(20f), y(12f), strokePaint)
                    canvas.drawLine(x(16f), y(12f), x(16f), y(16f), strokePaint)
                    canvas.drawLine(x(20f), y(12f), x(20f), y(15f), strokePaint)
                }
                4 -> { // terminal </>
                    path.moveTo(x(9f), y(7f)); path.lineTo(x(4f), y(12f)); path.lineTo(x(9f), y(17f))
                    path.moveTo(x(15f), y(7f)); path.lineTo(x(20f), y(12f)); path.lineTo(x(15f), y(17f))
                    path.moveTo(x(13.5f), y(5f)); path.lineTo(x(10.5f), y(19f))
                    canvas.drawPath(path, strokePaint)
                }
                5 -> { // 2x2 grid
                    canvas.drawRect(x(4f), y(4f), x(11f), y(11f), strokePaint)
                    canvas.drawRect(x(13f), y(4f), x(20f), y(11f), strokePaint)
                    canvas.drawRect(x(4f), y(13f), x(11f), y(20f), strokePaint)
                    canvas.drawRect(x(13f), y(13f), x(20f), y(20f), strokePaint)
                }
                else -> { // three dots
                    for (cx in listOf(5.5f, 12f, 18.5f)) {
                        canvas.drawCircle(x(cx), y(12f), 2f * s, fillPaint)
                    }
                }
            }
        }
    }

    /** Bottom navigation bar for the app shell.
     *  2.0.12: taller bar (64dp+ cells), geometric icon + label per
     *  tab, active tab = solid accent block with dark icon/text,
     *  inactive = dim gray on near-black, 2dp accent line along the
     *  top edge. */
    class TabBar(
        val view: LinearLayout,
        private val labels: List<TextView>,
        private val icons: List<TabIconView>,
        private val cells: List<LinearLayout>,
    ) {
        fun select(index: Int) {
            labels.forEachIndexed { i, label ->
                label.setTextColor(if (i == index) ON_ACCENT else TEXT_DIM)
            }
            icons.forEachIndexed { i, icon ->
                icon.iconColor = if (i == index) ON_ACCENT else TEXT_DIM
            }
            cells.forEachIndexed { i, cell ->
                cell.background = if (i == index) {
                    ChamferDrawable(
                        MAGENTA, MAGENTA,
                        view.context.dp(1).toFloat(), view.context.dp(4).toFloat(),
                    )
                } else {
                    ColorDrawable(Color.TRANSPARENT)
                }
            }
        }
    }

    fun tabBar(ctx: Context, titles: List<String>, onSelect: (Int) -> Unit): TabBar {
        val labels = mutableListOf<TextView>()
        val icons = mutableListOf<TabIconView>()
        val cells = mutableListOf<LinearLayout>()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CARD)
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        }
        titles.forEachIndexed { i, title ->
            val icon = TabIconView(ctx, i).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26))
                iconColor = if (i == 0) ON_ACCENT else TEXT_DIM
            }
            val label = TextView(ctx).apply {
                text = title
                isAllCaps = true
                textSize = 11f
                letterSpacing = 0.06f
                applyHeadingFont(this)
                gravity = Gravity.CENTER
                setTextColor(if (i == 0) ON_ACCENT else TEXT_DIM)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = ctx.dp(3) }
            }
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = ctx.dp(64)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = ctx.dp(2)
                    rightMargin = ctx.dp(2)
                    topMargin = ctx.dp(4)
                    bottomMargin = ctx.dp(4)
                }
                setPadding(0, ctx.dp(6), 0, ctx.dp(6))
                // First tab is active at build time: solid accent block
                // now; select() takes over after that.
                background = if (i == 0) {
                    ChamferDrawable(MAGENTA, MAGENTA, ctx.dp(1).toFloat(), ctx.dp(4).toFloat())
                } else {
                    ColorDrawable(Color.TRANSPARENT)
                }
                setOnClickListener { onSelect(i) }
                addView(icon)
                addView(label)
            }
            labels.add(label)
            icons.add(icon)
            cells.add(cell)
            row.addView(cell)
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 2dp accent line along the top edge of the bar.
            addView(View(ctx).apply {
                setBackgroundColor(MAGENTA)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(2),
                )
            })
            addView(row)
        }
        return TabBar(bar, labels, icons, cells)
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
            background = PanelDrawable(
                CARD, CARD_STROKE, blend(CARD, Color.WHITE, 0.16f),
                ctx.dp(2).toFloat(), ctx.dp(1).toFloat(),
            )
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(16))
        }
        body.addView(TextView(ctx).apply {
            text = title
            isAllCaps = true
            letterSpacing = 0.06f
            textSize = 18f
            setTextColor(MAGENTA)
            applyHeadingFont(this)
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
    setLed(kind)
}

/** Color only the LED square on a ledStatus line (no-op on plain
 *  status lines, which have no compound drawable). */
fun TextView.setLed(kind: UiStyle.Status) {
    (compoundDrawables[0] as? GradientDrawable)?.setColor(
        when (kind) {
            UiStyle.Status.OK -> UiStyle.GREEN
            UiStyle.Status.WAIT -> UiStyle.AMBER
            UiStyle.Status.FAIL -> UiStyle.RED
            UiStyle.Status.INFO -> UiStyle.LED_OFF
        },
    )
}
