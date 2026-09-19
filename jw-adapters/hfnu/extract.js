(function () {
    // 合肥师范学院教务适配器（树维 EAMS 平台）—— 第一步：只取数，把教务的原始数据原样交出去。
    //
    // 移植自 shiguang_warehouse 的 HFNU/hfnu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 平台：树维 EAMS（/eams/，上海树维信息科技有限公司 SupWisdom，新开普子公司）—— **不是强智**。
    //
    // 移植改动：
    //   ① 上游三条请求写的是**绝对 URL**（https://jw.hfnu.edu.cn/eams/...）。这里全部改成
    //      跟着**当前页面的源**走的相对路径（见 jwBase()）：用户在哪个主机、哪个前缀下打开教务，
    //      请求就打到那里。所以 manifest 的 allowHosts 留空、不写通配（移植手册 §5 第 3 条）。
    //      路径前缀仍固定为 /eams（树维 EAMS 的上下文路径）。
    //   ② 上游 dataQuery.action 的响应是用 Function("return (" + raw + ")") 求值的 ——
    //      把网络取回来的字符串当代码执行，命中手册 §5 第 6 条。这里只做文本读取 + 宽松 JSON 解析。
    //   ③ 上游用 showSingleSelection 让用户选学期。这里改成**自动取当前学期**：
    //      教务自己的 dataQuery 响应里带 semesterId（学期栏当前选中项），就用它；
    //      没有才按今天的日期推、再没有才取列表里的最后一个，并把「是怎么挑的」如实交出去
    //      （parse.js 会据此写 warnings）。
    //   ④ 上游用 showSingleSelection 让用户选校区。这里不再问用户：把页面上能读到的校区线索
    //      原样交出去（勾选的校区单选按钮、页面正文里出现的校区名、课表 HTML 里「第N节」括号中
    //      的时间），由 parse.js 自动判定 —— 判不出来时它会在 warnings 里说明，不静默选一套。
    //   ⑤ 只取数：课表 HTML 全文（含内嵌的 TaskActivity 与 unitCount）与学期日历原样交出去，
    //      课程解析、周次位图、节次寻址、作息选择全部在 parse.js 里（CI 只跑得动 parse.js）。
    //      页面线索只带走「校区按钮的 value/相邻文字/是否勾选」「正文里有没有出现校区名」
    //      「第N节表头括号里的时间」三样 —— 不含姓名、学号、身份证号等任何个人信息。
    //   ⑥ 上游的 showToast / notifyTaskCompletion 没有移植。
    //
    // 取数方式：同源请求，三条，全部打在当前教务主机上（地址由 window.location 拼出来）：
    //   ① GET  <origin><prefix>/courseTableForStd.action?sf_request_type=ajax     读 ids（学号栏）与
    //      tagId（学期栏元素 id）
    //   ② POST <origin><prefix>/dataQuery.action?sf_request_type=ajax             取学期日历
    //      （tagId=…&dataType=semesterCalendar），里面带当前学期 semesterId 与起止日期
    //   ③ POST <origin><prefix>/courseTableForStd!courseTable.action?sf_request_type=ajax
    //      取课表 HTML（ignoreHead=1&setting.kind=std&semester.id=<id>&ids=<ids>，与上游逐字一致）
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。
    // 本脚本也不写页面：读取的都是当前页面已有的文本/控件状态，fetch 回来的 HTML 只做字符串处理。

    var EAMS = '/eams';
    var MAX_PROBE_TEXT = 200000;

    function textOf(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    function tidy(value) {
        return textOf(value).replace(/\s+/g, ' ').trim();
    }

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 树维 EAMS 的上下文路径是 /eams；万一挂在前缀下（门户 / WebVPN / 反向代理），
    // 跟着当前地址里的那一段走 —— 脚本不替教务系统的部署方式做主
    function jwBase() {
        var path = textOf(window.location.pathname);
        var at = path.indexOf(EAMS + '/');
        if (at > 0) return originOf() + path.substring(0, at + EAMS.length);
        return originOf() + EAMS;
    }

    function request(url, method, body) {
        var options = {
            method: method,
            credentials: 'include',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': '*/*'
            }
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

    // dataQuery.action 返回的是 JS 对象字面量（形如 {semesters: {...}}）而不是严格 JSON，
    // 所以先按 JSON 解析，不行就把外层圆括号摘掉再解析 —— 不执行、不 eval。
    function lenientJson(raw) {
        var s = tidy(raw);
        if (!s) return null;
        var attempt;
        try {
            attempt = JSON.parse(s);
            return attempt;
        } catch (e) {
            /* 继续尝试去掉外层的圆括号 / 分号 */
        }
        if (s.charAt(s.length - 1) === ';') s = s.substring(0, s.length - 1);
        var guard = 0;
        while (guard < 3 && s.charAt(0) === '(' && s.charAt(s.length - 1) === ')') {
            s = s.substring(1, s.length - 1);
            guard++;
        }
        try {
            return JSON.parse(s);
        } catch (e2) {
            return null;
        }
    }

    // 学期列表归一：{id, schoolYear, termName, startDate, endDate}
    function semestersOf(calendar) {
        var out = [];
        if (!calendar || typeof calendar !== 'object') return out;
        var groups = calendar.semesters;
        if (!groups || typeof groups !== 'object') return out;
        var keys = Object.keys(groups);
        var i;
        var j;
        for (i = 0; i < keys.length; i++) {
            var list = groups[keys[i]];
            // 不用 instanceof Array：宿主页面与这里的 Array 可能不是同一个全局对象（iframe、Rhino）
            if (!list || typeof list !== 'object' || typeof list.length !== 'number') continue;
            for (j = 0; j < list.length; j++) {
                var item = list[j] || {};
                if (item.id === undefined || item.id === null || textOf(item.id) === '') continue;
                out.push({
                    id: textOf(item.id),
                    schoolYear: tidy(item.schoolYear),
                    termName: tidy(item.name),
                    startDate: textOf(item.startDate),
                    endDate: textOf(item.endDate)
                });
            }
        }
        return out;
    }

    // 当前学期：优先教务自己标的 semesterId；没有就按今天的日期推；再没有就取列表最后一个。
    // pickedBy 会一路交到 parse.js，由它决定要不要提醒用户核对。
    function pickSemester(calendar, list, todayIso) {
        if (!list.length) return null;
        var current = calendar && calendar.semesterId !== undefined && calendar.semesterId !== null
            ? textOf(calendar.semesterId) : '';
        var i;
        if (current) {
            for (i = 0; i < list.length; i++) {
                if (list[i].id === current) {
                    var hit = list[i];
                    hit.pickedBy = 'semesterId';
                    return hit;
                }
            }
        }
        var year = parseInt(todayIso.substring(0, 4), 10);
        var month = parseInt(todayIso.substring(5, 7), 10);
        var targetYear = month >= 8 ? year : year - 1;
        var targetTerm = month >= 8 ? '1' : (month >= 2 ? '2' : '1');
        var label = targetYear + '-' + (targetYear + 1);
        for (i = 0; i < list.length; i++) {
            var item = list[i];
            var term = tidy(item.termName).replace(/第/g, '').replace(/学期/g, '');
            if (tidy(item.schoolYear).indexOf(label) === 0 && term === targetTerm) {
                item.pickedBy = 'today';
                return item;
            }
        }
        var last = list[list.length - 1];
        last.pickedBy = 'last';
        return last;
    }

    // ---------- 页面线索（校区判定用，全部是只读） ----------
    // ① 校区单选按钮：value / 旁边的文字 / 是否勾选
    function radiosOnPage() {
        var out = [];
        var nodes;
        try {
            nodes = document.querySelectorAll('input[type=radio]');
        } catch (e) {
            return out;
        }
        if (!nodes || !nodes.length) return out;
        var i;
        for (i = 0; i < nodes.length && out.length < 200; i++) {
            var node = nodes[i];
            var label = '';
            try {
                var id = node.getAttribute ? node.getAttribute('id') : '';
                if (id && document.querySelector) {
                    var linked = document.querySelector('label[for="' + id + '"]');
                    if (linked) label = tidy(linked.textContent);
                }
                if (!label && node.parentNode) label = tidy(node.parentNode.textContent);
            } catch (e2) {
                label = '';
            }
            out.push({
                value: textOf(node.value),
                label: label.substring(0, 40),
                checked: !!node.checked
            });
        }
        return out;
    }

    // ② 页面正文里出现的校区名（只交布尔值，不外发任何正文）
    function tokensIn(source) {
        var s = textOf(source);
        if (s.length > MAX_PROBE_TEXT) s = s.substring(0, MAX_PROBE_TEXT);
        return {
            'jinxiu': s.indexOf('锦绣') >= 0,
            'binhu': s.indexOf('滨湖') >= 0
        };
    }

    // ③ 「第N节」表头旁边括号里的时间（ZUA / ZZVCAE 同款的作息读法，这里只用它做校区线索）
    function sectionTimesIn(source) {
        var s = textOf(source).replace(/<[^>]*>/g, ' ').replace(/&nbsp;|&#160;/gi, ' ');
        var out = [];
        var seen = {};
        var re = /第\s*(\d{1,2})\s*节[^0-9]{0,12}?(\d{1,2}:\d{2})\s*[-—~－]\s*(\d{1,2}:\d{2})/g;
        var m;
        while ((m = re.exec(s)) !== null) {
            var number = parseInt(m[1], 10);
            if (!(number >= 1 && number <= 40) || seen[number]) continue;
            seen[number] = true;
            out.push({ periodIndex: number, start: m[2], end: m[3] });
        }
        return out;
    }

    function pageProbe() {
        var bodyText = '';
        try {
            bodyText = document.body ? document.body.textContent : '';
        } catch (e) {
            bodyText = '';
        }
        var tableText = '';
        try {
            var table = document.querySelector('#manualArrangeCourseTable');
            if (table) tableText = table.textContent;
        } catch (e2) {
            tableText = '';
        }
        var times = sectionTimesIn(tableText);
        if (!times.length) times = sectionTimesIn(bodyText);
        return {
            url: textOf(window.location.href),
            tokens: tokensIn(bodyText),
            radios: radiosOnPage(),
            sectionTimes: times
        };
    }

    function todayIso() {
        var now = new Date();
        var month = now.getMonth() + 1;
        var day = now.getDate();
        return now.getFullYear() + '-' + (month < 10 ? '0' : '') + month + '-' + (day < 10 ? '0' : '') + day;
    }

    return get(jwBase() + '/courseTableForStd.action?sf_request_type=ajax')
        .then(function (bootstrapHtml) {
            var idsMatch = /bg\.form\.addInput\(\s*form\s*,\s*"ids"\s*,\s*"(\d+)"\s*\)/.exec(bootstrapHtml);
            var tagIdMatch = /id="(semesterBar\d+Semester)"/.exec(bootstrapHtml);
            if (!idsMatch || !tagIdMatch) {
                throw new Error(
                    '没能识别教务参数（学号栏 / 学期栏）：登录状态可能已失效，或教务系统改了课表页。' +
                    '请重新登录、确认能看到课表后再点「提取课表」'
                );
            }
            var ids = idsMatch[1];
            var tagId = tagIdMatch[1];
            var body = 'tagId=' + encodeURIComponent(tagId) + '&dataType=semesterCalendar';
            return post(jwBase() + '/dataQuery.action?sf_request_type=ajax', body)
                .then(function (calendarRaw) {
                    var calendar = lenientJson(calendarRaw);
                    var list = semestersOf(calendar);
                    var today = todayIso();
                    var semester = pickSemester(calendar, list, today);
                    if (!semester) {
                        throw new Error(
                            '教务系统没有返回学期列表：登录状态可能已失效，请重新登录后再点「提取课表」'
                        );
                    }
                    var tableBody = 'ignoreHead=1&setting.kind=std&semester.id=' +
                        encodeURIComponent(semester.id) + '&ids=' + encodeURIComponent(ids);
                    return post(
                        jwBase() + '/courseTableForStd!courseTable.action?sf_request_type=ajax',
                        tableBody
                    ).then(function (courseHtml) {
                        return JSON.stringify({
                            today: today,
                            semester: {
                                id: semester.id,
                                schoolYear: semester.schoolYear,
                                termName: semester.termName,
                                startDate: semester.startDate,
                                endDate: semester.endDate,
                                pickedBy: semester.pickedBy,
                                count: list.length
                            },
                            page: pageProbe(),
                            bootstrap: {
                                tokens: tokensIn(bootstrapHtml),
                                sectionTimes: sectionTimesIn(bootstrapHtml)
                            },
                            courseHtml: courseHtml
                        });
                    });
                });
        });
})()
