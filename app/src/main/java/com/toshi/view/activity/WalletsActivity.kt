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

package com.toshi.view.activity

import android.arch.lifecycle.Observer
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import com.toshi.R
import com.toshi.extensions.addHorizontalLineDivider
import com.toshi.extensions.getMultiplePxSize
import com.toshi.extensions.getViewModel
import com.toshi.view.adapter.WalletAdapter
import com.toshi.viewModel.WalletsViewModel
import kotlinx.android.synthetic.main.activity_wallets.closeButton
import kotlinx.android.synthetic.main.activity_wallets.wallets

class WalletsActivity : AppCompatActivity() {

    private lateinit var viewModel: WalletsViewModel
    private lateinit var walletAdapter: WalletAdapter

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallets)
        init()
    }

    private fun init() {
        initViewModel()
        initClickListeners()
        initAdapter()
        initObservers()
    }

    private fun initViewModel() {
        viewModel = getViewModel()
    }

    private fun initClickListeners() {
        closeButton.setOnClickListener { finish() }
    }

    private fun initAdapter() {
        walletAdapter = WalletAdapter {}
        wallets.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = walletAdapter
            val leftPadding = getMultiplePxSize(R.dimen.margin_primary, R.dimen.margin_primary, R.dimen.margin_three_quarters)
            addHorizontalLineDivider(leftPadding = leftPadding)
            itemAnimator = null
        }
    }

    private fun initObservers() {
        viewModel.wallets.observe(this, Observer {
            if (it != null) walletAdapter.setItems(it)
        })
    }
}