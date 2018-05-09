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

package com.toshi.presenter.chat

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import com.toshi.R
import com.toshi.extensions.isGroupId
import com.toshi.manager.messageQueue.AsyncOutgoingMessageQueue
import com.toshi.manager.model.PaymentTask
import com.toshi.manager.model.ResendToshiPaymentTask
import com.toshi.manager.model.ToshiPaymentTask
import com.toshi.model.local.Conversation
import com.toshi.model.local.Group
import com.toshi.model.local.Recipient
import com.toshi.model.local.User
import com.toshi.model.local.network.Networks
import com.toshi.model.sofa.Control
import com.toshi.model.sofa.PaymentRequest
import com.toshi.model.sofa.SofaMessage
import com.toshi.util.FileUtil
import com.toshi.util.SingleLiveEvent
import com.toshi.util.logging.LogUtil
import com.toshi.view.BaseApplication
import com.toshi.view.notification.ChatNotificationManager
import rx.Single
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription
import java.io.File

class ChatViewModel(private val threadId: String) : ViewModel() {

    private val subscriptions by lazy { CompositeSubscription() }
    private val recipientManager by lazy { BaseApplication.get().recipientManager }
    private val userManager by lazy { BaseApplication.get().userManager }
    private val transactionManager by lazy { BaseApplication.get().transactionManager }
    private val toshiManager by lazy { BaseApplication.get().toshiManager }
    private val chatManager by lazy { BaseApplication.get().chatManager }
    private val chatMessageQueue by lazy { ChatMessageQueue(AsyncOutgoingMessageQueue()) }

    var capturedImageName: String? = null
    val recipient by lazy { MutableLiveData<Recipient>() }
    val conversation by lazy { SingleLiveEvent<Conversation>() }
    val recipientError by lazy { SingleLiveEvent<Int>() }
    val confirmPayment by lazy { SingleLiveEvent<ConfirmPaymentInfo>() }
    val resendPayment by lazy { SingleLiveEvent<ResendPaymentInfo>() }
    val respondToPaymentRequest by lazy { SingleLiveEvent<SofaMessage>() }
    val acceptConversation by lazy { SingleLiveEvent<Unit>() }
    val declineConversation by lazy { SingleLiveEvent<Unit>() }
    val updateMessage by lazy { SingleLiveEvent<SofaMessage>() }
    val updateConversation by lazy { SingleLiveEvent<Conversation>() }
    val newMessage by lazy { SingleLiveEvent<SofaMessage>() }
    val deleteMessage by lazy { SingleLiveEvent<SofaMessage>() }
    val deleteError by lazy { SingleLiveEvent<Int>() }
    val error by lazy { SingleLiveEvent<Int>() }
    val viewProfileWithId by lazy { SingleLiveEvent<String>() }
    val isLoading by lazy { MutableLiveData<Boolean>() }

    init {
        if (threadId.isGroupId()) loadGroupRecipient(threadId)
        else loadUserRecipient(threadId)
    }

    private fun loadGroupRecipient(groupId: String) {
        val sub = Group.fromId(groupId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { isLoading.value = true }
                .doOnError { isLoading.value = false }
                .doOnSuccess { isLoading.value = false }
                .subscribe(
                        { handleGroupLoaded(it) },
                        { handleRecipientLoadFailed() }
                )

        subscriptions.add(sub)
    }

    private fun loadUserRecipient(toshiId: String) {
        val sub = recipientManager
                .getUserFromToshiId(toshiId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { isLoading.value = true }
                .doOnError { isLoading.value = false }
                .doOnSuccess { isLoading.value = false }
                .map { Recipient(it) }
                .subscribe(
                        { handleRecipientLoaded(it) },
                        { handleRecipientLoadFailed() }
                )

        subscriptions.add(sub)
    }

    private fun handleGroupLoaded(group: Group?) = group
            ?.let { handleRecipientLoaded(Recipient(it)) }
            ?: handleRecipientLoadFailed()

    private fun handleRecipientLoaded(recipient: Recipient?) = recipient
            ?.let { init(it) }
            ?: handleRecipientLoadFailed()

    private fun handleRecipientLoadFailed() {
        recipientError.value = R.string.error__app_loading
    }

    private fun init(recipient: Recipient) {
        this.recipient.value = recipient
        chatMessageQueue.init(recipient)
        initPendingTransactionsObservable(recipient)
        initChatMessageStore(recipient)
    }

    private fun initPendingTransactionsObservable(recipient: Recipient) {
        // Todo - handle groups
        if (recipient.isGroup) return

        val subscription = PendingTransactionsObservable()
                .init(recipient.user)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map { it.sofaMessage }
                .subscribe(
                        { updateMessage.value = it },
                        { LogUtil.exception(it) }
                )

        subscriptions.add(subscription)
    }

    private fun initChatMessageStore(recipient: Recipient) {
        ChatNotificationManager.suppressNotificationsForConversation(recipient.threadId)
        val conversationObservables = chatManager.registerForConversationChanges(recipient.threadId)

        val chatSub = conversationObservables
                .newMessageSubject
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { newMessage.value = it },
                        { LogUtil.exception(it) }
                )

        val updateSub = conversationObservables
                .updateMessageSubject
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { updateMessage.value = it },
                        { LogUtil.exception(it) }
                )

        val updateConversationSub = conversationObservables
                .conversationUpdatedSubject
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext { chatMessageQueue.updateRecipient(it.recipient) }
                .doOnNext { this.recipient.value = it.recipient }
                .subscribe(
                        { updateConversation.value = it },
                        { LogUtil.exception(it) }
                )

        val deleteSub = chatManager
                .registerForDeletedMessages(recipient.threadId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { deleteMessage.value = it },
                        { LogUtil.exception(it) }
                )

        subscriptions.addAll(
                chatSub,
                updateSub,
                updateConversationSub,
                deleteSub
        )
    }

    fun loadConversation() {
        val sub = getRecipient()
                .flatMap { chatManager.loadConversationAndResetUnreadCounter(threadId) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { handleConversation(it) },
                        { LogUtil.exception(it) }
                )

        subscriptions.add(sub)
    }

    private fun handleConversation(conversation: Conversation) {
        this.conversation.value = conversation
        val isConversationEmpty = conversation.allMessages?.isEmpty() ?: true
        if (isConversationEmpty) tryInitAppConversation(conversation.recipient)
    }

    private fun tryInitAppConversation(recipient: Recipient) {
        if (recipient.isGroup || !recipient.user.isBot) return
        val localUser = getCurrentLocalUser() ?: return
        chatManager.sendInitMessage(localUser, recipient)
    }

    fun sendPaymentWithValue(value: String) {
        val sub = getRecipient()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map { ConfirmPaymentInfo(it.user, value) }
                .subscribe(
                        { confirmPayment.value = it },
                        { LogUtil.exception(it) }
                )

        subscriptions.add(sub)
    }

    fun sendPaymentRequestWithValue(value: String) {
        val sub = toshiManager
                .getWallet()
                .flatMap { it.getPaymentAddressAsync() }
                .flatMap { generateLocalPrice(value, it) }
                .observeOn(Schedulers.io())
                .subscribe(
                        { sendPaymentRequest(it) },
                        { LogUtil.exception(it) }
                )

        subscriptions.add(sub)
    }

    private fun generateLocalPrice(value: String, paymentAddress: String) = PaymentRequest()
            .setDestinationAddress(paymentAddress)
            .setValue(value)
            .generateLocalPrice()

    private fun sendPaymentRequest(paymentRequest: PaymentRequest) {
        val localUser = getCurrentLocalUser()
        localUser?.let {
            chatMessageQueue.addPaymentRequestToQueue(paymentRequest, it)
        } ?: run {
            error.value = R.string.sending_payment_request_error
            LogUtil.w("User is null when sending payment request")
        }
    }

    fun sendMessage(userInput: String) {
        val localUser = getCurrentLocalUser()
        localUser?.let {
            chatMessageQueue.addMessageToQueue(userInput, it)
        } ?: run {
            error.value = R.string.sending_message_error
            LogUtil.w("User is null when sending message")
        }
    }

    fun sendCommandMessage(control: Control) {
        val localUser = getCurrentLocalUser()
        localUser?.let {
            chatMessageQueue.addCommandMessageToQueue(control, it)
        } ?: run {
            error.value = R.string.sending_message_error
            LogUtil.w("User is null when sending command message")
        }
    }

    fun sendMediaMessage(file: File) {
        val sub = FileUtil.compressImage(FileUtil.MAX_SIZE.toLong(), file)
                .subscribe(
                        { compressedFile -> sendMediaMessage(compressedFile.absolutePath) },
                        { LogUtil.exception("Unable to compress image $it") }
                )

        subscriptions.add(sub)
    }

    private fun sendMediaMessage(filePath: String) {
        val localUser = getCurrentLocalUser()
        localUser?.let {
            chatMessageQueue.addMediaMessageToQueue(filePath, it)
        } ?: run {
            error.value = R.string.sending_message_error
            LogUtil.w("User is null when sending media message")
        }
    }

    fun updatePaymentRequestState(existingMessage: SofaMessage, @PaymentRequest.State paymentState: Int) {
        val sub = getRecipient()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { updatePaymentRequestState(existingMessage, it, paymentState) },
                        { LogUtil.exception(it) }
                )

        subscriptions.add(sub)
    }

    private fun updatePaymentRequestState(existingMessage: SofaMessage,
                                          recipient: Recipient,
                                          @PaymentRequest.State paymentState: Int) {
        // Todo - Handle groups
        transactionManager.updatePaymentRequestState(recipient.user, existingMessage, paymentState)
    }

    fun sendPayment(paymentTask: PaymentTask) {
        if (paymentTask is ToshiPaymentTask) transactionManager.sendPayment(paymentTask)
        else LogUtil.w("Invalid payment task in this context")
    }

    fun resendPayment(sofaMessage: SofaMessage, paymentTask: PaymentTask) {
        if (paymentTask is ToshiPaymentTask) transactionManager.resendPayment(ResendToshiPaymentTask(paymentTask, sofaMessage))
        else LogUtil.w("Invalid payment task in this context")
    }

    fun resendMessage(sofaMessage: SofaMessage) = chatManager.resendPendingMessage(sofaMessage)

    fun deleteMessage(sofaMessage: SofaMessage) {
        val recipient = recipient.value
        if (recipient == null) {
            deleteError.value = R.string.delete_message_error
            return
        }

        val sub = chatManager
                .deleteMessage(recipient, sofaMessage)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { deleteMessage.value = sofaMessage },
                        { deleteError.value = R.string.delete_message_error }
                )

        subscriptions.add(sub)
    }

    fun showResendPaymentConfirmationDialog(sofaMessage: SofaMessage) {
        val sub = getRecipient()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map { ResendPaymentInfo(it.user, sofaMessage) }
                .subscribe(
                        { resendPayment.value = it },
                        { LogUtil.exception(it) }
                )

        subscriptions.add(sub)
    }

    fun viewRecipientProfile() {
        val sub = getRecipient()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { viewProfileWithId.value = it.threadId },
                        { viewProfileWithId.value = null }
                )

        subscriptions.add(sub)
    }

    fun acceptConversation(conversation: Conversation) {
        val sub = chatManager
                .acceptConversation(conversation)
                .observeOn(AndroidSchedulers.mainThread())
                .toCompletable()
                .subscribe(
                        { acceptConversation.value = Unit },
                        { LogUtil.w("Error while accepting conversation $it") }
                )

        subscriptions.add(sub)
    }

    fun declineConversation(conversation: Conversation) {
        val sub = chatManager
                .rejectConversation(conversation)
                .observeOn(AndroidSchedulers.mainThread())
                .toCompletable()
                .subscribe(
                        { declineConversation.value = Unit },
                        { LogUtil.w("Error while accepting conversation $it") }
                )

        subscriptions.add(sub)
    }

    fun respondToPaymentRequest(messageId: String) {
        val sub = chatManager
                .getSofaMessageById(messageId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { respondToPaymentRequest.value = it },
                        { respondToPaymentRequest.value = null }
                )

        subscriptions.add(sub)
    }

    fun getCurrentLocalUser(): User? {
        return userManager
                .getCurrentUser()
                .toBlocking()
                .value()
    }

    private fun getRecipient(): Single<Recipient> {
        return Single
                .fromCallable<Recipient> {
                    while (recipient.value == null) Thread.sleep(50)
                    return@fromCallable recipient.value
                }
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
    }

    fun getNetworks() = Networks.getInstance()

    override fun onCleared() {
        super.onCleared()
        subscriptions.clear()
        chatMessageQueue.clear()
        stopListeningForMessageChanges()
    }

    private fun stopListeningForMessageChanges() {
        recipient.value?.let {
            chatManager
                    .stopListeningForChanges(it.threadId)
            ChatNotificationManager.stopNotificationSuppression(it.threadId)
        }
    }
}