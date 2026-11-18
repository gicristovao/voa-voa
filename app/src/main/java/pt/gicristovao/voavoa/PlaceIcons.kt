package pt.gicristovao.voavoa

object PlaceIcons {
    fun icon(kind: PlaceKind): Int = when (kind) {
        PlaceKind.LOCALITY -> R.drawable.ic_city
        PlaceKind.LANDMARK -> R.drawable.ic_landmark
        PlaceKind.NATURE -> R.drawable.ic_park
        PlaceKind.TRANSPORT -> R.drawable.ic_transit
        PlaceKind.ADDRESS -> R.drawable.ic_home
        PlaceKind.OTHER -> R.drawable.ic_place
    }
}
