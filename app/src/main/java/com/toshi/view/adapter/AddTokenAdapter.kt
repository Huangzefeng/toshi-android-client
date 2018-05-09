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

package com.toshi.view.adapter

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import com.toshi.R
import com.toshi.view.adapter.viewholder.AddTokenViewHolder

class AddTokenAdapter(
        private val onAddTokenClicked: () -> Unit
) : BaseCompoundableAdapter<AddTokenViewHolder, Int>() {

    init {
        setItemList(listOf(1))
    }

    override fun compoundableBindViewHolder(viewHolder: RecyclerView.ViewHolder, adapterIndex: Int) {
        val typedHolder = viewHolder as AddTokenViewHolder
        onBindViewHolder(typedHolder, adapterIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddTokenViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.list_item__add_token, parent, false)
        return AddTokenViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: AddTokenViewHolder, position: Int) {
        holder.itemView.setOnClickListener { onAddTokenClicked() }
    }
}