package com.ivy.importdata.csv.domain

import com.ivy.base.legacy.Theme
import com.ivy.base.time.impl.TestTimeConverter
import com.ivy.data.db.dao.read.AccountDao
import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.db.entity.AccountEntity
import com.ivy.data.db.entity.SettingsEntity
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.CategoryRepository
import com.ivy.data.repository.CurrencyRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.data.repository.mapper.TransactionMapper
import com.ivy.importdata.csv.CSVRow
import com.ivy.importdata.csv.ColumnMapping
import com.ivy.importdata.csv.DateMetadata
import com.ivy.importdata.csv.ImportantFields
import com.ivy.importdata.csv.MappingStatus
import com.ivy.importdata.csv.OptionalFields
import com.ivy.importdata.csv.TransferFields
import com.ivy.importdata.csv.TrnTypeMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CSVImporterV2Test {
    private val accountDao = mockk<AccountDao>()
    private val settingsDao = mockk<SettingsDao>()
    private val accounts = mockk<AccountRepository>()
    private val categories = mockk<CategoryRepository>()
    private val writer = mockk<TransactionRepository>()
    private val cash = AccountEntity(name = "Cash", currency = "USD", color = 0)
    private val progress = mutableListOf<Double>()
    private val importer = CSVImporterV2(
        settingsDao,
        writer,
        TransactionMapper(accounts),
        accountDao,
        categories,
        mockk<CurrencyRepository>(),
        accounts,
        TestTimeConverter(),
    )

    init {
        coEvery { accountDao.findAll() } returns listOf(cash)
        coEvery { categories.findAll() } returns emptyList()
        coEvery { settingsDao.findFirst() } returns SettingsEntity(Theme.AUTO, "USD", 0.0, "")
        coEvery { accounts.findById(AccountId(cash.id)) } returns Account(
            AccountId(cash.id), NotBlankTrimmedString.from("Cash").getOrNull()!!,
            AssetCode.from("USD").getOrNull()!!, ColorInt(0), null, true, 0.0, false,
        )
        coEvery { writer.save(any()) } returns Unit
    }

    private fun <T> field(index: Int, metadata: T) =
        ColumnMapping("", "", "", index, metadata, false)
    private val status = MappingStatus(emptyList(), true)
    private val important = ImportantFields(
        field(0, 1), status,
        field(1, TrnTypeMetadata("income", "expense", "transfer")), status,
        field(2, DateMetadata.DateFirst), status,
        field(3, Unit), status, field(-1, Unit), status,
    )
    private val transfer = TransferFields(
        field(3, Unit),
        status,
        field(-1, Unit),
        status,
        field(0, 1),
        status,
    )
    private val optional = OptionalFields(
        field(-1, Unit),
        status,
        field(-1, Unit),
        status,
        field(-1, Unit),
        status,
    )
    private val header = CSVRow(listOf("amount", "type", "date", "account"))
    private fun row(type: String) = CSVRow(listOf("10", type, "19/10/2026", "Cash"))
    private suspend fun runImport(csv: List<CSVRow>) =
        importer.import(csv, important, transfer, optional) { progress.add(it) }

    @Test
    fun `one valid and one domain-invalid row count only confirmed save`() = runBlocking {
        val invalid = row("transfer")
        val result = runImport(listOf(header, row("expense"), invalid))
        assertEquals(2, result.rowsFound)
        assertEquals(1, result.transactionsImported)
        assertEquals(listOf(3), result.failedRows.map { it.index })
        assertEquals(invalid.values, result.failedRows.single().content)
        assertTrue(result.error!!.contains("domain"))
        assertEquals(1.0, progress.last(), 0.0)
        coVerify(exactly = 1) { writer.save(any()) }
    }

    @Test
    fun `domain rejection reports original source line and counts only saved rows`() = runBlocking {
        // A parse failure before the domain-invalid self-transfer exercises retained indices.
        val invalid = row("transfer")
        val result = runImport(listOf(header, row("unknown"), row("expense"), invalid))
        assertEquals(3, result.rowsFound)
        assertEquals(1, result.transactionsImported)
        assertEquals(listOf(2, 4), result.failedRows.map { it.index })
        assertEquals(invalid.values, result.failedRows.last().content)
        assertNotNull(result.error)
        assertEquals(1.0, progress.last(), 0.0)
        coVerify(exactly = 1) { writer.save(any()) }
    }

    @Test
    fun `writer failure is visible and not counted`() = runBlocking {
        coEvery { writer.save(any()) } throws IllegalStateException("write failed")
        val source = row("expense")
        val result = runImport(listOf(header, source))
        assertEquals(0, result.transactionsImported)
        assertEquals(listOf(2), result.failedRows.map { it.index })
        assertEquals(source.values, result.failedRows.single().content)
        assertTrue(result.error!!.contains("save"))
        assertEquals(1.0, progress.last(), 0.0)
    }

    @Test
    fun `empty and header only inputs report truthful zeros`() = runBlocking {
        for (csv in listOf(emptyList(), listOf(header))) {
            val result = runImport(csv)
            assertEquals(0, result.rowsFound)
            assertEquals(0, result.transactionsImported)
            assertEquals(0, result.accountsImported)
            assertEquals(0, result.categoriesImported)
            assertTrue(result.failedRows.isEmpty())
            assertNull(result.error)
            assertEquals(1.0, progress.last(), 0.0)
            assertTrue(progress.all { it.isFinite() })
        }
        coVerify(exactly = 0) { writer.save(any()) }
    }

    @Test
    fun `writer cancellation propagates without completion progress`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        coEvery { writer.save(any()) } throws cancellation
        try {
            runImport(listOf(header, row("expense")))
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertFalse(progress.contains(1.0))
    }
}
