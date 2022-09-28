/*
 * Copyright (c) 2022 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by oscars on 28/9/2022.
 */

package com.adyen.checkout.core.exception

/**
 * This exception indicates that the payment flow was manually cancelled by the user.
 */
class CancellationException(errorMessage: String) : CheckoutException(errorMessage)
