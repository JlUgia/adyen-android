/*
 * Copyright (c) 2022 Adyen N.V.
 *
 * This file is open source and available under the MIT license. See the LICENSE file for more info.
 *
 * Created by ozgur on 9/1/2023.
 */

package com.adyen.checkout.sessions.core.internal.data.model

import android.os.Parcelable
import androidx.annotation.RestrictTo
import com.adyen.checkout.components.core.Amount
import com.adyen.checkout.components.core.internal.ui.model.SessionParams
import com.adyen.checkout.sessions.core.SessionModel
import com.adyen.checkout.sessions.core.SessionSetupConfiguration
import com.adyen.checkout.sessions.core.SessionSetupResponse
import com.adyen.checkout.sessions.core.internal.ui.model.SessionParamsFactory
import kotlinx.parcelize.Parcelize

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@Parcelize
data class SessionDetails(
    val id: String,
    val sessionData: String,
    val amount: Amount?,
    val expiresAt: String,
    val returnUrl: String?,
    val sessionSetupConfiguration: SessionSetupConfiguration?
) : Parcelable

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun SessionSetupResponse.mapToDetails(): SessionDetails {
    return SessionDetails(
        id = id,
        sessionData = sessionData,
        amount = amount,
        expiresAt = expiresAt,
        returnUrl = returnUrl,
        sessionSetupConfiguration = configuration
    )
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun SessionDetails.mapToModel(): SessionModel {
    return SessionModel(
        id = id,
        sessionData = sessionData,
    )
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun SessionDetails.mapToParams(amount: Amount?): SessionParams {
    // TODO: Once Backend provides the correct amount in the SessionSetupResponse use that in SessionDetails
    return SessionParamsFactory.create(this.copy(amount = amount))
}
