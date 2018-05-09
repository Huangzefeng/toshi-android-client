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

package com.toshi.view.fragment.dialogFragment

import android.app.Dialog
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import com.toshi.R
import com.toshi.extensions.toast
import com.toshi.viewModel.ShareWalletAddressViewModel
import kotlinx.android.synthetic.main.fragment_share_wallet_address.closeButton
import kotlinx.android.synthetic.main.fragment_share_wallet_address.copyBtn
import kotlinx.android.synthetic.main.fragment_share_wallet_address.networkStatusView
import kotlinx.android.synthetic.main.fragment_share_wallet_address.qrCodeView
import kotlinx.android.synthetic.main.fragment_share_wallet_address.shareBtn
import kotlinx.android.synthetic.main.fragment_share_wallet_address.walletAddress

class ShareWalletAddressDialog : DialogFragment() {

    companion object {
        const val TAG = "ShareWalletAddressDialog"
        fun newInstance(): ShareWalletAddressDialog {
            return ShareWalletAddressDialog()
        }
    }

    private lateinit var viewModel: ShareWalletAddressViewModel

    override fun onCreateDialog(state: Bundle?): Dialog {
        val dialog = super.onCreateDialog(state)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_share_wallet_address, container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    private fun init() {
        initViewModel()
        initNetworkView()
        initClickListeners()
        initObservers()
    }

    private fun initViewModel() {
        viewModel = ViewModelProviders.of(this).get(ShareWalletAddressViewModel::class.java)
    }

    private fun initNetworkView() {
        networkStatusView.setNetworkVisibility(viewModel.getNetworks())
    }

    private fun initClickListeners() {
        closeButton.setOnClickListener { dismiss() }
        copyBtn.setOnClickListener { copyToClipboard() }
        shareBtn.setOnClickListener { sharePaymentAddress() }
    }

    private fun copyToClipboard() {
        val paymentAddress = viewModel.paymentAddress.value
                ?: throw IllegalStateException("PaymentAddress is null")
        val clipboard = activity?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        clipboard?.primaryClip = ClipData.newPlainText(getString(R.string.payment_address), paymentAddress)
        toast(R.string.copied_to_clipboard)
    }

    private fun sharePaymentAddress() {
        val paymentAddress = viewModel.paymentAddress.value ?: return
        startActivity(Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, paymentAddress)
            type = "text/plain"
        })
    }

    private fun initObservers() {
        viewModel.qrCode.observe(this, Observer {
            if (it != null) renderQrCode(it)
        })
        viewModel.paymentAddress.observe(this, Observer {
            if (it != null) walletAddress.text = it
        })
        viewModel.error.observe(this, Observer {
            if (it != null) toast(it)
        })
    }

    private fun renderQrCode(qrCode: Bitmap) {
        qrCodeView.alpha = 0.0f
        qrCodeView.setImageBitmap(qrCode)
        qrCodeView.animate().alpha(1f).setDuration(200).start()
    }

    override fun onPause() {
        dismissAllowingStateLoss()
        super.onPause()
    }
}