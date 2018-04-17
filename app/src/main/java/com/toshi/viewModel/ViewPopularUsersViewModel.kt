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

package com.toshi.viewModel

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import com.toshi.R
import com.toshi.manager.RecipientManager
import com.toshi.model.network.user.UserType
import com.toshi.model.network.user.UserV2
import com.toshi.util.SingleLiveEvent
import com.toshi.view.BaseApplication
import rx.android.schedulers.AndroidSchedulers
import rx.subscriptions.CompositeSubscription

class ViewPopularUsersViewModel(
        userType: UserType,
        private val recipientManager: RecipientManager = BaseApplication.get().recipientManager
) : ViewModel() {

    private val subscriptions by lazy { CompositeSubscription() }

    val popularUsers by lazy { MutableLiveData<List<UserV2>>() }
    val error by lazy { SingleLiveEvent<Int>() }
    val isLoading by lazy { MutableLiveData<Boolean>() }

    init {
        getPopularUsers(userType)
    }

    private fun getPopularUsers(userType: UserType) {
        val sub = recipientManager
                .searchForUsers(userType.name.toLowerCase())
                .map { it.results }
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { isLoading.value = true }
                .doAfterTerminate { isLoading.value = false }
                .subscribe(
                        { popularUsers.value = it },
                        { error.value = R.string.error_fetching_users }
                )

        subscriptions.add(sub)
    }

    override fun onCleared() {
        super.onCleared()
        subscriptions.clear()
    }
}