/*
 * Copyright (c) 2021 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by josephj on 18/5/2021.
 */

package com.adyen.checkout.components.core.internal

import android.app.Application
import androidx.annotation.RestrictTo
import com.adyen.checkout.components.core.CheckoutConfiguration
import com.adyen.checkout.components.core.ComponentAvailableCallback
import com.adyen.checkout.components.core.PaymentMethod

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class AlwaysAvailablePaymentMethod : PaymentMethodAvailabilityCheck {

    override fun isAvailable(
        application: Application,
        paymentMethod: PaymentMethod,
        checkoutConfiguration: CheckoutConfiguration,
        callback: ComponentAvailableCallback
    ) {
        callback.onAvailabilityResult(true, paymentMethod)
    }
}
