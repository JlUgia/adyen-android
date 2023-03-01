/*
 * Copyright (c) 2020 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by caiof on 17/12/2020.
 */
package com.adyen.checkout.core.exception

/**
 * Exception generated by the Checkout components to indicate an error.
 * Usually related to an implementation error.
 */
open class ComponentException(errorMessage: String, cause: Throwable? = null) :
    CheckoutException(errorMessage, cause)
