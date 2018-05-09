/*
 * Copyright (c) 2017. Toshi Inc
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

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import com.toshi.R
import com.toshi.util.KeyboardUtil
import com.toshi.view.adapter.listeners.TextChangedListener
import kotlinx.android.synthetic.main.activity_add_custom_token.addTokenBtn
import kotlinx.android.synthetic.main.activity_add_custom_token.contractAddress
import kotlinx.android.synthetic.main.activity_add_custom_token.decimals
import kotlinx.android.synthetic.main.activity_add_custom_token.name
import kotlinx.android.synthetic.main.activity_add_custom_token.symbol
import kotlinx.android.synthetic.main.activity_chat.closeButton

class AddCustomTokenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_custom_token)
        init()
    }

    private fun init() {
        initClickListeners()
        initTextListeners()
    }

    private fun initClickListeners() {
        closeButton.setOnClickListener { hideKeyboardAndFinish() }
    }

    private fun hideKeyboardAndFinish() {
        KeyboardUtil.hideKeyboard(contractAddress)
        finish()
    }

    private fun initTextListeners() {
        contractAddress.addTextChangedListener(textListener)
        name.addTextChangedListener(textListener)
        symbol.addTextChangedListener(textListener)
        decimals.addTextChangedListener(textListener)
    }

    private val textListener = object : TextChangedListener() {
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val isInputValid = isInputValid()
            if (isInputValid) enableAddBtn()
            else disableAddBtn()
        }
    }

    private fun isInputValid(): Boolean {
        val isContractAddressValid = contractAddress.text.isNotEmpty()
        val isNameValid = name.text.isNotEmpty()
        val isSymbolValid = symbol.text.isNotEmpty()
        val isDecimalsValid = decimals.text.isNotEmpty()
        return isContractAddressValid && isNameValid && isSymbolValid && isDecimalsValid
    }

    private fun enableAddBtn() {
        addTokenBtn.isClickable = true
        addTokenBtn.setBackgroundResource(R.drawable.background_with_radius_primary_color)
    }

    private fun disableAddBtn() {
        addTokenBtn.isClickable = false
        addTokenBtn.setBackgroundResource(R.drawable.background_with_radius_disabled)
    }
}