(function () {
    // 郑州航空工业管理学院教务适配器（树维 EAMS 平台）—— 第一步：只取数，把教务的原始数据原样交出去。
    //
    // 平台是**上海树维信息科技有限公司（SupWisdom，新开普子公司）的综合教务系统**（路径带 /eams/），
    // 不是湖南强智 —— 本批（批次四）统一订正过这个厂商名，证据见 AUDIT.md §1。
    //
    // 移植自 shiguang_warehouse 的 ZUA/zua.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游 maintainer xBefore）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 移植改动：
    //   ① ES6 → ES5（异步改成 then 链，去掉模板串、箭头函数、块级声明与 URLSearchParams）
    //   ② 地址不再写死 http://jwglxt.zua.edu.cn（上游三条 fetch 都用的绝对地址）：改成按当前页面的
    //      origin + /eams 上下文路径拼（eamsBase()）。走 http 还是 https、有没有挂在门户 / WebVPN
    //      前缀下，都由用户实际打开的那个地址决定，脚本不替教务系统做主
    //   ③ 只取数：TaskActivity 的解析、周次位图、作息、开学日全部挪到 parse.js（CI 里跑得到的那一段）；
    //      这里交出去的是课表 HTML 原文、学期日历 HTML 原文、学期列表的原始字段
    //   ④ 不弹窗问学期：上游用 showSingleSelection 让用户挑，这里自动取教务当前选中的那个学期
    //      （学期元素上的 value → dataQuery 响应里的 semesterId → 只有一个学期就取它 →
    //      否则取列表第一个并写进 warnings，见 parse.js）
    //   ⑤ 上游的 showToast / saveImportedCourses / saveCourseConfig / savePresetTimeSlots /
    //      notifyTaskCompletion 这些桥调用全部没有移植：我们这条链路是「拉」不是「推」，
    //      作息表也不单独推，直接由 parse.js 放进载荷的 periodTimes
    //   ⑥ 顺带把响应里 TaskActivity 的个数交出去对账（上游不数它），parse.js 取不全时会出声
    //   ⑦ 只交出上面这几样：学期列表里的 id / 名称 / 学年（以及学期起止日期，如果有）之外，
    //      响应里可能夹带的其它字段一律不带出，也不进 fixture
    //
    // 取数方式：同源请求，三到四条，全部打在本校教务主机上（地址都由当前页面的 origin 拼出来）：
    //   ① GET  /eams/courseTableForStd.action                      读 ids / 学期下拉元素的 id 与当前值
    //                                                              （同一份 HTML 也是 parse.js 读作息表头的来源）
    //   ② POST /eams/dataQuery.action（dataType=semesterCalendar）  取学期列表
    //   ③ POST /eams/courseTableForStd!courseTable.action           取课表 HTML（课程是内嵌的 TaskActivity）
    //   ④ POST /eams/base/calendar-info.action                      取学期日历（开学日与总周数；上游同款）
    //                                                              取不到不算失败，交空串并写进 warnings
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。

    var EAMS = '/eams';

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 树维 EAMS 的上下文路径是 /eams；万一挂在前缀下（门户 / 网关），跟着当前地址里的那一段走
    function eamsBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(EAMS + '/');
        if (at > 0) return originOf() + path.substring(0, at + EAMS.length);
        return originOf() + EAMS;
    }

    function textOf(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function localTodayIso() {
        var now = new Date();
        var month = now.getMonth() + 1;
        var day = now.getDate();
        return now.getFullYear() + '-' + (month < 10 ? '0' : '') + month + '-' + (day < 10 ? '0' : '') + day;
    }

    function request(url, method, body) {
        var options = {
            method: method,
            credentials: 'include',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }
        };
        if (typeof body === 'string') options.body = body;
        return fetch(url, options).then(function (response) {
            if (response.status < 200 || response.status >= 300) {
                throw new Error(
                    '教务系统返回 HTTP ' + response.status +
                    '：登录状态可能已失效，请重新登录后再点「提取课表」'
                );
            }
            return response.text();
        }, function (error) {
            var detail = error && error.message ? '（' + error.message + '）' : '';
            throw new Error('连不上教务系统' + detail + '：请确认已登录，再点「提取课表」');
        });
    }

    function get(url) {
        return request(url, 'GET', null);
    }

    function post(url, body) {
        return request(url, 'POST', body);
    }

    function pair(name, value) {
        return encodeURIComponent(name) + '=' + encodeURIComponent(String(value === null || value === undefined ? '' : value));
    }

    // 教务返回的是课表 HTML；登录态失效时它会回登录页而不是报错，这里认一下，别把登录页当课表
    function looksLikeLogin(html) {
        var source = String(html || '');
        if (!source) return true;
        if (/loginExt|login\.action|登录/.test(source) && source.indexOf('TaskActivity') < 0) return true;
        return false;
    }

    // 从 courseTableForStd.action 的 HTML 里读 ids / 学期下拉元素的 id / 当前学期 id
    // （三处正则与上游 zua.js 的 parseParameters 同源，只把 ids 的取值放宽到非纯数字）
    function parseParameters(html) {
        var source = String(html || '');
        var idsMatch = /bg\.form\.addInput\(\s*form\s*,\s*["']ids["']\s*,\s*["']([^"']+)["']\s*\)/.exec(source);
        var tagIdMatch = /id=["'](semesterBar\d*Semester)["']/.exec(source);
        if (!idsMatch || !tagIdMatch) return null;

        var tagId = tagIdMatch[1];
        var escaped = tagId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        var elementMatch = new RegExp('<[^>]*\\bid=["\']' + escaped + '["\'][^>]*>', 'i').exec(source);
        var valueMatch = elementMatch ? /\bvalue=["'](\d+)["']/i.exec(elementMatch[0]) : null;

        // 页面若自己声明了每周起始日（树维部分部署的课表页里有），带上；没有就交给 parse.js 取约定值
        var firstDayMatch = /\bfirstDayOfWeek\s*=\s*([1-7])\b/.exec(source);

        return {
            ids: idsMatch[1],
            tagId: tagId,
            currentSemesterId: valueMatch ? valueMatch[1] : null,
            firstDayOfWeek: firstDayMatch ? parseInt(firstDayMatch[1], 10) : null
        };
    }

    // ---- 从 JS 对象字面量文本里取字段 ----
    // 教务返回的是 {semesters:{...},semesterId:1} 这种**对象字面量文本**，不是 JSON，所以只能按
    // 括号配对去切。上游 zua.js / hfnu.js 在这里用的是 Function("return (" + raw + ")")（把网络
    // 取回的字符串当代码执行），命中移植手册 §5 第 6 条「不 eval 远程代码」，所以本件**没有**移植
    // 那一步，全部换成正则 + 括号配对扫描（sliceBalanced / propertyValue / fieldOf）。

    function sliceBalanced(source, start, openChar, closeChar) {
        var depth = 0;
        var quote = '';
        for (var i = start; i < source.length; i++) {
            var ch = source.charAt(i);
            if (quote) {
                if (ch === '\\') { i++; continue; }
                if (ch === quote) quote = '';
                continue;
            }
            if (ch === '"' || ch === "'") { quote = ch; continue; }
            if (ch === openChar) depth++;
            else if (ch === closeChar) {
                depth--;
                if (depth === 0) return source.substring(start, i + 1);
            }
        }
        return '';
    }

    function propertyValue(source, key, openChar, closeChar) {
        var at = 0;
        while (at < source.length) {
            var found = source.indexOf(key, at);
            if (found < 0) return '';
            at = found + key.length;
            var j = found + key.length;
            if (source.charAt(j) === '"' || source.charAt(j) === "'") j++;
            while (j < source.length && /\s/.test(source.charAt(j))) j++;
            if (source.charAt(j) !== ':') continue;
            j++;
            while (j < source.length && /\s/.test(source.charAt(j))) j++;
            if (source.charAt(j) !== openChar) continue;
            return sliceBalanced(source, j, openChar, closeChar);
        }
        return '';
    }

    function fieldOf(body, key) {
        var re = new RegExp('["\']?' + key + '["\']?\\s*:\\s*(["\']?)([^,;"\'}]*)\\1');
        var m = re.exec(body);
        if (!m) return '';
        return textOf(m[2]);
    }

    // 学期列表：semesters 可能是数组，也可能按学年分组（{"2026-2027":[{...}]}）。
    // 每个学期只取 id / 名称 / 学年 / 学期序号；起止日期有就带上（没有就由 parse.js 推算并出声）
    function parseSemesters(raw) {
        var source = String(raw || '');
        var slice = propertyValue(source, 'semesters', '{', '}');
        if (!slice) slice = propertyValue(source, 'semesters', '[', ']');

        var entries = [];
        if (slice) {
            if (slice.charAt(0) === '[') {
                pushSemesterEntries(slice, '', entries);
            } else {
                var outerRe = /["']?([^"'\s{},:[\]]+)["']?\s*:\s*\[/g;
                var om;
                while ((om = outerRe.exec(slice)) !== null) {
                    var start = slice.indexOf('[', om.index + om[0].length - 1);
                    var arr = start < 0 ? '' : sliceBalanced(slice, start, '[', ']');
                    if (!arr) continue;
                    pushSemesterEntries(arr, om[1], entries);
                    outerRe.lastIndex = start + arr.length;
                }
            }
        }

        var semesterIdMatch = /["']?semesterId["']?\s*:\s*["']?(\d+)/.exec(source);
        return {
            semesters: entries,
            currentSemesterId: semesterIdMatch ? semesterIdMatch[1] : null
        };
    }

    function pushSemesterEntries(slice, groupYear, out) {
        var entryRe = /\{([^{}]*)\}/g;
        var m;
        while ((m = entryRe.exec(slice)) !== null) {
            var body = m[1];
            var id = fieldOf(body, 'id');
            if (!id || !/^\d+$/.test(id)) continue;
            var name = fieldOf(body, 'name');
            var schoolYear = fieldOf(body, 'schoolYear') || textOf(groupYear);
            var entry = { id: id, name: name, schoolYear: schoolYear };
            var start = fieldOf(body, 'startDate') || fieldOf(body, 'beginDate') || fieldOf(body, 'start');
            var end = fieldOf(body, 'endDate') || fieldOf(body, 'finishDate') || fieldOf(body, 'end');
            var weeks = fieldOf(body, 'totalWeeks') || fieldOf(body, 'semesterTotalWeeks');
            if (start) entry.startDate = start;
            if (end) entry.endDate = end;
            if (/^\d+$/.test(weeks)) entry.totalWeeks = parseInt(weeks, 10);
            out.push(entry);
        }
    }

    // 学期日历取不到时不算失败：交空串，parse.js 回落到推算并在 warnings 里如实说明
    function optionalText(url, body, notes) {
        return post(url, body).then(function (text) {
            return String(text || '');
        }, function (error) {
            notes.push(
                '学期日历接口（calendar-info）没有取到数据' +
                (error && error.message ? '（' + error.message + '）' : '') +
                '，开学日与总周数只能推算'
            );
            return '';
        });
    }

    var notes = [];
    var today = localTodayIso();

    return get(eamsBase() + '/courseTableForStd.action').then(function (tableHtml) {
        var params = parseParameters(tableHtml);
        if (!params) {
            throw new Error(
                '没读到教务参数（学号 ids / 学期下拉框）：登录状态可能已失效，或教务系统改了课表页。' +
                '请重新登录教务系统后再点「提取课表」'
            );
        }

        var queryBody = pair('tagId', params.tagId) + '&' + pair('dataType', 'semesterCalendar');
        if (params.currentSemesterId) queryBody = queryBody + '&' + pair('value', params.currentSemesterId);
        queryBody = queryBody + '&' + pair('empty', 'false');

        return post(eamsBase() + '/dataQuery.action', queryBody).then(function (rawQuery) {
            var parsed = parseSemesters(rawQuery);
            if (!parsed.semesters.length) {
                throw new Error(
                    '教务系统没有返回可选的学期列表：登录状态可能已失效，请重新登录后再点「提取课表」'
                );
            }

            var currentId = params.currentSemesterId || parsed.currentSemesterId;
            var chosen = null;
            var i;
            for (i = 0; i < parsed.semesters.length; i++) {
                if (currentId && parsed.semesters[i].id === currentId) { chosen = parsed.semesters[i]; break; }
            }
            if (!chosen && parsed.semesters.length === 1) chosen = parsed.semesters[0];
            if (!chosen) {
                chosen = parsed.semesters[0];
                notes.push(
                    '教务系统没有明确给出当前学期，已按学期列表里的第一个（' + (chosen.name || chosen.id) +
                    '）导入；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
                );
            }

            var courseBody = pair('ignoreHead', '1') + '&' + pair('setting.kind', 'std') +
                '&' + pair('startWeek', '') + '&' + pair('semester.id', chosen.id) +
                '&' + pair('ids', params.ids);

            return post(eamsBase() + '/courseTableForStd!courseTable.action', courseBody)
                .then(function (courseHtml) {
                    if (looksLikeLogin(courseHtml)) {
                        throw new Error(
                            '教务系统没有返回课表（返回的看起来是登录页）：登录状态可能已失效，' +
                            '请重新登录后再点「提取课表」'
                        );
                    }
                    var activityCount = String(courseHtml).split(/new\s+TaskActivity\s*\(/).length - 1;
                    var calendarBody = pair('version', '1') + '&' + pair('semesterId', chosen.id);
                    return optionalText(eamsBase() + '/base/calendar-info.action', calendarBody, notes)
                        .then(function (calendarHtml) {
                            return JSON.stringify({
                                today: today,
                                semester: chosen,
                                semesters: parsed.semesters,
                                currentSemesterId: currentId === null ? '' : String(currentId),
                                firstDayOfWeek: params.firstDayOfWeek,
                                tableHtml: tableHtml,
                                courseHtml: courseHtml,
                                calendarHtml: calendarHtml,
                                taskActivityCount: activityCount,
                                extractWarnings: notes
                            });
                        });
                });
        });
    });
})()
