/*
 * Copyright (c) 2019 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by arman on 20/2/2019.
 */
package com.adyen.checkout.components.base

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import com.adyen.checkout.components.ComponentError
import com.adyen.checkout.components.PaymentComponentEvent
import com.adyen.checkout.components.PaymentComponentState
import com.adyen.checkout.components.analytics.AnalyticEvent.Companion.create
import com.adyen.checkout.components.analytics.AnalyticEvent.Flavor
import com.adyen.checkout.components.analytics.AnalyticsDispatcher.Companion.dispatchEvent
import com.adyen.checkout.components.base.lifecycle.PaymentComponentViewModel
import com.adyen.checkout.components.model.payments.request.PaymentMethodDetails
import com.adyen.checkout.core.exception.CheckoutException
import com.adyen.checkout.core.log.LogUtil
import com.adyen.checkout.core.log.Logger

@Suppress("TooManyFunctions")
abstract class BasePaymentComponent<
    ConfigurationT : Configuration,
    ComponentStateT : PaymentComponentState<out PaymentMethodDetails>
    >(
    savedStateHandle: SavedStateHandle,
    private val paymentMethodDelegate: PaymentMethodDelegate,
    configuration: ConfigurationT
) : PaymentComponentViewModel<ConfigurationT, ComponentStateT>(savedStateHandle, configuration) {

    private val paymentComponentStateLiveData = MutableLiveData<ComponentStateT>()
    private val componentErrorLiveData = MutableLiveData<ComponentError>()
    private var isCreatedForDropIn = false
    private var isAnalyticsEnabled = true

    init {
        assertSupported(paymentMethodDelegate.getPaymentMethodType())
    }

    override fun requiresInput(): Boolean {
        // By default all components require user input.
        return true
    }

    abstract fun observe(lifecycleOwner: LifecycleOwner, callback: (PaymentComponentEvent<ComponentStateT>) -> Unit)

    override fun observe(lifecycleOwner: LifecycleOwner, observer: Observer<ComponentStateT>) {
        paymentComponentStateLiveData.observe(lifecycleOwner, observer)
    }

    override fun removeObservers(lifecycleOwner: LifecycleOwner) {
        paymentComponentStateLiveData.removeObservers(lifecycleOwner)
    }

    override fun removeObserver(observer: Observer<ComponentStateT>) {
        paymentComponentStateLiveData.removeObserver(observer)
    }

    override fun observeErrors(lifecycleOwner: LifecycleOwner, observer: Observer<ComponentError>) {
        componentErrorLiveData.observe(lifecycleOwner, observer)
    }

    override fun removeErrorObservers(lifecycleOwner: LifecycleOwner) {
        componentErrorLiveData.removeObservers(lifecycleOwner)
    }

    override fun removeErrorObserver(observer: Observer<ComponentError>) {
        componentErrorLiveData.removeObserver(observer)
    }

    override val state: ComponentStateT?
        get() = paymentComponentStateLiveData.value

    /**
     * Sets if the analytics events can be sent by the component.
     * Default is True.
     *
     * @param isEnabled Is analytics should be enabled or not.
     */
    // TODO: 13/11/2020 Add to Configuration instead?
    fun setAnalyticsEnabled(isEnabled: Boolean) {
        isAnalyticsEnabled = isEnabled
    }

    /**
     * Send an analytic event about the Component being shown to the user.
     *
     * @param context The context where the component is.
     */
    // TODO change later when analytics are implemented
    fun sendAnalyticsEvent(context: Context) {
        if (isAnalyticsEnabled) {
            val flavor: Flavor = if (isCreatedForDropIn) {
                Flavor.DROPIN
            } else {
                Flavor.COMPONENT
            }
            val type = paymentMethodDelegate.getPaymentMethodType()
            if (type.isEmpty()) {
                throw CheckoutException("Payment method has empty or null type")
            }
            val analyticEvent = create(context, flavor, type, configuration.shopperLocale)
            dispatchEvent(context, configuration.environment, analyticEvent)
        }
    }

    protected fun notifyException(e: CheckoutException) {
        Logger.e(TAG, "notifyException - " + e.message)
        componentErrorLiveData.postValue(ComponentError(e))
    }

    /**
     * Asks the component to recreate its state and notify its observers.
     */
    @Suppress("TooGenericExceptionCaught")
    protected fun notifyStateChanged(componentState: ComponentStateT) {
        Logger.d(TAG, "notifyStateChanged")
        paymentComponentStateLiveData.postValue(componentState)
    }

    private fun assertSupported(paymentMethodType: String) {
        require(isSupported(paymentMethodType)) { "Unsupported payment method type $paymentMethodType" }
    }

    private fun isSupported(paymentMethodType: String): Boolean {
        for (supportedType in getSupportedPaymentMethodTypes()) {
            if (supportedType == paymentMethodType) {
                return true
            }
        }
        return false
    }

    fun setCreatedForDropIn() {
        isCreatedForDropIn = true
    }

    companion object {
        private val TAG = LogUtil.getTag()
    }
}
