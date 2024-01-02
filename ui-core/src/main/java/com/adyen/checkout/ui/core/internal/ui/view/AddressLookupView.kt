/*
 * Copyright (c) 2023 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by ozgur on 19/12/2023.
 */

package com.adyen.checkout.ui.core.internal.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SearchView.OnQueryTextListener
import androidx.annotation.RestrictTo
import androidx.core.view.isVisible
import com.adyen.checkout.components.core.LookupAddress
import com.adyen.checkout.components.core.internal.ui.ComponentDelegate
import com.adyen.checkout.ui.core.R
import com.adyen.checkout.ui.core.databinding.AddressLookupViewBinding
import com.adyen.checkout.ui.core.internal.ui.AddressLookupDelegate
import com.adyen.checkout.ui.core.internal.ui.ComponentView
import com.adyen.checkout.ui.core.internal.ui.model.AddressLookupState
import com.adyen.checkout.ui.core.internal.util.formatStringWithHyperlink
import com.adyen.checkout.ui.core.internal.util.setLocalizedTextFromStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@Suppress("TooManyFunctions")
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class AddressLookupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) :
    LinearLayout(
        context,
        attrs,
        defStyleAttr,
    ),
    ComponentView {

    private val binding: AddressLookupViewBinding = AddressLookupViewBinding.inflate(LayoutInflater.from(context), this)

    private lateinit var localizedContext: Context

    private lateinit var addressLookupDelegate: AddressLookupDelegate

    private var addressLookupOptionsAdapter: AddressLookupOptionsAdapter? = null

    init {
        orientation = VERTICAL
        val padding = resources.getDimension(R.dimen.standard_margin).toInt()
        setPadding(padding, padding, padding, padding)
    }

    override fun initView(delegate: ComponentDelegate, coroutineScope: CoroutineScope, localizedContext: Context) {
        require(delegate is AddressLookupDelegate) { "Unsupported delegate type" }
        addressLookupDelegate = delegate

        this.localizedContext = localizedContext
        initLocalizedStrings(localizedContext)

        observeDelegate(delegate, coroutineScope)

        initAddressLookupQuery()
        initAddressFormInput(coroutineScope)
        initAddressOptions()
        initManualEntryTextViews()
        initSubmitAddressButton()
    }

    override fun highlightValidationErrors() {
        binding.addressFormInput.highlightValidationErrors(false)
    }

    private fun observeDelegate(delegate: AddressLookupDelegate, coroutineScope: CoroutineScope) {
        delegate.addressLookupStateFlow
            .onEach { outputDataChanged(it) }
            .launchIn(coroutineScope)
    }

    private fun initLocalizedStrings(localizedContext: Context) {
        binding.addressFormInput.initLocalizedContext(localizedContext)

        binding.textViewInitialDisclaimer.setLocalizedTextFromStyle(
            styleResId = R.style.AdyenCheckout_AddressLookup_InitialDisclaimer_Title,
            localizedContext = localizedContext,
        )

        binding.textViewManualEntryInitial.text =
            localizedContext.getString(R.string.checkout_address_lookup_initial_description)
                .formatStringWithHyperlink("#")

        binding.textViewError.setLocalizedTextFromStyle(
            styleResId = R.style.AdyenCheckout_AddressLookup_Empty_Title,
            localizedContext = localizedContext,
        )

        binding.textViewManualEntryError.setLocalizedTextFromStyle(
            styleResId = R.style.AdyenCheckout_AddressLookup_Empty_Description,
            localizedContext = localizedContext,
            formatHyperLink = true,
        )
    }

    private fun initAddressLookupQuery() {
        binding.textInputLayoutAddressLookupQuerySearch.setOnQueryTextListener(
            object : OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    onQueryChanged(newText)
                    return true
                }
            },
        )
    }

    private fun initAddressFormInput(coroutineScope: CoroutineScope) {
        binding.addressFormInput.attachDelegate(addressLookupDelegate.addressDelegate, coroutineScope)
    }

    private fun initAddressOptions() {
        addressLookupOptionsAdapter = AddressLookupOptionsAdapter(::onAddressSelected)
        addressLookupOptionsAdapter?.let { adapter ->
            binding.recyclerViewAddressLookupOptions.adapter = adapter
        }
    }

    private fun initManualEntryTextViews() {
        binding.textViewManualEntryError.setOnClickListener {
            addressLookupDelegate.onManualEntryModeSelected()
        }

        binding.textViewManualEntryInitial.setOnClickListener {
            addressLookupDelegate.onManualEntryModeSelected()
        }
    }

    private fun initSubmitAddressButton() {
        binding.submitAddressButton.setOnClickListener {
            addressLookupDelegate.submitAddress()
        }
    }

    private fun outputDataChanged(addressLookupState: AddressLookupState) {
        when (addressLookupState) {
            is AddressLookupState.Error -> handleErrorState(addressLookupState)
            is AddressLookupState.Initial -> handleInitialState()
            AddressLookupState.Loading -> handleLoadingState()
            is AddressLookupState.Form -> handleFormState(addressLookupState)
            is AddressLookupState.SearchResult -> handleSearchResultState(addressLookupState)
            AddressLookupState.InvalidUI -> handleInvalidUIState()
        }
    }

    private fun handleErrorState(addressLookupState: AddressLookupState.Error) {
        binding.recyclerViewAddressLookupOptions.isVisible = false
        binding.textViewInitialDisclaimer.isVisible = false
        binding.textViewError.isVisible = true
        binding.textViewManualEntryError.isVisible = true
        binding.textViewManualEntryInitial.isVisible = false
        binding.addressFormInput.isVisible = false
        binding.progressBar.isVisible = false
        binding.submitAddressButton.isVisible = false
        binding.textViewManualEntryError.text =
            localizedContext.getString(R.string.checkout_address_lookup_empty_description, addressLookupState.query)
                .formatStringWithHyperlink("#")
    }

    private fun handleInitialState() {
        binding.recyclerViewAddressLookupOptions.isVisible = false
        binding.textViewInitialDisclaimer.isVisible = true
        binding.textViewError.isVisible = false
        binding.textViewManualEntryError.isVisible = false
        binding.textViewManualEntryInitial.isVisible = true
        binding.addressFormInput.isVisible = false
        binding.progressBar.isVisible = false
        binding.submitAddressButton.isVisible = false
    }

    private fun handleLoadingState() {
        binding.recyclerViewAddressLookupOptions.isVisible = false
        binding.textViewInitialDisclaimer.isVisible = false
        binding.textViewError.isVisible = false
        binding.textViewManualEntryError.isVisible = false
        binding.textViewManualEntryInitial.isVisible = false
        binding.addressFormInput.isVisible = false
        binding.progressBar.isVisible = true
        binding.submitAddressButton.isVisible = false
    }

    private fun handleFormState(addressLookupState: AddressLookupState.Form) {
        binding.recyclerViewAddressLookupOptions.isVisible = false
        binding.textViewInitialDisclaimer.isVisible = false
        binding.textViewError.isVisible = false
        binding.textViewManualEntryError.isVisible = false
        binding.textViewManualEntryInitial.isVisible = false
        binding.addressFormInput.isVisible = true
        binding.progressBar.isVisible = false
        binding.submitAddressButton.isVisible = true
        addressLookupDelegate.addressDelegate.updateAddressInputData {
            if (addressLookupState.selectedAddress == null) {
                this.resetAll()
            } else {
                this.set(addressLookupState.selectedAddress)
            }
        }
    }

    private fun handleSearchResultState(addressLookupState: AddressLookupState.SearchResult) {
        binding.recyclerViewAddressLookupOptions.isVisible = true
        binding.textViewInitialDisclaimer.isVisible = false
        binding.textViewError.isVisible = false
        binding.textViewManualEntryError.isVisible = false
        binding.textViewManualEntryInitial.isVisible = false
        binding.addressFormInput.isVisible = false
        binding.progressBar.isVisible = false
        binding.submitAddressButton.isVisible = false
        setAddressOptions(addressLookupState.options)
    }

    private fun handleInvalidUIState() {
        binding.recyclerViewAddressLookupOptions.isVisible = false
        binding.textViewInitialDisclaimer.isVisible = false
        binding.textViewError.isVisible = false
        binding.textViewManualEntryError.isVisible = false
        binding.textViewManualEntryInitial.isVisible = false
        binding.addressFormInput.isVisible = true
        binding.progressBar.isVisible = false
        binding.submitAddressButton.isVisible = true
        highlightValidationErrors()
    }

    private fun onQueryChanged(query: String) {
        addressLookupDelegate.onAddressQueryChanged(query)
    }

    private fun setAddressOptions(options: List<LookupOption>) {
        if (addressLookupOptionsAdapter == null) {
            initAddressOptions()
        }
        addressLookupOptionsAdapter?.submitList(options)
    }

    private fun onAddressSelected(lookupAddress: LookupAddress) {
        addressLookupDelegate.onAddressLookupCompleted(lookupAddress)
    }

    override fun getView(): View = this
}
