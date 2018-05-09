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
import com.toshi.model.network.token.Token
import com.toshi.view.activity.ViewERC721TokensActivity
import com.toshi.view.activity.ViewERC721TokensActivity.Companion.COLLECTIBLE_NAME
import com.toshi.view.activity.ViewERC721TokensActivity.Companion.CONTRACT_ADDRESS
import com.toshi.view.adapter.TokenAdapter
import com.toshi.view.adapter.viewholder.TokenType
import com.toshi.view.fragment.toplevel.WalletFragment
import com.toshi.viewModel.TokenViewModel
import kotlinx.android.synthetic.main.fragment_token.emptyState
import kotlinx.android.synthetic.main.fragment_token.emptyStateTitle
import kotlinx.android.synthetic.main.fragment_token.loadingSpinner
import kotlinx.android.synthetic.main.fragment_token.tokens

class ERC721Fragment : RefreshFragment() {
    private lateinit var viewModel: TokenViewModel
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
        tokenAdapter = TokenAdapter(TokenType.ERC721Token())
        tokens.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = tokenAdapter
            addHorizontalLineDivider(skipNEndPositions = 1)
        }
        tokenAdapter.ERC721Listener = {
            startActivity<ViewERC721TokensActivity> {
                putExtra(CONTRACT_ADDRESS, it.contractAddress)
                putExtra(COLLECTIBLE_NAME, it.name)
            }
        }
    }

    private fun initObservers() {
        viewModel.erc721Tokens.observe(this, Observer {
            if (it != null) showTokensOrEmptyState(it)
            stopRefreshing()
        })
        viewModel.erc721error.observe(this, Observer {
            if (it != null) toast(it)
            stopRefreshing()
            showEmptyStateView()
        })
        viewModel.isERC721Loading.observe(this, Observer {
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
        emptyStateTitle.text = getString(R.string.empty_state_collectibles)
    }

    override fun refresh() = viewModel.fetchERC721Tokens()

    override fun stopRefreshing() {
        if (parentFragment is WalletFragment) {
            val parentFragment = parentFragment as WalletFragment
            parentFragment.stopRefreshing()
        }
    }
}