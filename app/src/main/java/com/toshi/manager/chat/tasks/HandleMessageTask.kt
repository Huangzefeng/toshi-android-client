/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.manager.chat.tasks

import android.os.Looper
import com.toshi.crypto.HDWallet
import com.toshi.crypto.signal.model.DecryptedSignalMessage
import com.toshi.extensions.getTimeoutSingle
import com.toshi.manager.chat.SofaMessageSender
import com.toshi.manager.store.ConversationStore
import com.toshi.model.local.Conversation
import com.toshi.model.local.Group
import com.toshi.model.local.IncomingMessage
import com.toshi.model.local.Recipient
import com.toshi.model.local.SendState
import com.toshi.model.local.User
import com.toshi.model.sofa.Init
import com.toshi.model.sofa.SofaAdapters
import com.toshi.model.sofa.SofaMessage
import com.toshi.model.sofa.SofaType
import com.toshi.util.logging.LogUtil
import com.toshi.view.BaseApplication
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import rx.Observable
import rx.Single
import rx.schedulers.Schedulers
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class HandleMessageTask(
        private val messageReceiver: SignalServiceMessageReceiver,
        private val conversationStore: ConversationStore,
        private val hdWalletObservable: Observable<HDWallet>,
        private val messageSender: SofaMessageSender
) {
    private val taskProcessAttachments by lazy { ProcessAttachmentsTask(messageReceiver) }
    private val recipientManager by lazy { BaseApplication.get().recipientManager }
    private val chatManager by lazy { BaseApplication.get().chatManager }

    fun run(messageSource: String, dataMessage: SignalServiceDataMessage): IncomingMessage? {
        val signalGroup = dataMessage.groupInfo
        val messageBody = dataMessage.body
        val attachments = dataMessage.attachments
        val decryptedMessage = DecryptedSignalMessage(messageSource, messageBody.get(), attachments, signalGroup)
        return saveIncomingMessageToDatabase(decryptedMessage)
    }

    private fun saveIncomingMessageToDatabase(signalMessage: DecryptedSignalMessage?): IncomingMessage? {
        if (signalMessage?.isValid != true) {
            LogUtil.w("Attempt to save invalid DecryptedSignalMessage to database.")
            return null
        }

        taskProcessAttachments.run(signalMessage)

        try {
            val user = getUser(signalMessage.source)
            return saveIncomingMessageToDatabase(user, signalMessage)
        } catch (ex: Exception) {
            when (ex) {
                is IllegalStateException, is TimeoutException -> LogUtil.exception("Error saving message to database $ex")
                else -> throw ex
            }
        }

        return null
    }

    private fun getUser(toshiId: String) = recipientManager
                .getUserFromToshiId(toshiId)
                .timeout(30, TimeUnit.SECONDS)
                .toBlocking()
                .value() ?: throw IllegalStateException("Failure to get user")

    private fun saveIncomingMessageToDatabase(sender: User, signalMessage: DecryptedSignalMessage): IncomingMessage? {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IllegalStateException("Running a blocking DB call on main thread!")

        val remoteMessage = SofaMessage()
                .makeNew(sender, signalMessage.body)
                .setAttachmentFilePath(signalMessage.attachmentFilePath)
                .setSendState(SendState.STATE_RECEIVED)

        val recipient = generateRecipientFromSignalMessage(sender, signalMessage)
                .timeout(30, TimeUnit.SECONDS)
                .toBlocking()
                .value() ?: throw IllegalStateException("Failure to generate Recipient")

        val conversation = saveIncomingMessageToDatabase(sender, remoteMessage, recipient) ?: return null
        return IncomingMessage(remoteMessage, recipient, conversation)
    }

    private fun saveIncomingMessageToDatabase(sender: User, remoteMessage: SofaMessage, senderRecipient: Recipient): Conversation? {
        return when {
            remoteMessage.type == SofaType.INIT_REQUEST -> respondToInitRequest(sender, remoteMessage)
            remoteMessage.type == SofaType.PAYMENT -> fetchAndCacheIncomingPaymentSender(sender)
            remoteMessage.type == SofaType.PAYMENT_REQUEST -> savePaymentRequestAndShowNotification(remoteMessage, senderRecipient)
            else -> saveMessageToDatabase(remoteMessage, senderRecipient)
        }
    }

    private fun respondToInitRequest(sender: User, remoteMessage: SofaMessage): Conversation? {
        // Don't render initRequests, but respond to them.
        try {
            val initRequest = SofaAdapters.get().initRequestFrom(remoteMessage.payload)
            val initMessage = Init().construct(initRequest, getWallet().paymentAddress)
            val payload = SofaAdapters.get().toJson(initMessage)
            val newSofaMessage = SofaMessage().makeNew(sender, payload)
            val recipient = Recipient(sender)
            chatManager.sendMessage(recipient, newSofaMessage)
        } catch (e: IOException) {
            LogUtil.exception("Failed to respond to incoming init request. $e")
        } catch (e: TimeoutException) {
            LogUtil.exception("Failed to respond to incoming init request. $e")
        }
        return null
    }

    private fun getWallet() = hdWalletObservable.getTimeoutSingle().toBlocking().value()

    private fun fetchAndCacheIncomingPaymentSender(sender: User): Conversation? {
        // Don't render incoming SOFA::Payments, but ensure we have the sender cached.
        recipientManager.getUserFromToshiId(sender.toshiId)
        return null
    }

    private fun savePaymentRequestAndShowNotification(remoteMessage: SofaMessage, senderRecipient: Recipient): Conversation {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IllegalStateException("Running a blocking DB call on main thread!")
        val updatedPayload = generatePayloadWithLocalAmountEmbedded(remoteMessage)
                .timeout(30, TimeUnit.SECONDS)
                .toBlocking()
                .value()
        return conversationStore
                .saveNewMessageSingle(senderRecipient, remoteMessage.setPayload(updatedPayload))
                .timeout(30, TimeUnit.SECONDS)
                .toBlocking()
                .value()
    }

    private fun saveMessageToDatabase(remoteMessage: SofaMessage, senderRecipient: Recipient): Conversation {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IllegalStateException("Running a blocking DB call on main thread!")
        return conversationStore
                .saveNewMessageSingle(senderRecipient, remoteMessage)
                .timeout(30, TimeUnit.SECONDS)
                .toBlocking()
                .value()
    }

    private fun generateRecipientFromSignalMessage(sender: User, signalMessage: DecryptedSignalMessage): Single<Recipient> {
        return if (!signalMessage.isGroup) {
            Single.just(Recipient(sender))
        } else {
            Single.just(signalMessage.group)
                    .flatMap { Group.fromSignalGroup(it) }
                    .map { handleGetGroupFromSignalGroup(it) }
                    .doOnError { requestGroupInfo(sender, signalMessage) }
                    .onErrorReturn { Recipient(Group.emptyGroup(signalMessage.group.groupId)) }
        }
    }

    private fun requestGroupInfo(sender: User, signalMessage: DecryptedSignalMessage) {
        messageSender.requestGroupInfo(sender, signalMessage.group)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        {},
                        { LogUtil.exception("Error requesting group info") }
                )
    }

    private fun handleGetGroupFromSignalGroup(group: Group?): Recipient {
        return group?.let { Recipient(group) }
                ?: throw IllegalStateException("No group found with that ID")
    }

    private fun generatePayloadWithLocalAmountEmbedded(remoteMessage: SofaMessage): Single<String> {
        try {
            val request = SofaAdapters.get().txRequestFrom(remoteMessage.payload)
            return request
                    .generateLocalPrice()
                    .map { updatedPaymentRequest -> SofaAdapters.get().toJson(updatedPaymentRequest) }
        } catch (ex: IOException) {
            LogUtil.exception("Unable to embed local price. $ex")
        }

        return Single.just(remoteMessage.payloadWithHeaders)
    }
}