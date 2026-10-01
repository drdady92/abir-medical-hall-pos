package com.abir.medicalhall

// ============================================================
//  ABIR MEDICAL HALL — POS v2
//  Barcode scanning, Retail/Wholesale, live profit banner,
//  sell by box, drafts, AI invoice (OCR) purchase entry.
// ============================================================

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------- SETTINGS ----------------
const val SHOP_NAME = "Abir Medical Hall"
const val CURRENCY = "৳"
const val DEFAULT_MARKUP = 1.15

// ---------------- HELPERS ----------------
fun money(v: Double) = CURRENCY + String.format(Locale.US, "%,.2f", v)
fun fdate(ms: Long) = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(ms))
fun fdatetime(ms: Long) = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(ms))
fun parseDate(s: String): Long? = try {
    SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { isLenient = false }.parse(s)?.time
} catch (_: Exception) { null }
fun toD(s: String) = s.toDoubleOrNull() ?: 0.0
fun defaultExpiryString(): String {
    val c = Calendar.getInstance(); c.add(Calendar.YEAR, 2)
    return SimpleDateFormat("dd-MM-yyyy", Locale.US).format(c.time)
}

// ---------------- DATABASE ENTITIES ----------------
@Entity(tableName = "medicines")
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val generic: String = "",
    val company: String = "",
    val barcode: String = "",
    val unit: String = "Pcs",
    val piecesPerBox: Int = 1,
    val purchasePrice: Double = 0.0,
    val sellingPrice: Double = 0.0,
    val wholesalePrice: Double = 0.0,
    val minStock: Int = 10
)

@Entity(
    tableName = "batches",
    foreignKeys = [ForeignKey(
        entity = Medicine::class, parentColumns = ["id"],
        childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE
    )]
)
data class Batch(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val medicineId: Int,
    val batchNo: String = "",
    val expiryDate: Long,
    val quantity: Int,
    val purchasePrice: Double = 0.0
)

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val due: Double = 0.0
)

@Entity(tableName = "sales")
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val invoiceNo: String,
    val customerId: Int? = null,
    val dateTime: Long = System.currentTimeMillis(),
    val subtotal: Double,
    val discount: Double = 0.0,
    val total: Double,
    val paid: Double,
    val due: Double = 0.0,
    val paymentMethod: String = "Cash",
    val mode: String = "Retail"
)

@Entity(
    tableName = "sale_items",
    foreignKeys = [ForeignKey(
        entity = Sale::class, parentColumns = ["id"],
        childColumns = ["saleId"], onDelete = ForeignKey.CASCADE
    )]
)
data class SaleItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val saleId: Int,
    val medicineId: Int,
    val medicineName: String,
    val quantity: Int,
    val unitPrice: Double,
    val purchasePrice: Double,
    val total: Double
)

@Entity(tableName = "drafts")
data class Draft(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val customerId: Int? = null,
    val mode: String = "Retail",
    val itemsJson: String = "",
    val dateTime: Long = System.currentTimeMillis()
)

// ---------------- QUERY RESULT CLASSES ----------------
data class MedicineWithStock(@Embedded val medicine: Medicine, val stock: Int)
data class BatchWithMedicine(@Embedded val batch: Batch, val medicineName: String)
data class ProductSales(val medicineName: String, val qty: Int, val revenue: Double)
data class CartItem(val medicine: Medicine, val qty: Int, val unitPrice: Double, val isBox: Boolean = false)

// ---------------- AI INVOICE PARSING ----------------
data class ParsedItem(val name: String, val qty: Int, val rate: Double)

private val JUNK_STARTS = listOf(
    "subtotal", "total", "net", "grand", "invoice", "date", "vat", "discount",
    "page", "amount", "payable", "phone", "address", "supplier", "purchase", "sl "
)

fun parseInvoiceText(text: String): Triple<String, String, List<ParsedItem>> {
    var supplier = ""; var invoiceNo = ""
    val map = LinkedHashMap<String, ParsedItem>()
    for (raw in text.lineSequence()) {
        val line = raw.trim().replace('\t', ' ')
        if (line.length < 4) continue
        val up = line.uppercase(Locale.US)
        if (supplier.isBlank() && (up.contains("PHARMA") || up.contains("LABORATOR") ||
                up.contains(" LIMITED") || up.contains(" LTD") || up.contains(" PLC"))) {
            supplier = line; continue
        }
        if (invoiceNo.isBlank() && up.contains("INVOICE")) {
            Regex("\\d{3,}").find(line)?.let { invoiceNo = it.groupValues[0] }
            continue
        }
        if (JUNK_STARTS.any { up.startsWith(it) }) continue
        val item = parseItemLine(line) ?: continue
        val key = item.name.lowercase(Locale.US)
        val old = map[key]
        map[key] = if (old == null) item else old.copy(qty = old.qty + item.qty)
    }
    return Triple(supplier, invoiceNo, map.values.toList())
}

private fun parseItemLine(line: String): ParsedItem? {
    val cleaned = line.replace('|', ' ').replace(':', ' ').replace('*', ' ')
    val tokens = cleaned.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.size < 2) return null
    val nameParts = StringBuilder()
    val nums = mutableListOf<Double>()
    var numsStarted = false
    for (t in tokens) {
        val n = asNumber(t)
        if (!numsStarted && n == null && !isFieldWord(t)) {
            if (nameParts.isNotEmpty()) nameParts.append(' ')
            nameParts.append(t.trim('\'', '"', ',', '.'))
        } else {
            numsStarted = true
            n?.let { nums.add(it) }
        }
    }
    val name = nameParts.toString().trim()
    if (name.length < 3 || !name.any { it.isLetter() } || nums.isEmpty()) return null
    val rate: Double; val qty: Int
    if (nums.size >= 3) { rate = nums[nums.size - 2]; qty = lastWhole(nums.subList(0, nums.size - 2)) ?: 1 }
    else if (nums.size == 2) { rate = nums[1]; qty = lastWhole(listOf(nums[0])) ?: 1 }
    else { rate = nums[0]; qty = 1 }
    if (rate <= 0 || rate > 1_000_000) return null
    return ParsedItem(name, qty.coerceIn(1, 100000), rate)
}

private fun isFieldWord(t: String): Boolean {
    val up = t.uppercase(Locale.US)
    return up.startsWith("QTY") || up.startsWith("PACK") || up.startsWith("RATE") ||
            up.startsWith("VAT") || up.startsWith("ITEM") || up.startsWith("SL")
}

private fun asNumber(t: String): Double? {
    if (!t.any { it.isDigit() }) return null
    val letters = t.count { it.isLetter() }
    if (letters > 1) return null
    if (letters == 1 && !(t.contains('b') || t.contains('B') || t.contains('৳') || t.contains('$'))) return null
    val cleaned = t.filter { it.isDigit() || it == '.' }
    return if (cleaned.isEmpty() || cleaned == ".") null else cleaned.toDoubleOrNull()
}

private fun lastWhole(list: List<Double>): Int? =
    list.lastOrNull { it == Math.floor(it) && it in 1.0..99999.0 }?.toInt()

fun runOcr(ctx: Context, uri: Uri, onText: (String) -> Unit) {
    try {
        val image = InputImage.fromFilePath(ctx, uri)
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(image)
            .addOnSuccessListener { onText(it.text) }
            .addOnFailureListener { onText("") }
    } catch (_: Exception) { onText("") }
}

private fun decodeSampled(ctx: Context, uri: Uri, req: Int = 1024): Bitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > req) s *= 2
    BitmapFactory.Options().apply { inSampleSize = s }.let { op ->
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, op) }
    }
} catch (_: Exception) { null }

// ---------------- DAOs ----------------
@Dao
interface MedicineDao {
    @Insert suspend fun insert(m: Medicine): Long
    @Update suspend fun update(m: Medicine)
    @Query("DELETE FROM medicines WHERE id = :id") suspend fun delete(id: Int)

    @Query("SELECT * FROM medicines WHERE barcode = :code LIMIT 1")
    suspend fun byBarcode(code: String): Medicine?
    @Query("SELECT * FROM medicines WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun byNameExact(name: String): Medicine?
    @Query("SELECT * FROM medicines") suspend fun allList(): List<Medicine>
    @Query("SELECT COUNT(*) FROM medicines") fun count(): Flow<Long>

    @Query(
        """SELECT m.*, COALESCE((SELECT SUM(b.quantity) FROM batches b WHERE b.medicineId = m.id), 0) AS stock
           FROM medicines m ORDER BY m.name"""
    )
    fun allWithStock(): Flow<List<MedicineWithStock>>

    @Query(
        """SELECT m.*, COALESCE((SELECT SUM(b.quantity) FROM batches b WHERE b.medicineId = m.id), 0) AS stock
           FROM medicines m
           WHERE m.name LIKE '%' || :q || '%' OR m.generic LIKE '%' || :q || '%' OR m.barcode = :q
           ORDER BY m.name"""
    )
    fun searchWithStock(q: String): Flow<List<MedicineWithStock>>

    @Query(
        """SELECT m.*, COALESCE((SELECT SUM(b.quantity) FROM batches b WHERE b.medicineId = m.id), 0) AS stock
           FROM medicines m
           WHERE COALESCE((SELECT SUM(b.quantity) FROM batches b WHERE b.medicineId = m.id), 0) <= m.minStock"""
    )
    fun lowStock(): Flow<List<MedicineWithStock>>
}

@Dao
interface BatchDao {
    @Insert suspend fun insert(b: Batch): Long
    @Update suspend fun update(b: Batch)
    @Query("DELETE FROM batches WHERE id = :id") suspend fun delete(id: Int)

    @Query("SELECT * FROM batches WHERE medicineId = :medicineId AND quantity > 0 ORDER BY expiryDate")
    suspend fun availableFor(medicineId: Int): List<Batch>

    @Query("SELECT * FROM batches WHERE medicineId = :medicineId ORDER BY expiryDate")
    fun forMedicine(medicineId: Int): Flow<List<Batch>>

    @Query(
        """SELECT b.*, m.name AS medicineName FROM batches b
           JOIN medicines m ON b.medicineId = m.id
           WHERE b.quantity > 0 AND b.expiryDate <= :until ORDER BY b.expiryDate"""
    )
    fun expiringUntil(until: Long): Flow<List<BatchWithMedicine>>

    @Query(
        """SELECT b.*, m.name AS medicineName FROM batches b
           JOIN medicines m ON b.medicineId = m.id
           WHERE b.quantity > 0 ORDER BY b.expiryDate"""
    )
    fun allWithMedicine(): Flow<List<BatchWithMedicine>>
}

@Dao
interface CustomerDao {
    @Insert suspend fun insert(c: Customer): Long
    @Update suspend fun update(c: Customer)
    @Query("SELECT * FROM customers ORDER BY name") fun all(): Flow<List<Customer>>
    @Query("SELECT * FROM customers WHERE id = :id") suspend fun byId(id: Int): Customer?
    @Query("UPDATE customers SET due = due - :amount WHERE id = :id") suspend fun payDue(id: Int, amount: Double)
}

@Dao
interface SaleDao {
    @Insert suspend fun insert(s: Sale): Long
    @Insert suspend fun insertItems(items: List<SaleItem>)

    @Query("SELECT * FROM sales WHERE dateTime BETWEEN :from AND :to ORDER BY dateTime DESC")
    fun between(from: Long, to: Long): Flow<List<Sale>>

    @Query("SELECT * FROM sales WHERE customerId = :customerId ORDER BY dateTime DESC")
    fun forCustomer(customerId: Int): Flow<List<Sale>>

    @Query("SELECT COALESCE(SUM(total),0) FROM sales WHERE dateTime BETWEEN :from AND :to")
    fun totalBetween(from: Long, to: Long): Flow<Double>

    @Query("SELECT COUNT(*) FROM sales WHERE dateTime BETWEEN :from AND :to")
    fun countBetween(from: Long, to: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(due),0) FROM sales WHERE dateTime BETWEEN :from AND :to")
    fun dueBetween(from: Long, to: Long): Flow<Double>

    @Query(
        """SELECT COALESCE(SUM((si.unitPrice - si.purchasePrice) * si.quantity), 0)
           FROM sale_items si JOIN sales s ON si.saleId = s.id
           WHERE s.dateTime BETWEEN :from AND :to"""
    )
    fun profitBetween(from: Long, to: Long): Flow<Double>

    @Query(
        """SELECT si.medicineName AS medicineName, SUM(si.quantity) AS qty, SUM(si.total) AS revenue
           FROM sale_items si JOIN sales s ON si.saleId = s.id
           WHERE s.dateTime BETWEEN :from AND :to
           GROUP BY si.medicineName ORDER BY revenue DESC LIMIT 10"""
    )
    fun topProducts(from: Long, to: Long): Flow<List<ProductSales>>
}

@Dao
interface DraftDao {
    @Insert suspend fun insert(d: Draft): Long
    @Query("SELECT * FROM drafts ORDER BY dateTime DESC") fun all(): Flow<List<Draft>>
    @Query("DELETE FROM drafts WHERE id = :id") suspend fun delete(id: Int)
}

// ---------------- DATABASE ----------------
@Database(
    entities = [Medicine::class, Batch::class, Customer::class, Sale::class, SaleItem::class, Draft::class],
    version = 2, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicines(): MedicineDao
    abstract fun batches(): BatchDao
    abstract fun customers(): CustomerDao
    abstract fun sales(): SaleDao
    abstract fun drafts(): DraftDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medicines ADD COLUMN barcode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE medicines ADD COLUMN piecesPerBox INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE medicines ADD COLUMN wholesalePrice REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sales ADD COLUMN mode TEXT NOT NULL DEFAULT 'Retail'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS drafts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "customerId INTEGER, mode TEXT NOT NULL, itemsJson TEXT NOT NULL, dateTime INTEGER NOT NULL)"
                )
            }
        }
        fun get(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context, AppDatabase::class.java, "abir_medical.db")
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}

// ---------------- VIEW MODEL ----------------
@OptIn(ExperimentalCoroutinesApi::class)
class ShopViewModel(private val db: AppDatabase) : ViewModel() {

    fun startOfDay(offsetDays: Int = 0): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offsetDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun monthStart(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val medicines = db.medicines().allWithStock()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lowStock = db.medicines().lowStock()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val customers = db.customers().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val expiringSoon = db.batches().expiringUntil(System.currentTimeMillis() + 60L * 24 * 60 * 60 * 1000)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allBatches = db.batches().allWithMedicine()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val todaySales = db.sales().totalBetween(startOfDay(0), startOfDay(1))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val monthSales = db.sales().totalBetween(monthStart(), startOfDay(1))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val drafts = db.drafts().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val productCount = db.medicines().count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _query = MutableStateFlow("")
    fun search(q: String) { _query.value = q }
    val searchResults = _query.flatMapLatest { q ->
        if (q.isBlank()) flowOf(emptyList()) else db.medicines().searchWithStock(q.trim())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val posMode = MutableStateFlow("Retail")
    fun setMode(m: String) { posMode.value = m }
    var draftCustomerId: Int? = null
    private var loadedDraftId: Int? = null

    private val _cart = MutableStateFlow<List<CartItem>>(emptyList())
    val cart = _cart.asStateFlow()

    fun addToCart(m: Medicine, qty: Int, unitPrice: Double, isBox: Boolean) {
        if (m.id == 0 || qty <= 0) return
        _cart.update { list ->
            val i = list.indexOfFirst { it.medicine.id == m.id && it.unitPrice == unitPrice }
            if (i >= 0) list.toMutableList().apply { this[i] = this[i].copy(qty = this[i].qty + qty) }
            else list + CartItem(m, qty, unitPrice, isBox)
        }
    }

    fun removeLine(item: CartItem) { _cart.update { list -> list.filter { it != item } } }
    fun clearCart() { _cart.value = emptyList() }

    fun lookupBarcode(code: String, cb: (Medicine?) -> Unit) =
        viewModelScope.launch { cb(db.medicines().byBarcode(code.trim())) }

    fun saveDraft() {
        val items = _cart.value
        if (items.isEmpty()) return
        viewModelScope.launch {
            val json = items.joinToString(";") {
                "${it.medicine.id},${it.qty},${it.unitPrice},${if (it.isBox) 1 else 0}"
            }
            db.drafts().insert(Draft(customerId = draftCustomerId, mode = posMode.value, itemsJson = json))
            _cart.value = emptyList()
            loadedDraftId = null
        }
    }

    fun deleteDraft(id: Int) = viewModelScope.launch { db.drafts().delete(id) }

    fun loadDraft(d: Draft, onLoaded: () -> Unit) = viewModelScope.launch {
        val map = db.medicines().allList().associateBy { it.id }
        val items = d.itemsJson.split(";").mapNotNull { s ->
            val p = s.split(",")
            val m = p.getOrNull(0)?.toIntOrNull()?.let { map[it] } ?: return@mapNotNull null
            val qty = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val price = p.getOrNull(2)?.toDoubleOrNull() ?: return@mapNotNull null
            CartItem(m, qty, price, p.getOrNull(3) == "1")
        }
        if (items.isEmpty()) { db.drafts().delete(d.id); return@launch }
        _cart.value = items
        posMode.value = d.mode
        draftCustomerId = d.customerId
        loadedDraftId = d.id
        onLoaded()
    }

    fun checkout(customerId: Int?, discount: Double, paid: Double, method: String,
                 onDone: (Sale, List<CartItem>) -> Unit) {
        val items = _cart.value
        if (items.isEmpty()) return
        val mode = posMode.value
        viewModelScope.launch {
            val sale = db.withTransaction {
                val subtotal = items.sumOf { it.unitPrice * it.qty }
                val total = (subtotal - discount).coerceAtLeast(0.0)
                val invoiceNo = "INV-" + SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date())
                val s = Sale(
                    invoiceNo = invoiceNo, customerId = customerId, subtotal = subtotal,
                    discount = discount, total = total, paid = paid,
                    due = (total - paid).coerceAtLeast(0.0), paymentMethod = method, mode = mode
                )
                val saleId = db.sales().insert(s).toInt()
                db.sales().insertItems(items.map { ci ->
                    SaleItem(
                        saleId = saleId, medicineId = ci.medicine.id,
                        medicineName = ci.medicine.name, quantity = ci.qty,
                        unitPrice = ci.unitPrice, purchasePrice = ci.medicine.purchasePrice,
                        total = ci.unitPrice * ci.qty
                    )
                })
                items.forEach { ci ->
                    var remaining = ci.qty
                    for (b in db.batches().availableFor(ci.medicine.id)) {
                        if (remaining <= 0) break
                        val take = minOf(remaining, b.quantity)
                        if (take > 0) {
                            db.batches().update(b.copy(quantity = b.quantity - take))
                            remaining -= take
                        }
                    }
                }
                if (customerId != null && s.due > 0) {
                    db.customers().byId(customerId)?.let { c ->
                        db.customers().update(c.copy(due = c.due + s.due))
                    }
                }
                s
            }
            loadedDraftId?.let { db.drafts().delete(it); loadedDraftId = null }
            _cart.value = emptyList()
            onDone(sale, items)
        }
    }

    fun savePurchase(items: List<ParsedItem>, supplier: String, expiry: String, onDone: (Int) -> Unit) =
        viewModelScope.launch {
            val exp = parseDate(expiry.trim()) ?: System.currentTimeMillis() + 730L * 86400000
            val n = db.withTransaction {
                var added = 0
                for (pi in items) {
                    val medId = db.medicines().byNameExact(pi.name)?.id ?: db.medicines().insert(
                        Medicine(
                            name = pi.name, company = supplier.substringBefore(" ").ifBlank { "" },
                            purchasePrice = pi.rate,
                            sellingPrice = Math.round(pi.rate * DEFAULT_MARKUP * 100.0) / 100.0
                        )
                    ).toInt()
                    db.batches().insert(
                        Batch(medicineId = medId, expiryDate = exp, quantity = pi.qty, purchasePrice = pi.rate)
                    )
                    added++
                }
                added
            }
            onDone(n)
        }

    fun addMedicine(m: Medicine) = viewModelScope.launch { db.medicines().insert(m) }
    fun updateMedicine(m: Medicine) = viewModelScope.launch { db.medicines().update(m) }
    fun deleteMedicine(id: Int) = viewModelScope.launch { db.medicines().delete(id) }
    fun addBatch(b: Batch) = viewModelScope.launch { db.batches().insert(b) }
    fun discardBatch(id: Int) = viewModelScope.launch { db.batches().delete(id) }
    fun addCustomer(c: Customer) = viewModelScope.launch { db.customers().insert(c) }
    fun payDue(id: Int, amount: Double) = viewModelScope.launch { db.customers().payDue(id, amount) }
    fun batchesOf(medicineId: Int): Flow<List<Batch>> = db.batches().forMedicine(medicineId)

    fun salesBetween(f: Long, t: Long) = db.sales().between(f, t)
    fun salesTotal(f: Long, t: Long) = db.sales().totalBetween(f, t)
    fun salesCount(f: Long, t: Long) = db.sales().countBetween(f, t)
    fun profit(f: Long, t: Long) = db.sales().profitBetween(f, t)
    fun dues(f: Long, t: Long) = db.sales().dueBetween(f, t)
    fun topProducts(f: Long, t: Long) = db.sales().topProducts(f, t)
    fun salesOf(customerId: Int) = db.sales().forCustomer(customerId)
}

class ShopVmFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ShopViewModel(db) as T
}

// ---------------- ACTIVITY ----------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1B5E20),
                    secondary = Color(0xFF2E7D32)
                )
            ) { AbirApp(db) }
        }
    }
}

// ---------------- NAVIGATION ----------------
@Composable
fun AbirApp(db: AppDatabase) {
    val vm: ShopViewModel = viewModel(factory = ShopVmFactory(db))
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    data class Tab(val route: String, val icon: ImageVector, val label: String)
    val tabs = listOf(
        Tab("home", Icons.Filled.Dashboard, "Home"),
        Tab("pos", Icons.Filled.PointOfSale, "Sale"),
        Tab("stock", Icons.Filled.Medication, "Stock"),
        Tab("expiry", Icons.Filled.EventBusy, "Expiry"),
        Tab("reports", Icons.Filled.BarChart, "Reports")
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(vm, nav) }
            composable("pos") { PosScreen(vm) }
            composable("stock") { StockScreen(vm) }
            composable("expiry") { ExpiryScreen(vm) }
            composable("reports") { ReportsScreen(vm) }
            composable("customers") { CustomersScreen(vm) }
            composable("drafts") { DraftsScreen(vm) { nav.navigate("pos") } }
            composable("ai") { AiPurchaseScreen(vm) }
        }
    }
}

// ---------------- SMALL UI PIECES ----------------
@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier, danger: Boolean = false) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (danger) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ActionTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun CountBadge(n: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Text(
            "$n", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun Keypad(onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    val keys = listOf("7", "8", "9", "4", "5", "6", "1", "2", "3", "C", "0", ".")
    Column(modifier) {
        keys.chunked(3).forEach { rowKeys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowKeys.forEach { k ->
                    OutlinedButton(
                        onClick = { onKey(k) },
                        modifier = Modifier.weight(1f).height(46.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(k, fontSize = 18.sp,
                            color = if (k == "C") MaterialTheme.colorScheme.error else LocalContentColor.current)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ---------------- DASHBOARD ----------------
@Composable
fun HomeScreen(vm: ShopViewModel, nav: NavHostController) {
    val today by vm.todaySales.collectAsState()
    val month by vm.monthSales.collectAsState()
    val expiring by vm.expiringSoon.collectAsState()
    val low by vm.lowStock.collectAsState()
    val draftsList by vm.drafts.collectAsState()
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(SHOP_NAME, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Dashboard", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            StatCard("Today's Sales", money(today), Modifier.weight(1f))
            StatCard("This Month", money(month), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            StatCard("Expiring ≤60 days", expiring.size.toString(), Modifier.weight(1f), expiring.isNotEmpty())
            StatCard("Low Stock Items", low.size.toString(), Modifier.weight(1f), low.isNotEmpty())
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            ActionTile("New Sale", Icons.Filled.PointOfSale, Modifier.weight(1f)) { nav.navigate("pos") }
            ActionTile("Customers", Icons.Filled.People, Modifier.weight(1f)) { nav.navigate("customers") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            ActionTile("Manage Stock", Icons.Filled.Medication, Modifier.weight(1f)) { nav.navigate("stock") }
            ActionTile("Reports", Icons.Filled.BarChart, Modifier.weight(1f)) { nav.navigate("reports") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            ActionTile("AI Purchase", Icons.Filled.DocumentScanner, Modifier.weight(1f)) { nav.navigate("ai") }
            ActionTile("Drafts (${draftsList.size})", Icons.Filled.PendingActions, Modifier.weight(1f)) { nav.navigate("drafts") }
        }

        if (expiring.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("⚠ Expiring Soon", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            expiring.take(5).forEach { b ->
                Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.medicineName, fontWeight = FontWeight.SemiBold)
                            Text("Batch ${b.batch.batchNo.ifBlank { "-" }} • ${fdate(b.batch.expiryDate)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Qty ${b.batch.quantity}", fontWeight = FontWeight.SemiBold)
                            val days = (b.batch.expiryDate - now) / 86400000L
                            Text(if (days < 0) "EXPIRED" else "$days days left",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        if (low.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("⚠ Low Stock", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            low.take(5).forEach { m ->
                Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Row(Modifier.padding(10.dp)) {
                        Text(m.medicine.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(if (m.stock <= 0) "OUT OF STOCK" else "Only ${m.stock} ${m.medicine.unit} left",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- POS (NEW SALE) ----------------
@Composable
fun PosScreen(vm: ShopViewModel) {
    val cart by vm.cart.collectAsState()
    val results by vm.searchResults.collectAsState()
    val customers by vm.customers.collectAsState()
    val medsAll by vm.medicines.collectAsState()
    val mode by vm.posMode.collectAsState()
    val productCount by vm.productCount.collectAsState()
    val ctx = LocalContext.current

    var query by remember { mutableStateOf("") }
    var qtyFor by remember { mutableStateOf<Medicine?>(null) }
    var scanner by remember { mutableStateOf(false) }
    var pickCustomer by remember { mutableStateOf(false) }
    var payNow by remember { mutableStateOf(false) }
    var unknownCode by remember { mutableStateOf<String?>(null) }
    var addNew by remember { mutableStateOf(false) }
    var addPrefill by remember { mutableStateOf("") }
    var receipt by remember { mutableStateOf<Pair<Sale, List<CartItem>>?>(null) }

    LaunchedEffect(query) { vm.search(query) }
    val subtotal = cart.sumOf { it.unitPrice * it.qty }
    val profit = cart.sumOf { (it.unitPrice - it.medicine.purchasePrice) * it.qty }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CountBadge(cart.sumOf { it.qty })
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { pickCustomer = true }) {
                    Text(
                        vm.draftCustomerId?.let { id -> customers.firstOrNull { it.id == id }?.name }
                            ?: "Set party name", maxLines = 1
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(fdate(System.currentTimeMillis()), style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = mode == "Retail", onClick = { vm.setMode("Retail") }, label = { Text("Retail") })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = mode == "Wholesale", onClick = { vm.setMode("Wholesale") }, label = { Text("Whole Sale") })
            }
            Surface(
                color = Color(0xFF1B5E20), shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Text(
                    "Total : ${money(subtotal)}  |  PFT : ${money(profit)}",
                    Modifier.padding(10.dp), color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (productCount > 0) "Search $productCount products…" else "Search products…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    IconButton(onClick = { scanner = true }) { Icon(Icons.Filled.QrCodeScanner, "scan barcode") }
                },
                singleLine = true
            )
            Spacer(Modifier.height(6.dp))

            when {
                query.isNotBlank() -> LazyColumn(Modifier.weight(1f)) {
                    items(results, key = { it.medicine.id }) { mws ->
                        val price = if (mode == "Wholesale" && mws.medicine.wholesalePrice > 0)
                            mws.medicine.wholesalePrice else mws.medicine.sellingPrice
                        Row(
                            Modifier.fillMaxWidth().clickable { qtyFor = mws.medicine }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(mws.medicine.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOf(mws.medicine.company, mws.medicine.generic)
                                        .filter { it.isNotBlank() }.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(price), fontWeight = FontWeight.Bold)
                                Text(
                                    "Stock : ${mws.stock}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (mws.stock <= 0) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                cart.isNotEmpty() -> LazyColumn(Modifier.weight(1f)) {
                    item {
                        Text(
                            "Order List", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(cart, key = { "${it.medicine.id}-${it.unitPrice}" }) { ci ->
                        val stock = medsAll.firstOrNull { it.medicine.id == ci.medicine.id }?.stock ?: 0
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = if (ci.qty > stock) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary) {
                                Text("${ci.qty}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ci.medicine.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Stock : $stock | U. Price : ${money(ci.unitPrice)} | PFT : " +
                                            money((ci.unitPrice - ci.medicine.purchasePrice) * ci.qty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(money(ci.unitPrice * ci.qty), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { vm.removeLine(ci) }, Modifier.size(30.dp)) {
                                Icon(Icons.Filled.Close, "remove", Modifier.size(16.dp))
                            }
                        }
                        HorizontalDivider()
                    }
                }
                else -> Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.ReceiptLong, null, Modifier.size(110.dp), tint = Color.LightGray)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your sale list is empty.\nScan a barcode or search to add items.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { vm.saveDraft(); Toast.makeText(ctx, "Draft saved ✓", Toast.LENGTH_SHORT).show() },
                        enabled = cart.isNotEmpty()
                    ) { Text("Save as Draft") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { payNow = true }, enabled = cart.isNotEmpty()) {
                        Text("Checkout  ${money(subtotal)}")
                    }
                }
            }
        }

        if (scanner) {
            BarcodeScannerScreen(
                onResult = { code ->
                    scanner = false
                    vm.lookupBarcode(code) { m ->
                        if (m != null) qtyFor = m else unknownCode = code
                    }
                },
                onClose = { scanner = false }
            )
        }
    }

    qtyFor?.let { m ->
        val stock = medsAll.firstOrNull { it.medicine.id == m.id }?.stock ?: 0
        QuantityDialog(
            m, stock, mode,
            onAdd = { units, price, isBox ->
                vm.addToCart(m, units, price, isBox); qtyFor = null; query = ""
            },
            onDismiss = { qtyFor = null }
        )
    }
    if (pickCustomer) {
        CustomerPickerDialog(
            customers,
            onPick = { vm.draftCustomerId = it.id; pickCustomer = false },
            onDismiss = { pickCustomer = false },
            onAdd = { n, p -> vm.addCustomer(Customer(name = n, phone = p)) },
            onWalkIn = { vm.draftCustomerId = null; pickCustomer = false }
        )
    }
    unknownCode?.let { code ->
        AlertDialog(
            onDismissRequest = { unknownCode = null },
            title = { Text("Product not found") },
            text = { Text("No product has barcode:\n$code\n\nAdd it now?") },
            confirmButton = {
                Button(onClick = { addPrefill = code; addNew = true; unknownCode = null }) { Text("Add product") }
            },
            dismissButton = { TextButton(onClick = { unknownCode = null }) { Text("Cancel") } }
        )
    }
    if (addNew) {
        AddMedicineDialog(addPrefill, onSave = { vm.addMedicine(it); addNew = false }, onDismiss = { addNew = false })
    }
    if (payNow) {
        PaymentDialog(vm, onDone = { s, items -> payNow = false; receipt = s to items; query = "" },
            onDismiss = { payNow = false })
    }
    receipt?.let { (s, items) -> ReceiptDialog(s, items) { receipt = null } }
}

// ---------------- BARCODE SCANNER ----------------
@OptIn(ExperimentalGetImage::class)
@Composable
fun BarcodeScannerScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }
    var code by remember { mutableStateOf<String?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (granted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    val previewView = PreviewView(c)
                    val exec = ContextCompat.getMainExecutor(c)
                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(exec) { proxy ->
                        val media = proxy.image
                        if (media == null) { proxy.close(); return@setAnalyzer }
                        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { bs ->
                                if (code == null) bs.firstOrNull()?.rawValue?.let { code = it }
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                    val p = ProcessCameraProvider.getInstance(c).get()
                    p.unbindAll()
                    p.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                        Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) },
                        analysis
                    )
                    provider = p
                    previewView
                }
            )
            Text(
                "Point at the medicine barcode…",
                Modifier.align(Alignment.BottomCenter).padding(24.dp), color = Color.White
            )
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission required for scanning")
                Spacer(Modifier.height(8.dp))
                Button(onClick = { perm.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
            }
        }
        TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) { Text("✕ Close", color = Color.White) }

        code?.let { c ->
            AlertDialog(
                onDismissRequest = { code = null },
                title = { Text("Barcode found") },
                text = { Text(c) },
                confirmButton = { Button(onClick = { onResult(c) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { code = null }) { Text("Scan again") } }
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { provider?.unbindAll() }
    }
}

// ---------------- QUANTITY DIALOG ----------------
@Composable
fun QuantityDialog(m: Medicine, stock: Int, mode: String,
                   onAdd: (Int, Double, Boolean) -> Unit, onDismiss: () -> Unit) {
    var qty by remember { mutableStateOf("") }
    var box by remember { mutableStateOf(false) }
    val ppb = if (m.piecesPerBox > 0) m.piecesPerBox else 1
    val price = if (mode == "Wholesale" && m.wholesalePrice > 0) m.wholesalePrice else m.sellingPrice
    val units = (qty.toIntOrNull() ?: 0) * (if (box) ppb else 1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(m.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(m.company.ifBlank { m.generic }, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Buy : ${money(m.purchasePrice)}  |  Sale : ${money(m.sellingPrice)}",
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Box : $ppb pcs → Buy ${money(m.purchasePrice * ppb)} / Sale ${money(price * ppb)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "In Stock : $stock",
                    color = if (stock <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), Modifier.padding(vertical = 4.dp)) {
                    FilterChip(selected = !box, onClick = { box = false }, label = { Text("Sell Quantity (pcs)") })
                    FilterChip(selected = box, onClick = { box = true }, label = { Text("Sell Box") })
                }
                Text(
                    "Sell ${if (box) "Box" else "Quantity"} :  ${if (qty.isEmpty()) "0" else qty}",
                    fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
                Keypad { k ->
                    qty = when {
                        k == "C" -> ""
                        k == "." -> qty
                        qty.length >= 5 -> qty
                        else -> qty + k
                    }
                }
                Text("= $units pcs • ${money(units * price)}", fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            Button(enabled = units > 0, onClick = { onAdd(units, price, box) }) { Text("ADD Medicine") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------- PAYMENT ----------------
@Composable
fun PaymentDialog(vm: ShopViewModel, onDone: (Sale, List<CartItem>) -> Unit, onDismiss: () -> Unit) {
    val cart by vm.cart.collectAsState()
    val customers by vm.customers.collectAsState()
    var method by remember { mutableStateOf("Cash") }
    var customer by remember {
        mutableStateOf(vm.draftCustomerId?.let { id -> customers.firstOrNull { it.id == id } })
    }
    var discountT by remember { mutableStateOf("") }
    var receiveT by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(1) }
    var pick by remember { mutableStateOf(false) }

    val subtotal = cart.sumOf { it.unitPrice * it.qty }
    val discount = toD(discountT)
    val total = (subtotal - discount).coerceAtLeast(0.0)
    val paid = when {
        method == "Due" -> 0.0
        receiveT.isBlank() -> total
        else -> toD(receiveT)
    }

    fun press(k: String) {
        val cur = if (active == 0) discountT else receiveT
        val next = when {
            k == "C" -> ""
            k == "." -> if (cur.contains('.')) cur else if (cur.isEmpty()) "0." else "$cur."
            cur.length >= 9 -> cur
            else -> cur + k
        }
        if (active == 0) discountT = next else receiveT = next
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(customer?.name ?: "Walk-in (Stranger)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = method == "Cash", onClick = { method = "Cash" }, label = { Text("Cash") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = method == "Due", onClick = { method = "Due"; receiveT = "" }, label = { Text("Due") })
                    if (method == "Due" && customer == null) {
                        TextButton(onClick = { pick = true }) { Text("Select customer") }
                    }
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    StatCard("Total Amount", money(subtotal), Modifier.weight(1f))
                    StatCard("Bill", money(total), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = discountT, onValueChange = {}, readOnly = true,
                        modifier = Modifier.weight(1f).clickable { active = 0 },
                        label = { Text(if (active == 0) "▸ Discount Taka" else "Discount Taka") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = if (method == "Due") "0" else receiveT, onValueChange = {}, readOnly = true,
                        modifier = Modifier.weight(1f).clickable { active = 1 },
                        label = { Text(if (active == 1) "▸ Receive" else "Receive") },
                        singleLine = true
                    )
                }
                Text("Bill Total : ${money(total)}", fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 6.dp))
                Keypad(::press)
            }
        },
        confirmButton = {
            Button(
                enabled = cart.isNotEmpty() && (method != "Due" || customer != null),
                onClick = { vm.checkout(customer?.id, discount, paid, method) { s, items -> onDone(s, items) } }
            ) { Text(if (method == "Due") "Save Due Sale" else "Receive Taka") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } }
    )

    if (pick) {
        CustomerPickerDialog(
            customers,
            onPick = { customer = it; vm.draftCustomerId = it.id; pick = false },
            onDismiss = { pick = false },
            onAdd = { n, p -> vm.addCustomer(Customer(name = n, phone = p)) }
        )
    }
}

// ---------------- CUSTOMER PICKER ----------------
@Composable
fun CustomerPickerDialog(customers: List<Customer>, onPick: (Customer) -> Unit,
                         onDismiss: () -> Unit, onAdd: (String, String) -> Unit,
                         onWalkIn: (() -> Unit)? = null) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select customer") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(),
                    label = { Text("New customer name") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(),
                    label = { Text("Phone (optional)") }, singleLine = true)
                Row {
                    TextButton(onClick = {
                        if (name.isNotBlank()) { onAdd(name.trim(), phone.trim()); name = ""; phone = "" }
                    }) { Text("+ Save new customer") }
                    if (onWalkIn != null) {
                        TextButton(onClick = onWalkIn) { Text("Walk-in (Stranger)") }
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(customers, key = { it.id }) { c ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 8.dp)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold)
                            if (c.phone.isNotBlank()) Text(c.phone, style = MaterialTheme.typography.bodySmall)
                            if (c.due > 0) Text("Due: ${money(c.due)}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ---------------- RECEIPT ----------------
@Composable
fun ReceiptDialog(sale: Sale, items: List<CartItem>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sale completed ✓") },
        text = {
            Column {
                Text(SHOP_NAME, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Invoice: ${sale.invoiceNo}", style = MaterialTheme.typography.bodySmall)
                Text(fdatetime(sale.dateTime), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                items.forEach {
                    Row {
                        Text("${it.medicine.name} × ${it.qty}", Modifier.weight(1f))
                        Text(money(it.unitPrice * it.qty))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row { Text("Total", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(money(sale.total), fontWeight = FontWeight.Bold) }
                Row { Text("Paid (${sale.mode} • ${sale.paymentMethod})", Modifier.weight(1f)); Text(money(sale.paid)) }
                if (sale.due > 0) Row {
                    Text("DUE", Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold)
                    Text(money(sale.due), color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val text = buildString {
                    appendLine(SHOP_NAME)
                    appendLine("Invoice: ${sale.invoiceNo}")
                    appendLine(fdatetime(sale.dateTime))
                    appendLine("----------------------")
                    items.forEach { appendLine("${it.medicine.name} x${it.qty}  ${money(it.unitPrice * it.qty)}") }
                    appendLine("----------------------")
                    appendLine("Total: ${money(sale.total)}")
                    appendLine("Paid (${sale.paymentMethod}): ${money(sale.paid)}")
                    if (sale.due > 0) appendLine("Due: ${money(sale.due)}")
                    appendLine("Thank you!")
                }
                ctx.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                        }, "Share receipt"
                    )
                )
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Done") } }
    )
}

// ---------------- DRAFTS ----------------
@Composable
fun DraftsScreen(vm: ShopViewModel, onLoaded: () -> Unit) {
    val drafts by vm.drafts.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Pending Drafts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (drafts.isEmpty()) {
            Text("No saved drafts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            items(drafts, key = { it.id }) { d ->
                val parts = d.itemsJson.split(";").filter { it.isNotBlank() }
                val count = parts.size
                val total = parts.sumOf { p ->
                    val f = p.split(",")
                    (f.getOrNull(1)?.toDoubleOrNull() ?: 0.0) * (f.getOrNull(2)?.toDoubleOrNull() ?: 0.0)
                }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Draft #${d.id} • $count items", fontWeight = FontWeight.SemiBold)
                            Text("${fdatetime(d.dateTime)} • ${d.mode}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Total : ${money(total)}", fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = { vm.loadDraft(d) { onLoaded() } }) { Text("Load") }
                        TextButton(onClick = { vm.deleteDraft(d.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

// ---------------- AI PURCHASE EXTRACTION ----------------
@Composable
fun AiPurchaseScreen(vm: ShopViewModel) {
    val ctx = LocalContext.current
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("") }
    var supplier by remember { mutableStateOf("") }
    var invoiceNo by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<ParsedItem>()) }
    var expiry by remember { mutableStateOf(defaultExpiryString()) }
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            imageUri = uri; items = emptyList(); supplier = ""; invoiceNo = ""
            status = "Reading invoice…"; busy = true
            runOcr(ctx, uri) { text ->
                val (sup, inv, list) = parseInvoiceText(text)
                supplier = sup; invoiceNo = inv; items = list
                status = if (list.isEmpty())
                    "No items detected — try a clearer, brighter photo."
                else "Extracted ${list.size} items. Tap ✏ on any item to fix it."
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("AI Purchase Extraction", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Photograph the supplier invoice — items are added to stock automatically.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Button(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Pick Invoice Photo")
        }

        imageUri?.let { uri ->
            Spacer(Modifier.height(8.dp))
            val bmp by produceState<Bitmap?>(null, uri) {
                value = withContext(Dispatchers.IO) { decodeSampled(ctx, uri) }
            }
            bmp?.let {
                Image(
                    bitmap = it.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Fit
                )
            }
        }
        if (busy) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text(status)
            }
        } else if (status.isNotBlank()) {
            Text(status, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (items.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            if (supplier.isNotBlank()) Text(supplier, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            if (invoiceNo.isNotBlank()) Text("Invoice : $invoiceNo",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                expiry, { expiry = it }, Modifier.fillMaxWidth().padding(top = 6.dp),
                label = { Text("Expiry for these batches (DD-MM-YYYY)") }, singleLine = true
            )
            Spacer(Modifier.height(6.dp))
            Text("Items Extracted (${items.size})", fontWeight = FontWeight.Bold)
            items.forEachIndexed { idx, it ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.name, fontWeight = FontWeight.SemiBold)
                            Text("Qty: ${it.qty}  |  Rate: ${money(it.rate)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(money(it.qty * it.rate), fontWeight = FontWeight.Bold)
                        IconButton(onClick = { editingIdx = idx }, Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Edit, "edit", Modifier.size(16.dp))
                        }
                        IconButton(onClick = { items = items.filterIndexed { i, _ -> i != idx } },
                            Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Close, "delete", Modifier.size(16.dp))
                        }
                    }
                }
            }
            val grandTotal = items.sumOf { it.qty * it.rate }
            Text("Total : ${money(grandTotal)}", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
            Button(
                onClick = {
                    vm.savePurchase(items, supplier, expiry) { n ->
                        items = emptyList(); status = "✓ Saved $n items to stock."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Confirm & Save Purchase") }
        }
        Spacer(Modifier.height(24.dp))
    }

    editingIdx?.let { idx ->
        EditItemDialog(
            items[idx],
            onSave = { ni -> items = items.toMutableList().also { l -> l[idx] = ni }; editingIdx = null },
            onDismiss = { editingIdx = null }
        )
    }
}

@Composable
fun EditItemDialog(item: ParsedItem, onSave: (ParsedItem) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    var qty by remember { mutableStateOf(item.qty.toString()) }
    var rate by remember { mutableStateOf(item.rate.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fix item") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(),
                    label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(qty, { qty = it }, Modifier.weight(1f),
                        label = { Text("Qty") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(rate, { rate = it }, Modifier.weight(1f),
                        label = { Text("Rate") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && toD(qty) > 0 && toD(rate) > 0, onClick = {
                onSave(ParsedItem(name.trim(), toD(qty).toInt(), toD(rate)))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------- STOCK MANAGEMENT ----------------
@Composable
fun StockScreen(vm: ShopViewModel) {
    val meds by vm.medicines.collectAsState()
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<MedicineWithStock?>(null) }

    val filtered = meds.filter {
        it.medicine.name.contains(query, true) || it.medicine.generic.contains(query, true)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("Medicines & Stock", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("Search stock…") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(filtered, key = { it.medicine.id }) { mws ->
                    Row(Modifier.fillMaxWidth().clickable { manage = mws }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(mws.medicine.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(mws.medicine.company, mws.medicine.barcode)
                                    .filter { it.isNotBlank() }.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${mws.stock} ${mws.medicine.unit}",
                                fontWeight = FontWeight.Bold,
                                color = if (mws.stock <= mws.medicine.minStock) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                            Text("Buy ${money(mws.medicine.purchasePrice)} • Sell ${money(mws.medicine.sellingPrice)}",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Add, "add medicine") }
    }

    if (showAdd) AddMedicineDialog(onSave = { vm.addMedicine(it); showAdd = false }, onDismiss = { showAdd = false })
    manage?.let { mws -> ManageMedicineDialog(vm, mws) { manage = null } }
}

@Composable
fun AddMedicineDialog(prefillBarcode: String = "", onSave: (Medicine) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var generic by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf(prefillBarcode) }
    var unit by remember { mutableStateOf("Pcs") }
    var ppb by remember { mutableStateOf("1") }
    var buy by remember { mutableStateOf("") }
    var sell by remember { mutableStateOf("") }
    var wholesale by remember { mutableStateOf("") }
    var minS by remember { mutableStateOf("10") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add medicine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(),
                    label = { Text("Medicine name *") }, singleLine = true)
                OutlinedTextField(generic, { generic = it }, Modifier.fillMaxWidth(),
                    label = { Text("Generic name") }, singleLine = true)
                OutlinedTextField(company, { company = it }, Modifier.fillMaxWidth(),
                    label = { Text("Company") }, singleLine = true)
                OutlinedTextField(barcode, { barcode = it }, Modifier.fillMaxWidth(),
                    label = { Text("Barcode (scan or type)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(unit, { unit = it }, Modifier.weight(1f),
                        label = { Text("Unit (Pcs/Strip)") }, singleLine = true)
                    OutlinedTextField(ppb, { ppb = it }, Modifier.weight(1f),
                        label = { Text("Pieces per box") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(buy, { buy = it }, Modifier.weight(1f),
                        label = { Text("Purchase price") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(sell, { sell = it }, Modifier.weight(1f),
                        label = { Text("Retail price") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(wholesale, { wholesale = it }, Modifier.weight(1f),
                        label = { Text("Wholesale price") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(minS, { minS = it }, Modifier.weight(1f),
                        label = { Text("Min stock alert") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = {
                onSave(
                    Medicine(
                        name = name.trim(), generic = generic.trim(), company = company.trim(),
                        barcode = barcode.trim(), unit = unit.trim().ifBlank { "Pcs" },
                        piecesPerBox = toD(ppb).toInt().coerceAtLeast(1),
                        purchasePrice = toD(buy), sellingPrice = toD(sell),
                        wholesalePrice = toD(wholesale), minStock = toD(minS).toInt()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ManageMedicineDialog(vm: ShopViewModel, mws: MedicineWithStock, onDismiss: () -> Unit) {
    val m = mws.medicine
    var batches by remember { mutableStateOf<List<Batch>>(emptyList()) }
    LaunchedEffect(m.id) { vm.batchesOf(m.id).collect { batches = it } }

    var editing by remember { mutableStateOf(false) }
    var addingBatch by remember { mutableStateOf(false) }

    var name by remember(m.id) { mutableStateOf(m.name) }
    var generic by remember(m.id) { mutableStateOf(m.generic) }
    var company by remember(m.id) { mutableStateOf(m.company) }
    var barcode by remember(m.id) { mutableStateOf(m.barcode) }
    var unit by remember(m.id) { mutableStateOf(m.unit) }
    var ppb by remember(m.id) { mutableStateOf(m.piecesPerBox.toString()) }
    var buy by remember(m.id) { mutableStateOf(if (m.purchasePrice == 0.0) "" else m.purchasePrice.toString()) }
    var sell by remember(m.id) { mutableStateOf(if (m.sellingPrice == 0.0) "" else m.sellingPrice.toString()) }
    var wholesale by remember(m.id) { mutableStateOf(if (m.wholesalePrice == 0.0) "" else m.wholesalePrice.toString()) }
    var minS by remember(m.id) { mutableStateOf(m.minStock.toString()) }

    var batchNo by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var bBuy by remember { mutableStateOf("") }
    var dateErr by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(m.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Total stock: ${mws.stock} ${m.unit}", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                if (editing) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(generic, { generic = it }, Modifier.fillMaxWidth(), label = { Text("Generic") }, singleLine = true)
                    OutlinedTextField(company, { company = it }, Modifier.fillMaxWidth(), label = { Text("Company") }, singleLine = true)
                    OutlinedTextField(barcode, { barcode = it }, Modifier.fillMaxWidth(), label = { Text("Barcode") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(unit, { unit = it }, Modifier.weight(1f), label = { Text("Unit") }, singleLine = true)
                        OutlinedTextField(ppb, { ppb = it }, Modifier.weight(1f), label = { Text("Pieces/box") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(buy, { buy = it }, Modifier.weight(1f), label = { Text("Purchase price") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(sell, { sell = it }, Modifier.weight(1f), label = { Text("Retail price") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(wholesale, { wholesale = it }, Modifier.weight(1f), label = { Text("Wholesale") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(minS, { minS = it }, Modifier.weight(1f), label = { Text("Min stock") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Button(onClick = {
                        vm.updateMedicine(
                            m.copy(
                                name = name.trim(), generic = generic.trim(), company = company.trim(),
                                barcode = barcode.trim(), unit = unit.trim(),
                                piecesPerBox = toD(ppb).toInt().coerceAtLeast(1),
                                purchasePrice = toD(buy), sellingPrice = toD(sell),
                                wholesalePrice = toD(wholesale), minStock = toD(minS).toInt()
                            )
                        )
                        editing = false
                    }) { Text("Save changes") }
                } else {
                    Text("${m.generic}  •  ${m.company}", style = MaterialTheme.typography.bodySmall)
                    if (m.barcode.isNotBlank()) Text("Barcode: ${m.barcode}", style = MaterialTheme.typography.bodySmall)
                    Text("Buy ${money(m.purchasePrice)} → Retail ${money(m.sellingPrice)} → Wholesale ${money(m.wholesalePrice)}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Box: ${m.piecesPerBox} pcs → Buy ${money(m.purchasePrice * m.piecesPerBox)} / Sell ${money(m.sellingPrice * m.piecesPerBox)}",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { editing = true }) { Text("✏ Edit details") }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Batches (expiry tracking)", fontWeight = FontWeight.Bold)
                batches.forEach { b ->
                    val days = (b.expiryDate - System.currentTimeMillis()) / 86400000L
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Batch ${b.batchNo.ifBlank { "-" }} • Qty ${b.quantity}",
                                fontWeight = FontWeight.SemiBold)
                            Text(
                                "Exp: ${fdate(b.expiryDate)}" + if (days < 0) " (EXPIRED)" else " ($days days)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (days < 60) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { vm.discardBatch(b.id) }) { Text("Remove") }
                    }
                }

                if (addingBatch) {
                    OutlinedTextField(batchNo, { batchNo = it }, Modifier.fillMaxWidth(),
                        label = { Text("Batch number") }, singleLine = true)
                    OutlinedTextField(expiry, { expiry = it; dateErr = null }, Modifier.fillMaxWidth(),
                        label = { Text("Expiry (DD-MM-YYYY) *") }, singleLine = true,
                        isError = dateErr != null, supportingText = { dateErr?.let { Text(it) } })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(qty, { qty = it }, Modifier.weight(1f),
                            label = { Text("Quantity *") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(bBuy, { bBuy = it }, Modifier.weight(1f),
                            label = { Text("Purchase price") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    Row {
                        Button(onClick = {
                            val d = parseDate(expiry.trim())
                            when {
                                d == null -> dateErr = "Use DD-MM-YYYY e.g. 12-08-2026"
                                toD(qty) <= 0 -> dateErr = "Enter quantity"
                                else -> {
                                    vm.addBatch(
                                        Batch(
                                            medicineId = m.id, batchNo = batchNo.trim(),
                                            expiryDate = d, quantity = toD(qty).toInt(),
                                            purchasePrice = if (toD(bBuy) > 0) toD(bBuy) else m.purchasePrice
                                        )
                                    )
                                    batchNo = ""; expiry = ""; qty = ""; bBuy = ""
                                    addingBatch = false
                                }
                            }
                        }) { Text("Add batch") }
                        TextButton(onClick = { addingBatch = false }) { Text("Cancel") }
                    }
                } else {
                    Button(onClick = { addingBatch = true }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("+ Add stock (new batch)")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ---------------- EXPIRY MANAGEMENT ----------------
@Composable
fun ExpiryScreen(vm: ShopViewModel) {
    val all by vm.allBatches.collectAsState()
    var filter by remember { mutableStateOf(90) }
    val now = System.currentTimeMillis()
    val day = 86400000L

    val list = all.filter { b ->
        when (filter) {
            -1 -> b.batch.expiryDate < now
            0 -> true
            else -> b.batch.expiryDate in now..(now + filter * day)
        }
    }.sortedBy { it.batch.expiryDate }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Expiry Management", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == -1, onClick = { filter = -1 }, label = { Text("Expired") })
            FilterChip(selected = filter == 30, onClick = { filter = 30 }, label = { Text("30 days") })
            FilterChip(selected = filter == 90, onClick = { filter = 90 }, label = { Text("90 days") })
            FilterChip(selected = filter == 180, onClick = { filter = 180 }, label = { Text("180 days") })
            FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("All") })
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(list, key = { it.batch.id }) { b ->
                val days = (b.batch.expiryDate - now) / day
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.medicineName, fontWeight = FontWeight.SemiBold)
                            Text("Batch ${b.batch.batchNo.ifBlank { "-" }} • Qty ${b.batch.quantity}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(fdate(b.batch.expiryDate), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (days < 0) "EXPIRED ${-days}d ago" else "$days days",
                                color = if (days < 0) MaterialTheme.colorScheme.error
                                else if (days < 30) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { vm.discardBatch(b.batch.id) }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- REPORTS ----------------
enum class Rpt(val label: String) { TODAY("Today"), W7("7 days"), M1("30 days"), ALL("All time") }

@Composable
fun ReportsScreen(vm: ShopViewModel) {
    var range by remember { mutableStateOf(Rpt.TODAY) }
    val now = System.currentTimeMillis()
    val (from, to) = remember(range) {
        when (range) {
            Rpt.TODAY -> vm.startOfDay(0) to now
            Rpt.W7 -> vm.startOfDay(-6) to now
            Rpt.M1 -> vm.startOfDay(-29) to now
            Rpt.ALL -> 0L to now
        }
    }

    val total by remember(range) { vm.salesTotal(from, to) }.collectAsState(initial = 0.0)
    val count by remember(range) { vm.salesCount(from, to) }.collectAsState(initial = 0)
    val profit by remember(range) { vm.profit(from, to) }.collectAsState(initial = 0.0)
    val dues by remember(range) { vm.dues(from, to) }.collectAsState(initial = 0.0)
    val top by remember(range) { vm.topProducts(from, to) }.collectAsState(initial = emptyList())
    val sales by remember(range) { vm.salesBetween(from, to) }.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Reports", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Rpt.entries.forEach { r ->
                FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            StatCard("Sales", money(total), Modifier.weight(1f))
            StatCard("Profit", money(profit), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            StatCard("Invoices", count.toString(), Modifier.weight(1f))
            StatCard("Dues", money(dues), Modifier.weight(1f), dues > 0)
        }
        Spacer(Modifier.height(16.dp))
        Text("Top selling", fontWeight = FontWeight.Bold)
        top.take(5).forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(p.medicineName, Modifier.weight(1f))
                Text("${p.qty} sold • ${money(p.revenue)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Invoices", fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f)) {
            items(sales, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.invoiceNo, fontWeight = FontWeight.SemiBold)
                        Text("${fdatetime(s.dateTime)} • ${s.mode} • ${s.paymentMethod}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(s.total), fontWeight = FontWeight.Bold)
                        if (s.due > 0) Text("Due ${money(s.due)}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

// ---------------- CUSTOMERS ----------------
@Composable
fun CustomersScreen(vm: ShopViewModel) {
    val customers by vm.customers.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Customer?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("Customers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(customers, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { detail = c }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Person, null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold)
                            Text(c.phone, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (c.due > 0) Text("Due ${money(c.due)}",
                            color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.PersonAdd, "add customer") }
    }

    if (showAdd) AddCustomerDialog(onSave = { vm.addCustomer(it); showAdd = false }, onDismiss = { showAdd = false })
    detail?.let { c -> CustomerDetailDialog(vm, c) { detail = null } }
}

@Composable
fun AddCustomerDialog(onSave: (Customer) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add customer") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(),
                    label = { Text("Name *") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(),
                    label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(),
                    label = { Text("Address") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(),
                onClick = { onSave(Customer(name = name.trim(), phone = phone.trim(), address = address.trim())) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CustomerDetailDialog(vm: ShopViewModel, customer: Customer, onDismiss: () -> Unit) {
    val history by remember(customer.id) { vm.salesOf(customer.id) }.collectAsState(initial = emptyList())
    var showPay by remember { mutableStateOf(false) }
    var payText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(customer.name) },
        text = {
            Column {
                Text(customer.phone, style = MaterialTheme.typography.bodySmall)
                Text(customer.address, style = MaterialTheme.typography.bodySmall)
                Text("Total due: ${money(customer.due)}", fontWeight = FontWeight.Bold,
                    color = if (customer.due > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 6.dp))
                if (showPay) {
                    OutlinedTextField(payText, { payText = it }, Modifier.fillMaxWidth(),
                        label = { Text("Amount received") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Row {
                        Button(enabled = toD(payText) > 0, onClick = {
                            vm.payDue(customer.id, toD(payText)); showPay = false; payText = ""
                        }) { Text("Receive") }
                        TextButton(onClick = { showPay = false }) { Text("Cancel") }
                    }
                } else if (customer.due > 0) {
                    Button(onClick = { showPay = true }) { Text("Receive due payment") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Purchase history", fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(history, key = { it.id }) { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(s.invoiceNo, fontWeight = FontWeight.SemiBold)
                                Text(fdatetime(s.dateTime), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(money(s.total), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
