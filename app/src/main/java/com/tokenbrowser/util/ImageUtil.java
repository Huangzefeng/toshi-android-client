/*
 * 	Copyright (c) 2017. Token Browser, Inc
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

package com.tokenbrowser.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.Target;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.tokenbrowser.R;
import com.tokenbrowser.exception.QrCodeException;
import com.tokenbrowser.manager.network.image.CachedGlideUrl;
import com.tokenbrowser.manager.network.image.ForceLoadGlideUrl;
import com.tokenbrowser.view.BaseApplication;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import rx.Single;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

public class ImageUtil {

    public static void load(final String url, final ImageView imageView) {
        if (url == null || imageView == null) return;

        Single
            .fromCallable(() -> loadFromNetwork(url, imageView))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe(() -> renderFromCache(url, imageView))
            .subscribe(
                    __ -> {},
                    throwable -> LogUtil.exception(ImageUtil.class, throwable)
            );
    }

    @Nullable
    public static Target loadFromNetwork(final String url, final ImageView imageView) {
        try {
            return Glide
                    .with(imageView.getContext())
                    .load(new ForceLoadGlideUrl(url))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .into(imageView);
        } catch (final IllegalArgumentException ex) {
            LogUtil.i(ImageUtil.class, "Tried to render into a now destroyed view.");
            return null;
        }
    }

    private static void renderFromCache(final String url, final ImageView imageView) {
        try {
            Glide
                .with(imageView.getContext())
                .load(new CachedGlideUrl(url))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(imageView);
        } catch (final IllegalArgumentException ex) {
            LogUtil.i(ImageUtil.class, "Tried to render into a now destroyed view.");
        }
    }

    public static void renderFileIntoTarget(final File result, final ImageView imageView) {
        if (imageView == null || imageView.getContext() == null) return;

        try {
            Glide
                .with(imageView.getContext())
                .load(result)
                .into(imageView);
        } catch (final IllegalArgumentException ex) {
            LogUtil.i(ImageUtil.class, "Tried to render into a now destroyed view.");
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
            LogUtil.i(ImageUtil.class, "Tried to render into a now destroyed view.");
        }
    }

    public static Single<Bitmap> generateQrCode(final String value) {
        return Single.fromCallable(() -> {
            try {
                return generateQrCodeBitmap(value);
            } catch (final WriterException e) {
                throw new QrCodeException(e);
            }
        });
    }

    private static Bitmap generateQrCodeBitmap(final String value) throws WriterException {
        if (value == null) return null;
        
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
}
