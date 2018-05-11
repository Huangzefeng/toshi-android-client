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

package com.toshi.manager.chat;


import android.support.annotation.NonNull;

import com.toshi.BuildConfig;
import com.toshi.crypto.signal.store.ProtocolStore;
import com.toshi.manager.chat.tasks.CreateGroupTask;
import com.toshi.manager.chat.tasks.LeaveGroupTask;
import com.toshi.manager.chat.tasks.RequestGroupInfoTask;
import com.toshi.manager.chat.tasks.SendGroupInfoTask;
import com.toshi.manager.chat.tasks.SendGroupUpdateTask;
import com.toshi.manager.chat.tasks.SendMessageToRecipientTask;
import com.toshi.manager.chat.tasks.StoreMessageTask;
import com.toshi.manager.model.SofaMessageTask;
import com.toshi.manager.store.ConversationStore;
import com.toshi.manager.store.PendingMessageStore;
import com.toshi.model.local.Group;
import com.toshi.model.local.PendingMessage;
import com.toshi.model.local.Recipient;
import com.toshi.model.local.SendState;
import com.toshi.model.local.User;
import com.toshi.model.sofa.SofaMessage;
import com.toshi.util.logging.LogUtil;

import org.jetbrains.annotations.NotNull;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.internal.configuration.SignalCdnUrl;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl;

import rx.Completable;
import rx.Single;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

public class SofaMessageSender {

    private final static String USER_AGENT = "Android " + BuildConfig.APPLICATION_ID + " - " + BuildConfig.VERSION_NAME +  ":" + BuildConfig.VERSION_CODE;

    private final CompositeSubscription subscriptions;
    private final ConversationStore conversationStore;
    private final PendingMessageStore pendingMessageStore;
    private final ProtocolStore protocolStore;
    private final PublishSubject<SofaMessageTask> messageQueue;
    private final SignalServiceMessageSender signalMessageSender;
    private final SendMessageToRecipientTask taskSendMessage;
    private final StoreMessageTask taskStoreMessage;


    public SofaMessageSender(@NonNull final String ownerAddress,
                             @NonNull final ProtocolStore protocolStore,
                             @NonNull final ConversationStore conversationStore,
                             @NonNull final SignalServiceUrl[] urls) {
        this.conversationStore = conversationStore;
        this.messageQueue = PublishSubject.create();
        this.pendingMessageStore = new PendingMessageStore();
        this.protocolStore = protocolStore;
        this.subscriptions = new CompositeSubscription();

        this.signalMessageSender =
                new SignalServiceMessageSender(
                        new SignalServiceConfiguration(urls, new SignalCdnUrl[0]),
                        ownerAddress,
                        this.protocolStore.getPassword(),
                        this.protocolStore,
                        USER_AGENT,
                        Optional.absent(),
                        Optional.absent()
                );

        this.taskSendMessage = new SendMessageToRecipientTask(
                this.conversationStore,
                this.pendingMessageStore,
                this.protocolStore,
                this.signalMessageSender);
        this.taskStoreMessage = new StoreMessageTask(this.conversationStore);

        attachSubscriber();
    }

    private void attachSubscriber() {
        final Subscription sub =
                this.messageQueue
                .onBackpressureBuffer(25)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        this::processTask,
                        throwable -> handleMessageError(throwable, "Message sending/receiving now broken due to this error")
                );
        this.subscriptions.add(sub);
    }

    private void processTask(final SofaMessageTask messageTask) {
        switch (messageTask.getAction()) {
            case SofaMessageTask.SEND_AND_SAVE:
                taskSendMessage.run(messageTask, true);
                break;
            case SofaMessageTask.SAVE_ONLY:
                taskStoreMessage.run(messageTask.getReceiver(), messageTask.getSofaMessage(), SendState.STATE_LOCAL_ONLY);
                break;
            case SofaMessageTask.SAVE_TRANSACTION:
                taskStoreMessage.run(messageTask.getReceiver(), messageTask.getSofaMessage(), SendState.STATE_SENDING);
                break;
            case SofaMessageTask.SEND_ONLY:
                taskSendMessage.run(messageTask, false);
                break;
            case SofaMessageTask.UPDATE_MESSAGE:
                updateExistingMessage(messageTask.getReceiver(), messageTask.getSofaMessage());
                break;
        }
    }

    public void addNewTask(final SofaMessageTask messageTask) {
        this.messageQueue.onNext(messageTask);
    }

    public void sendPendingMessage(final SofaMessage sofaMessage) {
        Single.fromCallable(() -> this.pendingMessageStore.fetchPendingMessage(sofaMessage))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(throwable -> handleMessageError(throwable, "Error while sending pending message"))
                .subscribe(this::handlePendingMessage);
    }

    private void handlePendingMessage(final PendingMessage pendingMessage) {
        if (pendingMessage == null) return;

        final SofaMessageTask messageTask = new SofaMessageTask(
                pendingMessage.getReceiver(),
                pendingMessage.getSofaMessage(),
                SofaMessageTask.SEND_AND_SAVE);
        addNewTask(messageTask);
    }

    public Single<Group> createGroup(final Group group) {
        return new CreateGroupTask(this.signalMessageSender).run(group);
    }

    public Completable requestGroupInfo(final User sender, final SignalServiceGroup group) {
        return new RequestGroupInfoTask(this.signalMessageSender).run(sender, group);
    }

    public Completable sendGroupInfo(@NotNull final String messageSource, @NotNull final Group group) {
        return new SendGroupInfoTask(this.signalMessageSender).run(messageSource, group);
    }

    public Completable sendGroupUpdate(@NotNull final Group group) {
        return new SendGroupUpdateTask(this.signalMessageSender).run(group);
    }

    public Completable leaveGroup(final Group group) {
        return new LeaveGroupTask(this.signalMessageSender).run(group);
    }

    private void updateExistingMessage(final Recipient receiver, final SofaMessage message) {
        this.conversationStore.updateMessage(receiver, message);
    }

    public void clear() {
        this.subscriptions.clear();
    }

    private void handleMessageError(final Throwable throwable, final String message) {
        LogUtil.exception(message, throwable);
    }
}
