package app.mise.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Creates a version 3 database (v0.5) with a real row, opens it with the current Room (which
 * runs the 3 to 4 migration and rejects any schema mismatch), and that "To check" moved from tags to status.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MiseDb::class.java)

    @Test fun threeToFour() {
        helper.createDatabase("mig", 3).apply {
            execSQL(
                "INSERT INTO recipes (id, title, cuisine, tags, baseServings, prepMins, cookMins, notes, source, createdAt, updatedAt) " +
                    "VALUES (1, 'Butter chicken', 'Indian', 'From Folio, To check', 4, NULL, NULL, '', 'folio', 0, 0)",
            )
            close()
        }
        // Opening with Room runs the migration and checks every table matches what Room expects.
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), MiseDb::class.java, "mig")
            .addMigrations(*MiseDb.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val db = room.openHelper.writableDatabase
        db.query("SELECT status, tags, dishType FROM recipes WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals("to_check", c.getString(0))
            assertEquals("", c.getString(1))
            assertEquals("", c.getString(2))
        }
        room.close()
    }
}
