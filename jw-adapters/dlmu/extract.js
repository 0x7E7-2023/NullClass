(function () {
    // 大连海事大学教务适配器（树维 EAMS 平台）—— 第一步：只取数，把教务的原始数据原样交出去。
    //
    // 移植自 shiguang_warehouse 的 DLMU/dlmu_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 whynusn）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 移植改动：
    //   ① 地址不再写死 http://jw.xpaas.dlmu.edu.cn（上游五条请求全部用的绝对地址）：改成按当前页面的
    //      origin + 上下文路径 /eams 拼。走 http 还是 https、有没有挂在门户 / WebVPN 前缀下，
    //      都由用户实际打开的那个地址决定，脚本不替教务系统做主 —— 请求主机只剩用户当前所在的那一个，
    //      所以 manifest 的 allowHosts 留空（见 AUDIT.md §1）
    //   ② 不问用户：上游先 showAlert 公告、再 showPrompt 问起始学年（还要用户自己把「2025-2026」
    //      换算成「2025」）、再 showSingleSelection 问第一 / 第二 / 小学期。这里改成自动取教务当前学年
    //      学期：用户在课表页时读页面上的学期下拉框与隐藏的 semesterId 输入框（他自己切过的学期优先），
    //      读不到再取一次课表页 HTML 读同样几处。要别的学期，用户在教务页面里切一下再点「提取课表」
    //      （移植手册 §3 第 1 步：自动取当前学期，不要弹窗）
    //   ③ 只取数：课表 HTML 全文、学号、页面里的 var unitCount、学期线索原样交出去 ——
    //      课程解析、周次位图、index 换算、开学日推算全部在 parse.js（CI 里跑得到的那一段）
    //   ④ **不剥响应里的空白**：上游的 fetchWithCleanup 会把整份 HTML 的空白全删掉
    //      （连带引号字符串里的空格一起没）。这里交原文，让 parse.js 的解析器容忍空白 ——
    //      剥空白会改掉课程名与教室名
    //   ⑤ 多取一个同模块的只读接口取学期起止日期（base/calendar-info.action，同族 ZUA 的写法）。
    //      树维的 semesterCalendar 响应里没有起止日期，拿不到日期就只能按学期序号推算开学日；
    //      有了它开学日就是教务给的真值。取不到交 null，parse.js 回落到推算并写进 warnings
    //   ⑥ 桥调用一个都没搬：showAlert / showPrompt / showSingleSelection / showToast /
    //      notifyTaskCompletion / saveImportedCourses / savePresetTimeSlots 全部不要
    //   ⑦ ES6 → ES5：async/await 改成 then 链，去掉模板串、箭头函数、块级声明与扩展运算符
    //   ⑧ 不写页面：只读当前页面上的下拉框与输入框，用离线 DOMParser 解析取回的 HTML，
    //      不插入当前页面、不改表单、不触发提交或点击
    //
    // 取数方式：同源请求，最多四条（地址都由 window.location.origin + 上下文路径拼出来）：
    //   ① GET  /eams/courseTableForStd.action?sf_request_type=ajax              读学号 ids、学期 tagId、
    //      页面里的 var unitCount（已经开在课表页时跳过这一步，改读当前页面）
    //   ② POST /eams/dataQuery.action?sf_request_type=ajax                      学期列表
    //      （dataType=semesterCalendar；上游同款）
    //   ③ GET  /eams/base/calendar-info.action?version=1&semesterId=<id>        学期起止日期与周数
    //      （上游 dlmu 没有这条；取自同族的 ZUA。取不到交 null）
    //   ④ POST /eams/courseTableForStd!courseTable.action?sf_request_type=ajax  课表 HTML（上游同款，
    //      请求体逐字一致：ignoreHead=1&setting.kind=std&startWeek=&project.id=1&semester.id=&ids=）
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。

    var EAMS = '/eams';
    var ACTION_STD = '/courseTableForStd.action?sf_request_type=ajax';
    var ACTION_QUERY = '/dataQuery.action?sf_request_type=ajax';
    var ACTION_TABLE = '/courseTableForStd!courseTable.action?sf_request_type=ajax';
    var ACTION_CALENDAR = '/base/calendar-info.action';

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 树维的上下文路径是 /eams；万一挂在前缀下（门户 / WebVPN），跟着当前地址里的那一段走。
    // 例如用户开在 /webvpn/eams/courseTableForStd.action 时，请求也走 /webvpn/eams/...
    function eamsBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(EAMS + '/');
        if (at > 0) return originOf() + path.substring(0, at + EAMS.length);
        return originOf() + EAMS;
    }

    // 当前页面就是课表页时，直接请求当前这个地址（保留它自己的查询串），少猜一次地址
    function courseTableUrl() {
        var path = String(window.location.pathname || '');
        if (path.indexOf('courseTableForStd') < 0) return eamsBase() + ACTION_STD;
        var search = String(window.location.search || '');
        return originOf() + path + (search ? search : '?sf_request_type=ajax');
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

    function jsonOf(raw) {
        try {
            return JSON.parse(String(raw));
        } catch (e) {
            return null;
        }
    }

    function formOf(pairs) {
        var out = [];
        var i;
        for (i = 0; i < pairs.length; i++) {
            out.push(encodeURIComponent(pairs[i][0]) + '=' + encodeURIComponent(pairs[i][1]));
        }
        return out.join('&');
    }

    // ---------- 从课表页 HTML（或当前页面）里读学号 / 学期 tagId / var unitCount ----------
    // 上游 parseStudentIds 读的就是 bg.form.addInput(form,"ids","<数字>"); 这一句
    function idsIn(html) {
        var m = /addInput\s*\(\s*[A-Za-z_$][A-Za-z0-9_$]*\s*,\s*["']ids["']\s*,\s*["']([0-9]+)["']/.exec(html);
        if (m) return m[1];
        var f = /name\s*=\s*["']ids["'][^>]*value\s*=\s*["']([0-9]+)["']/.exec(html);
        if (f) return f[1];
        var f2 = /value\s*=\s*["']([0-9]+)["'][^>]*name\s*=\s*["']ids["']/.exec(html);
        return f2 ? f2[1] : null;
    }

    // 学期标签的 id 形态是 semesterBar<数字>Semester（上游把它硬编码成了
    // semesterBar20826294511Semester，这里读页面真实的那一个）
    function tagIdIn(html) {
        var m = /id\s*=\s*["'](semesterBar[0-9]*[A-Za-z]*Semester)["']/.exec(html);
        return m ? m[1] : null;
    }

    // 页面脚本里的 var unitCount = N（每天的课程节数）。上游 dlmu 用的是脚本常量 10，
    // 没有读页面；这里读出来交给 parse.js 用（读不到就回落常量并写进 warnings）
    function unitCountIn(html) {
        var m = /var\s+unitCount\s*=\s*([0-9]{1,2})\s*[;,}]/.exec(html);
        if (!m) return null;
        var n = parseInt(m[1], 10);
        return n >= 1 ? n : null;
    }

    // 读一个下拉框：跳过 value 为空的占位项，被 selected 标记的那项优先，没标记就取第一项；
    // 页面把它放在隐藏 input 里时直接读 value
    function readSelect(selectEl) {
        if (!selectEl) return null;
        var options = selectEl.options;
        if (!options && selectEl.querySelectorAll) options = selectEl.querySelectorAll('option');
        if (!options || !options.length) {
            var raw = textOf(selectEl.value);
            return raw ? { value: raw, text: '' } : null;
        }
        var list = [];
        var picked = -1;
        var i;
        for (i = 0; i < options.length; i++) {
            var option = options[i];
            var value = String(option.value === null || option.value === undefined ? '' : option.value)
                .replace(/\s+/g, '');
            if (!value) continue;
            var label = textOf(option.textContent);
            if (option.selected && picked < 0) picked = list.length;
            list.push({ value: value, text: label });
        }
        if (!list.length) return null;
        if (picked < 0) picked = 0;
        return { value: list[picked].value, text: list[picked].text };
    }

    // 页面上的 semesterId：优先读元素自己的 value（对 select 来说这就是选中项的值），
    // value 拿不到再按「被 selected 标记的 option / 第一项」读一遍
    function semesterIdIn(doc) {
        if (!doc || !doc.querySelector) return null;
        var nodes = ['#semesterId', 'input[name="semester.id"]', 'input[name="semesterId"]',
            'select[name="semester.id"]', 'select[id$="Semester"]'];
        var i;
        for (i = 0; i < nodes.length; i++) {
            var found = null;
            try { found = doc.querySelector(nodes[i]); } catch (e) { found = null; }
            if (!found) continue;
            var raw = textOf(found.value);
            if (/^[0-9]+$/.test(raw)) return raw;
            var picked = readSelect(found);
            if (picked && /^[0-9]+$/.test(picked.value)) return picked.value;
        }
        return null;
    }

    function docOf(html) {
        try {
            return new DOMParser().parseFromString(String(html || ''), 'text/html');
        } catch (e) {
            return null;
        }
    }

    // ---------- 学期列表 ----------
    // 树维 semesterCalendar 的响应是 { semesters: { <学年>: [ {id, schoolYear, name}, ... ] }, semesterId }
    // 也可能被一串 JSON 包着（有的部署回 { datas: {...} }）。这里两种都认，认不出交空数组。
    function semesterList(data) {
        var holder = data && data.semesters ? data.semesters : null;
        if (!holder && data && data.datas) holder = data.datas.semesters;
        if (!holder || typeof holder !== 'object') return [];
        var keys = [];
        var k;
        for (k in holder) {
            if (!Object.prototype.hasOwnProperty.call(holder, k)) continue;
            keys.push(k);
        }
        keys.sort();
        var list = [];
        var i;
        var j;
        for (i = 0; i < keys.length; i++) {
            var arr = holder[keys[i]];
            if (!arr || typeof arr.length !== 'number') continue;
            for (j = 0; j < arr.length; j++) {
                var one = arr[j];
                if (!one) continue;
                var id = textOf(one.id);
                if (!id) continue;
                var kind = textOf(one.name);
                list.push({
                    id: id,
                    kind: kind,
                    schoolYear: textOf(one.schoolYear),
                    label: (textOf(one.schoolYear) + ' 第' + (kind || '?') + '学期').replace(/\s+/g, ' ').trim()
                });
            }
        }
        return list;
    }

    function currentSemesterId(data) {
        var raw = data ? data.semesterId : null;
        var s = textOf(raw);
        return /^[0-9]+$/.test(s) ? s : null;
    }

    // 选学期：优先页面上那个（用户自己切过），其次响应里的当前学期，再其次列表第一个
    function pickSemester(list, pageSemesterId, responseSemesterId) {
        if (!list.length) return null;
        var wanted = pageSemesterId || responseSemesterId;
        var i;
        if (wanted) {
            for (i = 0; i < list.length; i++) {
                if (list[i].id === wanted) return list[i];
            }
        }
        return list[0];
    }

    // ---------- 学期起止日期与周数 ----------
    // 同族 ZUA 的写法：POST base/calendar-info.action（version=1&semesterId=<id>），
    // 回的是 HTML，里面有一行「开始/结束日期：2026-09-07 ~ 2027-01-17 (19)」。取不到交 null。
    function calendarDate(text) {
        var m = /([0-9]{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(text);
        if (!m) return null;
        var month = parseInt(m[2], 10);
        var day = parseInt(m[3], 10);
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return m[1] + '-' + (month < 10 ? '0' : '') + month + '-' + (day < 10 ? '0' : '') + day;
    }

    function parseCalendarInfo(html) {
        var plain = String(html || '').replace(/<[^>]*>/g, ' ');
        plain = plain.split('&nbsp;').join(' ').split('&#160;').join(' ');
        plain = plain.split(String.fromCharCode(160)).join(' ');
        var range = /开始[\s/／]*结束日期[：:]?\s*([0-9]{4}[-/.]\d{1,2}[-/.]\d{1,2})\s*[~～至-]\s*([0-9]{4}[-/.]\d{1,2}[-/.]\d{1,2})/.exec(plain);
        if (!range) return null;
        var start = calendarDate(range[1]);
        if (!start) return null;
        var weeks = null;
        var count = /[~～至-]\s*[0-9]{4}[-/.]\d{1,2}[-/.]\d{1,2}\s*\(\s*([0-9]{1,2})\s*\)/.exec(plain);
        if (count) {
            var n = parseInt(count[1], 10);
            if (n >= 1 && n <= 40) weeks = n;
        }
        return { startDate: start, weekCount: weeks };
    }

    // ---------- 主流程 ----------
    var task = null;
    try {
        task = JSON.parse(String(__ncInput === undefined || __ncInput === null ? '' : __ncInput));
    } catch (e) {
        task = null;
    }
    // extract.js 是第一步，通常没有输入；允许带一个 {semesterId} 指定学期（宿主 / 调试用）
    if (!task || typeof task !== 'object') task = {};

    var today = localTodayIso();
    var pageDoc = docOf(document.documentElement ? document.documentElement.outerHTML : '');
    var pageSemesterId = semesterIdIn(document) || semesterIdIn(pageDoc);
    if (!pageSemesterId && /^[0-9]+$/.test(textOf(task.semesterId))) pageSemesterId = textOf(task.semesterId);

    var htmlForIds;
    var fromPage = false;
    if (String(window.location.pathname || '').indexOf('courseTableForStd') >= 0) {
        // 已经开在课表页：直接读当前页面（少发一条请求）
        htmlForIds = String(document.documentElement ? document.documentElement.outerHTML : '');
        fromPage = true;
    }

    var ensureIds = (htmlForIds !== undefined && htmlForIds !== null)
        ? Promise.resolve(htmlForIds)
        : get(courseTableUrl());

    return ensureIds.then(function (tablePage) {
        var ids = idsIn(tablePage);
        var tagId = tagIdIn(tablePage);
        var unitCountPage = unitCountIn(tablePage);
        if (!ids || !tagId) {
            throw new Error(
                '没能从课表页里认出学号或学期标签：请确认已登录海大教务系统、' +
                '并停在教务系统内的任意页面上，再点「提取课表」'
            );
        }

        var body = formOf([
            ['tagId', tagId],
            ['dataType', 'semesterCalendar'],
            ['value', pageSemesterId || ''],
            ['empty', 'false']
        ]);

        return post(eamsBase() + ACTION_QUERY, body).then(function (raw) {
            var data = jsonOf(raw);
            var list = semesterList(data);
            var term = pickSemester(list, pageSemesterId, currentSemesterId(data));
            if (!term) {
                throw new Error(
                    '教务系统没有返回可用的学期列表（可能登录状态已失效）：' +
                    '请重新登录、确认页面上能看到课表后再点「提取课表」'
                );
            }
            return {
                ids: ids,
                tagId: tagId,
                unitCountPage: unitCountPage,
                term: term,
                fromPage: fromPage
            };
        });
    }).then(function (ctx) {
        // 学期起止日期：取不到不是错误，交 null 让 parse.js 回落到推算并写进 warnings
        var calendarUrl = eamsBase() + ACTION_CALENDAR + '?' + formOf([
            ['version', '1'],
            ['semesterId', ctx.term.id]
        ]);
        return get(calendarUrl).then(function (html) {
            return parseCalendarInfo(html);
        }).then(null, function () {
            return null;
        }).then(function (calendar) {
            if (calendar && calendar.startDate) {
                ctx.term.startDate = calendar.startDate;
                ctx.term.weekCount = calendar.weekCount;
            }
            return ctx;
        });
    }).then(function (ctx) {
        var tableBody = formOf([
            ['ignoreHead', '1'],
            ['setting.kind', 'std'],
            ['startWeek', ''],
            ['project.id', '1'],
            ['semester.id', ctx.term.id],
            ['ids', ctx.ids]
        ]);
        return post(eamsBase() + ACTION_TABLE, tableBody).then(function (tableHtml) {
            // 课表页 HTML 原样交出去（**不剥空白**）
            var out = {
                today: today,
                term: {
                    id: ctx.term.id,
                    kind: ctx.term.kind,
                    schoolYear: ctx.term.schoolYear,
                    label: ctx.term.label,
                    name: ctx.term.label,
                    startDate: ctx.term.startDate || null,
                    weekCount: ctx.term.weekCount === undefined ? null : ctx.term.weekCount
                },
                unitCountPage: ctx.unitCountPage === undefined ? null : ctx.unitCountPage,
                ids: ctx.ids,
                tableHtml: String(tableHtml || '')
            };
            if (typeof __ncDone === 'function') {
                __ncDone(JSON.stringify(out));
                return undefined;
            }
            return JSON.stringify(out);
        });
    });
})()
