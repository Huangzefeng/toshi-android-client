/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 *  This program is free software: you can redistribute it and/or modify
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

package com.toshi.manager.chat

import com.toshi.BuildConfig
import com.toshi.R
import com.toshi.crypto.HDWallet
import com.toshi.crypto.signal.ChatService
import com.toshi.crypto.signal.store.ProtocolStore
import com.toshi.crypto.signal.store.SignalTrustStore
import com.toshi.manager.UserManager
import com.toshi.manager.model.SofaMessageTask
import com.toshi.manager.store.ConversationStore
import com.toshi.model.local.Conversation
import com.toshi.model.local.Group
import com.toshi.model.local.IncomingMessage
import com.toshi.model.local.Recipient
import com.toshi.model.local.User
import com.toshi.model.sofa.Init
import com.toshi.model.sofa.SofaAdapters
import com.toshi.model.sofa.SofaMessage
import com.toshi.util.LocaleUtil
import com.toshi.util.logging.LogUtil
import com.toshi.util.sharedPrefs.SignalPrefs
import com.toshi.view.BaseApplication
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl
import rx.Completable
import rx.Observable
import rx.Scheduler
import rx.Single
import rx.Subscription
import rx.schedulers.Schedulers

class SofaMessageManager(
        private val conversationStore: ConversationStore,
        private val userManager: UserManager,
        private val baseApplication: BaseApplication = BaseApplication.get(),
        private val protocolStore: ProtocolStore = ProtocolStore().init(),
        private val trustStore: SignalTrustStore = SignalTrustStore(),
        private val signalServiceUrl: SignalServiceUrl = SignalServiceUrl(baseApplication.getString(R.string.chat_url), trustStore),
        private val signalServiceUrls: Array<SignalServiceUrl> = Array(1, { signalServiceUrl }),
        private val signalPrefs: SignalPrefs = SignalPrefs,
        private val walletObservable: Observable<HDWallet>,
        private val userAgent: String = "Android " + BuildConfig.APPLICATION_ID + " - " + BuildConfig.VERSION_NAME + ":" + BuildConfig.VERSION_CODE,
        private val scheduler: Scheduler = Schedulers.io()
) {

    private var chatService: ChatService? = null
    private var messageRegister: SofaMessageRegistration? = null
    private var messageReceiver: SofaMessageReceiver? = null
    private var messageSender: SofaMessageSender? = null
    private var groupManager: SofaGroupManager? = null
    private var connectivitySub: Subscription? = null

    fun initEverything(wallet: HDWallet): Completable {
        initChatService(wallet)
        initSenderAndReceiver(wallet)
        return initRegistrationTask()
                .doOnCompleted { attachConnectivityObserver() }
    }

    private fun initChatService(wallet: HDWallet) {
        chatService = ChatService(signalServiceUrls, wallet.ownerAddress, protocolStore, userAgent)
    }

    private fun initSenderAndReceiver(wallet: HDWallet) {
        val messageSender = initMessageSender(wallet, protocolStore, conversationStore, signalServiceUrls)
        this.messageReceiver = initMessageReceiver(wallet, protocolStore, conversationStore, signalServiceUrls, messageSender)
        this.groupManager = SofaGroupManager(messageSender, conversationStore, userManager)
        this.messageSender = messageSender
    }

    private fun initMessageSender(wallet: HDWallet, protocolStore: ProtocolStore,
                                  conversationStore: ConversationStore,
                                  signalServiceUrls: Array<SignalServiceUrl>): SofaMessageSender {
        return messageSender ?: SofaMessageSender(
                wallet.ownerAddress,
                protocolStore,
                conversationStore,
                signalServiceUrls
        )
    }

    private fun initMessageReceiver(wallet: HDWallet, protocolStore: ProtocolStore, conversationStore: ConversationStore,
                                    signalServiceUrls: Array<SignalServiceUrl>, messageSender: SofaMessageSender): SofaMessageReceiver {
        return messageReceiver ?: SofaMessageReceiver(
                wallet.ownerAddress,
                walletObservable,
                protocolStore,
                conversationStore,
                signalServiceUrls,
                messageSender
        )
    }

    private fun attachConnectivityObserver() {
        clearConnectivitySubscription()
        connectivitySub = baseApplication
                .isConnectedSubject
                .subscribeOn(scheduler)
                .filter { isConnected -> isConnected }
                .subscribe(
                        { handleConnectivity() },
                        { LogUtil.exception("Error checking connection state", it) }
                )
    }

    private fun handleConnectivity() {
        redoRegistrationTask()
                .subscribeOn(scheduler)
                .subscribe(
                        { },
                        { LogUtil.exception("Error during registration task", it) }
                )
    }

    private fun initRegistrationTask(): Completable {
        return if (messageRegister != null) return Completable.complete()
        else initSofaMessageRegistration()
    }

    private fun initSofaMessageRegistration(): Completable {
        val messageRegister = SofaMessageRegistration(chatService, protocolStore)
        this.messageRegister = messageRegister
        return messageRegister
                .registerIfNeeded()
                .doOnCompleted { messageReceiver?.receiveMessagesAsync() }
    }

    private fun redoRegistrationTask(): Completable {
        val messageRegister = messageRegister ?: SofaMessageRegistration(chatService, protocolStore)
        this.messageRegister = messageRegister
        return messageRegister
                .registerIfNeededWithOnboarding()
                .doOnCompleted { messageReceiver?.receiveMessagesAsync() }
    }

    // Will send the message to a remote peer
    // and store the message in the local database
    fun sendAndSaveMessage(receiver: Recipient, message: SofaMessage) {
        val messageTask = SofaMessageTask(receiver, message, SofaMessageTask.SEND_AND_SAVE)
        messageSender?.addNewTask(messageTask)
    }

    // Will send the message to a remote peer
    // but not store the message in the local database
    fun sendMessage(recipient: Recipient, message: SofaMessage) {
        val messageTask = SofaMessageTask(recipient, message, SofaMessageTask.SEND_ONLY)
        messageSender?.addNewTask(messageTask)
    }

    // Will send an init message to remote peer
    fun sendInitMessage(sender: User, recipient: Recipient) {
        val initMessage = Init()
                .setPaymentAddress(sender.paymentAddress)
                .setLanguage(LocaleUtil.getLocale().language)
        val messageBody = SofaAdapters.get().toJson(initMessage)
        val sofaMessage = SofaMessage().makeNew(sender, messageBody)
        val messageTask = SofaMessageTask(recipient, sofaMessage, SofaMessageTask.SEND_ONLY)
        messageSender?.addNewTask(messageTask)
    }

    // Will store a transaction in the local database
    // but not send the message to a remote peer. It will also save the state as "SENDING".
    fun saveTransaction(user: User, message: SofaMessage) {
        val recipient = Recipient(user)
        val messageTask = SofaMessageTask(recipient, message, SofaMessageTask.SAVE_TRANSACTION)
        messageSender?.addNewTask(messageTask)
    }

    // Updates a pre-existing message.
    fun updateMessage(recipient: Recipient, message: SofaMessage) {
        val messageTask = SofaMessageTask(recipient, message, SofaMessageTask.UPDATE_MESSAGE)
        messageSender?.addNewTask(messageTask)
    }

    fun resendPendingMessage(sofaMessage: SofaMessage) = messageSender?.sendPendingMessage(sofaMessage)

    fun createConversationFromGroup(group: Group): Single<Conversation> {
        return groupManager
                ?.createConversationFromGroup(group)
                ?.subscribeOn(scheduler)
                ?: Single.error(IllegalStateException("SofaGroupManager is null while createConversationFromGroup"))
    }

    fun updateConversationFromGroup(group: Group): Completable {
        return groupManager
                ?.updateConversationFromGroup(group)
                ?.subscribeOn(scheduler)
                ?: Completable.error(IllegalStateException("SofaGroupManager is null while updateConversationFromGroup"))
    }

    fun leaveGroup(group: Group): Completable {
        return groupManager
                ?.leaveGroup(group)
                ?.subscribeOn(scheduler)
                ?: Completable.error(IllegalStateException("SofaGroupManager is null while leaveGroup"))
    }

    fun resumeMessageReceiving() {
        val registeredWithServer = signalPrefs.getRegisteredWithServer()
        if (registeredWithServer) messageReceiver?.receiveMessagesAsync()
    }

    fun tryUnregisterGcm(): Completable {
        return messageRegister
                ?.tryUnregisterGcm()
                ?: Completable.error(IllegalStateException("Unable to register as class hasn't been initialised yet."))
    }

    fun forceRegisterChatGcm(): Completable {
        return messageRegister
                ?.registerChatGcm()
                ?: Completable.error(IllegalStateException("Unable to register as class hasn't been initialised yet."))
    }

    fun fetchLatestMessage(): Single<IncomingMessage> {
        return messageReceiver
                ?.fetchLatestMessage()
                ?: Single.error(IllegalStateException("SofaMessageReceiver is null while fetchLatestMessage"))
    }

    fun clear() {
        clearMessageReceiver()
        clearMessageSender()
        clearConnectivitySubscription()
    }

    private fun clearMessageReceiver() {
        messageReceiver?.shutdown()
        messageReceiver = null
    }

    private fun clearMessageSender() {
        messageSender?.clear()
        messageSender = null
    }

    private fun clearConnectivitySubscription() = connectivitySub?.unsubscribe()

    fun deleteSession() = protocolStore.deleteAllSessions()

    fun disconnect() = messageReceiver?.shutdown()
}