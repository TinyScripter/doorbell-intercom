package de.kleinabersmart.doorbellintercom

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hält die prozessweiten Objekte (siehe [AppContainer]). */
class DoorbellApp : Application() {
    val container by lazy { AppContainer(this) }
}

/**
 * Kleiner manueller Container statt Hilt: für die Handvoll prozessweiter Objekte reicht das.
 * Activities, Dienste und Empfänger holen sie über [Context.appContainer].
 */
class AppContainer(app: Application) {

    /**
     * Für Arbeit, die einen Bildschirm überdauern soll (Vorschaubild, Erreichbarkeitstest,
     * Klingel-Timeout). SupervisorJob: ein fehlgeschlagener Job beendet die anderen nicht.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val callController = CallController(app, appScope)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DoorbellApp).container
