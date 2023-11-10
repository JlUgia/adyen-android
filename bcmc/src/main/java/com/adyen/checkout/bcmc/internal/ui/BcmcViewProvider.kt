/*
 * Copyright (c) 2022 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by oscars on 29/9/2022.
 */

package com.adyen.checkout.bcmc.internal.ui

import android.content.Context
import com.adyen.checkout.bcmc.internal.ui.view.BcmcView
import com.adyen.checkout.ui.core.internal.ui.AmountButtonComponentViewType
import com.adyen.checkout.ui.core.internal.ui.ButtonComponentViewType
import com.adyen.checkout.ui.core.internal.ui.ComponentView
import com.adyen.checkout.ui.core.internal.ui.ComponentViewType
import com.adyen.checkout.ui.core.internal.ui.ViewProvider

internal object BcmcViewProvider : ViewProvider {

    override fun getView(
        viewType: ComponentViewType,
        context: Context,
    ): ComponentView = when (viewType) {
        BcmcComponentViewType -> BcmcView(context)
        else -> throw IllegalArgumentException("Unsupported view type")
    }
}

internal object BcmcComponentViewType : AmountButtonComponentViewType {
    override val viewProvider: ViewProvider = BcmcViewProvider
    override val buttonTextResId: Int = ButtonComponentViewType.DEFAULT_BUTTON_TEXT_RES_ID
}
