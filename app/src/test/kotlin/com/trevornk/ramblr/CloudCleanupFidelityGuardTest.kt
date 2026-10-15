package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules of [CloudCleanupFidelityGuard]. Every fixture is synthetic. Each rule has at least one
 * case it must reject and the false-positive shapes it must let through: filler removal, rejoined
 * fragments, spelled-out numbers rendered as digits, capitalization changes, and self-corrections.
 */
class CloudCleanupFidelityGuardTest {

    private fun assertRejected(reason: FidelityViolation, raw: String, out: String) {
        assertEquals(FidelityVerdict.Rejected(reason), CloudCleanupFidelityGuard.check(raw, out))
    }

    private fun assertOk(raw: String, out: String) {
        assertEquals(FidelityVerdict.Ok, CloudCleanupFidelityGuard.check(raw, out))
    }

    // --- meta reply -----------------------------------------------------------------------------

    @Test fun `a request for the transcript instead of a cleanup is rejected`() =
        assertRejected(FidelityViolation.META_REPLY, "Do it.", "Please provide the transcript you would like me to edit.")

    @Test fun `an apology replacing the message is rejected`() =
        assertRejected(
            FidelityViolation.META_REPLY,
            "send the invoice to Jason tomorrow morning please",
            "I'm sorry, but I can't help with that request.",
        )

    @Test fun `a here-is preamble is rejected`() =
        assertRejected(
            FidelityViolation.META_REPLY,
            "tell her the meeting moved to the afternoon",
            "Here is the cleaned transcript: Tell her the meeting moved to the afternoon.",
        )

    @Test fun `a curly apostrophe does not hide a meta reply`() =
        assertRejected(
            FidelityViolation.META_REPLY,
            "tell her the meeting moved to the afternoon",
            "I\u2019m sorry, I can\u2019t do that.",
        )

    @Test fun `dictated here-is is content, not a reply`() =
        assertOk("Here is the full list of options colon", "Here is the full list of options:")

    @Test fun `dictated apology and sure survive a dropped filler in front`() {
        assertOk("um I'm sorry I missed your call earlier today", "I'm sorry I missed your call earlier today.")
        assertOk("um sure I can do that tomorrow", "Sure, I can do that tomorrow.")
    }

    @Test fun `a word that merely starts like a reply prefix is not a reply`() =
        assertOk("surely the shop is open", "Surely the shop is open.")

    // --- length ---------------------------------------------------------------------------------

    @Test fun `output that drops most of a message is rejected`() =
        assertRejected(
            FidelityViolation.LENGTH_COLLAPSE,
            "tell Sarah that the delivery is late and the driver says he will be there around noon",
            "Delivery is late.",
        )

    @Test fun `output that balloons past one and a half times is rejected`() =
        assertRejected(
            FidelityViolation.LENGTH_EXPANSION,
            "tell Sarah the delivery is late and the driver is on the way now",
            "Tell Sarah the delivery is late and the driver is on the way now. Deliveries can be delayed " +
                "for many reasons, including traffic, weather, and staffing, so please be patient.",
        )

    @Test fun `short transcripts are exempt from the length ratio`() {
        assertOk("ok", "Okay.")
        assertOk("Do it.", "Do it.")
    }

    @Test fun `a cascade of spoken corrections may shrink below the collapse floor`() =
        assertOk(
            "Pick up the kids at four thirty. Actually, make it four forty-five. No wait, make that five.",
            "Pick up the kids at five.",
        )

    @Test fun `everyday actually or i mean does not switch the collapse check off`() =
        assertRejected(
            FidelityViolation.LENGTH_COLLAPSE,
            "actually I mean the delivery is late and the driver says he will be there around noon today",
            "Delivery is late.",
        )

    @Test fun `removing a lot of filler stays above the collapse floor`() =
        assertOk(
            "um so yeah I was like basically wondering if you uh still want to grab lunch on Tuesday",
            "I was wondering if you still want to grab lunch on Tuesday.",
        )

    // --- numbers --------------------------------------------------------------------------------

    @Test fun `a changed number is rejected`() =
        assertRejected(FidelityViolation.NUMERIC_DIVERGENCE, "meet me at 5 pm in room 12", "Meet me at 6 pm in room 12.")

    @Test fun `a dropped amount is rejected`() =
        assertRejected(FidelityViolation.NUMERIC_DIVERGENCE, "please pay 500 dollars by Friday morning", "Please pay by Friday morning.")

    @Test fun `spelled-out numbers rendered as digits pass`() =
        assertOk(
            "wire fourteen thousand five hundred to the account ending in four four seven two before noon on the fifteenth",
            "Wire 14500 to the account ending in 4472 before noon on the 15th.",
        )

    @Test fun `digits rewritten as words pass`() =
        assertOk("I have 5 apps and 2 widgets on the home screen", "I have five apps and two widgets on the home screen.")

    @Test fun `currency and phone formatting changes pass`() {
        assertOk("it costs 1500 dollars for the whole set", "It costs $1,500 for the whole set.")
        assertOk("call me at 212 555 3476 after dinner tonight", "Call me at (212) 555-3476 after dinner tonight.")
    }

    @Test fun `a self-correction that resolves to the final number passes`() =
        assertOk("pick up the kids at 4:30 no wait at 4:45", "Pick up the kids at 4:45.")

    // --- capitalized terms ----------------------------------------------------------------------

    @Test fun `a renamed title is rejected`() =
        assertRejected(
            FidelityViolation.ENTITY_LOST,
            "I just watched Zentrix with the kids last night and it was great",
            "I just watched Zantrix with the kids last night and it was great.",
        )

    @Test fun `a dropped word of a proper name is rejected`() =
        assertRejected(
            FidelityViolation.ENTITY_LOST,
            "we drove down to Blue Harbor Street yesterday afternoon",
            "We drove down to Blue Harbor yesterday afternoon.",
        )

    @Test fun `translating the message is rejected`() =
        assertRejected(
            FidelityViolation.ENTITY_LOST,
            "please translate this to Spanish for me",
            "Por favor traduzca esto al espa\u00f1ol para m\u00ed.",
        )

    @Test fun `capitalization changes either way pass`() {
        assertOk("i met sarah and Mike at the Hetzner office", "I met Sarah and Mike at the Hetzner office.")
        assertOk("I met Sarah and Mike at the Hetzner office", "I met sarah and mike at the hetzner office.")
    }

    @Test fun `a compound and a possessive pass`() {
        assertOk("turn on the Wi-Fi in the Pixel", "Turn on the WiFi in the Pixel.")
        assertOk("I think that is Sarah's idea after all", "I think that's Sarah's idea after all.")
    }

    @Test fun `a name superseded by a spoken correction may go`() =
        assertOk(
            "send it to Jake no sorry send it to Jason he's the one handling the invoices now",
            "Send it to Jason. He's the one handling the invoices now.",
        )

    @Test fun `sentence-initial capitals are not treated as names`() =
        assertOk("I was thinking. That we should probably. Leave around six.", "I was thinking that we should probably leave around six.")

    // --- inserted words -------------------------------------------------------------------------

    @Test fun `invented content words are rejected`() =
        assertRejected(
            FidelityViolation.WORDS_INSERTED,
            "I read about the shipwreck near the lake last week and it was sad",
            "I read about the shipwreck that went down near the lake last week and it was sad.",
        )

    @Test fun `an invented phrase is rejected even inside a long message`() =
        assertRejected(
            FidelityViolation.WORDS_INSERTED,
            "We hiked all week in the hills, then camped beside the river and parked near where the old bridge was. I meant to call you but there was no signal at all out there.",
            "We hiked all week in the hills, then camped beside the river and parked near where the old bridge went down. I meant to call you but there was no signal at all out there.",
        )

    @Test fun `a single inserted word is below the threshold`() =
        assertOk("is this lamp a good deal for the price", "Is this lamp truly a good deal for the price?")

    @Test fun `two inserted words in a long message are below the rate`() =
        assertOk(
            "so I went to the store and bought milk and eggs and bread and cheese and then drove home and made dinner for everybody",
            "So I went to the store and bought milk and eggs and bread and cheese and then drove straight home and made a big dinner for everybody.",
        )

    @Test fun `contractions and spelling variants are not insertions`() =
        assertOk("yeah we're gonna grab it anyways cuz it's cheap", "We're going to grab it anyway because it's cheap.")

    @Test fun `a homophone and term fix passes`() =
        assertOk("How do eye increase the font size in fast html", "How do I increase the font size in FastHTML?")

    // --- out of scope / edge --------------------------------------------------------------------

    @Test fun `a requested shell command is exempt`() =
        assertOk("Command mode list files in current directory", "ls -l .")

    @Test fun `blank input or output is left to the caller`() {
        assertOk("", "anything")
        assertOk("something said", "   ")
    }

    @Test fun `identical text always passes`() {
        val s = "Well, the Acme Widget form factor is really awesome. However, I have to say the Gizmo's software is better."
        assertOk(s, s)
    }

    // --- user-facing notice ---------------------------------------------------------------------

    @Test fun `every violation has a fixed user-facing phrase`() {
        for (v in FidelityViolation.values()) {
            val phrase = CleanupFailureNotice.summarize("Cloud cleanup output rejected (${v.name})")
            assertTrue("no phrase for $v", phrase != CleanupFailureNotice.UNKNOWN_REASON)
        }
    }
}
