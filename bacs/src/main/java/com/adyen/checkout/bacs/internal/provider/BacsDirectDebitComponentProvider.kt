/*
 * Copyright (c) 2022 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by caiof on 12/4/2022.
 */

package com.adyen.checkout.bacs.internal.provider

import android.app.Application
import androidx.annotation.RestrictTo
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.adyen.checkout.action.internal.DefaultActionHandlingComponent
import com.adyen.checkout.action.internal.provider.GenericActionComponentProvider
import com.adyen.checkout.bacs.BacsDirectDebitComponent
import com.adyen.checkout.bacs.BacsDirectDebitComponentState
import com.adyen.checkout.bacs.BacsDirectDebitConfiguration
import com.adyen.checkout.bacs.internal.ui.DefaultBacsDirectDebitDelegate
import com.adyen.checkout.components.PaymentComponentProvider
import com.adyen.checkout.components.analytics.AnalyticsMapper
import com.adyen.checkout.components.analytics.AnalyticsSource
import com.adyen.checkout.components.analytics.DefaultAnalyticsRepository
import com.adyen.checkout.components.api.AnalyticsService
import com.adyen.checkout.components.base.ButtonComponentParamsMapper
import com.adyen.checkout.components.base.ComponentCallback
import com.adyen.checkout.components.base.ComponentParams
import com.adyen.checkout.components.base.DefaultComponentEventHandler
import com.adyen.checkout.components.base.lifecycle.get
import com.adyen.checkout.components.base.lifecycle.viewModelFactory
import com.adyen.checkout.components.model.paymentmethods.PaymentMethod
import com.adyen.checkout.components.model.payments.request.Order
import com.adyen.checkout.components.repository.PaymentObserverRepository
import com.adyen.checkout.components.ui.SubmitHandler
import com.adyen.checkout.core.api.HttpClientFactory
import com.adyen.checkout.core.exception.ComponentException
import com.adyen.checkout.sessions.CheckoutSession
import com.adyen.checkout.sessions.SessionComponentCallback
import com.adyen.checkout.sessions.SessionComponentEventHandler
import com.adyen.checkout.sessions.SessionSavedStateHandleContainer
import com.adyen.checkout.sessions.api.SessionService
import com.adyen.checkout.sessions.interactor.SessionInteractor
import com.adyen.checkout.sessions.model.setup.SessionSetupConfiguration
import com.adyen.checkout.sessions.provider.SessionPaymentComponentProvider
import com.adyen.checkout.sessions.repository.SessionRepository

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class BacsDirectDebitComponentProvider(
    private val overrideComponentParams: ComponentParams? = null,
    private val sessionSetupConfiguration: SessionSetupConfiguration? = null
) :
    PaymentComponentProvider<
        BacsDirectDebitComponent,
        BacsDirectDebitConfiguration,
        BacsDirectDebitComponentState>,
    SessionPaymentComponentProvider<
        BacsDirectDebitComponent,
        BacsDirectDebitConfiguration,
        BacsDirectDebitComponentState> {

    private val componentParamsMapper = ButtonComponentParamsMapper()

    override fun get(
        savedStateRegistryOwner: SavedStateRegistryOwner,
        viewModelStoreOwner: ViewModelStoreOwner,
        lifecycleOwner: LifecycleOwner,
        paymentMethod: PaymentMethod,
        configuration: BacsDirectDebitConfiguration,
        application: Application,
        componentCallback: ComponentCallback<BacsDirectDebitComponentState>,
        order: Order?,
        key: String?
    ): BacsDirectDebitComponent {
        assertSupported(paymentMethod)

        val genericFactory = viewModelFactory(savedStateRegistryOwner, null) { savedStateHandle ->
            val componentParams = componentParamsMapper.mapToParams(configuration, overrideComponentParams)
            val httpClient = HttpClientFactory.getHttpClient(componentParams.environment)
            val analyticsService = AnalyticsService(httpClient)
            val analyticsRepository = DefaultAnalyticsRepository(
                packageName = application.packageName,
                locale = componentParams.shopperLocale,
                source = AnalyticsSource.PaymentComponent(componentParams.isCreatedByDropIn, paymentMethod),
                analyticsService = analyticsService,
                analyticsMapper = AnalyticsMapper(),
            )

            val bacsDelegate = DefaultBacsDirectDebitDelegate(
                observerRepository = PaymentObserverRepository(),
                componentParams = componentParams,
                paymentMethod = paymentMethod,
                order = order,
                analyticsRepository = analyticsRepository,
                submitHandler = SubmitHandler(savedStateHandle),
            )

            val genericActionDelegate = GenericActionComponentProvider(componentParams).getDelegate(
                configuration = configuration.genericActionConfiguration,
                savedStateHandle = savedStateHandle,
                application = application,
            )

            BacsDirectDebitComponent(
                bacsDelegate = bacsDelegate,
                genericActionDelegate = genericActionDelegate,
                actionHandlingComponent = DefaultActionHandlingComponent(genericActionDelegate, bacsDelegate),
                componentEventHandler = DefaultComponentEventHandler()
            )
        }

        return ViewModelProvider(viewModelStoreOwner, genericFactory)[key, BacsDirectDebitComponent::class.java]
            .also { component ->
                component.observe(lifecycleOwner) {
                    component.componentEventHandler.onPaymentComponentEvent(it, componentCallback)
                }
            }
    }

    override fun get(
        savedStateRegistryOwner: SavedStateRegistryOwner,
        viewModelStoreOwner: ViewModelStoreOwner,
        lifecycleOwner: LifecycleOwner,
        checkoutSession: CheckoutSession,
        paymentMethod: PaymentMethod,
        configuration: BacsDirectDebitConfiguration,
        application: Application,
        componentCallback: SessionComponentCallback<BacsDirectDebitComponentState>,
        key: String?
    ): BacsDirectDebitComponent {
        assertSupported(paymentMethod)

        val genericFactory = viewModelFactory(savedStateRegistryOwner, null) { savedStateHandle ->
            val componentParams = componentParamsMapper.mapToParams(configuration, overrideComponentParams)
            val httpClient = HttpClientFactory.getHttpClient(componentParams.environment)
            val analyticsService = AnalyticsService(httpClient)
            val analyticsRepository = DefaultAnalyticsRepository(
                packageName = application.packageName,
                locale = componentParams.shopperLocale,
                source = AnalyticsSource.PaymentComponent(componentParams.isCreatedByDropIn, paymentMethod),
                analyticsService = analyticsService,
                analyticsMapper = AnalyticsMapper(),
            )

            val bacsDelegate = DefaultBacsDirectDebitDelegate(
                observerRepository = PaymentObserverRepository(),
                componentParams = componentParams,
                paymentMethod = paymentMethod,
                order = checkoutSession.order,
                analyticsRepository = analyticsRepository,
                submitHandler = SubmitHandler(savedStateHandle),
            )

            val genericActionDelegate = GenericActionComponentProvider(componentParams).getDelegate(
                configuration = configuration.genericActionConfiguration,
                savedStateHandle = savedStateHandle,
                application = application,
            )

            val sessionSavedStateHandleContainer = SessionSavedStateHandleContainer(
                savedStateHandle = savedStateHandle,
                checkoutSession = checkoutSession,
            )
            val sessionInteractor = SessionInteractor(
                sessionRepository = SessionRepository(
                    sessionService = SessionService(httpClient),
                    clientKey = componentParams.clientKey,
                ),
                sessionModel = sessionSavedStateHandleContainer.getSessionModel(),
                isFlowTakenOver = sessionSavedStateHandleContainer.isFlowTakenOver ?: false
            )
            val sessionComponentEventHandler = SessionComponentEventHandler<BacsDirectDebitComponentState>(
                sessionInteractor = sessionInteractor,
                sessionSavedStateHandleContainer = sessionSavedStateHandleContainer
            )

            BacsDirectDebitComponent(
                bacsDelegate = bacsDelegate,
                genericActionDelegate = genericActionDelegate,
                actionHandlingComponent = DefaultActionHandlingComponent(genericActionDelegate, bacsDelegate),
                componentEventHandler = sessionComponentEventHandler
            )
        }
        return ViewModelProvider(viewModelStoreOwner, genericFactory)[key, BacsDirectDebitComponent::class.java]
            .also { component ->
                component.observe(lifecycleOwner) {
                    component.componentEventHandler.onPaymentComponentEvent(it, componentCallback)
                }
            }
    }

    private fun assertSupported(paymentMethod: PaymentMethod) {
        if (!isPaymentMethodSupported(paymentMethod)) {
            throw ComponentException("Unsupported payment method ${paymentMethod.type}")
        }
    }

    override fun isPaymentMethodSupported(paymentMethod: PaymentMethod): Boolean {
        return BacsDirectDebitComponent.PAYMENT_METHOD_TYPES.contains(paymentMethod.type)
    }
}
