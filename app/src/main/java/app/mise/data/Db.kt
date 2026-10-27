package app.mise.data

import android.content.Context
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

/** Shared ingredient catalogue, so "Onion" in five recipes is one ingredient (merged in M2). */
@Entity(tableName = "ingredients", indices = [Index(value = ["nameKey"], unique = true)])
data class IngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameKey: String,
    val aisle: String = "",
    val isStaple: Boolean = false,
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

/** A dish on a day and meal, with its own servings and chosen swap options (indices, comma list). */
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
)

/** A ticked grocery line for a week. A row exists only while the line is ticked. */
@Entity(tableName = "list_checks", primaryKeys = ["weekStart", "itemKey"])
data class ListCheckEntity(
    val weekStart: String,
    val itemKey: String,
    val checkedAt: Long,
)

/** A one-off item typed onto a week's list (milk, tissues). */
@Entity(tableName = "list_extras", indices = [Index("weekStart")])
data class ListExtraEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: String,
    val name: String,
    val qty: String,
    val checked: Boolean,
    val createdAt: Long,
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
    val baseServings: Int,
    val slotCount: Int,
    val ingredientCount: Int,
)

@Dao
interface RecipeDao {
    @Query(
        "SELECT r.id, r.title, r.cuisine, r.baseServings, " +
            "(SELECT COUNT(*) FROM variant_slots s WHERE s.recipeId = r.id) AS slotCount, " +
            "(SELECT COUNT(*) FROM recipe_ingredients i WHERE i.recipeId = r.id AND i.optionId IS NULL) AS ingredientCount " +
            "FROM recipes r ORDER BY r.title COLLATE NOCASE",
    )
    fun observeSummaries(): Flow<List<RecipeSummaryRow>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun observeRecipe(id: Long): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun recipe(id: Long): RecipeEntity?

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

    @Query("SELECT * FROM ingredients WHERE nameKey = :key")
    suspend fun ingredientByKey(key: String): IngredientEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIngredient(i: IngredientEntity): Long

    @Query("SELECT name FROM ingredients ORDER BY name COLLATE NOCASE")
    fun observeIngredientNames(): Flow<List<String>>

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

    // M2: plan and list

    @Query("SELECT p.* FROM plan_entries p JOIN recipes r ON r.id = p.recipeId WHERE p.date >= :from AND p.date <= :to ORDER BY p.date, p.createdAt")
    fun observePlan(from: String, to: String): Flow<List<PlanEntryEntity>>

    @Query("SELECT * FROM plan_entries WHERE id = :id")
    suspend fun planEntry(id: Long): PlanEntryEntity?

    @Insert
    suspend fun insertPlan(e: PlanEntryEntity): Long

    @Update
    suspend fun updatePlan(e: PlanEntryEntity)

    @Query("DELETE FROM plan_entries WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Query("SELECT itemKey FROM list_checks WHERE weekStart = :week")
    fun observeChecks(week: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun check(c: ListCheckEntity)

    @Query("DELETE FROM list_checks WHERE weekStart = :week AND itemKey = :key")
    suspend fun uncheck(week: String, key: String)

    @Query("SELECT * FROM list_extras WHERE weekStart = :week ORDER BY createdAt")
    fun observeExtras(week: String): Flow<List<ListExtraEntity>>

    @Insert
    suspend fun insertExtra(e: ListExtraEntity): Long

    @Query("UPDATE list_extras SET checked = :checked WHERE id = :id")
    suspend fun setExtraChecked(id: Long, checked: Boolean)

    @Query("DELETE FROM list_extras WHERE id = :id")
    suspend fun deleteExtra(id: Long)

    @Query("SELECT * FROM ingredients")
    fun observeIngredients(): Flow<List<IngredientEntity>>

    @Query("UPDATE ingredients SET aisle = :aisle WHERE nameKey = :key")
    suspend fun setAisle(key: String, aisle: String)

    @Query("UPDATE ingredients SET isStaple = :staple WHERE nameKey = :key")
    suspend fun setStaple(key: String, staple: Boolean)
}

@Database(
    entities = [
        IngredientEntity::class, RecipeEntity::class, StepEntity::class, SlotEntity::class,
        OptionEntity::class, RecipeIngredientEntity::class, DraftEntity::class,
        PlanEntryEntity::class, ListCheckEntity::class, ListExtraEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class MiseDb : RoomDatabase() {
    abstract fun recipes(): RecipeDao

    companion object {
        @Volatile private var instance: MiseDb? = null

        /** M1 to M2: adds the plan and the list. Recipes are untouched. */
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

        fun get(context: Context): MiseDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MiseDb::class.java, "mise.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
