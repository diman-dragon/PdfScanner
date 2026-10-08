package com.example.pdfscanner.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
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

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val pageCount: Int,
    val sizeBytes: Long,
    val pdfPath: String,
    val thumbPath: String?,
    /** Папка с исходными страницами: по ним можно заново применить другой фильтр. */
    val origDir: String? = null,
    /** В PDF есть распознанный текстовый слой. */
    val hasText: Boolean = false,
    val filterMode: String? = null,
    /** Распознанный текст, для поиска по документам. */
    val ocrText: String? = null,
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: String): Flow<DocumentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: DocumentEntity)

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("UPDATE documents SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Delete
    suspend fun delete(doc: DocumentEntity)
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN origDir TEXT")
        db.execSQL("ALTER TABLE documents ADD COLUMN hasText INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE documents ADD COLUMN filterMode TEXT")
        db.execSQL("ALTER TABLE documents ADD COLUMN ocrText TEXT")
    }
}

@Database(entities = [DocumentEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "scanner.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
