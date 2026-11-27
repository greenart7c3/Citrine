package com.greenart7c3.citrine.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EphemeralEventCleanupTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun hasEphemeralEventsTracksPresenceAcrossDeletions() = runBlocking {
        val dao = db.eventDao()
        assertFalse(dao.hasEphemeralEvents())

        // Non-ephemeral events (kind 1 and kind 30000) do not count as ephemeral.
        dao.insertEvents(
            listOf(
                EventEntity(id = "note1", pubkey = "pk", createdAt = 100L, kind = 1, content = "hi", sig = "s"),
                EventEntity(id = "addr1", pubkey = "pk", createdAt = 100L, kind = 30000, content = "", sig = "s"),
            ),
        )
        assertFalse(dao.hasEphemeralEvents())

        // Insert one old ephemeral event and one recent ephemeral event.
        dao.insertEvents(
            listOf(
                EventEntity(id = "eph_old", pubkey = "pk", createdAt = 100L, kind = 20000, content = "", sig = "s"),
                EventEntity(id = "eph_new", pubkey = "pk", createdAt = 200L, kind = 29999, content = "", sig = "s"),
            ),
        )
        assertTrue(dao.hasEphemeralEvents())

        // Deleting before 150 removes only the old ephemeral event; the recent one keeps hasEphemeralEvents true.
        dao.deleteEphemeralEvents(150L)
        assertTrue(dao.hasEphemeralEvents())

        // Deleting before 250 clears all remaining ephemeral events while leaving regular events intact.
        dao.deleteEphemeralEvents(250L)
        assertFalse(dao.hasEphemeralEvents())
        assertTrue(dao.existsById("note1"))
        assertTrue(dao.existsById("addr1"))
    }
}
