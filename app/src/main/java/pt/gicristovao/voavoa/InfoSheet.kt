package pt.gicristovao.voavoa

import android.app.Activity
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M

/** Painel "Sobre": dados das zonas, versão da app (com atualização) e legenda. */
class InfoSheet(
    private val act: Activity,
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
    private val dialog = BottomSheetDialog(act)

    fun show(newer: Updater.Release?, checked: Boolean) {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(24))
        }
        col.addView(BottomSheetDragHandleView(act))

        // Cabeçalho
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(act).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
        })
        header.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Voa Voa", 20f, bold = true))
            addView(text("Zonas de voo de drones em Portugal", 13f, secondary = true))
        })
        col.addView(header, lp(bottom = dp(16)))

        // Dados das zonas (só informação: a atualização faz-se no cartão do mapa)
        col.addView(card(
            text("Dados das zonas", 12f, secondary = true),
            text(dataLine, 16f, bold = true, top = dp(4)),
            text(dataDetail, 13f, secondary = true),
        ), lp(bottom = dp(10)))

        // App
        badge = text("", 12f, bold = true).apply { setPadding(dp(10), dp(3), dp(10), dp(3)) }
        val versionRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Versão $versionName", 16f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(badge)
        }
        buttonBox = FrameLayout(act)
        col.addView(card(
            text("App", 12f, secondary = true),
            versionRow.apply { setPadding(0, dp(4), 0, dp(10)) },
            buttonBox,
        ), lp(bottom = dp(18)))
        setAppState(newer, checked)

        // Legenda
        col.addView(text("Cores no mapa", 14f, bold = true), lp(bottom = dp(8)))
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

        dialog.setContentView(ScrollView(act).apply { addView(col) })
        dialog.show()
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
                setOnClickListener { dialog.dismiss(); onInstall(newer) }
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
