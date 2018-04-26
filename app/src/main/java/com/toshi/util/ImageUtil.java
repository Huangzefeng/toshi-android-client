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

package com.toshi.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.widget.ImageView;

import com.bumptech.glide.DrawableTypeRequest;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.Target;
import com.google.common.io.Files;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.toshi.R;
import com.toshi.exception.QrCodeException;
import com.toshi.extensions.Identicon;
import com.toshi.manager.network.image.CachedGlideUrl;
import com.toshi.manager.network.image.ForceLoadGlideUrl;
import com.toshi.model.local.Avatar;
import com.toshi.model.local.Recipient;
import com.toshi.util.logging.LogUtil;
import com.toshi.view.BaseApplication;
import com.toshi.view.custom.CropCircleTransformation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import rx.Completable;
import rx.Single;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

public class ImageUtil {

    private static final List<String> supportedImageTypes = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");

    public static void load(final String url, final ImageView imageView) {
        if (url == null || imageView == null) return;

        Single
            .fromCallable(() -> loadFromNetwork(url, imageView))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe(() -> renderFromCache(url, imageView))
            .subscribe(
                    __ -> {},
                    throwable -> LogUtil.exception(throwable)
            );
    }

    @Nullable
    public static Target loadFromNetwork(final String url, final ImageView imageView) {
        try {
            return Glide
                    .with(imageView.getContext())
                    .load(new ForceLoadGlideUrl(url))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .error(R.drawable.placeholder)
                    .skipMemoryCache(true)
                    .into(imageView);
        } catch (final IllegalArgumentException ex) {
            LogUtil.i("Tried to render into a now destroyed view.");
            return null;
        }
    }

    private static void renderFromCache(final String url, final ImageView imageView) {
        try {
            Glide
                .with(imageView.getContext())
                .load(new CachedGlideUrl(url))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .error(R.drawable.placeholder)
                .skipMemoryCache(true)
                .into(imageView);
        } catch (final IllegalArgumentException ex) {
            LogUtil.i("Tried to render into a now destroyed view.");
        }
    }

    public static void renderFileIntoTarget(final File result, final ImageView imageView) {
        if (imageView == null || imageView.getContext() == null) return;

        try {
            Glide
                .with(imageView.getContext())
                .load(result)
                .diskCacheStrategy(DiskCacheStrategy.SOURCE)
                .into(imageView);
        } catch (final IllegalArgumentException ex) {
            LogUtil.i("Tried to render into a now destroyed view.");
        }
    }

    public static void renderFileIntoTarget(final Uri uri, final ImageView imageView) {
        if (imageView == null || imageView.getContext() == null) return;

        try {
            Glide
                    .with(imageView.getContext())
                    .load(uri)
                    .into(imageView);
        } catch (final IllegalArgumentException ex) {
            LogUtil.i("Tried to render into a now destroyed view.");
        }
    }

    public static void load(final Avatar avatar, final ImageView imageView) {
        if (avatar == null || avatar.getBytes() == null || imageView == null) return;

        Single
                .fromCallable(() -> BitmapFactory.decodeByteArray(avatar.getBytes(), 0, avatar.getBytes().length))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        imageView::setImageBitmap,
                        LogUtil::exception
                );
    }

    public static void loadIdenticon(final String value, final ImageView imageView) {
        if (value == null || imageView == null) return;

        Single
                .fromCallable(() -> Identicon.toIdenticon(value))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        imageView::setImageBitmap,
                        LogUtil::exception
                );
    }

    public static Single<Bitmap> loadAsBitmap(final Uri uri, final Context context) {
        return Single.fromCallable(() -> {
            if (uri == null || context == null) return null;

            try {
                return Glide
                        .with(context)
                        .load(uri)
                        .asBitmap()
                        .into(300, 300)
                        .get();
            } catch (final InterruptedException | ExecutionException ex) {
                LogUtil.i("Error fetching bitmap. " + ex);
            }
            return null;
        })
        .subscribeOn(Schedulers.io());
    }

    public static Bitmap loadNotificationIcon(final Recipient recipient) throws ExecutionException, InterruptedException {
        final RequestManager requestManager = Glide.with(BaseApplication.get());
        final DrawableTypeRequest typeRequest = recipient.isGroup()
                ? requestManager.load(recipient.getGroupAvatar().getBytes())
                : requestManager.load(recipient.getUserAvatar());
        return (Bitmap) typeRequest
                .asBitmap()
                .transform(new CropCircleTransformation(BaseApplication.get()))
                .into(200, 200)
                .get();
    }

    public static Single<Bitmap> generateQrCode(@NonNull final String value) {
        return Single.fromCallable(() -> {
            try {
                return generateQrCodeBitmap(value);
            } catch (final WriterException e) {
                throw new QrCodeException(e);
            }
        });
    }

    private static Bitmap generateQrCodeBitmap(@NonNull final String value) throws WriterException {
        final QRCodeWriter writer = new QRCodeWriter();
        final int size = BaseApplication.get().getResources().getDimensionPixelSize(R.dimen.qr_code_size);
        final Map<EncodeHintType, Integer> map = new HashMap<>();
        map.put(EncodeHintType.MARGIN, 0);
        final BitMatrix bitMatrix = writer.encode(value, BarcodeFormat.QR_CODE, size, size, map);
        final int width = bitMatrix.getWidth();
        final int height = bitMatrix.getHeight();
        final Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        final int contrastColour = ContextCompat.getColor(BaseApplication.get(), R.color.windowBackground);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : contrastColour);
            }
        }
        return bmp;
    }

    public static boolean isImageType(final String path) {
        if (path == null) return false;
        final String fileExtension = Files.getFileExtension(path.toLowerCase());
        return supportedImageTypes.contains(fileExtension);
    }

    public static @Nullable byte[] toByteArray(@Nullable Bitmap bitmap) {
        final Bitmap.CompressFormat format = Bitmap.CompressFormat.PNG;
        if (bitmap == null) return null;
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(format, 100, stream);
        return stream.toByteArray();
    }

    public static void clear() {
        Completable.fromAction(() -> {
            Glide
                    .get(BaseApplication.get())
                    .clearDiskCache();
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(
                () -> Glide.get(BaseApplication.get()).clearMemory(),
                t -> LogUtil.w(t.toString())
        );
    }

    public static void loadImageOrPlaceholder(final @NonNull ImageView imageView, @Nullable final String url) {
        if (url != null) ImageUtil.load(url, imageView);
        else imageView.setImageResource(R.drawable.placeholder);
    }
}