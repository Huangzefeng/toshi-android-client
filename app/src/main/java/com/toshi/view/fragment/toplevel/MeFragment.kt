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

package com.toshi.view.fragment.toplevel

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.v4.app.FragmentActivity
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.toshi.BuildConfig
import com.toshi.R
import com.toshi.extensions.addHorizontalLineDivider
import com.toshi.extensions.getColorById
import com.toshi.extensions.getPxSize
import com.toshi.extensions.startActivity
import com.toshi.extensions.startActivityAndFinish
import com.toshi.extensions.startActivityForResult
import com.toshi.extensions.toast
import com.toshi.model.local.User
import com.toshi.model.network.Balance
import com.toshi.util.ImageUtil
import com.toshi.util.ScannerResultType
import com.toshi.util.sharedPrefs.AppPrefs
import com.toshi.view.activity.BackupPhraseInfoActivity
import com.toshi.view.activity.CurrencyActivity
import com.toshi.view.activity.LicenseListActivity
import com.toshi.view.activity.NetworkSwitcherActivity
import com.toshi.view.activity.ScannerActivity
import com.toshi.view.activity.SignOutActivity
import com.toshi.view.activity.ViewProfileActivity
import com.toshi.view.activity.WalletsActivity
import com.toshi.view.adapter.MeAdapter
import com.toshi.view.adapter.listeners.OnItemClickListener
import com.toshi.viewModel.MeViewModel
import kotlinx.android.synthetic.main.fragment_me.avatar
import kotlinx.android.synthetic.main.fragment_me.backupPhrase
import kotlinx.android.synthetic.main.fragment_me.checkboxBackupPhrase
import kotlinx.android.synthetic.main.fragment_me.currentNetwork
import kotlinx.android.synthetic.main.fragment_me.currentWallet
import kotlinx.android.synthetic.main.fragment_me.myProfileCard
import kotlinx.android.synthetic.main.fragment_me.name
import kotlinx.android.synthetic.main.fragment_me.network
import kotlinx.android.synthetic.main.fragment_me.securityStatus
import kotlinx.android.synthetic.main.fragment_me.settings
import kotlinx.android.synthetic.main.fragment_me.username
import kotlinx.android.synthetic.main.fragment_me.version
import kotlinx.android.synthetic.main.fragment_me.wallet
import java.math.BigInteger

class MeFragment : TopLevelFragment() {

    companion object {
        private const val TAG = "MeFragment"
        private const val SCAN_REQUEST_CODE = 200
    }

    override fun getFragmentTag() = TAG

    private lateinit var meAdapter: MeAdapter
    private lateinit var viewModel: MeViewModel

    private var scannerCounter = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, inState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_me, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = init()

    private fun init() {
        val activity = activity ?: return
        setStatusBarColor(activity)
        initViewModel(activity)
        setVersionName()
        setSecurityState()
        initClickListeners()
        initRecyclerView()
        initObservers()
    }

    private fun setStatusBarColor(activity: FragmentActivity) {
        activity.window.statusBarColor = getColorById(R.color.colorPrimaryDark) ?: 0
    }

    private fun initViewModel(activity: FragmentActivity) {
        viewModel = ViewModelProviders.of(activity).get(MeViewModel::class.java)
    }

    private fun setVersionName() {
        val versionName = BuildConfig.VERSION_NAME
        val appVersion = getString(R.string.app_version, versionName)
        version.text = appVersion
    }

    private fun setSecurityState() {
        if (AppPrefs.hasBackedUpPhrase()) {
            checkboxBackupPhrase.isChecked = true
            securityStatus.visibility = View.GONE
        }
    }

    private fun initClickListeners() {
        myProfileCard.setOnClickListener { startActivity<ViewProfileActivity>() }
        backupPhrase.setOnClickListener { startActivity<BackupPhraseInfoActivity>() }
        wallet.setOnClickListener { startActivity<WalletsActivity>() }
        network.setOnClickListener { startActivity<NetworkSwitcherActivity>() }
        version.setOnClickListener { handleVersionClicked() }
    }

    private fun handleVersionClicked() {
        scannerCounter++
        if (scannerCounter % 10 == 0) startActivityForResult<ScannerActivity>(SCAN_REQUEST_CODE) {
            putExtra(ScannerActivity.SCANNER_RESULT_TYPE, ScannerResultType.NO_ACTION)
        }
    }

    private fun initRecyclerView() {
        meAdapter = MeAdapter()
                .apply { onItemClickListener = OnItemClickListener { handleItemClickListener(it) } }

        settings.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = meAdapter
            addHorizontalLineDivider(leftPadding = getPxSize(R.dimen.margin_primary))
        }
    }

    private fun handleItemClickListener(option: Int) {
        when (option) {
            MeAdapter.LOCAL_CURRENCY -> startActivity<CurrencyActivity>()
            MeAdapter.LEGAL_AND_PRIVACY -> startActivity<LicenseListActivity>()
            MeAdapter.SIGN_OUT -> viewModel.getBalance()
            else -> toast(R.string.option_not_supported)
        }
    }

    private fun initObservers() {
        viewModel.user.observe(this, Observer {
            user -> user?.let { updateUi(it) } ?: handleNoUser()
        })
        viewModel.singleBalance.observe(this, Observer {
            balance -> balance?.let { showDialog(it) }
        })
        viewModel.currentNetwork.observe(this, Observer {
            if (it != network) currentNetwork.text = it?.name
        })
        viewModel.currentWalletName.observe(this, Observer {
            if (it != null) currentWallet.text = it
        })
    }

    private fun updateUi(user: User) {
        name.text = user.displayName
        username.text = user.username
        ImageUtil.load(user.avatar, avatar)
    }

    private fun handleNoUser() {
        name.text = getString(R.string.profile__unknown_name)
        username.text = ""
        avatar.setImageResource(R.drawable.ic_unknown_user_24dp)
    }

    private fun showDialog(balance: Balance) {
        val isWalletEmpty = balance.unconfirmedBalance.compareTo(BigInteger.ZERO) == 0
        val shouldCancelSignOut = !AppPrefs.hasBackedUpPhrase() && !isWalletEmpty
        if (shouldCancelSignOut) showSignOutCancelledDialog()
        else showSignOutWarning()
    }

    private fun showSignOutCancelledDialog() {
        val context = context ?: return
        val builder = AlertDialog.Builder(context, R.style.AlertDialogCustom)
                .setTitle(R.string.sign_out_cancelled_title)
                .setMessage(R.string.sign_out_cancelled_message)
                .setPositiveButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
        builder.create().show()
    }

    private fun showSignOutWarning() {
        val context = context ?: return
        val builder = AlertDialog.Builder(context, R.style.AlertDialogCustom)
                .setTitle(R.string.sign_out_warning_title)
                .setMessage(R.string.sign_out_warning_message)
                .setPositiveButton(R.string.yes) { dialog, _ ->
                    dialog.dismiss()
                    showSignOutConfirmationDialog()
                }
                .setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
        builder.create().show()
    }

    private fun showSignOutConfirmationDialog() {
        val context = context ?: return
        val builder = AlertDialog.Builder(context, R.style.AlertDialogCustom)
                .setTitle(R.string.sign_out_confirmation_title)
                .setPositiveButton(R.string.sign_out) { dialog, _ ->
                    dialog.dismiss()
                    startActivityAndFinish<SignOutActivity>()
                }
                .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
        builder.create().show()
    }

    override fun onStart() {
        super.onStart()
        updateMeAdapter()
        updateNetworkView()
    }

    private fun updateMeAdapter() = meAdapter.notifyDataSetChanged()

    private fun updateNetworkView() = getMainActivity()?.showNetworkStatusView()
}