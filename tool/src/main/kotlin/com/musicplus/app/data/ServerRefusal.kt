package com.musicplus.app.data

/**
 * An error from a server that answered and said no, as opposed to one that could not be reached. A write that was refused is told
 * apart from one that merely did not get through: the second is worth sending again later, the first is not always.
 */
interface ServerRefusal {
    /** True when sending the same request again cannot help: what it names is not on the server, or it is malformed. A refusal for a login or permission can clear, so it is not this. */
    val retryingCannotHelp: Boolean
}
