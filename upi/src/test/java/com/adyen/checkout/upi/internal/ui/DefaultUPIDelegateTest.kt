/*
 * Copyright (c) 2023 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by oscars on 28/2/2023.
 */

package com.adyen.checkout.upi.internal.ui

import app.cash.turbine.test
import com.adyen.checkout.components.core.Amount
import com.adyen.checkout.components.core.CheckoutConfiguration
import com.adyen.checkout.components.core.Order
import com.adyen.checkout.components.core.OrderRequest
import com.adyen.checkout.components.core.PaymentMethod
import com.adyen.checkout.components.core.PaymentMethodTypes
import com.adyen.checkout.components.core.internal.PaymentObserverRepository
import com.adyen.checkout.components.core.internal.data.api.AnalyticsRepository
import com.adyen.checkout.components.core.internal.ui.model.ButtonComponentParamsMapper
import com.adyen.checkout.core.AdyenLogger
import com.adyen.checkout.core.Environment
import com.adyen.checkout.core.internal.util.Logger
import com.adyen.checkout.test.extensions.test
import com.adyen.checkout.ui.core.internal.ui.SubmitHandler
import com.adyen.checkout.upi.UPIComponentState
import com.adyen.checkout.upi.getUPIConfiguration
import com.adyen.checkout.upi.internal.ui.model.UPIMode
import com.adyen.checkout.upi.internal.ui.model.UPIOutputData
import com.adyen.checkout.upi.upi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockitoExtension::class)
internal class DefaultUPIDelegateTest(
    @Mock private val submitHandler: SubmitHandler<UPIComponentState>,
    @Mock private val analyticsRepository: AnalyticsRepository,
) {

    private lateinit var delegate: DefaultUPIDelegate

    @BeforeEach
    fun beforeEach() {
        delegate = createUPIDelegate()
        AdyenLogger.setLogLevel(Logger.NONE)
    }

    @Test
    fun `when delegate is initialized then analytics event is sent`() = runTest {
        delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

        verify(analyticsRepository).setupAnalytics()
    }

    @Nested
    @DisplayName("when input data changes and")
    inner class InputDataChangedTest {

        @Test
        fun `mode is VPA and VPA is empty, then output should be invalid`() = runTest {
            val outputTestFlow = delegate.outputDataFlow.test(testScheduler)

            delegate.updateInputData {
                mode = UPIMode.VPA
                virtualPaymentAddress = " "
            }

            assertFalse(outputTestFlow.latestValue.isValid)

            outputTestFlow.cancel()
        }

        @Test
        fun `mode is VPA and VPA is some value, then output should be valid`() = runTest {
            val outputTestFlow = delegate.outputDataFlow.test(testScheduler)

            delegate.updateInputData {
                mode = UPIMode.VPA
                virtualPaymentAddress = "somevpa"
            }

            assertTrue(outputTestFlow.latestValue.isValid)

            outputTestFlow.cancel()
        }

        @Test
        fun `mode is QR, then output should be valid`() = runTest {
            val outputTestFlow = delegate.outputDataFlow.test(testScheduler)

            delegate.updateInputData {
                mode = UPIMode.QR
                virtualPaymentAddress = ""
            }

            assertTrue(outputTestFlow.latestValue.isValid)

            outputTestFlow.cancel()
        }
    }

    @Nested
    @DisplayName("when creating component state and")
    inner class CreateComponentStateTest {

        @Test
        fun `output is invalid, then component state should be invalid`() = runTest {
            val componentStateTestFlow = delegate.componentStateFlow.test(testScheduler)

            delegate.updateComponentState(UPIOutputData(UPIMode.VPA, ""))

            with(componentStateTestFlow.latestValue) {
                assertFalse(isInputValid)
                assertFalse(isValid)
            }
        }

        @Test
        fun `mode is VPA and output is valid, then component state should be valid`() = runTest {
            val componentStateTestFlow = delegate.componentStateFlow.test(testScheduler)

            delegate.updateComponentState(UPIOutputData(UPIMode.VPA, "test"))

            with(componentStateTestFlow.latestValue) {
                assertEquals("test", data.paymentMethod?.virtualPaymentAddress)
                assertEquals(PaymentMethodTypes.UPI_COLLECT, data.paymentMethod?.type)
                assertEquals(TEST_ORDER, data.order)
                assertTrue(isInputValid)
                assertTrue(isValid)
            }
        }

        @Test
        fun `mode is QR and output is valid, then component state should be valid`() = runTest {
            val componentStateTestFlow = delegate.componentStateFlow.test(testScheduler)

            delegate.updateComponentState(UPIOutputData(UPIMode.QR, ""))

            with(componentStateTestFlow.latestValue) {
                assertNull(data.paymentMethod?.virtualPaymentAddress)
                assertEquals(PaymentMethodTypes.UPI_QR, data.paymentMethod?.type)
                assertEquals(TEST_ORDER, data.order)
                assertTrue(isInputValid)
                assertTrue(isValid)
            }
        }

        @ParameterizedTest
        @MethodSource("com.adyen.checkout.upi.internal.ui.DefaultUPIDelegateTest#amountSource")
        fun `when input data is valid then amount is propagated in component state if set`(
            configurationValue: Amount?,
            expectedComponentStateValue: Amount?,
        ) = runTest {
            if (configurationValue != null) {
                val configuration = createCheckoutConfiguration(configurationValue)
                delegate = createUPIDelegate(configuration = configuration)
            }
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))
            delegate.componentStateFlow.test {
                delegate.updateInputData {
                    mode = UPIMode.VPA
                    virtualPaymentAddress = "somevpa"
                }
                assertEquals(expectedComponentStateValue, expectMostRecentItem().data.amount)
            }
        }
    }

    @Nested
    inner class SubmitHandlerTest {

        @Test
        fun `when delegate is initialized then submit handler event is initialized`() = runTest {
            val coroutineScope = CoroutineScope(UnconfinedTestDispatcher())
            delegate.initialize(coroutineScope)
            verify(submitHandler).initialize(coroutineScope, delegate.componentStateFlow)
        }

        @Test
        fun `when delegate setInteractionBlocked is called then submit handler setInteractionBlocked is called`() =
            runTest {
                delegate.setInteractionBlocked(true)
                verify(submitHandler).setInteractionBlocked(true)
            }

        @Test
        fun `when delegate onSubmit is called then submit handler onSubmit is called`() = runTest {
            delegate.componentStateFlow.test {
                delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))
                delegate.onSubmit()
                verify(submitHandler).onSubmit(expectMostRecentItem())
            }
        }
    }

    @Nested
    inner class AnalyticsTest {

        @Test
        fun `when component state is valid then PaymentMethodDetails should contain checkoutAttemptId`() = runTest {
            whenever(analyticsRepository.getCheckoutAttemptId()) doReturn TEST_CHECKOUT_ATTEMPT_ID

            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.componentStateFlow.test {
                delegate.updateInputData {
                    mode = UPIMode.QR
                }

                assertEquals(TEST_CHECKOUT_ATTEMPT_ID, expectMostRecentItem().data.paymentMethod?.checkoutAttemptId)
            }
        }
    }

    private fun createCheckoutConfiguration(
        amount: Amount? = null,
    ) = CheckoutConfiguration(
        shopperLocale = Locale.US,
        environment = Environment.TEST,
        clientKey = TEST_CLIENT_KEY,
        amount = amount,
    ) {
        upi()
    }

    private fun createUPIDelegate(
        order: Order? = TEST_ORDER,
        configuration: CheckoutConfiguration = createCheckoutConfiguration(),
    ) = DefaultUPIDelegate(
        submitHandler = submitHandler,
        analyticsRepository = analyticsRepository,
        observerRepository = PaymentObserverRepository(),
        paymentMethod = PaymentMethod(),
        order = order,
        componentParams = ButtonComponentParamsMapper(null, null).mapToParams(
            checkoutConfiguration = configuration,
            configuration = configuration.getUPIConfiguration(),
            sessionParams = null,
        ),
    )

    companion object {
        private const val TEST_CLIENT_KEY = "test_qwertyuiopasdfghjklzxcvbnmqwerty"
        private val TEST_ORDER = OrderRequest("PSP", "ORDER_DATA")
        private const val TEST_CHECKOUT_ATTEMPT_ID = "TEST_CHECKOUT_ATTEMPT_ID"

        @JvmStatic
        fun amountSource() = listOf(
            // configurationValue, expectedComponentStateValue
            arguments(Amount("EUR", 100), Amount("EUR", 100)),
            arguments(Amount("USD", 0), Amount("USD", 0)),
            arguments(null, null),
            arguments(null, null),
        )
    }
}
