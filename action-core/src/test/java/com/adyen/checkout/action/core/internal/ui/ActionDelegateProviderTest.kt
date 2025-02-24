package com.adyen.checkout.action.core.internal.ui

import android.app.Application
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import com.adyen.checkout.adyen3ds2.internal.ui.Adyen3DS2Delegate
import com.adyen.checkout.await.internal.ui.AwaitDelegate
import com.adyen.checkout.components.core.CheckoutConfiguration
import com.adyen.checkout.components.core.action.Action
import com.adyen.checkout.components.core.action.AwaitAction
import com.adyen.checkout.components.core.action.QrCodeAction
import com.adyen.checkout.components.core.action.RedirectAction
import com.adyen.checkout.components.core.action.SdkAction
import com.adyen.checkout.components.core.action.Threeds2Action
import com.adyen.checkout.components.core.action.Threeds2ChallengeAction
import com.adyen.checkout.components.core.action.Threeds2FingerprintAction
import com.adyen.checkout.components.core.action.VoucherAction
import com.adyen.checkout.components.core.action.WeChatPaySdkData
import com.adyen.checkout.components.core.internal.ui.ActionDelegate
import com.adyen.checkout.core.Environment
import com.adyen.checkout.core.exception.CheckoutException
import com.adyen.checkout.qrcode.internal.ui.QRCodeDelegate
import com.adyen.checkout.redirect.internal.ui.RedirectDelegate
import com.adyen.checkout.voucher.internal.ui.VoucherDelegate
import com.adyen.checkout.wechatpay.internal.ui.WeChatDelegate
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Locale

internal class ActionDelegateProviderTest {

    private lateinit var actionDelegateProvider: ActionDelegateProvider

    @BeforeEach
    fun setup() {
        actionDelegateProvider = ActionDelegateProvider(null, null)
    }

    @ParameterizedTest
    @MethodSource("actionSource")
    fun `when action is of certain type, then related delegate is provided`(
        action: Action,
        expectedDelegate: Class<ActionDelegate>,
    ) {
        val configuration = CheckoutConfiguration(Locale.US, Environment.TEST, "")

        val delegate = actionDelegateProvider.getDelegate(action, configuration, SavedStateHandle(), Application())

        assertInstanceOf(expectedDelegate, delegate)
    }

    @Test
    fun `when unknown action is used, then an error will be thrown`() {
        val configuration = CheckoutConfiguration(Locale.US, Environment.TEST, "")

        assertThrows<CheckoutException> {
            actionDelegateProvider.getDelegate(UnknownAction(), configuration, SavedStateHandle(), Application())
        }
    }

    companion object {

        @JvmStatic
        fun actionSource() = listOf(
            arguments(AwaitAction(), AwaitDelegate::class.java),
            arguments(QrCodeAction(), QRCodeDelegate::class.java),
            arguments(RedirectAction(), RedirectDelegate::class.java),
            arguments(Threeds2Action(), Adyen3DS2Delegate::class.java),
            arguments(Threeds2ChallengeAction(), Adyen3DS2Delegate::class.java),
            arguments(Threeds2FingerprintAction(), Adyen3DS2Delegate::class.java),
            arguments(VoucherAction(), VoucherDelegate::class.java),
            arguments(SdkAction<WeChatPaySdkData>(), WeChatDelegate::class.java),
        )
    }

    private class UnknownAction(
        override var type: String? = null,
        override var paymentMethodType: String? = null,
        override var paymentData: String? = null,
    ) : Action() {
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }
}
