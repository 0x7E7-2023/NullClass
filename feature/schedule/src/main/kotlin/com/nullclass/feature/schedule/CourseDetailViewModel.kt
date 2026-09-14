package com.nullclass.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.ExamRepository
import com.nullclass.core.model.ExamWithCourse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 课程详情弹层的考试关联查询。课程 id 变化时自动切换观察对象。 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CourseDetailViewModel @Inject constructor(
    private val examRepository: ExamRepository,
) : ViewModel() {

    private val courseId = MutableStateFlow<String?>(null)

    val exams: StateFlow<List<ExamWithCourse>> = courseId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else examRepository.observeForCourse(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectCourse(id: String) {
        courseId.value = id
    }
}
