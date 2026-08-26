package com.trevornk.ramblr

/**
 * A small embedded list of everyday English words, used exclusively as
 * [VocabularyPostCorrector]'s false-positive guard (#182): a word in local cleanup output that
 * only FUZZILY matches a vocabulary term (edit distance > 0) is never rewritten when it is
 * itself an ordinary English word. "code" is one deletion from the default term "Codex", "fast"
 * one from "fast.ai's" first core, "answer" is the first word-core of "Answer.AI" -- without
 * this guard the corrector would rewrite normal prose into the user's jargon, which is exactly
 * the corruption the pass's conservatism contract forbids. Exact (case-insensitive) matches
 * bypass the guard entirely, so a user who deliberately configures a common word as a term
 * ("Pi") still gets recasing.
 *
 * Deliberately NOT a comprehensive dictionary: it needs to cover words a speaker plausibly says
 * that sit within edit distance 1-2 of typical project/name jargon, and the cost of a missing
 * word is one wrong correction only when it ALSO clears the first-letter and edit-distance
 * gates. ~1000 high-frequency lemmas plus common inflections is the right size for that job
 * without dragging a wordlist asset or a library dependency into the app (pure Kotlin, checked
 * in O(1) per candidate).
 */
object CommonEnglishWords {

    fun contains(word: String): Boolean = WORDS.contains(word.lowercase())

    private val WORDS: Set<String> = buildSet {
        val base = """
            the be to of and a in that have i it for not on with he as you do at this but his by
            from they we say her she or an will my one all would there their what so up out if
            about who get which go me when make can like time no just him know take people into
            year your good some could them see other than then now look only come its over think
            also back after use two how our work first well way even new want because any these
            give day most us is was are were been has had did says said gets got made makes went
            gone knows knew known takes took taken comes came sees saw seen looks looked uses
            used works worked wants wanted gives gave given days years ways things thing life
            child children world school state family student group country problem hand part
            place case week company system program question government number night point home
            water room mother area money story fact month lot right study book eye job word
            business issue side kind head house service friend father power hour game line end
            member law car city community name president team minute idea body information
            back parent face others level office door health person art war history party result
            change morning reason research girl guy moment air teacher force education foot boy
            age policy process music market sense nation plan college interest death experience
            effect class control care field development role effort rate heart drug show leader
            light voice wife whole police mind price report decision son view relationship town
            road arm difference value building action model season society tax director position
            player record paper space ground form event official matter center couple site
            project activity table court american oil situation cost industry figure street
            image phone data picture practice piece land product doctor wall patient worker news
            test movie north love support technology step baby computer type attention film tree
            source kind truth top current wind fire future site loss bank west sport board
            subject officer rule case management goal bed order growth listen letter condition
            choice single dinner rock salt fun horse target prison guard terms demand reporting
            capital model factor coach energy nice quite sort army bill dog bird lead read write
            written wrote reads writes reading writing run ran runs running walk walked walking
            talk talked talking called call calls calling ask asked asking asks need needed
            needs needing feel felt feels feeling become became becomes leave left leaves let
            lets put puts mean meant means keep kept keeps begin began begins seem seemed seems
            help helped helps show showed shows hear heard hears play played plays move moved
            moves live lived lives believe believed believes bring brought brings happen
            happened happens must might shall may stand stood stands lose lost loses pay paid
            pays meet met meets include included includes continue continued continues set sets
            learn learned learns lead leads understand understood understands watch watched
            watches follow followed follows stop stopped stops create created creates speak
            spoke speaks spoken allow allowed allows add added adds spend spent spends grow grew
            grows grown open opened opens win won wins offer offered offers remember remembered
            remembers consider considered considers appear appeared appears buy bought buys wait
            waited waits serve served serves die died dies send sent sends expect expected
            expects build built builds stay stayed stays fall fell falls fallen cut cuts reach
            reached reaches kill killed kills remain remained remains suggest suggested suggests
            raise raised raises pass passed passes sell sold sells require required requires
            report reported reports decide decided decides pull pulled pulls return returned
            returns explain explained explains hope hoped hopes develop developed develops carry
            carried carries break broke breaks broken receive received receives agree agreed
            agrees support supported supports hit hits produce produced produces eat ate eats
            eaten cover covered covers catch caught catches draw drew draws drawn choose chose
            chooses chosen cause caused causes point pointed points fight fought fights
            provide provided provides turn turned turns start started starts hold held holds
            big small large great high low long short old young early late important public bad
            same able best better sure free true full special easy clear recent certain
            personal open red white black hard possible whole real major military national
            human local late strong past political available economic social little less least
            different following international difficult simple both several final main
            beautiful nice happy serious ready common poor natural significant similar hot cold
            entire likely federal wrong tough deep dark various entire physical private
            medical top only close legal religious cold final green nearby blue fine popular
            traditional cultural fast slow faster slower quick quickly slowly really very too
            still even also never always often sometimes usually again once twice here where
            everywhere anywhere together alone almost enough far near away around behind above
            below between among during before after under since until while against without
            within along across toward towards perhaps maybe actually probably certainly
            clearly finally suddenly recently especially instead however although though yet
            code codes coded coding fast faster answer answers answered answering pie pit pin
            pine pip pig fasten fascia fashion caste cast paste taste vast last mast past
            answering clause clawed cloud clouds clout applaud claw claws played plot
            heaven hessian hasten headset henna herds hedges nab nabbed dev devs debit devote
            fascism fasted fastest court corked cortex context convex complex coded codecs
            solve solves solved solving solvent salute pieces piece
        """.trimIndent()
        base.split(Regex("\\s+")).forEach { if (it.isNotBlank()) add(it) }
    }
}
