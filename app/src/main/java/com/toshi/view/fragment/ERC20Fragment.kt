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

package com.toshi.view.fragment

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.v4.app.FragmentActivity
import android.support.v7.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.toshi.R
import com.toshi.extensions.addHorizontalLineDivider
import com.toshi.extensions.isVisible
import com.toshi.extensions.startActivity
import com.toshi.extensions.toast
import com.toshi.model.network.token.ERCToken
import com.toshi.model.network.token.EtherToken
import com.toshi.model.network.token.Token
import com.toshi.view.activity.AddCustomTokenActivity
import com.toshi.view.activity.ViewTokenActivity
import com.toshi.view.adapter.AddTokenAdapter
import com.toshi.view.adapter.CompoundAdapter
import com.toshi.view.adapter.TokenAdapter
import com.toshi.view.adapter.TokenFooterAdapter
import com.toshi.view.adapter.viewholder.TokenType
import com.toshi.view.fragment.toplevel.WalletFragment
import com.toshi.viewModel.TokenViewModel
import kotlinx.android.synthetic.main.fragment_token.emptyState
import kotlinx.android.synthetic.main.fragment_token.emptyStateTitle
import kotlinx.android.synthetic.main.fragment_token.loadingSpinner
import kotlinx.android.synthetic.main.fragment_token.tokens

class ERC20Fragment : RefreshFragment() {

    private lateinit var viewModel: TokenViewModel

    private lateinit var compoundAdapter: CompoundAdapter
    private lateinit var tokenAdapter: TokenAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_token, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = init()

    private fun init() {
        val activity = activity ?: return
        initViewModel(activity)
        initAdapter()
        initObservers()
    }

    private fun initViewModel(activity: FragmentActivity) {
        viewModel = ViewModelProviders.of(activity).get(TokenViewModel::class.java)
    }

    private fun initAdapter() {
        tokenAdapter = TokenAdapter(TokenType.ERC20Token())
        tokenAdapter.tokenListener = { startViewTokenActivity(it) }

        val addTokenAdapter = AddTokenAdapter { startActivity<AddCustomTokenActivity>() }
        val tokenFooterAdapter = TokenFooterAdapter()

        compoundAdapter = CompoundAdapter(
                listOf(
                        tokenAdapter,
                        addTokenAdapter,
                        tokenFooterAdapter
                )
        )

        tokens.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = compoundAdapter
            addHorizontalLineDivider(skipNEndPositions = 1)
        }
    }

    private fun startViewTokenActivity(token: Token) {
        when (token) {
            is EtherToken -> startActivity<ViewTokenActivity> { EtherToken.buildIntent(this, token) }
            is ERCToken -> startActivity<ViewTokenActivity> { ERCToken.buildIntent(this, token) }
            else -> throw IllegalStateException(Throwable("Invalid token in this context"))
        }
    }

    private fun initObservers() {
        viewModel.erc20Tokens.observe(this, Observer {
            if (it != null) showTokensOrEmptyState(it)
            stopRefreshing()
        })
        viewModel.erc20error.observe(this, Observer {
            if (it != null) toast(it)
            stopRefreshing()
            showEmptyStateView()
        })
        viewModel.isERC20Loading.observe(this, Observer {
            if (it != null) loadingSpinner.isVisible(it)
        })
    }

    private fun showTokensOrEmptyState(ERCTokens: List<Token>) {
        if (ERCTokens.isNotEmpty()) showAndAddTokens(ERCTokens)
        else showEmptyStateView()
    }

    private fun showAndAddTokens(ERCTokenList: List<Token>) {
        tokens.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        tokenAdapter.setItemList(ERCTokenList)
    }

    private fun showEmptyStateView() {
        emptyState.visibility = View.VISIBLE
        tokens.visibility = View.GONE
        emptyStateTitle.text = getString(R.string.empty_state_tokens)
    }

    override fun refresh() = viewModel.fetchERC20Tokens()

    override fun stopRefreshing() {
        if (parentFragment is WalletFragment) {
            val parentFragment = parentFragment as WalletFragment
            parentFragment.stopRefreshing()
        }
    }
}