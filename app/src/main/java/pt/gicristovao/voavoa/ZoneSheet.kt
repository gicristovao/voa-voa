package pt.gicristovao.voavoa

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Locale

/** Painel inferior com o resultado de "posso voar aqui?" e o detalhe de cada zona. */
object ZoneSheet {

    fun show(act: Activity, p: LatLon, fromGps: Boolean, active: List<Zone>, upcoming: List<Zone>) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        // Cabeçalho: local
        col.addView(text(act, String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon) +
            if (fromGps) "  ·  a tua localização" else "", 12f, color = Color.GRAY))

        // Veredicto
        val worst = active.maxByOrNull { it.type.rank }?.type
        val (bg, fg) = verdictColors(worst)
        val verdict = MaterialCardView(act).apply {
            setCardBackgroundColor(bg)
            radius = dp(14).toFloat()
            cardElevation = 0f
            val inner = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(text(act, Texts.verdict(worst), 20f, bold = true, color = fg))
                addView(text(act, Texts.verdictDetail(worst), 14f, color = fg, top = dp(4)))
            }
            addView(inner)
        }
        col.addView(verdict, lp(top = dp(8)))

        if (active.isNotEmpty()) {
            col.addView(text(act, "Zonas neste ponto (${active.size})", 15f, bold = true, top = dp(18)))
            active.forEach { col.addView(zoneCard(act, it, future = false), lp(top = dp(10))) }
        }
        if (upcoming.isNotEmpty()) {
            col.addView(text(act, "Restrições temporárias futuras (${upcoming.size})", 15f, bold = true, top = dp(18)))
            upcoming.forEach { col.addView(zoneCard(act, it, future = true), lp(top = dp(10))) }
        }

        col.addView(text(act, MainActivity.DISCLAIMER, 12f, color = Color.GRAY, top = dp(18)))

        val scroll = ScrollView(act).apply { addView(col) }
        BottomSheetDialog(act).apply {
            setContentView(scroll)
            behavior.peekHeight = (act.resources.displayMetrics.heightPixels * 0.55).toInt()
            show()
        }
    }

    private fun zoneCard(act: Activity, z: Zone, future: Boolean): MaterialCardView {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val inner = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val (chipBg, chipFg) = verdictColors(z.type)

        inner.addView(text(act, z.name, 16f, bold = true))
        inner.addView(text(act, "  ${Texts.type(z.type)}  ", 12f, bold = true, color = chipFg).apply {
            setBackgroundColor(chipBg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })

        fun field(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            inner.addView(text(act, label, 12f, color = Color.GRAY, top = dp(10)))
            inner.addView(text(act, value, 14f))
        }

        field("Condições", z.message)
        field("Altitude da zona", Texts.altitude(z))
        val motivo = (z.reasons.map { Texts.reason(it) } + listOfNotNull(z.otherReason)).distinct().joinToString(" · ")
        field("Motivo", motivo)
        field(if (future) "Quando" else "Validade", Texts.validity(z))
        field("Horário", z.periods.firstOrNull()?.schedule)

        for (a in z.authorities) {
            val isAuth = a.purpose.equals("AUTHORIZATION", true)
            val title = if (isAuth) "Pedir autorização a" else "Informação"
            val who = listOfNotNull(a.name, a.service, a.contactName?.let { "Contacto: $it" })
                .distinct().joinToString("\n")
            val lead = Texts.interval(a.intervalBefore)?.let { "\nAntecedência do pedido: $it" } ?: ""
            field(title, who + lead)
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            a.email?.let { email ->
                row.addView(button(act, "Email") {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                        .putExtra(Intent.EXTRA_SUBJECT, "Pedido de autorização de voo UAS – ${z.name} (${z.id})")
                    open(act, i)
                })
            }
            a.phone?.let { ph ->
                row.addView(button(act, "Ligar") {
                    open(act, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Texts.phoneForDial(ph))))
                })
            }
            a.site?.let { url ->
                row.addView(button(act, "Site") { open(act, Intent(Intent.ACTION_VIEW, Uri.parse(url))) })
            }
            if (row.childCount > 0) inner.addView(row)
        }

        z.notam?.let {
            inner.addView(text(act, "NOTAM", 12f, color = Color.GRAY, top = dp(10)))
            inner.addView(text(act, it.replace("\r", ""), 12f).apply { typeface = Typeface.MONOSPACE })
        }
        inner.addView(text(act, "Identificador: ${z.id}", 11f, color = Color.GRAY, top = dp(10)))

        return MaterialCardView(act).apply {
            radius = dp(14).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#DDDDDD")
            cardElevation = 0f
            addView(inner)
        }
    }

    private fun verdictColors(t: ZoneType?): Pair<Int, Int> = when (t) {
        ZoneType.PROHIBITED -> Color.parseColor("#C62828") to Color.WHITE
        ZoneType.REQ_AUTHORIZATION -> Color.parseColor("#F9A825") to Color.parseColor("#212121")
        ZoneType.CONDITIONAL -> Color.parseColor("#FFF176") to Color.parseColor("#212121")
        ZoneType.NO_RESTRICTION -> Color.parseColor("#B0BEC5") to Color.parseColor("#212121")
        ZoneType.UNKNOWN -> Color.parseColor("#FFF176") to Color.parseColor("#212121")
        null -> Color.parseColor("#2E7D32") to Color.WHITE
    }

    private fun text(
        act: Activity, s: String, size: Float, bold: Boolean = false, color: Int? = null, top: Int = 0,
    ) = TextView(act).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        color?.let { setTextColor(it) }
        setTextIsSelectable(!bold)
        setPadding(0, top, 0, 0)
    }

    private fun button(act: Activity, label: String, onClick: () -> Unit) =
        MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * act.resources.displayMetrics.density).toInt() }
        }

    private fun open(act: Activity, i: Intent) {
        try {
            act.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(act, "Nenhuma app disponível para abrir isto", Toast.LENGTH_SHORT).show()
        }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }
}
