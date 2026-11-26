package app.plain.maps

/**
 * The Ankara tab: the same map as Istanbul ([MainActivity]), run in the ":ankara" process where [City.id] is
 * "ankara" (set by Plain Maps' Application). Its data, caches and settings are its own (see [City]).
 */
class AnkaraActivity : MainActivity()

/** Ankara's sync, in the ":ankara" process with [AnkaraActivity]. */
class AnkaraSyncService : SyncService()
