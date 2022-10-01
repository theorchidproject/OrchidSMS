package com.moez.QKSMS.feature.blocking.manager

import android.content.Context
import com.moez.QKSMS.R
import com.moez.QKSMS.blocking.*
import com.moez.QKSMS.common.Navigator
import com.moez.QKSMS.common.base.QkPresenter
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.util.Preferences
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

class BlockingManagerPresenter @Inject constructor(
    private val callBlocker: CallBlockerBlockingClient,
    private val callControl: CallControlBlockingClient,
    private val context: Context,
    private val conversationRepo: ConversationRepository,
    private val navigator: Navigator,
    private val prefs: Preferences,
    private val qksms: QksmsBlockingClient,
    private val shouldIAnswer: ShouldIAnswerBlockingClient
) : QkPresenter<BlockingManagerView, BlockingManagerState>(BlockingManagerState(
        blockingManager = prefs.blockingManager.get(),
        callBlockerInstalled = callBlocker.isAvailable(),
        callControlInstalled = callControl.isAvailable(),
        siaInstalled = shouldIAnswer.isAvailable()
)) {

    init {
        disposables += prefs.blockingManager.asObservable()
                .subscribe { manager -> newState { copy(blockingManager = manager) } }
    }

    override fun bindIntents(view: BlockingManagerView) {
        super.bindIntents(view)

        view.activityResumed()
            .map { callBlocker.isAvailable() }
            .distinctUntilChanged()
            .autoDispose(view.scope())
                .subscribe { available -> newState { copy(callBlockerInstalled = available) } }

        view.activityResumed()
                .map { callControl.isAvailable() }
                .distinctUntilChanged()
                .autoDispose(view.scope())
                .subscribe { available -> newState { copy(callControlInstalled = available) } }

        view.activityResumed()
                .map { shouldIAnswer.isAvailable() }
                .distinctUntilChanged()
                .autoDispose(view.scope())
                .subscribe { available -> newState { copy(siaInstalled = available) } }

        view.qksmsClicked()
                .observeOn(Schedulers.io())
                .map { getAddressesToBlock(qksms) }
                .switchMap { numbers -> qksms.block(numbers).andThen(Observable.just(Unit)) } // Hack
                .autoDispose(view.scope())
                .subscribe {
                    prefs.blockingManager.set(Preferences.BLOCKING_MANAGER_QKSMS)
                }

        view.callBlockerClicked()
                .filter {
                    val installed = callBlocker.isAvailable()
                    if (!installed) {
                        navigator.installCallBlocker()
                    }

                    val enabled = prefs.blockingManager.get() == Preferences.BLOCKING_MANAGER_CB
                    installed && !enabled
                }
                .autoDispose(view.scope())
                .subscribe {
                    prefs.blockingManager.set(Preferences.BLOCKING_MANAGER_CB)
                }

        view.callControlClicked()
                .filter {
                    val installed = callControl.isAvailable()
                    if (!installed) {
                        navigator.installCallControl()
                    }

                    val enabled = prefs.blockingManager.get() == Preferences.BLOCKING_MANAGER_CC
                    installed && !enabled
                }
                .observeOn(Schedulers.io())
                .map { getAddressesToBlock(callControl) }
                .observeOn(AndroidSchedulers.mainThread())
                .switchMap { numbers ->
                    when (numbers.size) {
                        0 -> Observable.just(true)
                        else -> view.showCopyDialog(context.getString(R.string.blocking_manager_call_control_title))
                                .toObservable()
                    }
                }
                .doOnNext { newState { copy() } } // Radio button may have been selected when it shouldn't, fix it
                .filter { it }
                .observeOn(Schedulers.io())
                .map { getAddressesToBlock(callControl) } // This sucks. Can't wait to use coroutines
                .switchMap { numbers -> callControl.block(numbers).andThen(Observable.just(Unit)) } // Hack
                .autoDispose(view.scope())
                .subscribe {
                    callControl.shouldBlock("callcontrol").blockingGet()
                    prefs.blockingManager.set(Preferences.BLOCKING_MANAGER_CC)
                }

        view.siaClicked()
                .filter {
                    val installed = shouldIAnswer.isAvailable()
                    if (!installed) {
                        navigator.installSia()
                    }

                    val enabled = prefs.blockingManager.get() == Preferences.BLOCKING_MANAGER_SIA
                    installed && !enabled
                }
                .autoDispose(view.scope())
                .subscribe {
                    prefs.blockingManager.set(Preferences.BLOCKING_MANAGER_SIA)
                }
    }

    private fun getAddressesToBlock(client: BlockingClient) = conversationRepo.getBlockedConversations()
            .fold(listOf<String>(), { numbers, conversation -> numbers + conversation.recipients.map { it.address } })
            .filter { number -> client.isBlacklisted(number).blockingGet() !is BlockingClient.Action.Block }

}
