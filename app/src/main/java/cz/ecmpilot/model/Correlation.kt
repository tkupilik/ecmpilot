package cz.ecmpilot.model

import java.util.UUID

/** One logical operation across preflight, retries, CAN work and result queries. */
data class Correlation(val session: String, val id: Long) {
    init { require(Regex("[a-f0-9]{32}").matches(session) && id in 1..0xffff_ffffL) }
    val label get() = "$session/$id"
}

/** Wrap starts a new scope rather than reusing a live operation's identity. */
class CorrelationCounter(private val newSession: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    initial: Long = 0) {
    private var session = newSession()
    private var counter = initial.also { require(it in 0..0xffff_ffffL) }
    @Synchronized fun next(): Correlation {
        if (counter == 0xffff_ffffL) { session = newSession(); counter = 0 }
        return Correlation(session, ++counter)
    }
}
