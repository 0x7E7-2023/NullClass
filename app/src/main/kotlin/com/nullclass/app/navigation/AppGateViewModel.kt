package com.nullclass.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.TimetableRepository
import com.nullclass.core.data.repository.TimetableOverview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 首次启动闸门：新装应用第一件事是创建课表。
 * 库里一张课表都没有时整棵导航树不渲染，只渲染创建页。
 */
@HiltViewModel
class AppGateViewModel @Inject constructor(
    timetableRepository: TimetableRepository,
) : ViewModel() {

    /** null = 首帧还没读到（画背景色，不闪引导页）；空列表 = 全新安装，进引导。 */
    val overviews: StateFlow<List<TimetableOverview>?> =
        timetableRepository.observeOverviews()
            .map<List<TimetableOverview>, List<TimetableOverview>?> { it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
