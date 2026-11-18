package pt.gicristovao.voavoa

import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M

/** Cartão "Sobre", que se expande a partir do botão ⓘ: dados, versão da app e legenda. */
class InfoSheet(
    private val act: AppCompatActivity,
    private val anchor: View,
    private val dataLine: String,
    private val dataDetail: String,
    private val versionName: String,
    private val onSearch: (InfoSheet) -> Unit,
    private val onInstall: (Updater.Release) -> Unit,
) {
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun attr(a: Int, fallback: Int) = MaterialColors.getColor(act, a, fallback)

    private lateinit var badge: TextView
    private lateinit var buttonBox: FrameLayout
    private var overlay: FrameLayout? = null
    private var card: MaterialCardView? = null
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = close()
    }
    val isOpen get() = overlay != null

    fun show(newer: Updater.Release?, checked: Boolean) {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        // Cabeçalho
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(act).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        })
        header.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Voa Voa", 18f, bold = true))
            addView(text("Zonas de voo de drones em Portugal", 12f, secondary = true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ImageButton(act).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Fechar"
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { close() }
        })
        col.addView(header, lp(bottom = dp(12)))

        // Dados das zonas (só informação: a atualização faz-se no cartão do mapa)
        col.addView(card(
            text("Dados das zonas", 12f, secondary = true),
            text(dataLine, 15f, bold = true, top = dp(2)),
            text(dataDetail, 12f, secondary = true),
        ), lp(bottom = dp(8)))

        // App
        badge = text("", 12f, bold = true).apply { setPadding(dp(10), dp(3), dp(10), dp(3)) }
        val versionRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Versão $versionName", 15f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(badge)
        }
        buttonBox = FrameLayout(act)
        col.addView(card(
            text("App", 12f, secondary = true),
            versionRow.apply { setPadding(0, dp(2), 0, dp(6)) },
            buttonBox,
        ), lp(bottom = dp(14)))
        setAppState(newer, checked)

        // Legenda
        col.addView(text("Cores no mapa", 13f, bold = true), lp(bottom = dp(6)))
        legend(col, "#E24B4A", false, "Voo proibido")
        legend(col, "#EF9F27", false, "Precisa de autorização ou tem condições")
        legend(col, "#888780", false, "Zona assinalada, sem restrição")
        legend(col, "#E24B4A", true, "Restrição temporária que ainda não começou")

        col.addView(text(
            "Toca em qualquer ponto do mapa para o consultar, ou usa \"Posso voar aqui?\".",
            13f, secondary = true, top = dp(10)
        ))
        col.addView(text(
            "App não oficial. Confirma sempre em dnt.anac.pt antes de voar. " +
                "Dados: ANAC (ED-318). Mapa © colaboradores do OpenStreetMap.",
            12f, secondary = true, top = dp(12)
        ))

        val root = act.findViewById<ViewGroup>(android.R.id.content)
        val anchorPos = IntArray(2).also { anchor.getLocationInWindow(it) }
        val rootPos = IntArray(2).also { root.getLocationInWindow(it) }
        val top = anchorPos[1] - rootPos[1] + anchor.height + dp(8)

        val c = MaterialCardView(act).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(8).toFloat()
            setCardBackgroundColor(attr(M.attr.colorSurfaceContainerLow, Color.WHITE))
            addView(ScrollView(act).apply { addView(col) })
            isClickable = true
        }
        val o = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(0x33, 0, 0, 0))
            alpha = 0f
            isClickable = true
            setOnClickListener { close() }
            addView(c, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
            ).apply { setMargins(dp(12), top, dp(12), 0) })
        }
        root.addView(o, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = o
        card = c
        act.onBackPressedDispatcher.addCallback(act, back)

        c.visibility = View.INVISIBLE
        c.post {
            // Não deixar o cartão passar do fundo do ecrã (o conteúdo faz scroll).
            val maxH = root.height - top - dp(24)
            if (c.height > maxH) { c.layoutParams.height = maxH; c.requestLayout() }
            setPivotOnAnchor(c)
            c.scaleX = 0.05f; c.scaleY = 0.05f; c.alpha = 0f
            c.visibility = View.VISIBLE
            c.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
                .setInterpolator(DecelerateInterpolator(2f)).start()
            o.animate().alpha(1f).setDuration(220).start()
        }
    }

    /** O ponto de origem da animação é o centro do botão ⓘ. */
    private fun setPivotOnAnchor(c: View) {
        val a = IntArray(2).also { anchor.getLocationInWindow(it) }
        val p = IntArray(2).also { c.getLocationInWindow(it) }
        c.pivotX = (a[0] + anchor.width / 2f) - p[0]
        c.pivotY = (a[1] + anchor.height / 2f) - p[1]
    }

    fun close() {
        val o = overlay ?: return
        val c = card ?: return
        overlay = null
        card = null
        back.remove()
        setPivotOnAnchor(c)
        c.animate().scaleX(0.05f).scaleY(0.05f).alpha(0f).setDuration(220)
            .setInterpolator(AccelerateInterpolator()).start()
        o.animate().alpha(0f).setDuration(220).withEndAction {
            (o.parent as? ViewGroup)?.removeView(o)
        }.start()
    }

    /** Atualiza o bloco da app: "a mais recente" + procurar, ou "nova" + instalar. */
    fun setAppState(newer: Updater.Release?, checked: Boolean) {
        val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        buttonBox.removeAllViews()
        if (newer != null) {
            badge.text = "Nova: ${newer.versionName}"
            bg.setColor(attr(M.attr.colorPrimaryContainer, Color.LTGRAY))
            badge.setTextColor(attr(M.attr.colorOnPrimaryContainer, Color.BLACK))
            badge.visibility = View.VISIBLE
            buttonBox.addView(MaterialButton(act).apply {
                text = "Instalar ${newer.versionName}"
                setIconResource(R.drawable.ic_download)
                setOnClickListener { close(); onInstall(newer) }
            }, fullWidth())
        } else {
            badge.text = "A mais recente"
            bg.setColor(attr(M.attr.colorSecondaryContainer, Color.LTGRAY))
            badge.setTextColor(attr(M.attr.colorOnSecondaryContainer, Color.BLACK))
            badge.visibility = if (checked) View.VISIBLE else View.INVISIBLE
            buttonBox.addView(MaterialButton(act, null, M.attr.materialButtonOutlinedStyle).apply {
                text = "Procurar atualizações"
                setIconResource(R.drawable.ic_search)
                setOnClickListener {
                    text = "A procurar…"
                    isEnabled = false
                    onSearch(this@InfoSheet)
                }
            }, fullWidth())
        }
        badge.background = bg
    }

    /** Resultado de "Procurar atualizações" quando não foi possível verificar. */
    fun searchFailed() {
        (buttonBox.getChildAt(0) as? MaterialButton)?.apply {
            text = "Não foi possível verificar. Tentar de novo"
            isEnabled = true
        }
    }

    // ---------- auxiliares ----------

    private fun card(vararg views: View) = MaterialCardView(act).apply {
        radius = dp(14).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(attr(M.attr.colorSurfaceContainerHigh, Color.parseColor("#EEEEEE")))
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            views.forEach { addView(it) }
        })
    }

    private fun legend(parent: LinearLayout, hex: String, dashed: Boolean, label: String) {
        val c = Color.parseColor(hex)
        val swatch = View(act).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                if (dashed) {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), c, dp(4).toFloat(), dp(3).toFloat())
                } else setColor(c)
            }
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) }
        }
        parent.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(swatch)
            addView(text(label, 14f))
        }, lp(bottom = dp(8)))
    }

    private fun text(s: String, size: Float, bold: Boolean = false, secondary: Boolean = false, top: Int = 0) =
        TextView(act).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextColor(
                if (secondary) attr(M.attr.colorOnSurfaceVariant, Color.GRAY)
                else attr(M.attr.colorOnSurface, Color.BLACK)
            )
            setPadding(0, top, 0, 0)
        }

    private fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = bottom }

    private fun fullWidth() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
