/*
 * Copyright (c) 2021 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by josephj on 28/5/2021.
 */

package com.adyen.checkout.redirect.internal.provider

import android.app.Application
import androidx.annotation.RestrictTo
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.adyen.checkout.components.core.ActionComponentCallback
import com.adyen.checkout.components.core.CheckoutConfiguration
import com.adyen.checkout.components.core.action.Action
import com.adyen.checkout.components.core.action.RedirectAction
import com.adyen.checkout.components.core.internal.ActionObserverRepository
import com.adyen.checkout.components.core.internal.DefaultActionComponentEventHandler
import com.adyen.checkout.components.core.internal.PaymentDataRepository
import com.adyen.checkout.components.core.internal.provider.ActionComponentProvider
import com.adyen.checkout.components.core.internal.ui.model.DropInOverrideParams
import com.adyen.checkout.components.core.internal.ui.model.GenericComponentParamsMapper
import com.adyen.checkout.components.core.internal.ui.model.SessionParams
import com.adyen.checkout.components.core.internal.util.get
import com.adyen.checkout.components.core.internal.util.viewModelFactory
import com.adyen.checkout.redirect.RedirectComponent
import com.adyen.checkout.redirect.RedirectConfiguration
import com.adyen.checkout.redirect.internal.ui.DefaultRedirectDelegate
import com.adyen.checkout.redirect.internal.ui.RedirectDelegate
import com.adyen.checkout.redirect.toCheckoutConfiguration
import com.adyen.checkout.ui.core.internal.DefaultRedirectHandler

class RedirectComponentProvider
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
constructor(
    dropInOverrideParams: DropInOverrideParams? = null,
    overrideSessionParams: SessionParams? = null,
) : ActionComponentProvider<RedirectComponent, RedirectConfiguration, RedirectDelegate> {

    private val componentParamsMapper = GenericComponentParamsMapper(dropInOverrideParams, overrideSessionParams)

    override fun get(
        savedStateRegistryOwner: SavedStateRegistryOwner,
        viewModelStoreOwner: ViewModelStoreOwner,
        lifecycleOwner: LifecycleOwner,
        application: Application,
        checkoutConfiguration: CheckoutConfiguration,
        callback: ActionComponentCallback,
        key: String?
    ): RedirectComponent {
        val redirectFactory = viewModelFactory(savedStateRegistryOwner, null) { savedStateHandle ->
            val redirectDelegate = getDelegate(checkoutConfiguration, savedStateHandle, application)
            RedirectComponent(
                delegate = redirectDelegate,
                actionComponentEventHandler = DefaultActionComponentEventHandler(callback),
            )
        }
        return ViewModelProvider(viewModelStoreOwner, redirectFactory)[key, RedirectComponent::class.java]
            .also { component ->
                component.observe(lifecycleOwner, component.actionComponentEventHandler::onActionComponentEvent)
            }
    }

    override fun getDelegate(
        checkoutConfiguration: CheckoutConfiguration,
        savedStateHandle: SavedStateHandle,
        application: Application
    ): RedirectDelegate {
        val componentParams = componentParamsMapper.mapToParams(checkoutConfiguration, null)
        val redirectHandler = DefaultRedirectHandler()
        val paymentDataRepository = PaymentDataRepository(savedStateHandle)
        return DefaultRedirectDelegate(
            observerRepository = ActionObserverRepository(),
            componentParams = componentParams,
            redirectHandler = redirectHandler,
            paymentDataRepository = paymentDataRepository,
        )
    }

    override fun get(
        savedStateRegistryOwner: SavedStateRegistryOwner,
        viewModelStoreOwner: ViewModelStoreOwner,
        lifecycleOwner: LifecycleOwner,
        application: Application,
        configuration: RedirectConfiguration,
        callback: ActionComponentCallback,
        key: String?,
    ): RedirectComponent {
        return get(
            savedStateRegistryOwner = savedStateRegistryOwner,
            viewModelStoreOwner = viewModelStoreOwner,
            lifecycleOwner = lifecycleOwner,
            application = application,
            checkoutConfiguration = configuration.toCheckoutConfiguration(),
            callback = callback,
            key = key,
        )
    }

    override val supportedActionTypes: List<String>
        get() = listOf(RedirectAction.ACTION_TYPE)

    override fun canHandleAction(action: Action): Boolean {
        return supportedActionTypes.contains(action.type)
    }

    override fun providesDetails(action: Action): Boolean {
        return true
    }
}
