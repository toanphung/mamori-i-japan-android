package jp.co.tracecovid19.screen.trace

import androidx.lifecycle.ViewModel
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import jp.co.tracecovid19.data.database.deepcontactuser.DeepContactUserEntity
import jp.co.tracecovid19.data.model.DeepContact
import jp.co.tracecovid19.data.repository.trase.TraceRepository
import jp.co.tracecovid19.screen.common.LogoutHelper
import jp.co.tracecovid19.screen.common.TraceCovid19Error
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.*


class TraceDataUploadViewModel(private val traceRepository: TraceRepository,
                               private val logoutHelper: LogoutHelper,
                               private val disposable: CompositeDisposable): ViewModel() {

    lateinit var navigator: TraceDataUploadNavigator

    val uploadState = PublishSubject.create<UploadState>()
    val uploadError = PublishSubject.create<TraceCovid19Error>()

    enum class UploadState {
        Ready,
        InProgress,
        Complete
    }

    override fun onCleared() {
        disposable.dispose()
        super.onCleared()
    }

    fun onClickUpload() {
        // アップロード開始
        uploadState.onNext(UploadState.InProgress)
        // TODO 仮
        val data1 = DeepContact.create(DeepContactUserEntity("test1", Date().time, Date().time + 200000))
        val data2 = DeepContact.create(DeepContactUserEntity("test2", Date().time + 3000000, Date().time + 4000000))
        // TODO 濃厚接触情報をLiveDataではなく持ってくる方法
        traceRepository.uploadDeepContacts(listOf(data1, data2))
            .subscribeOn(Schedulers.io())
            .subscribeBy(
                onSuccess = {
                    uploadState.onNext(UploadState.Complete)
                },
                onError = { e ->
                    uploadState.onNext(UploadState.Ready)
                    val reason = TraceCovid19Error.mappingReason(e)
                    if (reason == TraceCovid19Error.Reason.Auth) {
                        // 認証エラーの場合はログアウト処理をする
                        runBlocking (Dispatchers.IO) {
                            logoutHelper.logout()
                        }
                    }
                    uploadError.onNext(
                        when (reason) {
                            TraceCovid19Error.Reason.NetWork -> TraceCovid19Error(reason, "文言検討18",
                                TraceCovid19Error.Action.DialogCloseOnly
                            )
                            TraceCovid19Error.Reason.Auth -> TraceCovid19Error(reason, "文言検討22",
                                TraceCovid19Error.Action.DialogLogout
                            )
                            else -> TraceCovid19Error(reason, "文言検討19",
                                TraceCovid19Error.Action.DialogCloseOnly
                            )
                        })
                }
            ).addTo(disposable)
        /*
        traceRepository.selectAllDeepContacttUsers().value?.map { DeepContact.create(it) }?.let {
            // TODO 0件の時どうする？
            if (it.count() == 0) {
                return
            }
        }?: return // TODO 0件の時どうする*/
    }

    fun onClickHome() {
        navigator.goToHome()
    }
}