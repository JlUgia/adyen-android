/*
 * Copyright (c) 2023 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by ozgur on 24/1/2023.
 */

package com.adyen.checkout.onlinebankingjp

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.adyen.checkout.action.DefaultActionHandlingComponent
import com.adyen.checkout.action.GenericActionComponentProvider
import com.adyen.checkout.components.PaymentComponentProvider
import com.adyen.checkout.components.analytics.AnalyticsMapper
import com.adyen.checkout.components.analytics.AnalyticsSource
import com.adyen.checkout.components.analytics.DefaultAnalyticsRepository
import com.adyen.checkout.components.api.AnalyticsService
import com.adyen.checkout.components.base.ButtonComponentParamsMapper
import com.adyen.checkout.components.base.ComponentParams
import com.adyen.checkout.components.base.lifecycle.get
import com.adyen.checkout.components.base.lifecycle.viewModelFactory
import com.adyen.checkout.components.model.paymentmethods.PaymentMethod
import com.adyen.checkout.components.model.payments.request.OnlineBankingJPPaymentMethod
import com.adyen.checkout.components.repository.PaymentObserverRepository
import com.adyen.checkout.components.ui.SubmitHandler
import com.adyen.checkout.core.api.HttpClientFactory
import com.adyen.checkout.core.exception.ComponentException
import com.adyen.checkout.econtext.DefaultEContextDelegate

class OnlineBankingJPComponentProvider(
    overrideComponentParams: ComponentParams? = null,
) : PaymentComponentProvider<OnlineBankingJPComponent, OnlineBankingJPConfiguration> {

    private val componentParamsMapper = ButtonComponentParamsMapper(overrideComponentParams)

    override fun get(
        savedStateRegistryOwner: SavedStateRegistryOwner,
        viewModelStoreOwner: ViewModelStoreOwner,
        paymentMethod: PaymentMethod,
        configuration: OnlineBankingJPConfiguration,
        application: Application,
        defaultArgs: Bundle?,
        key: String?
    ): OnlineBankingJPComponent {
        assertSupported(paymentMethod)

        val genericFactory: ViewModelProvider.Factory =
            viewModelFactory(savedStateRegistryOwner, defaultArgs) { savedStateHandle ->
                val componentParams = componentParamsMapper.mapToParams(configuration)
                val httpClient = HttpClientFactory.getHttpClient(componentParams.environment)
                val analyticsService = AnalyticsService(httpClient)
                val analyticsRepository = DefaultAnalyticsRepository(
                    packageName = application.packageName,
                    locale = componentParams.shopperLocale,
                    source = AnalyticsSource.PaymentComponent(componentParams.isCreatedByDropIn, paymentMethod),
                    analyticsService = analyticsService,
                    analyticsMapper = AnalyticsMapper(),
                )
                val eContextDelegate = DefaultEContextDelegate(
                    observerRepository = PaymentObserverRepository(),
                    componentParams = componentParams,
                    paymentMethod = paymentMethod,
                    analyticsRepository = analyticsRepository,
                    submitHandler = SubmitHandler()
                ) { OnlineBankingJPPaymentMethod() }

                val genericActionDelegate = GenericActionComponentProvider(componentParams).getDelegate(
                    configuration = configuration.genericActionConfiguration,
                    savedStateHandle = savedStateHandle,
                    application = application,
                )

                OnlineBankingJPComponent(
                    delegate = eContextDelegate,
                    genericActionDelegate = genericActionDelegate,
                    actionHandlingComponent = DefaultActionHandlingComponent(genericActionDelegate, eContextDelegate),
                )
            }
        return ViewModelProvider(viewModelStoreOwner, genericFactory)[key, OnlineBankingJPComponent::class.java]
    }

    private fun assertSupported(paymentMethod: PaymentMethod) {
        if (!isPaymentMethodSupported(paymentMethod)) {
            throw ComponentException("Unsupported payment method ${paymentMethod.type}")
        }
    }

    override fun isPaymentMethodSupported(paymentMethod: PaymentMethod): Boolean {
        return OnlineBankingJPComponent.PAYMENT_METHOD_TYPES.contains(paymentMethod.type)
    }
}
