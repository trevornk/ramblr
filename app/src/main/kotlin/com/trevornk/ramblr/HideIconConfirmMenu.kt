package com.trevornk.ramblr

/** Row specs for the "Hide the floating icon?" confirm page (#373), kept pure so it's unit-testable. */
enum class ConfirmRowKind { HEADER, PRIMARY, SECONDARY }

data class ConfirmRowSpec(val kind: ConfirmRowKind, val title: String, val subtitle: String) {
    /** Only action rows respond to taps; the question header must never be tappable. */
    val clickable: Boolean get() = kind != ConfirmRowKind.HEADER
}

object HideIconConfirmMenu {
    val rows: List<ConfirmRowSpec> = listOf(
        ConfirmRowSpec(ConfirmRowKind.HEADER, "Hide the floating icon?", "The ring disappears until you bring it back."),
        ConfirmRowSpec(
            ConfirmRowKind.PRIMARY, "Hide it",
            "Get it back from the notification, the Quick Settings tile, or Settings > Floating icon",
        ),
        ConfirmRowSpec(ConfirmRowKind.SECONDARY, "Cancel", "Leave the icon where it is"),
    )
}
