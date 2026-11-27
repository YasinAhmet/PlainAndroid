package app.plain.maps

/*
 * The other city tabs: the same map as Istanbul ([MainActivity]), each run in its own process (":ankara", ":izmir" …)
 * where [City.id] is that city (set by Plain Maps' Application). Their data, caches and settings are their own (see
 * [City]). Each has its own sync service in the same process.
 */

class AnkaraActivity : MainActivity()
class AnkaraSyncService : SyncService()

class IzmirActivity : MainActivity()
class IzmirSyncService : SyncService()

class KocaeliActivity : MainActivity()
class KocaeliSyncService : SyncService()

class AntalyaActivity : MainActivity()
class AntalyaSyncService : SyncService()
