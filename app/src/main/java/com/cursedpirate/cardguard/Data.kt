package com.cursedpirate.cardguard

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "cards", indices = [Index("last4", unique = true)])
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bank: String,
    val name: String,
    val last4: String,
    val limit: Double,
    val outstanding: Double = 0.0,
    val dueDay: Int = 0, // day of month, 0 = unset
)

@Entity(tableName = "txns", indices = [Index("hash", unique = true), Index("cardId")])
data class TxnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val amount: Double,
    val merchant: String?,
    val ts: Long,
    val type: TxType,
    val hash: String, // dedupes duplicate SMS
)

@Dao
interface CardDao {
    @Query("SELECT * FROM cards ORDER BY bank") fun cards(): Flow<List<CardEntity>>
    @Query("SELECT * FROM cards") suspend fun allCards(): List<CardEntity>
    @Query("SELECT * FROM cards WHERE last4 = :l4") suspend fun byLast4(l4: String): CardEntity?
    @Query("SELECT * FROM txns ORDER BY ts DESC LIMIT 50") fun recentTxns(): Flow<List<TxnEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: CardEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTxn(t: TxnEntity): Long
    @Delete suspend fun delete(c: CardEntity)
    @Query("DELETE FROM txns WHERE cardId = :id") suspend fun deleteTxns(id: Long)
}

@Database(entities = [CardEntity::class, TxnEntity::class], version = 1, exportSchema = false)
@TypeConverters(Conv::class)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): CardDao

    companion object {
        @Volatile private var inst: AppDb? = null
        // ponytail: plain Room; swap in SQLCipher (SupportFactory) for at-rest encryption per spec.
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "cardguard.db").build().also { inst = it }
        }
    }
}

class Conv {
    @TypeConverter fun toType(s: String) = TxType.valueOf(s)
    @TypeConverter fun fromType(t: TxType) = t.name
}

private val ingestLock = kotlinx.coroutines.sync.Mutex()

/** Apply one SMS to the DB. Returns true if a new transaction was recorded. */
suspend fun CardDao.ingest(body: String, ts: Long): Boolean = ingestLock.withLock {
    val p = SmsParser.parse(body) ?: return@withLock false
    val card = byLast4(p.last4) ?: CardEntity(bank = p.bank ?: "Card", name = "Credit Card", last4 = p.last4, limit = 0.0)
        .let { it.copy(id = upsert(it)) }
    val hash = "${p.last4}|${p.amount}|${ts / 60000}|${p.type}" // same-minute duplicates collapse
    val ok = insertTxn(TxnEntity(cardId = card.id, amount = p.amount, merchant = p.merchant, ts = ts, type = p.type, hash = hash)) != -1L
    if (ok) {
        val delta = if (p.type == TxType.DEBIT) p.amount else -p.amount
        upsert(card.copy(outstanding = (card.outstanding + delta).coerceAtLeast(0.0)))
    }
    ok
}
