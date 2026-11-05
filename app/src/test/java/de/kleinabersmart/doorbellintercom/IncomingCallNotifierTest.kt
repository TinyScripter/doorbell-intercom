package de.kleinabersmart.doorbellintercom

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class IncomingCallNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        AppConfig(context).doorOpenUrl = "http://ha.local/api/webhook/door"
    }

    private fun showRinging(picture: Bitmap? = null): Notification {
        val now = SystemClock.elapsedRealtime()
        IncomingCallNotifier.showRinging(
            context,
            CallState.Ringing(seq = 1, id = "A", source = "Push", rangAt = System.currentTimeMillis(), endsAt = now + 30_000),
            picture
        )
        return manager.activeNotifications.single { it.id == CALL_NOTIFICATION }.notification
    }

    private val Notification.template get() = extras.getString(NotificationCompat.EXTRA_TEMPLATE)
    private val Notification.actionTitles get() = actions.orEmpty().map { it.title.toString() }

    /** Vor Android 14 gibt es die Full-Screen-Erlaubnis nur über das Manifest. */
    @Test
    @Config(sdk = [33])
    fun ringing_isCallStyle_withDoorAsCallerAndDoorAction() {
        val notification = showRinging(Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888))

        assertEquals(Notification.CallStyle::class.java.name, notification.template)
        val caller = notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        assertEquals(context.getString(R.string.call_caller_name), caller?.name)
        // Vorschaubild als quadratisches Bild der "Anruferin", nicht als großes Bild.
        val icon = caller?.icon?.loadDrawable(context) as BitmapDrawable
        assertEquals(256 to 256, icon.bitmap.width to icon.bitmap.height)
        assertNull(notification.extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java))
        assertTrue(context.getString(R.string.btn_door_open) in notification.actionTitles)
        assertNotNull(notification.fullScreenIntent)
    }

    /** Ohne Full-Screen-Erlaubnis (Android 14+) lässt das System CallStyle nicht zu: wie früher. */
    @Test
    fun ringing_withoutFullScreenPermission_fallsBackToBigPicture() {
        // Robolectric verweigert die Erlaubnis auf SDK 36.
        assertFalse(manager.canUseFullScreenIntent())

        val notification = showRinging(Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888))

        assertEquals(Notification.BigPictureStyle::class.java.name, notification.template)
        assertEquals(
            listOf(R.string.btn_accept, R.string.btn_decline, R.string.btn_door_open).map(context::getString),
            notification.actionTitles
        )
    }

    private companion object {
        const val CALL_NOTIFICATION = 2
    }
}
