package xyz.znix.xftl.savegame

/** A saved run could not be read back. Its message is the cause's, so whatever shows it says the same as before. */
class RunLoadException(cause: RuntimeException) : RuntimeException(cause.message, cause)
