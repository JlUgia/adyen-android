/*
 * Copyright (c) 2023 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by atef on 31/3/2023.
 */

package com.adyen.checkout.boleto.internal.ui.model

import com.adyen.checkout.components.core.Amount
import com.adyen.checkout.components.core.internal.ui.model.ButtonParams
import com.adyen.checkout.components.core.internal.ui.model.ComponentParams
import com.adyen.checkout.core.Environment
import com.adyen.checkout.ui.core.internal.ui.model.AddressParams
import kotlinx.parcelize.Parcelize
import java.util.Locale

@Parcelize
internal data class BoletoComponentParams(
    override val isSubmitButtonVisible: Boolean,
    override val shopperLocale: Locale,
    override val environment: Environment,
    override val clientKey: String,
    override val isAnalyticsEnabled: Boolean,
    override val isCreatedByDropIn: Boolean,
    override val amount: Amount,
    val addressParams: AddressParams,
    val isEmailVisible: Boolean,
) : ComponentParams, ButtonParams
