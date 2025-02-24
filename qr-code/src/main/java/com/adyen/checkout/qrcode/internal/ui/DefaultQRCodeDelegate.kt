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
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleOwner
import com.adyen.checkout.components.core.ActionComponentData
import com.adyen.checkout.components.core.PaymentMethodTypes
import com.adyen.checkout.components.core.action.Action
import com.adyen.checkout.components.core.action.QrCodeAction
import com.adyen.checkout.components.core.internal.ActionComponentEvent
import com.adyen.checkout.components.core.internal.ActionObserverRepository
import com.adyen.checkout.components.core.internal.PaymentDataRepository
import com.adyen.checkout.components.core.internal.PermissionRequestData
import com.adyen.checkout.components.core.internal.data.api.StatusRepository
import com.adyen.checkout.components.core.internal.data.model.StatusResponse
import com.adyen.checkout.components.core.internal.ui.model.GenericComponentParams
import com.adyen.checkout.components.core.internal.ui.model.TimerData
import com.adyen.checkout.components.core.internal.util.DateUtils
import com.adyen.checkout.components.core.internal.util.StatusResponseUtils
import com.adyen.checkout.components.core.internal.util.bufferedChannel
import com.adyen.checkout.components.core.internal.util.repeatOnResume
import com.adyen.checkout.core.PermissionHandlerCallback
import com.adyen.checkout.core.exception.CheckoutException
import com.adyen.checkout.core.exception.ComponentException
import com.adyen.checkout.core.internal.util.LogUtil
import com.adyen.checkout.core.internal.util.Logger
import com.adyen.checkout.qrcode.internal.QRCodeCountDownTimer
import com.adyen.checkout.qrcode.internal.ui.model.QRCodeOutputData
import com.adyen.checkout.qrcode.internal.ui.model.QRCodePaymentMethodConfig
import com.adyen.checkout.qrcode.internal.ui.model.QrCodeUIEvent
import com.adyen.checkout.ui.core.internal.RedirectHandler
import com.adyen.checkout.ui.core.internal.exception.PermissionRequestException
import com.adyen.checkout.ui.core.internal.ui.ComponentViewType
import com.adyen.checkout.ui.core.internal.util.ImageSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions", "LongParameterList")
internal class DefaultQRCodeDelegate(
    private val observerRepository: ActionObserverRepository,
    override val componentParams: GenericComponentParams,
    private val statusRepository: StatusRepository,
    private val statusCountDownTimer: QRCodeCountDownTimer,
    private val redirectHandler: RedirectHandler,
    private val paymentDataRepository: PaymentDataRepository,
    private val imageSaver: ImageSaver
) : QRCodeDelegate {

    private val _outputDataFlow = MutableStateFlow(createOutputData())
    override val outputDataFlow: Flow<QRCodeOutputData> = _outputDataFlow

    private val exceptionChannel: Channel<CheckoutException> = bufferedChannel()
    override val exceptionFlow: Flow<CheckoutException> = exceptionChannel.receiveAsFlow()

    private val permissionChannel: Channel<PermissionRequestData> = bufferedChannel()
    override val permissionFlow: Flow<PermissionRequestData> = permissionChannel.receiveAsFlow()

    override val outputData: QRCodeOutputData get() = _outputDataFlow.value

    private val detailsChannel: Channel<ActionComponentData> = bufferedChannel()
    override val detailsFlow: Flow<ActionComponentData> = detailsChannel.receiveAsFlow()

    private val _timerFlow = MutableStateFlow(TimerData(0, 0))
    override val timerFlow: Flow<TimerData> = _timerFlow

    private val _viewFlow: MutableStateFlow<ComponentViewType?> = MutableStateFlow(null)
    override val viewFlow: Flow<ComponentViewType?> = _viewFlow

    private val eventChannel: Channel<QrCodeUIEvent> = bufferedChannel()
    override val eventFlow: Flow<QrCodeUIEvent> = eventChannel.receiveAsFlow()

    private var _coroutineScope: CoroutineScope? = null
    private val coroutineScope: CoroutineScope get() = requireNotNull(_coroutineScope)

    private var statusPollingJob: Job? = null

    private var maxPollingDurationMillis = DEFAULT_MAX_POLLING_DURATION

    private fun attachStatusTimer() {
        statusCountDownTimer.attach(
            millisInFuture = maxPollingDurationMillis,
            countDownInterval = STATUS_POLLING_INTERVAL_MILLIS
        ) { millisUntilFinished -> onTimerTick(millisUntilFinished) }
    }

    @VisibleForTesting
    internal fun onTimerTick(millisUntilFinished: Long) {
        val progressPercentage =
            (HUNDRED * millisUntilFinished / maxPollingDurationMillis).toInt()
        _timerFlow.tryEmit(TimerData(millisUntilFinished, progressPercentage))
    }

    override fun initialize(coroutineScope: CoroutineScope) {
        _coroutineScope = coroutineScope
    }

    override fun observe(
        lifecycleOwner: LifecycleOwner,
        coroutineScope: CoroutineScope,
        callback: (ActionComponentEvent) -> Unit
    ) {
        observerRepository.addObservers(
            detailsFlow = detailsFlow,
            exceptionFlow = exceptionFlow,
            permissionFlow = permissionFlow,
            lifecycleOwner = lifecycleOwner,
            coroutineScope = coroutineScope,
            callback = callback
        )

        // Immediately request a new status if the user resumes the app
        lifecycleOwner.repeatOnResume { refreshStatus() }
    }

    override fun removeObserver() {
        observerRepository.removeObservers()
    }

    @Suppress("ReturnCount")
    override fun handleAction(action: Action, activity: Activity) {
        if (action !is QrCodeAction) {
            exceptionChannel.trySend(ComponentException("Unsupported action"))
            return
        }

        val paymentData = action.paymentData
        paymentDataRepository.paymentData = paymentData
        if (paymentData == null) {
            Logger.e(TAG, "Payment data is null")
            exceptionChannel.trySend(ComponentException("Payment data is null"))
            return
        }

        if (shouldLaunchRedirect(action)) {
            Logger.d(TAG, "Action does not require a view, redirecting.")
            _viewFlow.tryEmit(QrCodeComponentViewType.REDIRECT)
            makeRedirect(activity, action)
            return
        }

        var viewType = QrCodeComponentViewType.SIMPLE_QR_CODE

        action.paymentMethodType?.let {
            val qrConfig = QRCodePaymentMethodConfig.getByPaymentMethodType(it)
            viewType = qrConfig.viewType
            maxPollingDurationMillis = qrConfig.maxPollingDurationMillis
        }
        _viewFlow.tryEmit(viewType)

        // Notify UI to get the logo.
        createOutputData(null, action)

        attachStatusTimer()
        startStatusPolling(paymentData, action)
        statusCountDownTimer.start()
    }

    private fun makeRedirect(activity: Activity, action: QrCodeAction) {
        val url = action.url
        try {
            Logger.d(TAG, "makeRedirect - $url")
            redirectHandler.launchUriRedirect(activity, url)
        } catch (ex: CheckoutException) {
            exceptionChannel.trySend(ex)
        }
    }

    private fun startStatusPolling(paymentData: String, action: QrCodeAction) {
        statusPollingJob?.cancel()
        statusPollingJob = statusRepository.poll(paymentData, maxPollingDurationMillis)
            .onEach { onStatus(it, action) }
            .launchIn(coroutineScope)
    }

    private fun onStatus(result: Result<StatusResponse>, action: QrCodeAction) {
        result.fold(
            onSuccess = { response ->
                Logger.v(TAG, "Status changed - ${response.resultCode}")
                createOutputData(response, action)
                if (StatusResponseUtils.isFinalResult(response)) {
                    onPollingSuccessful(response)
                }
            },
            onFailure = {
                Logger.e(TAG, "Error while polling status", it)
                exceptionChannel.trySend(ComponentException("Error while polling status", it))
            }
        )
    }

    private fun createOutputData(statusResponse: StatusResponse?, action: QrCodeAction) {
        val isValid = statusResponse != null && StatusResponseUtils.isFinalResult(statusResponse)

        var qrImageUrl: String? = null
        if (_viewFlow.value == QrCodeComponentViewType.FULL_QR_CODE) {
            val encodedQrCodeData = Uri.encode(action.qrCodeData)
            qrImageUrl = String.format(
                QR_IMAGE_BASE_PATH,
                componentParams.environment.checkoutShopperBaseUrl.toString(),
                encodedQrCodeData
            )
        }

        var messageTextResource: Int? = null
        action.paymentMethodType?.let {
            val qrConfig = QRCodePaymentMethodConfig.getByPaymentMethodType(it)
            messageTextResource = qrConfig.messageTextResource
        }

        val outputData = QRCodeOutputData(
            isValid = isValid,
            paymentMethodType = action.paymentMethodType,
            qrCodeData = action.qrCodeData,
            qrImageUrl = qrImageUrl,
            messageTextResource = messageTextResource
        )
        _outputDataFlow.tryEmit(outputData)
    }

    private fun onPollingSuccessful(statusResponse: StatusResponse) {
        val payload = statusResponse.payload
        // Not authorized status should still call /details so that merchant can get more info
        if (StatusResponseUtils.isFinalResult(statusResponse) && !payload.isNullOrEmpty()) {
            val details = createDetails(payload)
            detailsChannel.trySend(createActionComponentData(details))
        } else {
            exceptionChannel.trySend(ComponentException("Payment was not completed. - " + statusResponse.resultCode))
        }
    }

    private fun shouldLaunchRedirect(action: QrCodeAction): Boolean {
        return !VIEWABLE_PAYMENT_METHODS.contains(action.paymentMethodType)
    }

    override fun refreshStatus() {
        val paymentData = paymentDataRepository.paymentData ?: return
        statusRepository.refreshStatus(paymentData)
    }

    override fun handleIntent(intent: Intent) {
        try {
            val details = redirectHandler.parseRedirectResult(intent.data)
            detailsChannel.trySend(createActionComponentData(details))
        } catch (ex: CheckoutException) {
            exceptionChannel.trySend(ex)
        }
    }

    private fun createActionComponentData(details: JSONObject): ActionComponentData {
        return ActionComponentData(
            details = details,
            paymentData = paymentDataRepository.paymentData,
        )
    }

    private fun createDetails(payload: String): JSONObject {
        val jsonObject = JSONObject()
        try {
            jsonObject.put(PAYLOAD_DETAILS_KEY, payload)
        } catch (e: JSONException) {
            exceptionChannel.trySend(ComponentException("Failed to create details.", e))
        }
        return jsonObject
    }

    override fun onError(e: CheckoutException) {
        exceptionChannel.trySend(e)
    }

    private fun createOutputData() = QRCodeOutputData(
        isValid = false,
        paymentMethodType = null,
        qrCodeData = null
    )

    override fun downloadQRImage(context: Context) {
        val paymentMethodType = outputData.paymentMethodType ?: ""
        val timestamp = DateUtils.formatDateToString(Calendar.getInstance())
        val imageName = String.format(IMAGE_NAME_FORMAT, paymentMethodType, timestamp)

        coroutineScope.launch {
            imageSaver.saveImageFromUrl(
                context = context,
                permissionHandler = this@DefaultQRCodeDelegate,
                imageUrl = outputData.qrImageUrl.orEmpty(),
                fileName = imageName
            ).fold(
                onSuccess = {
                    eventChannel.trySend(QrCodeUIEvent.QrImageDownloadResult.Success)
                },
                onFailure = { throwable ->
                    when (throwable) {
                        is PermissionRequestException ->
                            eventChannel.trySend(QrCodeUIEvent.QrImageDownloadResult.PermissionDenied)

                        else -> eventChannel.trySend(QrCodeUIEvent.QrImageDownloadResult.Failure(throwable))
                    }
                }
            )
        }
    }

    override fun requestPermission(context: Context, requiredPermission: String, callback: PermissionHandlerCallback) {
        val requestData = PermissionRequestData(requiredPermission, callback)
        permissionChannel.trySend(requestData)
    }

    override fun setOnRedirectListener(listener: () -> Unit) {
        redirectHandler.setOnRedirectListener(listener)
    }

    override fun onCleared() {
        removeObserver()
        statusPollingJob?.cancel()
        statusPollingJob = null
        statusCountDownTimer.cancel()
        _coroutineScope = null
        redirectHandler.removeOnRedirectListener()
    }

    companion object {
        private val TAG = LogUtil.getTag()

        private val VIEWABLE_PAYMENT_METHODS = listOf(
            PaymentMethodTypes.DUIT_NOW,
            PaymentMethodTypes.PIX,
            PaymentMethodTypes.PAY_NOW,
            PaymentMethodTypes.PROMPT_PAY,
            PaymentMethodTypes.UPI_QR,
        )

        @VisibleForTesting
        internal const val PAYLOAD_DETAILS_KEY = "payload"
        private val STATUS_POLLING_INTERVAL_MILLIS = 1.seconds.inWholeMilliseconds
        private val DEFAULT_MAX_POLLING_DURATION = 15.minutes.inWholeMilliseconds
        private const val HUNDRED = 100

        private const val IMAGE_NAME_FORMAT = "%s-%s.png"
        private const val QR_IMAGE_BASE_PATH = "%sbarcode.shtml?barcodeType=qrCode&fileType=png&data=%s"
    }
}
