/*
 * Copyright (c) 2022 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by oscars on 15/8/2022.
 */

package com.adyen.checkout.qrcode.internal.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Parcel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.adyen.checkout.components.core.CheckoutConfiguration
import com.adyen.checkout.components.core.PaymentMethodTypes
import com.adyen.checkout.components.core.action.Action
import com.adyen.checkout.components.core.action.QrCodeAction
import com.adyen.checkout.components.core.internal.ActionComponentEvent
import com.adyen.checkout.components.core.internal.ActionObserverRepository
import com.adyen.checkout.components.core.internal.PaymentDataRepository
import com.adyen.checkout.components.core.internal.data.api.StatusRepository
import com.adyen.checkout.components.core.internal.data.model.StatusResponse
import com.adyen.checkout.components.core.internal.test.TestStatusRepository
import com.adyen.checkout.components.core.internal.ui.model.GenericComponentParams
import com.adyen.checkout.components.core.internal.ui.model.GenericComponentParamsMapper
import com.adyen.checkout.components.core.internal.ui.model.TimerData
import com.adyen.checkout.core.AdyenLogger
import com.adyen.checkout.core.Environment
import com.adyen.checkout.core.PermissionHandlerCallback
import com.adyen.checkout.core.exception.CheckoutException
import com.adyen.checkout.core.exception.ComponentException
import com.adyen.checkout.core.internal.util.Logger
import com.adyen.checkout.qrcode.internal.QRCodeCountDownTimer
import com.adyen.checkout.qrcode.internal.ui.model.QrCodeUIEvent
import com.adyen.checkout.qrcode.qrCode
import com.adyen.checkout.ui.core.internal.RedirectHandler
import com.adyen.checkout.ui.core.internal.exception.PermissionRequestException
import com.adyen.checkout.ui.core.internal.test.TestRedirectHandler
import com.adyen.checkout.ui.core.internal.util.ImageSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockitoExtension::class)
internal class DefaultQRCodeDelegateTest(
    @Mock private val countDownTimer: QRCodeCountDownTimer,
    @Mock private val context: Context,
    @Mock private val imageSaver: ImageSaver
) {

    private lateinit var redirectHandler: TestRedirectHandler
    private lateinit var statusRepository: TestStatusRepository
    private lateinit var paymentDataRepository: PaymentDataRepository
    private lateinit var delegate: DefaultQRCodeDelegate

    @BeforeEach
    fun beforeEach() {
        statusRepository = TestStatusRepository()
        redirectHandler = TestRedirectHandler()
        paymentDataRepository = PaymentDataRepository(SavedStateHandle())
        val configuration = CheckoutConfiguration(
            Locale.US,
            Environment.TEST,
            TEST_CLIENT_KEY,
        ) {
            qrCode()
        }
        delegate = createDelegate(
            observerRepository = ActionObserverRepository(),
            componentParams = GenericComponentParamsMapper(null, null).mapToParams(configuration, null),
            statusRepository = statusRepository,
            statusCountDownTimer = countDownTimer,
            redirectHandler = redirectHandler,
            paymentDataRepository = paymentDataRepository,
            imageSaver = imageSaver,
        )
        AdyenLogger.setLogLevel(Logger.NONE)
    }

    @Test
    fun `when observe is called, then observers are being added to the repository`() {
        val observerRepository = mock<ActionObserverRepository>()
        val delegate = createDelegate(
            observerRepository = observerRepository,
        )
        val lifecycleOwner = mock<LifecycleOwner>().apply {
            whenever(lifecycle).thenReturn(mock())
        }
        val coroutineScope = mock<CoroutineScope>()
        val callback = mock<(ActionComponentEvent) -> Unit>()

        delegate.observe(lifecycleOwner, coroutineScope, callback)

        verify(observerRepository).addObservers(
            detailsFlow = eq(delegate.detailsFlow),
            exceptionFlow = eq(delegate.exceptionFlow),
            permissionFlow = eq(delegate.permissionFlow),
            lifecycleOwner = eq(lifecycleOwner),
            coroutineScope = eq(coroutineScope),
            callback = eq(callback),
        )
    }

    @Test
    fun `when removeObserver is called, then observers are being removed`() {
        val observerRepository = mock<ActionObserverRepository>()
        val delegate = createDelegate(
            observerRepository = observerRepository,
        )

        delegate.removeObserver()

        verify(observerRepository).removeObservers()
    }

    @Test
    fun `when handleAction is called with unsupported action, then an error should be emitted`() = runTest {
        delegate.exceptionFlow.test {
            delegate.handleAction(
                createTestAction(),
                mock(),
            )

            assert(expectMostRecentItem() is ComponentException)
        }
    }

    @Test
    fun `when handleAction is called with null payment data, then an error should be emitted`() = runTest {
        delegate.exceptionFlow.test {
            delegate.handleAction(
                QrCodeAction(paymentMethodType = PaymentMethodTypes.PIX, paymentData = null),
                mock(),
            )

            assert(expectMostRecentItem() is ComponentException)
        }
    }

    @Nested
    @DisplayName("when in the QR code flow and")
    inner class QRCodeFlowTest {

        @Test
        fun `timer ticks, then left over time and progress are emitted`() = runTest {
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.timerFlow.test {
                delegate.onTimerTick(10000)

                skipItems(1)

                assertEquals(TimerData(10000, 1), awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `polling status for pix is running, then output data will be emitted`() = runTest {
            statusRepository.pollingResults = listOf(
                Result.success(StatusResponse(resultCode = "pending")),
                Result.success(StatusResponse(resultCode = "finished")),
            )
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.outputDataFlow.test {
                delegate.handleAction(
                    QrCodeAction(
                        paymentMethodType = PaymentMethodTypes.PIX,
                        qrCodeData = "qrData",
                        paymentData = "paymentData",
                    ),
                    Activity(),
                )

                skipItems(1)

                with(awaitItem()) {
                    assertFalse(isValid)
                    assertEquals(PaymentMethodTypes.PIX, paymentMethodType)
                    assertEquals("qrData", qrCodeData)
                }

                with(awaitItem()) {
                    assertTrue(isValid)
                    assertEquals(PaymentMethodTypes.PIX, paymentMethodType)
                    assertEquals("qrData", qrCodeData)
                }

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `polling for pix is final, then details will be emitted`() = runTest {
            statusRepository.pollingResults = listOf(
                Result.success(StatusResponse(resultCode = "finished", payload = "testpayload")),
            )
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.detailsFlow.test {
                delegate.handleAction(
                    QrCodeAction(paymentMethodType = PaymentMethodTypes.PIX, paymentData = "paymentData"),
                    Activity(),
                )

                assertEquals("testpayload", awaitItem().details?.getString(DefaultQRCodeDelegate.PAYLOAD_DETAILS_KEY))

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `polling for pix fails, then an error is propagated`() = runTest {
            val error = IOException("test")
            statusRepository.pollingResults = listOf(Result.failure(error))
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.exceptionFlow.test {
                delegate.handleAction(
                    QrCodeAction(paymentMethodType = PaymentMethodTypes.PIX, paymentData = "paymentData"),
                    Activity(),
                )

                assertEquals(error, awaitItem().cause)

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `polling for pix is final and payload is empty, then an error is propagated`() = runTest {
            statusRepository.pollingResults = listOf(
                Result.success(StatusResponse(resultCode = "finished", payload = "")),
            )
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.exceptionFlow.test {
                delegate.handleAction(
                    QrCodeAction(paymentMethodType = PaymentMethodTypes.PIX, paymentData = "paymentData"),
                    Activity(),
                )

                assertTrue(awaitItem() is ComponentException)

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `handleAction is called, then simple qr view flow is updated`() = runTest {
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.viewFlow.test {
                assertNull(awaitItem())

                delegate.handleAction(
                    QrCodeAction(paymentMethodType = PaymentMethodTypes.PIX, paymentData = "paymentData"),
                    Activity(),
                )

                assertEquals(QrCodeComponentViewType.SIMPLE_QR_CODE, awaitItem())
            }
        }

        @Test
        fun `polling status for payNow is running, then output data will be emitted`() = runTest {
            statusRepository.pollingResults = listOf(
                Result.success(StatusResponse(resultCode = "pending")),
                Result.success(StatusResponse(resultCode = "finished")),
            )
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.outputDataFlow.test {
                delegate.handleAction(
                    QrCodeAction(
                        paymentMethodType = PaymentMethodTypes.PAY_NOW,
                        qrCodeData = "qrData",
                        paymentData = "paymentData",
                    ),
                    Activity(),
                )

                skipItems(1)

                with(awaitItem()) {
                    assertFalse(isValid)
                    assertEquals(PaymentMethodTypes.PAY_NOW, paymentMethodType)
                    assertEquals("qrData", qrCodeData)
                }

                with(expectMostRecentItem()) {
                    assertTrue(isValid)
                    assertEquals(PaymentMethodTypes.PAY_NOW, paymentMethodType)
                    assertEquals("qrData", qrCodeData)
                }
            }
        }

        @Test
        fun `polling for payNow is final, then details will be emitted`() = runTest {
            statusRepository.pollingResults = listOf(
                Result.success(StatusResponse(resultCode = "finished", payload = "testpayload")),
            )
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.detailsFlow.test {
                delegate.handleAction(
                    QrCodeAction(paymentMethodType = PaymentMethodTypes.PAY_NOW, paymentData = "paymentData"),
                    Activity(),
                )

                assertEquals("testpayload", awaitItem().details?.getString(DefaultQRCodeDelegate.PAYLOAD_DETAILS_KEY))

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `polling for payNow is final and payload is empty, then an error is propagated`() = runTest {
            statusRepository.pollingResults = listOf(
                Result.success(StatusResponse(resultCode = "finished", payload = "")),
            )
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.exceptionFlow.test {
                delegate.handleAction(
                    QrCodeAction(paymentMethodType = PaymentMethodTypes.PAY_NOW, paymentData = "paymentData"),
                    Activity(),
                )

                assertTrue(awaitItem() is ComponentException)

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `polling for payNow fails, then an error is propagated`() = runTest {
            val error = IOException("test")
            statusRepository.pollingResults = listOf(Result.failure(error))
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.exceptionFlow.test {
                delegate.handleAction(
                    QrCodeAction(paymentMethodType = PaymentMethodTypes.PAY_NOW, paymentData = "paymentData"),
                    Activity(),
                )

                assertEquals(error, expectMostRecentItem().cause)
            }
        }

        @Test
        fun `handleAction is called, then full qr view flow is updated`() = runTest {
            delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))

            delegate.viewFlow.test {
                assertNull(awaitItem())

                delegate.handleAction(
                    QrCodeAction(paymentMethodType = PaymentMethodTypes.PAY_NOW, paymentData = "paymentData"),
                    Activity(),
                )

                assertEquals(QrCodeComponentViewType.FULL_QR_CODE, awaitItem())
            }
        }
    }

    @Nested
    @DisplayName("when in the redirect flow and")
    inner class RedirectFlowTest {

        @Test
        fun `handleAction is called and RedirectHandler returns an error, then the error is propagated`() = runTest {
            val error = ComponentException("Failed to make redirect.")
            redirectHandler.exception = error

            delegate.exceptionFlow.test {
                delegate.handleAction(QrCodeAction(paymentMethodType = "test", paymentData = "paymentData"), Activity())

                assertEquals(error, awaitItem())
            }
        }

        @Test
        fun `handleAction is called with valid data, then no error is propagated`() = runTest {
            delegate.exceptionFlow.test {
                delegate.handleAction(QrCodeAction(paymentMethodType = "test", paymentData = "paymentData"), Activity())

                expectNoEvents()
            }
        }

        @Test
        fun `handleIntent is called and RedirectHandler returns an error, then the error is propagated`() = runTest {
            val error = ComponentException("Failed to parse redirect result.")
            redirectHandler.exception = error

            delegate.exceptionFlow.test {
                delegate.handleIntent(Intent())

                assertEquals(error, awaitItem())
            }
        }

        @Test
        fun `handleIntent is called with valid data, then the details are emitted`() = runTest {
            delegate.detailsFlow.test {
                delegate.handleAction(QrCodeAction(paymentData = "paymentData"), Activity())
                delegate.handleIntent(Intent())

                with(awaitItem()) {
                    assertEquals(TestRedirectHandler.REDIRECT_RESULT, details)
                    assertEquals("paymentData", paymentData)
                }
            }
        }

        @Test
        fun `handleAction is called, then the view flow is updated`() = runTest {
            delegate.viewFlow.test {
                assertNull(awaitItem())

                delegate.handleAction(QrCodeAction(paymentData = "paymentData"), Activity())

                assertEquals(awaitItem(), QrCodeComponentViewType.REDIRECT)
            }
        }
    }

    @Test
    fun `when refreshStatus is called, then status for statusRepository gets refreshed`() = runTest {
        val statusRepository = mock<StatusRepository>()
        val paymentData = "Payment Data"
        val delegate = createDelegate(
            statusRepository = statusRepository,
            paymentDataRepository = paymentDataRepository,
        ).apply {
            initialize(CoroutineScope(UnconfinedTestDispatcher()))
            handleAction(
                QrCodeAction(paymentMethodType = PaymentMethodTypes.PIX, paymentData = paymentData),
                mock(),
            )
        }

        delegate.refreshStatus()

        verify(statusRepository).refreshStatus(paymentData)
    }

    @Test
    fun `when refreshStatus is called with no payment data, then status for statusRepository does not get refreshed`() =
        runTest {
            val statusRepository = mock<StatusRepository>()
            val delegate = createDelegate(
                statusRepository = statusRepository,
                paymentDataRepository = paymentDataRepository,
            ).apply {
                handleAction(
                    QrCodeAction(paymentMethodType = PaymentMethodTypes.PIX, paymentData = null),
                    mock(),
                )
            }

            delegate.refreshStatus()

            verify(statusRepository, never()).refreshStatus(any())
        }

    @Test
    fun `when downloadQRImage is called with success, then Success gets emitted`() = runTest {
        whenever(imageSaver.saveImageFromUrl(any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(
            Result.success(Unit),
        )

        delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))
        delegate.eventFlow.test {
            val expectedResult = QrCodeUIEvent.QrImageDownloadResult.Success

            delegate.downloadQRImage(context)

            assertEquals(expectedResult, expectMostRecentItem())
        }
    }

    @Test
    fun `when downloadQRImage is called with permission exception, then PermissionDenied gets emitted`() = runTest {
        whenever(imageSaver.saveImageFromUrl(any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(
            Result.failure(PermissionRequestException("Error message for permission request exception")),
        )

        delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))
        delegate.eventFlow.test {
            val expectedResult = QrCodeUIEvent.QrImageDownloadResult.PermissionDenied

            delegate.downloadQRImage(context)

            assertEquals(expectedResult, expectMostRecentItem())
        }
    }

    @Test
    fun `when downloadQRImage is called with failure, then Success gets emitted`() = runTest {
        val throwable = CheckoutException("error")
        whenever(imageSaver.saveImageFromUrl(any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(
            Result.failure(throwable),
        )

        delegate.initialize(CoroutineScope(UnconfinedTestDispatcher()))
        delegate.eventFlow.test {
            val expectedResult = QrCodeUIEvent.QrImageDownloadResult.Failure(throwable)

            delegate.downloadQRImage(context)

            assertEquals(expectedResult, expectMostRecentItem())
        }
    }

    @Test
    fun `when requestPermission is called, then correct permission request data is being emitted`() = runTest {
        val requiredPermission = "Required Permission"
        val permissionCallback = mock<PermissionHandlerCallback>()

        delegate.permissionFlow.test {
            delegate.requestPermission(context, requiredPermission, permissionCallback)

            val mostRecentValue = expectMostRecentItem()
            assertEquals(requiredPermission, mostRecentValue.requiredPermission)
            assertEquals(permissionCallback, mostRecentValue.permissionCallback)
        }
    }

    @Test
    fun `when onCleared is called, observers are removed`() {
        val observerRepository = mock<ActionObserverRepository>()
        val countDownTimer = mock<QRCodeCountDownTimer>()
        val redirectHandler = mock<RedirectHandler>()
        val delegate = createDelegate(
            observerRepository = observerRepository,
            statusCountDownTimer = countDownTimer,
            redirectHandler = redirectHandler,
        )

        delegate.onCleared()

        verify(observerRepository).removeObservers()
        verify(countDownTimer).cancel()
        verify(redirectHandler).removeOnRedirectListener()
    }

    private fun createTestAction(
        type: String = "test",
        paymentData: String = "paymentData",
        paymentMethodType: String = "paymentMethodType",
    ) = object : Action() {
        override var type: String? = type
        override var paymentData: String? = paymentData
        override var paymentMethodType: String? = paymentMethodType
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    @Suppress("LongParameterList")
    private fun createDelegate(
        observerRepository: ActionObserverRepository = mock(),
        componentParams: GenericComponentParams = mock(),
        statusRepository: StatusRepository = mock(),
        statusCountDownTimer: QRCodeCountDownTimer = mock(),
        redirectHandler: RedirectHandler = mock(),
        paymentDataRepository: PaymentDataRepository = mock(),
        imageSaver: ImageSaver = mock(),
    ) = DefaultQRCodeDelegate(
        observerRepository = observerRepository,
        componentParams = componentParams,
        statusRepository = statusRepository,
        statusCountDownTimer = statusCountDownTimer,
        redirectHandler = redirectHandler,
        paymentDataRepository = paymentDataRepository,
        imageSaver = imageSaver,
    )

    companion object {
        private const val TEST_CLIENT_KEY = "test_qwertyuiopasdfghjklzxcvbnmqwerty"
    }
}
