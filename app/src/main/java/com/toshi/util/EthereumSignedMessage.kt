package com.toshi.util

import com.toshi.crypto.HDWallet
import com.toshi.crypto.util.TypeConverter
import com.toshi.model.local.PersonalMessage
import com.toshi.view.BaseApplication
import rx.Single
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import java.io.UnsupportedEncodingException

class EthereumSignedMessage(private val id: String, private val personalMessage: PersonalMessage) {

    @Throws(UnsupportedEncodingException::class)
    fun signPersonalMessage(): Single<String> {
        val hexEncodedValue = getEthereumSignedMessage(personalMessage)
        return signPersonalMessage(hexEncodedValue)
    }

    @Throws(UnsupportedEncodingException::class)
    fun signMessage(): Single<String> {
        return signMessage(TypeConverter.toJsonHex(personalMessage.getDataFromMessageAsBytes()))
    }

    @Throws(UnsupportedEncodingException::class)
    private fun getEthereumSignedMessage(personalMessage: PersonalMessage): String {
        val resultArray = ("\u0019Ethereum Signed Message:\n"
                + personalMessage.getDataFromMessageAsBytes().size
                + personalMessage.getDataFromMessageAsString()).toByteArray(charset("UTF-8"))
        return TypeConverter.toJsonHex(resultArray)
    }

    private fun signPersonalMessage(unsignedValue: String): Single<String> {
        return getWallet()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap { it.signTransaction(unsignedValue, true) }
                .map { mapToMethodCall(it) }
                .subscribeOn(Schedulers.io())
    }

    private fun signMessage(unsignedValue: String): Single<String> {
        return getWallet()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap { it.signTransaction(unsignedValue, false) }
                .map { mapToMethodCall(it) }
                .subscribeOn(Schedulers.io())
    }

    private fun mapToMethodCall(signedValue: String) = String.format(
            "SOFA.callback(\"%s\",\"%s\")",
            id,
            String.format("{\\\"result\\\":\\\"%s\\\"}", signedValue)
    )

    private fun getWallet(): Single<HDWallet> {
        return BaseApplication
                .get()
                .toshiManager
                .getWallet()
    }
}