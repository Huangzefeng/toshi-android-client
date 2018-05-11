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

package com.toshi.presenter.webview;


import android.graphics.Bitmap;
import android.os.Build;
import android.support.annotation.MainThread;
import android.support.v7.app.AppCompatActivity;
import android.util.Pair;
import android.webkit.WebView;

import com.toshi.R;
import com.toshi.crypto.HDWallet;
import com.toshi.crypto.util.TypeConverter;
import com.toshi.manager.model.PaymentTask;
import com.toshi.manager.model.W3PaymentTask;
import com.toshi.model.local.PersonalMessage;
import com.toshi.model.local.UnsignedW3Transaction;
import com.toshi.model.network.SentTransaction;
import com.toshi.model.network.SignedTransaction;
import com.toshi.model.sofa.SofaAdapters;
import com.toshi.presenter.webview.model.ApproveTransactionCallback;
import com.toshi.presenter.webview.model.GetAccountsCallback;
import com.toshi.presenter.webview.model.RejectTransactionCallback;
import com.toshi.presenter.webview.model.SignTransactionCallback;
import com.toshi.util.DialogUtil;
import com.toshi.util.EthereumSignedMessage;
import com.toshi.util.logging.LogUtil;
import com.toshi.view.BaseApplication;
import com.toshi.view.fragment.PaymentConfirmationFragment;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;

import kotlin.Unit;
import rx.Completable;
import rx.Single;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.subscriptions.CompositeSubscription;

public class SofaHostWrapper implements SofaHostListener {

    private final AppCompatActivity activity;
    private final WebView webView;
    private final SOFAHost sofaHost;
    private final HDWallet wallet;
    private final CompositeSubscription subscriptions;
    public Bitmap favicon;
    public String url;
    public String title;

    public SofaHostWrapper(
            final AppCompatActivity activity,
            final WebView webView
    ) {
        this.activity = activity;
        this.subscriptions = new CompositeSubscription();
        this.webView = webView;
        this.sofaHost = new SOFAHost(this);
        this.wallet = getWallet();
    }

    private HDWallet getWallet() {
        return BaseApplication
                .get()
                .getToshiManager()
                .getWallet()
                .toBlocking()
                .value();
    }

    public SOFAHost getSofaHost() {
        return this.sofaHost;
    }

    @Override
    public void getAccounts(final String id) {
        String paymentAddress = this.wallet.getPaymentAddress();
        final GetAccountsCallback callback = new GetAccountsCallback().setResult(paymentAddress);
        postCallbackTask(id, callback.toJsonEncodedString());
    }

    @Override
    public void approveTransaction(final String id, final String unsignedTransaction) {
        final boolean shouldApprove = shouldApproveTransaction(unsignedTransaction);
        final ApproveTransactionCallback callback =
                new ApproveTransactionCallback()
                        .setResult(shouldApprove);
        postCallbackTask(id, callback.toJsonEncodedString());
    }

    private boolean shouldApproveTransaction(final String unsignedTransaction) {
        final UnsignedW3Transaction transaction;
        try {
            transaction = SofaAdapters.get().unsignedW3TransactionFrom(unsignedTransaction);
        } catch (final IOException e) {
            LogUtil.exception("Unable to parse unsigned transaction. ", e);
            return false;
        }

        return transaction.getFrom().equals(this.wallet.getPaymentAddress());
    }

    @Override
    public void signTransaction(final String id, final String unsignedTransaction) {
        final Subscription sub =
                getWebViewInfo()
                .subscribe(
                        pair -> signTransaction(id, unsignedTransaction, url, pair.first, pair.second),
                        throwable -> LogUtil.w("Error while retrieving web view info " + throwable)
                );

        this.subscriptions.add(sub);
    }

    @MainThread
    private Single<Pair<String, Bitmap>> getWebViewInfo() {
        return Single.fromCallable(() -> new Pair<>(title, favicon))
            .subscribeOn(AndroidSchedulers.mainThread());
    }

    private void signTransaction(final String id,
                                 final String unsignedTransaction,
                                 final String url,
                                 final String title,
                                 final Bitmap favicon) {
        final UnsignedW3Transaction transaction;
        try {
            transaction = SofaAdapters.get().unsignedW3TransactionFrom(unsignedTransaction);
        } catch (final IOException e) {
            LogUtil.exception("Unable to parse unsigned transaction. ", e);
            return;
        }
        if (this.activity == null) return;
        final PaymentConfirmationFragment dialog =
                PaymentConfirmationFragment.Companion
                        .newInstanceWebPayment(
                                unsignedTransaction,
                                transaction.getTo(),
                                transaction.getValue(),
                                id,
                                null,
                                url,
                                title,
                                favicon
                        );
        dialog.show(activity.getSupportFragmentManager(), PaymentConfirmationFragment.TAG);
        dialog.setOnPaymentConfirmationApprovedListener(this::handlePaymentApproved)
                .setOnPaymentConfirmationCanceledListener(this::handleCanceledClicked);
    }

    private Unit handlePaymentApproved(final PaymentTask paymentTask) {
        if (paymentTask instanceof W3PaymentTask) {
            final W3PaymentTask w3PaymentTask = (W3PaymentTask) paymentTask;
            final String callbackId = w3PaymentTask.getCallbackId();
            final Subscription sub = signW3Transaction(w3PaymentTask)
                    .subscribe(
                            signedTransaction -> handleSignedW3Transaction(callbackId, signedTransaction),
                            throwable -> handleTransactionError(throwable, callbackId)
                    );

            this.subscriptions.add(sub);
        } else LogUtil.w("Invalid payment task in this context");
        return null;
    }

    private void handleTransactionError(final Throwable throwable, final String callbackId) {
        LogUtil.exception("Error while signing W3 transaction " + throwable);
        final String errorMessage = createErrorMessage("Not able to sign transaction");
        postCallbackTask(callbackId, errorMessage);
    }

    private Single<SignedTransaction> signW3Transaction(final W3PaymentTask paymentTask) {
        return BaseApplication
                .get()
                .getTransactionManager()
                .signW3Transaction(paymentTask);
    }

    private void handleSignedW3Transaction(final String callbackId, final SignedTransaction signedTransaction) {
        final SignTransactionCallback callback =
                new SignTransactionCallback()
                        .setSkeleton(signedTransaction.getSkeleton())
                        .setSignature(signedTransaction.getSignature());
        try {
            postCallbackTask(callbackId, callback.toJsonEncodedString());
        } catch (Exception e) {
            LogUtil.exception(e);
            final String errorMessage = createErrorMessage(e.getMessage());
            postCallbackTask(callbackId, errorMessage);
        }
    }

    private Unit handleCanceledClicked(final String callbackId) {
        final RejectTransactionCallback callback =
                new RejectTransactionCallback()
                        .setError(BaseApplication.get().getString(R.string.error__reject_transaction));
        postCallbackTask(callbackId, callback.toJsonEncodedString());
        return null;
    }

    @Override
    public void publishTransaction(final String callbackId, final String signedTransactionPayload) {
        final String cleanPayload = TypeConverter.jsonStringToString(signedTransactionPayload);
        final SignedTransaction transaction = new SignedTransaction()
                .setEncodedTransaction(cleanPayload);

        final Subscription sub = BaseApplication
                .get()
                .getTransactionManager()
                .sendSignedTransaction(transaction)
                .subscribe(
                        sentTransaction -> handleSentTransaction(callbackId, sentTransaction),
                        throwable -> handlePublishTransactionError(throwable, callbackId)
                );

        this.subscriptions.add(sub);
    }

    private void handlePublishTransactionError(final Throwable throwable, final String callbackId) {
        LogUtil.exception("Error while publishing transaction " + throwable);
        final String errorMessage = createErrorMessage("Not able to publish transaction");
        postCallbackTask(callbackId, errorMessage);
    }

    private void handleSentTransaction(final String callbackId, final SentTransaction sentTransaction) {
        postCallbackTask(callbackId, String.format(
                "{\\\"result\\\":\\\"%s\\\"}",
                sentTransaction.getTxHash()
        ));
    }

    @Override
    public void signPersonalMessage(final String id, final String msgParams) {
        try {
            final PersonalMessage personalMessage = PersonalMessage.build(msgParams);
            showPersonalSignDialog(id, personalMessage);
        } catch (IOException e) {
            LogUtil.w("Error while parsing PersonalMessageSign" + e);
            final String errorMessage = createErrorMessage("Unable to parse personal message");
            postCallbackTask(id, errorMessage);
        }
    }

    private void showPersonalSignDialog(final String id, final PersonalMessage personalMessage) {
        if (this.activity == null) return;
        DialogUtil.getBaseDialog(
                this.activity,
                this.activity.getString(R.string.personal_sign_title),
                personalMessage.getDataFromMessageAsString(),
                R.string.agree,
                R.string.cancel,
                (dialog, which) -> handleSignPersonalMessageClicked(id, personalMessage)
        ).show();
    }

    private void handleSignPersonalMessageClicked(final String id, final PersonalMessage personalMessage) {
        try {
            final EthereumSignedMessage ethereumSignedMessage = new EthereumSignedMessage(id, personalMessage);
            final Subscription sub = ethereumSignedMessage.signPersonalMessage()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            this::executeJavascriptMethod,
                            throwable -> LogUtil.w("Error " + throwable)
                    );
            this.subscriptions.add(sub);
        } catch (UnsupportedEncodingException e) {
            LogUtil.w("Error " + e);
        }
    }

    @Override
    public void signMessage(final String id, final String from, final String data) {
        if (!from.equalsIgnoreCase(this.wallet.getPaymentAddress())) {
            final String errorMessage = createErrorMessage("Invalid Address");
            postCallbackTask(id, errorMessage);
            return;
        }
        if (data.length() != 66) {
            final String errorMessage = createErrorMessage("Invalid Message Length");
            postCallbackTask(id, errorMessage);
            return;
        } else if (!data.substring(0, 2).equalsIgnoreCase("0x")) {
            final String errorMessage = createErrorMessage("Invalid Message Data");
            postCallbackTask(id, errorMessage);
            return;
        } else {
            try {
                new BigInteger(data.substring(2), 16);
            } catch (final NumberFormatException e) {
                final String errorMessage = createErrorMessage("Invalid Message Data");
                postCallbackTask(id, errorMessage);
                return;
            }
        }
        final PersonalMessage personalMessage = new PersonalMessage(from, data);
        showSignMessageDialog(id, personalMessage);
    }

    private void postCallbackTask(final String id, final String encodedCallback) {
        if (activity == null) return;
        final Subscription sub =
                doCallback(id, encodedCallback)
                .subscribe(
                        () -> {},
                        throwable -> LogUtil.w("Error while executing javascript method " + throwable)
                );

        subscriptions.add(sub);
    }

    @MainThread
    private Completable doCallback(final String id, final String encodedCallback) {
        return Completable.fromAction(() -> {
            final String methodCall = String.format("SOFA.callback(\"%s\",\"%s\")", id, encodedCallback);
            executeJavascriptMethod(methodCall);
        })
        .subscribeOn(AndroidSchedulers.mainThread());
    }

    private void showSignMessageDialog(final String id, final PersonalMessage personalMessage) {
        if (this.activity == null) return;
        DialogUtil.getBaseDialog(
                this.activity,
                this.activity.getString(R.string.eth_sign_title),
                this.activity.getString(R.string.eth_sign_warning) + "\n\n" +
                TypeConverter.toJsonHex(personalMessage.getDataFromMessageAsBytes()),
                R.string.agree,
                R.string.cancel,
                (dialog, which) -> handleSignMessageClicked(id, personalMessage)
        ).show();
    }

    private void handleSignMessageClicked(final String id, final PersonalMessage personalMessage) {
        try {
            final EthereumSignedMessage ethereumSignedMessage = new EthereumSignedMessage(id, personalMessage);
            final Subscription sub = ethereumSignedMessage.signMessage()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            this::executeJavascriptMethod,
                            throwable -> LogUtil.w("Error " + throwable)
                    );
            this.subscriptions.add(sub);
        } catch (UnsupportedEncodingException e) {
            LogUtil.w("Error " + e);
        }
    }

    private void executeJavascriptMethod(final String methodCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(methodCall, null);
        } else {
            webView.loadUrl("javascript:" + methodCall);
        }
    }

    private String createErrorMessage(final String errorMessage) {
        return String.format("{\\\"error\\\":\\\"%s\\\"}", errorMessage);
    }

    public void clear() {
        this.subscriptions.clear();
    }
}
