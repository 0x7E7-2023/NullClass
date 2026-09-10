(function () {
    // 中国科学技术大学综合教务系统课表提取
    //
    // 取数流程：
    //   1. 定位 studentId 与 学期 semesterId：
    //      - 优先从当前页面 URL (/for-std/course-table/info/{studentId}) 或 DOM 元素 (#allSemesters)
    //      - 兜底通过 GET /for-std/course-table 自动重定向并解析页面 HTML
    //   2. GET /for-std/course-table/get-data?bizTypeId=2&semesterId={semesterId}&dataId={studentId}
    //      - 获取该学期发布的选课 ID 列表 (publishLessonIds)
    //   3. POST /for-std/course-table/datum
    //      - 参数 JSON { "lessonIds": publishLessonIds }
    //      - 获取完整的课程列表 (lessonList) 与全学期排课记录 (scheduleList)
    //
    // 个人隐私脱敏：已剔除身份证号、手机号、邮箱、personId 等敏感字段。

    function snippetOf(body) {
        var text = String(body || '').replace(/\s+/g, ' ');
        if (text.length > 180) text = text.slice(0, 180) + '…';
        return text;
    }

    function safeUrl(url) {
        return String(url).replace(/([?&](dataId|studentId)=)[^&]*/gi, '$1***');
    }

    function get(url, accept) {
        return fetch(url, {
            method: 'GET',
            credentials: 'include',
            headers: {
                'Accept': accept,
                'X-Requested-With': 'XMLHttpRequest'
            }
        }).then(function (response) {
            if (response.status === 401 || response.status === 403) {
                throw new Error('教务系统拒绝访问（' + response.status + '）：请在页面中登录后再试');
            }
            if (response.status < 200 || response.status >= 300) {
                throw new Error('教务系统返回异常状态码：' + response.status + '（' + safeUrl(url) + '）');
            }
            return response;
        }, function () {
            throw new Error('连不上教务系统：网络异常或会话已超时（' + safeUrl(url) + '）');
        });
    }

    function getText(url) {
        return get(url, 'text/html,application/xhtml+xml,*/*;q=0.8').then(function (res) {
            return res.text().then(function (body) {
                return { url: res.url, body: body };
            });
        });
    }

    function getJson(url) {
        return get(url, 'application/json, */*;q=0.1').then(function (res) {
            return res.text().then(function (body) {
                try {
                    return JSON.parse(body);
                } catch (e) {
                    var ctype = (res.headers && res.headers.get) ? (res.headers.get('content-type') || '') : '';
                    throw new Error(
                        '接口未返回合法 JSON（HTTP ' + res.status +
                        (ctype ? ' ' + ctype : '') + ' ' + safeUrl(res.url || url) +
                        '）：' + snippetOf(body)
                    );
                }
            });
        });
    }

    function postJson(url, payload) {
        return fetch(url, {
            method: 'POST',
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json, */*;q=0.1',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: JSON.stringify(payload)
        }).then(function (response) {
            if (response.status < 200 || response.status >= 300) {
                throw new Error('排课数据接口返回异常状态码：' + response.status);
            }
            return response.text().then(function (body) {
                try {
                    return JSON.parse(body);
                } catch (e) {
                    throw new Error('排课数据接口未返回合法 JSON：' + snippetOf(body));
                }
            });
        }, function () {
            throw new Error('请求排课数据接口网络失败');
        });
    }

    function extractStudentId(url, html) {
        var m = /\/for-std\/course-table\/info\/(\d+)/.exec(url) ||
            /\/for-std\/course-table\/(\d+)/.exec(url) ||
            /\/info\/(\d+)/.exec(url);
        if (m) return m[1];
        if (typeof window !== 'undefined') {
            var winM = /\/for-std\/course-table\/info\/(\d+)/.exec(window.location.pathname) ||
                /\/for-std\/course-table\/(\d+)/.exec(window.location.pathname);
            if (winM) return winM[1];
            if (typeof window.studentId !== 'undefined' && window.studentId) {
                return String(window.studentId);
            }
        }
        if (html) {
            var htmlM = /studentId\s*[:=]\s*['"]?(\d+)['"]?/.exec(html) ||
                /\/for-std\/course-table\/info\/(\d+)/.exec(html);
            if (htmlM) return htmlM[1];
        }
        return null;
    }

    function extractSemesterInfo(html) {
        var semesterId = null;
        var semesterName = null;
        var startDate = null;

        // 尝试从 DOM 读取
        if (typeof document !== 'undefined') {
            var elem = document.getElementById('allSemesters');
            if (elem && elem.value) {
                semesterId = elem.value;
                if (elem.options && elem.selectedIndex >= 0) {
                    semesterName = elem.options[elem.selectedIndex].text;
                }
            }
        }

        // 尝试从全局 semesters 读取
        var semList = null;
        if (typeof window !== 'undefined' && typeof window.semesters !== 'undefined' && window.semesters) {
            semList = window.semesters;
        }

        // 尝试从 HTML 正则匹配
        if (html) {
            if (!semesterId) {
                var optRegex = /<option([^>]*)value="(\d+)"[^>]*>([\s\S]*?)<\/option>/gi;
                var firstId = null;
                var firstName = null;
                var match;
                while ((match = optRegex.exec(html)) !== null) {
                    var attrs = match[1];
                    var val = match[2];
                    var label = match[3].replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
                    if (!firstId) {
                        firstId = val;
                        firstName = label;
                    }
                    if (attrs.indexOf('selected') >= 0) {
                        semesterId = val;
                        semesterName = label;
                        break;
                    }
                }
                if (!semesterId) {
                    semesterId = firstId;
                    semesterName = firstName;
                }
            }
            if (!semList) {
                var mSem = /var\s+semesters\s*=\s*(\[[\s\S]*?\]);/.exec(html);
                if (mSem) {
                    try { semList = JSON.parse(mSem[1]); } catch (e) {}
                }
            }
        }

        if (semList && semList.length) {
            if (!semesterId) {
                semesterId = String(semList[0].id);
                semesterName = semList[0].nameZh || semList[0].name;
            }
            for (var i = 0; i < semList.length; i++) {
                if (String(semList[i].id) === String(semesterId)) {
                    startDate = semList[i].startDate || null;
                    if (!semesterName) {
                        semesterName = semList[i].nameZh || semList[i].name;
                    }
                    break;
                }
            }
        }

        return {
            id: semesterId,
            name: semesterName || '当前学期',
            startDate: startDate
        };
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function calculateFirstMondayFromSchedule(s) {
        if (!s || !s.date || !s.weekIndex || !s.weekday) return null;
        var d = new Date(s.date);
        if (isNaN(d.getTime())) return null;
        var daysBack = (s.weekIndex - 1) * 7 + (s.weekday - 1);
        var firstMon = new Date(d.getTime() - daysBack * 86400000);
        return firstMon.getFullYear() + '-' + pad2(firstMon.getMonth() + 1) + '-' + pad2(firstMon.getDate());
    }

    function sanitizeLessons(rawList) {
        var out = [];
        var list = rawList || [];
        for (var i = 0; i < list.length; i++) {
            var l = list[i];
            var teachers = [];
            if (l.teacherAssignmentList && l.teacherAssignmentList.length) {
                for (var t = 0; t < l.teacherAssignmentList.length; t++) {
                    var item = l.teacherAssignmentList[t];
                    var name = item.name || (item.person && item.person.nameZh);
                    if (name && teachers.indexOf(name) === -1) {
                        teachers.push(name);
                    }
                }
            }
            out.push({
                id: l.id,
                code: l.code,
                name: l.courseName || l.name,
                teachers: teachers
            });
        }
        return out;
    }

    function sanitizeSchedules(rawList) {
        var out = [];
        var list = rawList || [];
        for (var i = 0; i < list.length; i++) {
            var s = list[i];
            var roomStr = null;
            if (s.room) {
                var parts = [];
                if (s.room.building && s.room.building.nameZh) parts.push(s.room.building.nameZh);
                if (s.room.nameZh) parts.push(s.room.nameZh);
                if (parts.length) roomStr = parts.join(' ');
            }
            out.push({
                lessonId: s.lessonId,
                weekday: s.weekday,
                startTime: s.startTime,
                endTime: s.endTime,
                periods: s.periods,
                weekIndex: s.weekIndex,
                date: s.date,
                personName: s.personName || null,
                room: roomStr,
                customPlace: s.customPlace || null
            });
        }
        return out;
    }

    var jwOrigin = 'https://jw.ustc.edu.cn';
    var tableUrl = (typeof window !== 'undefined' && window.location.href.indexOf('course-table') !== -1)
        ? window.location.href
        : jwOrigin + '/for-std/course-table';

    return getText(tableUrl).then(function (pageRes) {
        var studentId = extractStudentId(pageRes.url, pageRes.body);
        if (!studentId) {
            throw new Error('未获取到学生 ID，请确认教务系统已成功登录');
        }

        var semInfo = extractSemesterInfo(pageRes.body);
        if (!semInfo.id) {
            throw new Error('未获取到学期信息，请确认教务系统正常');
        }

        var getDataUrl = jwOrigin + '/for-std/course-table/get-data?bizTypeId=2&semesterId=' +
            encodeURIComponent(semInfo.id) + '&dataId=' + encodeURIComponent(studentId);

        return getJson(getDataUrl).then(function (getDataRes) {
            var lessonIds = getDataRes.publishLessonIds || getDataRes.lessonIds || [];
            if (!lessonIds.length) {
                return JSON.stringify({
                    term: {
                        id: semInfo.id,
                        name: semInfo.name,
                        firstDay: semInfo.startDate,
                        totalWeeks: 18
                    },
                    lessonList: [],
                    scheduleList: []
                });
            }

            var apiFirstDay = null;
            if (getDataRes.oddWeekIndex2dayOfWeek2Date && getDataRes.oddWeekIndex2dayOfWeek2Date['1']) {
                apiFirstDay = getDataRes.oddWeekIndex2dayOfWeek2Date['1']['1'];
            }
            var apiTotalWeeks = (getDataRes.weekIndices && getDataRes.weekIndices.length)
                ? getDataRes.weekIndices.length
                : null;
            var apiTermName = null;
            if (getDataRes.lessons && getDataRes.lessons.length && getDataRes.lessons[0].semester) {
                apiTermName = getDataRes.lessons[0].semester.nameZh;
            }

            return postJson(jwOrigin + '/for-std/course-table/datum', { lessonIds: lessonIds }).then(function (datumRes) {
                var resObj = (datumRes && datumRes.result) ? datumRes.result : {};
                var cleanLessons = sanitizeLessons(resObj.lessonList);
                var cleanSchedules = sanitizeSchedules(resObj.scheduleList);

                var firstDay = apiFirstDay || semInfo.startDate;
                if (!firstDay && cleanSchedules.length > 0) {
                    firstDay = calculateFirstMondayFromSchedule(cleanSchedules[0]);
                }

                var maxWeek = apiTotalWeeks || 0;
                for (var w = 0; w < cleanSchedules.length; w++) {
                    if (cleanSchedules[w].weekIndex > maxWeek) {
                        maxWeek = cleanSchedules[w].weekIndex;
                    }
                }

                return JSON.stringify({
                    term: {
                        id: semInfo.id,
                        name: apiTermName || semInfo.name,
                        firstDay: firstDay,
                        totalWeeks: Math.max(18, maxWeek)
                    },
                    lessonList: cleanLessons,
                    scheduleList: cleanSchedules
                });
            });
        });
    });
})()
