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

package com.toshi.crypto.signal;


import com.squareup.moshi.Moshi;
import com.toshi.crypto.signal.model.SignalBootstrap;
import com.toshi.crypto.signal.network.ChatInterface;
import com.toshi.crypto.signal.store.ProtocolStore;
import com.toshi.manager.network.interceptor.AppInfoUserAgentInterceptor;
import com.toshi.manager.network.interceptor.LoggingInterceptor;
import com.toshi.manager.network.interceptor.SigningInterceptor;
import com.toshi.util.logging.LogUtil;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.SignedPreKeyEntity;
import org.whispersystems.signalservice.internal.configuration.SignalCdnUrl;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl;
import org.whispersystems.signalservice.internal.push.PreKeyEntity;
import org.whispersystems.signalservice.internal.util.JsonUtil;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory;
import retrofit2.converter.moshi.MoshiConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;
import rx.Completable;
import rx.schedulers.Schedulers;

public final class ChatService extends SignalServiceAccountManager {

    private final ChatInterface chatInterface;
    private final OkHttpClient.Builder client;
    private final String url;

    public ChatService(
            final SignalServiceUrl[] urls,
            final String ownerAddress,
            final ProtocolStore protocolStore,
            final String userAgent) {
        this(urls, ownerAddress, protocolStore.getPassword(), userAgent);
    }

    private ChatService(final SignalServiceUrl[] urls,
                        final String user,
                        final String password,
                        final String userAgent) {
        super(new SignalServiceConfiguration(urls, new SignalCdnUrl[0]),
                user,
                password,
                userAgent);
        this.url = urls[0].getUrl();
        this.client = new OkHttpClient.Builder();
        this.chatInterface = generateSignalInterface();
    }

    private ChatInterface generateSignalInterface() {
        final RxJavaCallAdapterFactory rxAdapter = RxJavaCallAdapterFactory.createWithScheduler(Schedulers.io());

        addUserAgentHeader();
        addSigningInterceptor();
        addLogging();

        final Moshi moshi = new Moshi.Builder()
                .build();

        final Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(this.url)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .addCallAdapterFactory(rxAdapter)
                .client(client.build())
                .build();
        return retrofit.create(ChatInterface.class);
    }

    private void addUserAgentHeader() {
        this.client.addInterceptor(new AppInfoUserAgentInterceptor());
    }

    private void addSigningInterceptor() {
        this.client.addInterceptor(new SigningInterceptor());
    }

    private void addLogging() {
        final HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor(new LoggingInterceptor());
        interceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        this.client.addInterceptor(interceptor);
    }

    public Completable registerKeys(final ProtocolStore protocolStore) {
        try {
            return registerKeys(
                    protocolStore.getIdentityKeyPair().getPublicKey(),
                    protocolStore.getLastResortKey(),
                    protocolStore.getPassword(),
                    protocolStore.getLocalRegistrationId(),
                    protocolStore.getSignalingKey(),
                    protocolStore.getSignedPreKey(),
                    protocolStore.getPreKeys()
            );
        } catch (final IOException | InvalidKeyIdException | InvalidKeyException ex) {
            LogUtil.exception("Error while registering keys" + ex.toString());
            return Completable.error(ex);
        }
    }

    private Completable registerKeys(
            final IdentityKey identityKey,
            final PreKeyRecord lastResortKey,
            final String password,
            final int registrationId,
            final String signalingKey,
            final SignedPreKeyRecord signedPreKey,
            final List<PreKeyRecord> preKeys) {

        return this.chatInterface
                .getTimestamp()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .flatMapCompletable(
                        serverTime -> registerKeysWithTimestamp(
                                serverTime.get(),
                                identityKey,
                                lastResortKey,
                                password,
                                registrationId,
                                signalingKey,
                                signedPreKey,
                                preKeys
                        )
                );
    }

    private Completable registerKeysWithTimestamp(
            final long timestamp,
            final IdentityKey identityKey,
            final PreKeyRecord lastResortKey,
            final String password,
            final int registrationId,
            final String signalingKey,
            final SignedPreKeyRecord signedPreKey,
            final List<PreKeyRecord> preKeys) {

        final long startTime = System.currentTimeMillis();

        final List<PreKeyEntity> entities = new LinkedList<>();
        for (PreKeyRecord preKey : preKeys) {
            final PreKeyEntity entity = new PreKeyEntity(
                    preKey.getId(),
                    preKey.getKeyPair().getPublicKey());
            entities.add(entity);
        }

        final PreKeyEntity lastResortEntity = new PreKeyEntity(
                lastResortKey.getId(),
                lastResortKey.getKeyPair().getPublicKey());

        final SignedPreKeyEntity signedPreKeyEntity = new SignedPreKeyEntity(
                signedPreKey.getId(),
                signedPreKey.getKeyPair().getPublicKey(),
                signedPreKey.getSignature());

        final long endTime = System.currentTimeMillis();
        final long elapsedSeconds = (endTime - startTime) / 1000;
        final long amendedTimestamp = timestamp + elapsedSeconds;

        final SignalBootstrap payload = new SignalBootstrap(
                entities,
                lastResortEntity,
                password,
                registrationId,
                signalingKey,
                signedPreKeyEntity,
                identityKey);

        final String payloadForSigning = JsonUtil.toJson(payload);

        return this.chatInterface
                .register(payloadForSigning, amendedTimestamp)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .toCompletable();
    }
}
