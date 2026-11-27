package pt.gicristovao.voavoa.wear

import android.graphics.Color as AColor
import androidx.compose.ui.graphics.Color
import pt.gicristovao.voavoa.MILITARY_COLOR
import pt.gicristovao.voavoa.Zone
import pt.gicristovao.voavoa.ZoneType

/** Cores e símbolos do veredicto, iguais aos da app do telemóvel. */
object WearUi {

    fun verdictColor(t: ZoneType?): Color = when (t) {
        ZoneType.PROHIBITED -> Color(0xFFC62828)
        ZoneType.REQ_AUTHORIZATION -> Color(0xFFF9A825)
        ZoneType.CONDITIONAL, ZoneType.UNKNOWN -> Color(0xFFFFF176)
        ZoneType.NO_RESTRICTION -> Color(0xFFB0BEC5)
        null -> Color(0xFF2E7D32)
    }

    fun verdictInk(t: ZoneType?): Color = when (t) {
        ZoneType.PROHIBITED, null -> Color.White
        else -> Color(0xFF212121)
    }

    fun symbol(t: ZoneType?): String = when (t) {
        null -> "✓"
        ZoneType.PROHIBITED -> "✕"
        else -> "!"
    }

    fun shortVerdict(t: ZoneType?): String = when (t) {
        null -> "Sem zonas aqui"
        ZoneType.PROHIBITED -> "Voo proibido"
        ZoneType.REQ_AUTHORIZATION -> "Precisa de autorização"
        ZoneType.CONDITIONAL -> "Voo condicionado"
        ZoneType.NO_RESTRICTION -> "Sem restrição"
        ZoneType.UNKNOWN -> "Com restrições"
    }

    /** Cor da zona no mapa (cor da ANAC, ou pelo tipo). */
    fun zoneColor(z: Zone): Int {
        if (z.isMilitary && z.type != ZoneType.PROHIBITED) return MILITARY_COLOR
        z.colorHex?.let { hex -> runCatching { return AColor.parseColor("#$hex") } }
        return when (z.type) {
            ZoneType.PROHIBITED -> AColor.RED
            ZoneType.REQ_AUTHORIZATION, ZoneType.CONDITIONAL -> AColor.YELLOW
            else -> AColor.GRAY
        }
    }
}
