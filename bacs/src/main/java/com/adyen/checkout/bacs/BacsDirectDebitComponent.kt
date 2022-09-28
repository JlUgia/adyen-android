/*
 * Copyright (c) 2021 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by ozgur on 2/11/2021.
 */

package com.adyen.checkout.bacs

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.adyen.checkout.bacs.BacsDirectDebitComponent.Companion.PROVIDER
import com.adyen.checkout.components.PaymentComponentProvider
import com.adyen.checkout.components.base.BasePaymentComponent
import com.adyen.checkout.components.ui.ViewProvidingComponent
import com.adyen.checkout.components.ui.view.ComponentViewType
import com.adyen.checkout.components.util.PaymentMethodTypes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Component should not be instantiated directly. Instead use the [PROVIDER] object.
 */
class BacsDirectDebitComponent(
    savedStateHandle: SavedStateHandle,
    override val delegate: BacsDirectDebitDelegate,
    configuration: BacsDirectDebitConfiguration
) : BasePaymentComponent<BacsDirectDebitConfiguration, BacsDirectDebitInputData, BacsDirectDebitOutputData,
    BacsDirectDebitComponentState>(savedStateHandle, delegate, configuration),
    ViewProvidingComponent {

    override val viewFlow: Flow<ComponentViewType?> get() = delegate.viewFlow

    override val inputData: BacsDirectDebitInputData
        get() = delegate.inputData

    init {
        delegate.outputDataFlow
            .filterNotNull()
            .onEach { notifyOutputDataChanged(it) }
            .launchIn(viewModelScope)

        delegate.componentStateFlow
            .filterNotNull()
            .onEach { notifyStateChanged(it) }
            .launchIn(viewModelScope)
    }

    override fun getSupportedPaymentMethodTypes(): Array<String> = PAYMENT_METHOD_TYPES

    override fun onInputDataChanged(inputData: BacsDirectDebitInputData) {
        delegate.onInputDataChanged(inputData)
    }

    /**
     * Sets the displayed BACS view as the final confirmation view.
     * Should only be called if the form is valid.
     *
     * @return whether the view was successfully changed.
     */
    fun setConfirmationMode(): Boolean {
        return delegate.setMode(BacsDirectDebitMode.CONFIRMATION)
    }

    /**
     * Resets the displayed BACS view back to the form input view.
     *
     * @return whether the view was successfully changed.
     */
    fun setInputMode(): Boolean {
        return delegate.setMode(BacsDirectDebitMode.INPUT)
    }

    companion object {
        @JvmField
        val PROVIDER: PaymentComponentProvider<BacsDirectDebitComponent, BacsDirectDebitConfiguration> =
            BacsComponentProvider()

        @JvmField
        val PAYMENT_METHOD_TYPES = arrayOf(PaymentMethodTypes.BACS)
    }
}
