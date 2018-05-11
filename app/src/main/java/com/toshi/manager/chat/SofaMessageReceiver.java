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
import android.support.annotation.WorkerThread;

import com.toshi.BuildConfig;
import com.toshi.crypto.HDWallet;
import com.toshi.crypto.signal.store.ProtocolStore;
import com.toshi.manager.chat.tasks.GroupUpdateTask;
import com.toshi.manager.chat.tasks.HandleMessageTask;
import com.toshi.manager.store.ConversationStore;
import com.toshi.model.local.IncomingMessage;
import com.toshi.util.logging.LogUtil;
import com.toshi.view.BaseApplication;
import com.toshi.view.notification.ChatNotificationManager;

import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.signalservice.api.SignalServiceMessagePipe;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.configuration.SignalCdnUrl;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import rx.Observable;
import rx.Single;
import rx.Subscription;
import rx.schedulers.Schedulers;

public class SofaMessageReceiver {

    private final static String USER_AGENT = "Android " + BuildConfig.APPLICATION_ID + " - " + BuildConfig.VERSION_NAME +  ":" + BuildConfig.VERSION_CODE;
    public final static int INCOMING_MESSAGE_TIMEOUT = 10;

    private final ProtocolStore protocolStore;
    private final SignalServiceMessageReceiver messageReceiver;
    private final String ownerAddress;
    private final GroupUpdateTask taskGroupUpdate;
    private final HandleMessageTask taskHandleMessage;

    private SignalServiceMessagePipe messagePipe;
    private boolean isReceivingMessages;
    private Subscription messagesSubscription;
    private final ExecutorService messageReceiverThread = Executors.newSingleThreadExecutor();

    public SofaMessageReceiver(@NonNull final String ownerAddress,
                               @NonNull final Observable<HDWallet> hdWalletObservable,
                               @NonNull final ProtocolStore protocolStore,
                               @NonNull final ConversationStore conversationStore,
                               @NonNull final SignalServiceUrl[] urls,
                               @NonNull final SofaMessageSender messageSender) {
        this.protocolStore = protocolStore;
        this.ownerAddress = ownerAddress;
        this.messageReceiver =
                new SignalServiceMessageReceiver(
                        new SignalServiceConfiguration(urls, new SignalCdnUrl[0]),
                        this.ownerAddress,
                        this.protocolStore.getPassword(),
                        this.protocolStore.getSignalingKey(),
                        USER_AGENT);

        this.taskGroupUpdate = new GroupUpdateTask(this.messageReceiver, messageSender, conversationStore);
        this.taskHandleMessage = new HandleMessageTask(this.messageReceiver, conversationStore, hdWalletObservable, messageSender);
    }

    public void receiveMessagesAsync() {
        if (this.isReceivingMessages) {
            // Already running.
            return;
        }

        this.isReceivingMessages = true;

        this.messagesSubscription = fetchLatestMessage()
                .toObservable()
                .onErrorResumeNext(this::returnNullIfTimeoutException)
                .repeatWhen(completed -> completed)
                .subscribe(
                        ChatNotificationManager::showNotification,
                        throwable -> LogUtil.exception("Error while receiving messages " + throwable)
                );
    }

    private Observable returnNullIfTimeoutException(final Throwable throwable) {
        if (throwable instanceof TimeoutException) return Observable.just(null); // TimeoutException is expected
        else return Observable.error(throwable);
    }

    public Single<IncomingMessage> fetchLatestMessage() {
        return Single.fromCallable(this::tryFetchLatestMessage)
                .subscribeOn(Schedulers.from(messageReceiverThread));
    }

    @WorkerThread
    private IncomingMessage tryFetchLatestMessage() throws TimeoutException {
        if (this.messagePipe == null) {
            this.messagePipe = messageReceiver.createMessagePipe();
        }

        try {
            final SignalServiceEnvelope envelope = messagePipe.read(INCOMING_MESSAGE_TIMEOUT, TimeUnit.SECONDS);
            return decryptIncomingSignalServiceEnvelope(envelope);
        } catch (final TimeoutException ex) {
            throw new TimeoutException(ex.getMessage());
        } catch (final IllegalStateException | InvalidKeyException | InvalidKeyIdException | DuplicateMessageException | InvalidVersionException | LegacyMessageException | InvalidMessageException | NoSessionException | org.whispersystems.libsignal.UntrustedIdentityException | IOException e) {
            LogUtil.exception("Error while fetching latest message " + e);
        }
        return null;
    }

    private IncomingMessage decryptIncomingSignalServiceEnvelope(final SignalServiceEnvelope envelope) throws InvalidVersionException, InvalidMessageException, InvalidKeyException, DuplicateMessageException, InvalidKeyIdException, org.whispersystems.libsignal.UntrustedIdentityException, LegacyMessageException, NoSessionException {
        // ToDo -- When do we need to create new keys?
 /*       if (envelope.getType() == SignalServiceProtos.Envelope.Type.PREKEY_BUNDLE_VALUE) {
            // New keys need to be registered with the server.
            registerWithServer();
            return;
        }*/
        return handleIncomingSofaMessage(envelope);
    }

    private IncomingMessage handleIncomingSofaMessage(final SignalServiceEnvelope envelope) throws InvalidVersionException, InvalidMessageException, InvalidKeyException, DuplicateMessageException, InvalidKeyIdException, org.whispersystems.libsignal.UntrustedIdentityException, LegacyMessageException, NoSessionException {
        final SignalServiceAddress localAddress = new SignalServiceAddress(this.ownerAddress);
        final SignalServiceCipher cipher = new SignalServiceCipher(localAddress, this.protocolStore);
        final SignalServiceContent content = cipher.decrypt(envelope);
        final String messageSource = envelope.getSource();

        if (isUserBlocked(messageSource)) {
            LogUtil.i("A blocked user is trying to send a message");
            return null;
        }

        if (content.getDataMessage().isPresent()) {
            final SignalServiceDataMessage dataMessage = content.getDataMessage().get();
            if (dataMessage.isGroupUpdate()) return taskGroupUpdate.run(messageSource, dataMessage);
            else return taskHandleMessage.run(messageSource, dataMessage);
        }
        return null;
    }

    private boolean isUserBlocked(final String address) {
        return BaseApplication
                .get()
                .getRecipientManager()
                .isUserBlocked(address)
                .toBlocking()
                .value();
    }

    public void shutdown() {
        this.isReceivingMessages = false;
        if (this.messagesSubscription != null) this.messagesSubscription.unsubscribe();
        if (this.messagePipe != null) {
            this.messagePipe.shutdown();
            this.messagePipe = null;
        }
    }
}
