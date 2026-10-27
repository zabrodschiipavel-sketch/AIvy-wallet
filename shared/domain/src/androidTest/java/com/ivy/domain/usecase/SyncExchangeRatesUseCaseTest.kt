package com.ivy.domain.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import arrow.core.Either
import com.ivy.base.TestDispatchersProvider
import com.ivy.data.db.IvyRoomDatabase
import com.ivy.data.db.entity.ExchangeRateEntity
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.remote.RemoteExchangeRatesDataSource
import com.ivy.data.remote.responses.ExchangeRatesResponse
import com.ivy.data.repository.ExchangeRatesRepository
import com.ivy.data.repository.mapper.ExchangeRateMapper
import com.ivy.domain.usecase.exchange.SyncExchangeRatesUseCase
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncExchangeRatesUseCaseTest {
    private lateinit var useCase: SyncExchangeRatesUseCase
    private lateinit var repository: ExchangeRatesRepository
    private lateinit var db: IvyRoomDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, IvyRoomDatabase::class.java).build()

        repository = ExchangeRatesRepository(
            exchangeRatesDao = db.exchangeRatesDao,
            writeExchangeRatesDao = db.writeExchangeRatesDao,
            mapper = ExchangeRateMapper(),
            remoteExchangeRatesDataSource = object : RemoteExchangeRatesDataSource {
                override suspend fun fetchEurExchangeRates() = Either.Right(
                    ExchangeRatesResponse(
                        date = "2026-10-27",
                        rates = mapOf("eur" to 1.0, "usd" to 2.0, "gbp" to 0.5)
                    )
                )
            },
            dispatchers = TestDispatchersProvider,
        )

        useCase = SyncExchangeRatesUseCase(repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun syncsExchangeRates(): Unit = runBlocking {
        // given
        val exchangeRatesDao = db.exchangeRatesDao
        val expected = listOf(
            ExchangeRateEntity(baseCurrency = "USD", currency = "EUR", rate = 0.5),
            ExchangeRateEntity(baseCurrency = "USD", currency = "USD", rate = 1.0),
            ExchangeRateEntity(baseCurrency = "USD", currency = "GBP", rate = 0.25)
        )

        // when
        val res = useCase.sync(AssetCode.USD)

        // then
        res.shouldBeRight()
        val savedRates = exchangeRatesDao.findAll().first()
        savedRates shouldContainExactlyInAnyOrder expected

        // when syncing again
        val res2 = useCase.sync(AssetCode.USD)

        // then still the same exact rows
        res2.shouldBeRight()
        val savedRates2 = exchangeRatesDao.findAll().first()
        savedRates2 shouldContainExactlyInAnyOrder expected
    }
}
