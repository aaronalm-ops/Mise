package app.mise.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Shared ingredient catalogue. aisle and stapleChoice are the household's own settings
 * (stapleChoice -1 = not set, so the built-in staples list decides). aliasOf merges this name
 * into another on the list ("Same as...").
 */
@Entity(tableName = "ingredients", indices = [Index(value = ["nameKey"], unique = true)])
data class IngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameKey: String,
    val aisle: String = "",
    val isStaple: Boolean = false,
    @ColumnInfo(defaultValue = "-1") val stapleChoice: Int = -1,
    @ColumnInfo(defaultValue = "''") val aliasOf: String = "",
)

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val cuisine: String = "",
    val tags: String = "",
    val baseServings: Int = 4,
    val prepMins: Int? = null,
    val cookMins: Int? = null,
    val notes: String = "",
    val source: String = "manual",
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val dishType: String = "",
    @ColumnInfo(defaultValue = "'ok'") val status: String = "ok",
)

@Entity(
    tableName = "recipe_steps",
    foreignKeys = [ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recipeId")],
)
data class StepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val position: Int,
    val text: String,
)

@Entity(
    tableName = "variant_slots",
    foreignKeys = [ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recipeId")],
)
data class SlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val position: Int,
    val name: String,
)

@Entity(
    tableName = "variant_options",
    foreignKeys = [ForeignKey(entity = SlotEntity::class, parentColumns = ["id"], childColumns = ["slotId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("slotId"), Index("recipeId")],
)
data class OptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slotId: Long,
    val recipeId: Long,
    val position: Int,
    val label: String,
    val isDefault: Boolean,
    val note: String = "",
)

/** optionId null: always used. Set: only when that swap option is chosen. */
@Entity(
    tableName = "recipe_ingredients",
    foreignKeys = [ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recipeId"), Index("ingredientId")],
)
data class RecipeIngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val ingredientId: Long,
    val optionId: Long?,
    val position: Int,
    val qty: Double?,
    val unit: String,
    val prepNote: String = "",
)

/** An unsaved recipe editor, kept as JSON so closing the app never loses typing. */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long?,
    val title: String,
    val json: String,
    val updatedAt: Long,
)

/** A dish on a day and meal, with its own servings and chosen swap options (labels). */
@Entity(
    tableName = "plan_entries",
    foreignKeys = [ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recipeId"), Index("date")],
)
data class PlanEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO date, e.g. 2026-10-27. */
    val date: String,
    val meal: String,
    val recipeId: Long,
    val servings: Int,
    val choices: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val leftovers: Boolean = false,
)

/** Eating out, takeaway or any note on the plan. */
@Entity(tableName = "plan_notes", indices = [Index("date")])
data class PlanNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val meal: String,
    val text: String,
    val createdAt: Long,
)

/**
 * A ticked grocery line for a week. A row exists only while the line is ticked. amount is the
 * line's total when it was ticked, so the line reopens if a dish added later needs more.
 */
@Entity(tableName = "list_checks", primaryKeys = ["weekStart", "itemKey"])
data class ListCheckEntity(
    val weekStart: String,
    val itemKey: String,
    val checkedAt: Long,
    @ColumnInfo(defaultValue = "0") val amount: Double = 0.0,
    @ColumnInfo(defaultValue = "''") val checkedBy: String = "",
)

/** A one-off item typed onto a week's list (milk, tissues), in its aisle. */
@Entity(tableName = "list_extras", indices = [Index("weekStart")])
data class ListExtraEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: String,
    val name: String,
    val qty: String,
    val checked: Boolean,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val aisle: String = "",
    @ColumnInfo(defaultValue = "''") val checkedBy: String = "",
)

/**
 * Links a local item to its shared row. dirty counts local edits not yet sent; remoteAt is the
 * shared row's last known change time, so a copy already applied is not applied again.
 */
@Entity(tableName = "sync_rows", primaryKeys = ["kind", "localKey"], indices = [Index(value = ["kind", "uid"], unique = true)])
data class SyncRowEntity(
    val kind: String,
    val localKey: String,
    val uid: String,
    val dirty: Int,
    @ColumnInfo(defaultValue = "''") val remoteAt: String = "",
)

/** A shared item deleted here, waiting to be marked deleted for the household. */
@Entity(tableName = "sync_deletes", primaryKeys = ["kind", "uid"])
data class SyncDeleteEntity(
    val kind: String,
    val uid: String,
)

data class IngredientRow(
    val optionId: Long?,
    val position: Int,
    val qty: Double?,
    val unit: String,
    val prepNote: String,
    val name: String,
)

data class RecipeSummaryRow(
    val id: Long,
    val title: String,
    val cuisine: String,
    val dishType: String,
    val status: String,
    val baseServings: Int,
    val slotCount: Int,
    val ingredientCount: Int,
    val ingredientNames: String?,
    val lastPlanned: String?,
)

data class TitleRow(val id: Long, val title: String, val status: String, val source: String)

@Dao
interface RecipeDao {
    @Query(
        "SELECT r.id, r.title, r.cuisine, r.dishType, r.status, r.baseServings, " +
            "(SELECT COUNT(*) FROM variant_slots s WHERE s.recipeId = r.id) AS slotCount, " +
            "(SELECT COUNT(*) FROM recipe_ingredients i WHERE i.recipeId = r.id AND i.optionId IS NULL) AS ingredientCount, " +
            "(SELECT group_concat(g.name, ' ') FROM recipe_ingredients ri JOIN ingredients g ON g.id = ri.ingredientId WHERE ri.recipeId = r.id) AS ingredientNames, " +
            "(SELECT MAX(p.date) FROM plan_entries p WHERE p.recipeId = r.id AND p.leftovers = 0) AS lastPlanned " +
            "FROM recipes r ORDER BY r.title COLLATE NOCASE",
    )
    fun observeSummaries(): Flow<List<RecipeSummaryRow>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun observeRecipe(id: Long): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun recipe(id: Long): RecipeEntity?

    @Query("UPDATE recipes SET status = :status, updatedAt = :at WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, at: Long)

    @Query("SELECT id, title, status, source FROM recipes")
    suspend fun titleRows(): List<TitleRow>

    @Query("SELECT * FROM recipe_steps WHERE recipeId = :id ORDER BY position")
    suspend fun steps(id: Long): List<StepEntity>

    @Query("SELECT * FROM variant_slots WHERE recipeId = :id ORDER BY position")
    suspend fun slots(id: Long): List<SlotEntity>

    @Query("SELECT * FROM variant_options WHERE recipeId = :id ORDER BY position")
    suspend fun options(id: Long): List<OptionEntity>

    @Query(
        "SELECT ri.optionId AS optionId, ri.position AS position, ri.qty AS qty, ri.unit AS unit, " +
            "ri.prepNote AS prepNote, i.name AS name FROM recipe_ingredients ri " +
            "JOIN ingredients i ON i.id = ri.ingredientId WHERE ri.recipeId = :id ORDER BY ri.position",
    )
    suspend fun ingredientRows(id: Long): List<IngredientRow>

    @Insert
    suspend fun insertRecipe(r: RecipeEntity): Long

    @Update
    suspend fun updateRecipe(r: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun deleteRecipe(id: Long)

    @Query("DELETE FROM recipe_steps WHERE recipeId = :id")
    suspend fun deleteSteps(id: Long)

    @Query("DELETE FROM variant_slots WHERE recipeId = :id")
    suspend fun deleteSlots(id: Long)

    @Query("DELETE FROM variant_options WHERE recipeId = :id")
    suspend fun deleteOptions(id: Long)

    @Query("DELETE FROM recipe_ingredients WHERE recipeId = :id")
    suspend fun deleteIngredients(id: Long)

    @Insert
    suspend fun insertSteps(s: List<StepEntity>)

    @Insert
    suspend fun insertSlot(s: SlotEntity): Long

    @Insert
    suspend fun insertOption(o: OptionEntity): Long

    @Insert
    suspend fun insertRecipeIngredients(i: List<RecipeIngredientEntity>)

    // Ingredient catalogue

    @Query("SELECT * FROM ingredients WHERE nameKey = :key")
    suspend fun ingredientByKey(key: String): IngredientEntity?

    @Query("SELECT * FROM ingredients WHERE nameKey = :key")
    suspend fun ingredient(key: String): IngredientEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIngredient(i: IngredientEntity): Long

    @Query("SELECT name FROM ingredients ORDER BY name COLLATE NOCASE")
    fun observeIngredientNames(): Flow<List<String>>

    @Query("SELECT * FROM ingredients")
    fun observeIngredients(): Flow<List<IngredientEntity>>

    @Query("SELECT * FROM ingredients")
    suspend fun allIngredients(): List<IngredientEntity>

    @Query("UPDATE ingredients SET aisle = :aisle WHERE id = :id")
    suspend fun setAisle(id: Long, aisle: String)

    @Query("UPDATE ingredients SET stapleChoice = :choice, isStaple = (:choice = 1) WHERE id = :id")
    suspend fun setStapleChoice(id: Long, choice: Int)

    @Query("UPDATE ingredients SET aliasOf = :aliasOf WHERE id = :id")
    suspend fun setAlias(id: Long, aliasOf: String)

    // Drafts

    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    fun observeDrafts(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun draft(id: Long): DraftEntity?

    @Query("SELECT * FROM drafts WHERE recipeId = :recipeId LIMIT 1")
    suspend fun draftForRecipe(recipeId: Long): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(d: DraftEntity): Long

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteDraft(id: Long)

    // Plan

    @Query("SELECT p.* FROM plan_entries p JOIN recipes r ON r.id = p.recipeId WHERE p.date >= :from AND p.date <= :to ORDER BY p.date, p.createdAt")
    fun observePlan(from: String, to: String): Flow<List<PlanEntryEntity>>

    @Query("SELECT p.* FROM plan_entries p JOIN recipes r ON r.id = p.recipeId WHERE p.date >= :from AND p.date <= :to ORDER BY p.date, p.createdAt")
    suspend fun planRange(from: String, to: String): List<PlanEntryEntity>

    @Query("SELECT * FROM plan_notes WHERE date >= :from AND date <= :to ORDER BY date, createdAt")
    fun observeNotes(from: String, to: String): Flow<List<PlanNoteEntity>>

    @Query("SELECT * FROM plan_entries WHERE id = :id")
    suspend fun planEntry(id: Long): PlanEntryEntity?

    @Insert
    suspend fun insertPlan(e: PlanEntryEntity): Long

    @Update
    suspend fun updatePlan(e: PlanEntryEntity)

    @Query("DELETE FROM plan_entries WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Query("SELECT id FROM plan_entries WHERE recipeId = :recipeId")
    suspend fun planIdsForRecipe(recipeId: Long): List<Long>

    @Query("SELECT recipeId FROM plan_entries GROUP BY recipeId ORDER BY MAX(createdAt) DESC LIMIT :n")
    fun observeRecentRecipeIds(n: Int): Flow<List<Long>>

    @Query("SELECT DISTINCT date FROM plan_entries WHERE date < :before ORDER BY date DESC LIMIT 120")
    suspend fun pastPlanDates(before: String): List<String>

    @Query("SELECT * FROM plan_notes WHERE id = :id")
    suspend fun note(id: Long): PlanNoteEntity?

    @Insert
    suspend fun insertNote(n: PlanNoteEntity): Long

    @Update
    suspend fun updateNote(n: PlanNoteEntity)

    @Query("DELETE FROM plan_notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    // List

    @Query("SELECT * FROM list_checks WHERE weekStart = :week")
    fun observeChecks(week: String): Flow<List<ListCheckEntity>>

    @Query("SELECT * FROM list_checks WHERE weekStart = :week AND itemKey = :key")
    suspend fun checkRow(week: String, key: String): ListCheckEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun check(c: ListCheckEntity)

    @Query("DELETE FROM list_checks WHERE weekStart = :week AND itemKey = :key")
    suspend fun uncheck(week: String, key: String)

    @Query("SELECT COUNT(*) FROM list_checks WHERE weekStart = :week AND itemKey = :key")
    suspend fun isChecked(week: String, key: String): Int

    /** This week's extras, plus unbought ones carried over from earlier weeks. */
    @Query("SELECT * FROM list_extras WHERE weekStart = :week OR (weekStart < :week AND checked = 0) ORDER BY createdAt")
    fun observeExtras(week: String): Flow<List<ListExtraEntity>>

    /** Names added by hand before, most recent first, for the quick chips when adding. */
    @Query("SELECT name FROM list_extras GROUP BY name COLLATE NOCASE ORDER BY MAX(createdAt) DESC LIMIT :n")
    fun observeRecentExtraNames(n: Int): Flow<List<String>>

    @Query("SELECT * FROM list_extras WHERE id = :id")
    suspend fun extra(id: Long): ListExtraEntity?

    @Insert
    suspend fun insertExtra(e: ListExtraEntity): Long

    @Update
    suspend fun updateExtra(e: ListExtraEntity)

    @Query("DELETE FROM list_extras WHERE id = :id")
    suspend fun deleteExtra(id: Long)
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_rows WHERE kind = :kind AND localKey = :key")
    suspend fun row(kind: String, key: String): SyncRowEntity?

    @Query("SELECT * FROM sync_rows WHERE kind = :kind AND uid = :uid")
    suspend fun byUid(kind: String, uid: String): SyncRowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(r: SyncRowEntity)

    @Query("UPDATE sync_rows SET dirty = dirty + 1 WHERE kind = :kind AND localKey = :key")
    suspend fun bump(kind: String, key: String)

    @Query("UPDATE sync_rows SET dirty = 0 WHERE kind = :kind AND localKey = :key AND dirty = :dirty")
    suspend fun clean(kind: String, key: String, dirty: Int)

    @Query("UPDATE sync_rows SET remoteAt = :at WHERE kind = :kind AND uid = :uid")
    suspend fun setRemoteAt(kind: String, uid: String, at: String)

    @Query("SELECT * FROM sync_rows WHERE dirty > 0")
    suspend fun dirty(): List<SyncRowEntity>

    @Query("SELECT COUNT(*) FROM sync_rows WHERE dirty > 0")
    suspend fun dirtyCount(): Int

    @Query("DELETE FROM sync_rows WHERE kind = :kind AND localKey = :key")
    suspend fun remove(kind: String, key: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun tomb(d: SyncDeleteEntity)

    @Query("SELECT * FROM sync_deletes")
    suspend fun tombs(): List<SyncDeleteEntity>

    @Query("DELETE FROM sync_deletes WHERE kind = :kind AND uid = :uid")
    suspend fun clearTomb(kind: String, uid: String)

    @Query("DELETE FROM sync_rows")
    suspend fun clearRows()

    @Query("DELETE FROM sync_deletes")
    suspend fun clearTombs()

    @Query("SELECT id FROM recipes")
    suspend fun recipeIds(): List<Long>

    @Query("SELECT id FROM plan_entries")
    suspend fun planIds(): List<Long>

    @Query("SELECT id FROM plan_notes")
    suspend fun noteIds(): List<Long>

    @Query("SELECT weekStart || '|' || itemKey FROM list_checks")
    suspend fun checkKeys(): List<String>

    @Query("SELECT id FROM list_extras")
    suspend fun extraIds(): List<Long>

    @Query("SELECT nameKey FROM ingredients WHERE aisle != '' OR stapleChoice >= 0 OR aliasOf != ''")
    suspend fun ingredientKeys(): List<String>
}

@Database(
    entities = [
        IngredientEntity::class, RecipeEntity::class, StepEntity::class, SlotEntity::class,
        OptionEntity::class, RecipeIngredientEntity::class, DraftEntity::class,
        PlanEntryEntity::class, ListCheckEntity::class, ListExtraEntity::class,
        SyncRowEntity::class, SyncDeleteEntity::class, PlanNoteEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MiseDb : RoomDatabase() {
    abstract fun recipes(): RecipeDao
    abstract fun sync(): SyncDao

    companion object {
        @Volatile private var instance: MiseDb? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, " +
                        "`meal` TEXT NOT NULL, `recipeId` INTEGER NOT NULL, `servings` INTEGER NOT NULL, `choices` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, FOREIGN KEY(`recipeId`) REFERENCES `recipes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_entries_recipeId` ON `plan_entries` (`recipeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_entries_date` ON `plan_entries` (`date`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `list_checks` (`weekStart` TEXT NOT NULL, `itemKey` TEXT NOT NULL, " +
                        "`checkedAt` INTEGER NOT NULL, PRIMARY KEY(`weekStart`, `itemKey`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `list_extras` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `weekStart` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `qty` TEXT NOT NULL, `checked` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_list_extras_weekStart` ON `list_extras` (`weekStart`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_rows` (`kind` TEXT NOT NULL, `localKey` TEXT NOT NULL, `uid` TEXT NOT NULL, " +
                        "`dirty` INTEGER NOT NULL, PRIMARY KEY(`kind`, `localKey`))",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_rows_kind_uid` ON `sync_rows` (`kind`, `uid`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_deletes` (`kind` TEXT NOT NULL, `uid` TEXT NOT NULL, PRIMARY KEY(`kind`, `uid`))")
            }
        }


        /**
         * M3 to v0.6: dish type and review status on recipes, leftovers on plan entries, plan
         * notes, tick amounts and who ticked, aisles on extras, staple choice and aliases on
         * ingredients, last-known change time for sync. "To check" moves from tags to status.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ingredients` ADD COLUMN `stapleChoice` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE `ingredients` ADD COLUMN `aliasOf` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `ingredients` SET `stapleChoice` = 1 WHERE `isStaple` = 1")
                db.execSQL("ALTER TABLE `recipes` ADD COLUMN `dishType` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `recipes` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ok'")
                db.execSQL("UPDATE `recipes` SET `status` = 'to_check' WHERE `tags` LIKE '%To check%'")
                db.execSQL(
                    "UPDATE `recipes` SET `tags` = trim(replace(replace(replace(replace(`tags`, ', To check', ''), 'To check', ''), " +
                        "', From Folio', ''), 'From Folio', ''), ', ')",
                )
                db.execSQL("ALTER TABLE `plan_entries` ADD COLUMN `leftovers` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `list_checks` ADD COLUMN `amount` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `list_checks` ADD COLUMN `checkedBy` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `list_extras` ADD COLUMN `aisle` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `list_extras` ADD COLUMN `checkedBy` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `sync_rows` ADD COLUMN `remoteAt` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, " +
                        "`meal` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_notes_date` ON `plan_notes` (`date`)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /**
         * Records a local change so it is shared with the household on the next sync. Call it in
         * the same transaction as the change itself.
         */
        suspend fun mark(db: MiseDb, kind: String, key: String) {
            val d = db.sync()
            val r = d.row(kind, key)
            if (r == null) {
                val uid = when (kind) {
                    // Ticks and ingredient settings use the same id on every phone, so two
                    // phones changing the same item meet in one shared row.
                    "check", "ingredient" -> key
                    else -> java.util.UUID.randomUUID().toString()
                }
                d.put(SyncRowEntity(kind, key, uid, 1))
            } else {
                d.bump(kind, key)
            }
            app.mise.sync.SyncEngine.poke()
        }

        /** Records a local delete of a shared item. */
        suspend fun markDeleted(db: MiseDb, kind: String, key: String) {
            val d = db.sync()
            val r = d.row(kind, key) ?: return
            d.tomb(SyncDeleteEntity(kind, r.uid))
            d.remove(kind, key)
            app.mise.sync.SyncEngine.poke()
        }

        fun get(context: Context): MiseDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MiseDb::class.java, "mise.db")
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}
