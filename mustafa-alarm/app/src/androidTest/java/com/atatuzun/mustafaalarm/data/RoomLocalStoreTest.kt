package com.atatuzun.mustafaalarm.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atatuzun.mustafaalarm.data.local.AlarmDatabase
import com.atatuzun.mustafaalarm.data.local.RoomLocalStore
import com.atatuzun.mustafaalarm.domain.CachedOccurrence
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.domain.RingAction
import com.atatuzun.mustafaalarm.domain.RingingEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLocalStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AlarmDatabase
    private lateinit var store: RoomLocalStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java).build()
        store = RoomLocalStore(db.dao())
    }

    @After fun tearDown() { db.close() }

    @Test
    fun handled_roundTrip_upsert_andPrune() {
        store.markHandled(InstanceKey(1, 1_000), RingAction.STOP, 5)
        store.markHandled(InstanceKey(2, 9_000), RingAction.SNOOZE, 6)
        store.markHandled(InstanceKey(1, 1_000), RingAction.SNOOZE, 7)
        assertEquals(setOf(InstanceKey(1, 1_000), InstanceKey(2, 9_000)), store.handledKeys())
        store.pruneHandled(5_000)
        assertEquals(setOf(InstanceKey(2, 9_000)), store.handledKeys())
    }

    @Test
    fun ringCache_keepsUnicodeTitles() {
        val title = "Fırat şap makinesi 🚚\nteklif ver " + "ğüşöçİ".repeat(60)
        store.replaceRingCache(listOf(CachedOccurrence(InstanceKey(3, 2_000), 3, title), CachedOccurrence(InstanceKey(4, 1_000), 9, "b")))
        assertEquals(listOf(InstanceKey(4, 1_000), InstanceKey(3, 2_000)), store.ringCache().map { it.key })
        assertEquals(title, store.ringCache().last().title)
        assertEquals(9L, store.ringCache().first().alarmId)
        store.addToRingCache(CachedOccurrence(InstanceKey(5, 500), 5, "c"))
        assertEquals(3, store.ringCache().size)
        store.replaceRingCache(emptyList())
        assertTrue(store.ringCache().isEmpty())
    }

    @Test
    fun ringing_addAndRemove() {
        store.addRinging(listOf(RingingEntry(InstanceKey(1, 10), 1, "a", 100), RingingEntry(InstanceKey(2, 10), 2, "b", 101)))
        assertEquals(listOf("a", "b"), store.ringing().map { it.title })
        store.removeRinging(listOf(InstanceKey(1, 10)))
        assertEquals(listOf(InstanceKey(2, 10)), store.ringing().map { it.key })
    }

    @Test
    fun pending_inPressOrder() {
        store.addPending(InstanceKey(1, 10), RingAction.SNOOZE, 20)
        store.addPending(InstanceKey(2, 10), RingAction.STOP, 10)
        val pending = store.pendingActions()
        assertEquals(listOf(10L, 20L), pending.map { it.pressedAt })
        assertEquals(RingAction.STOP, pending.first().action)
        store.removePending(pending.first().id)
        assertEquals(listOf(InstanceKey(1, 10)), store.pendingActions().map { it.key })
    }

    @Test
    fun soundAndHistory() {
        store.setSound(7, "content://x")
        assertEquals("content://x", store.soundFor(7))
        store.setSound(7, null)
        assertNull(store.soundFor(7))
        store.recordCreation(450, 100)
        store.recordCreation(480, 50)
        assertEquals(listOf(450 to 100L), store.creationHistory(60))
    }

    @Test
    fun snoozeOrigin_roundTrip_overwrite_andClear() {
        assertNull(store.snoozeOrigin(7))
        store.setSnoozeOrigin(7, 1_000)
        store.setSnoozeOrigin(7, 2_000)
        assertEquals(2_000L, store.snoozeOrigin(7))
        store.clearSnoozeOrigin(7)
        assertNull(store.snoozeOrigin(7))
    }
}
