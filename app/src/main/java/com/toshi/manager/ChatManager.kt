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

package com.toshi.manager

import com.toshi.crypto.HDWallet
import com.toshi.manager.chat.SofaMessageManager
import com.toshi.manager.store.ConversationStore
import com.toshi.model.local.Conversation
import com.toshi.model.local.ConversationObservables
import com.toshi.model.local.Group
import com.toshi.model.local.IncomingMessage
import com.toshi.model.local.Recipient
import com.toshi.model.local.User
import com.toshi.model.sofa.SofaMessage
import rx.Completable
import rx.Observable
import rx.Scheduler
import rx.Single
import rx.schedulers.Schedulers

class ChatManager(
        private val userManager: UserManager,
        private val recipientManager: RecipientManager,
        private val conversationStore: ConversationStore = ConversationStore(),
        private val walletObservable: Observable<HDWallet>,
        private val sofaMessageManager: SofaMessageManager = SofaMessageManager(
                conversationStore = conversationStore,
                userManager = userManager,
                walletObservable = walletObservable
        ),
        private val scheduler: Scheduler = Schedulers.io()
) {

    fun init(wallet: HDWallet): Completable = sofaMessageManager.initEverything(wallet)

    fun loadAllAcceptedConversations(): Single<List<Conversation>> {
        return conversationStore
                .loadAllAcceptedConversation()
                .subscribeOn(scheduler)
    }

    fun loadAllUnacceptedConversations(): Single<List<Conversation>> {
        return conversationStore
                .loadAllUnacceptedConversation()
                .subscribeOn(scheduler)
    }

    fun loadConversation(threadId: String): Single<Conversation> {
        return conversationStore
                .loadByThreadId(threadId)
                .subscribeOn(scheduler)
    }

    fun loadConversationAndResetUnreadCounter(threadId: String): Single<Conversation> {
        return loadConversation(threadId)
                .flatMap { createEmptyConversationIfNullAndSetToAccepted(it, threadId) }
                .doOnSuccess { conversationStore.resetUnreadMessageCounter(it.threadId) }
    }

    private fun createEmptyConversationIfNullAndSetToAccepted(conversation: Conversation?, threadId: String): Single<Conversation> {
        return if (conversation != null) Single.just(conversation)
        else recipientManager
                .getUserFromToshiId(threadId)
                .map { Recipient(it) }
                .flatMap { conversationStore.createEmptyConversation(it) }
    }

    fun deleteConversation(conversation: Conversation): Completable {
        return conversationStore
                .deleteByThreadId(conversation.threadId)
                .subscribeOn(scheduler)
    }

    fun deleteMessage(recipient: Recipient, sofaMessage: SofaMessage): Completable {
        return conversationStore
                .deleteMessageById(recipient, sofaMessage)
    }

    fun registerForAllConversationChanges(): Observable<Conversation> {
        return conversationStore.conversationChangedObservable
    }

    fun registerForConversationChanges(threadId: String): ConversationObservables {
        return conversationStore.registerForChanges(threadId)
    }

    fun registerForDeletedMessages(threadId: String): Observable<SofaMessage> {
        return conversationStore.registerForDeletedMessages(threadId)
    }

    fun stopListeningForChanges(threadId: String) {
        conversationStore.stopListeningForChanges(threadId)
    }

    fun areUnreadMessages(): Single<Boolean> {
        return Single
                .fromCallable { conversationStore.areUnreadMessages() }
                .subscribeOn(scheduler)
    }

    fun getSofaMessageById(id: String): Single<SofaMessage> {
        return conversationStore
                .getSofaMessageById(id)
                .subscribeOn(scheduler)
    }

    fun isConversationMuted(threadId: String): Single<Boolean> {
        return conversationStore
                .loadByThreadId(threadId)
                .map { it.conversationStatus.isMuted }
                .subscribeOn(scheduler)
    }

    fun muteConversation(threadId: String): Completable {
        return conversationStore
                .loadByThreadId(threadId)
                .flatMap { muteConversation(it) }
                .subscribeOn(scheduler)
                .toCompletable()
    }

    fun unmuteConversation(threadId: String): Completable {
        return conversationStore
                .loadByThreadId(threadId)
                .flatMap { unmuteConversation(it) }
                .subscribeOn(scheduler)
                .toCompletable()
    }

    fun muteConversation(conversation: Conversation): Single<Conversation> {
        return conversationStore
                .muteConversation(conversation, true)
                .subscribeOn(scheduler)
    }

    fun unmuteConversation(conversation: Conversation): Single<Conversation> {
        return conversationStore
                .muteConversation(conversation, false)
                .subscribeOn(scheduler)
    }

    fun acceptConversation(conversation: Conversation): Single<Conversation> {
        return conversationStore
                .acceptConversation(conversation)
                .subscribeOn(scheduler)
    }

    fun rejectConversation(conversation: Conversation): Single<Conversation> {
        return if (conversation.isGroup) sofaMessageManager
                .leaveGroup(conversation.recipient.group)
                .toSingle { conversation }
        else recipientManager
                .blockUser(conversation.threadId)
                .andThen(deleteConversation(conversation))
                .toSingle { conversation }
    }

    fun leaveGroup(group: Group): Completable = sofaMessageManager.leaveGroup(group)

    fun sendAndSaveMessage(receiver: Recipient, message: SofaMessage) {
        sofaMessageManager.sendAndSaveMessage(receiver, message)
    }

    fun sendMessage(recipient: Recipient, message: SofaMessage) {
        sofaMessageManager.sendMessage(recipient, message)
    }

    fun sendInitMessage(sender: User, recipient: Recipient) {
        sofaMessageManager.sendInitMessage(sender, recipient)
    }

    fun saveTransaction(user: User, message: SofaMessage) {
        sofaMessageManager.saveTransaction(user, message)
    }

    fun updateMessage(recipient: Recipient, message: SofaMessage) {
        sofaMessageManager.updateMessage(recipient, message)
    }

    fun resendPendingMessage(sofaMessage: SofaMessage) = sofaMessageManager.resendPendingMessage(sofaMessage)

    fun createConversationFromGroup(group: Group): Single<Conversation> {
        return sofaMessageManager.createConversationFromGroup(group)
    }

    fun updateConversationFromGroup(group: Group): Completable {
        return sofaMessageManager.updateConversationFromGroup(group)
    }

    fun resumeMessageReceiving() = sofaMessageManager.resumeMessageReceiving()

    fun tryUnregisterGcm(): Completable = sofaMessageManager.tryUnregisterGcm()

    fun forceRegisterChatGcm(): Completable = sofaMessageManager.forceRegisterChatGcm()

    fun fetchLatestMessage(): Single<IncomingMessage> = sofaMessageManager.fetchLatestMessage()

    fun clear() = sofaMessageManager.clear()

    fun deleteSession() = sofaMessageManager.deleteSession()

    fun disconnect() = sofaMessageManager.disconnect()
}
