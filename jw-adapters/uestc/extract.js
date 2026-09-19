(function () {
    // 电子科技大学教务系统（树维 EAMS 平台，eams.uestc.edu.cn/eams）适配器 —— 第一步：只取数。
    //
    // 移植自 shiguang_warehouse 的 UESTC/uestc.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游作者 CorunLing）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 平台：树维 EAMS（/eams/，上海树维信息科技有限公司 SupWisdom，新开普子公司）。
    //   注意：不是「强智」——强智的路径是 /jsxsd/、登录页署名「湖南强智科技发展有限公司」，
    //   与本族不是一套（同批其它上游脚本也全都自称树维）。
    //
    // 移植改动（上游 → 本文件）：
    //   ① ES6 → ES5：去掉 async/await、模板串、箭头函数、对象展开与块级变量声明。
    //      流程控制一律改成 Promise 的 then 链。
    //   ② 只取数：上游在同一个脚本里取数 + 解析 TaskActivity + 算周次 + 合并课程 + 存储。
    //      这里只把教务的原始数据交出去（学期列表原文、课表 HTML 全文、学期日历 HTML 全文），
    //      课程解析、周次解析、单双周、开学日全部挪到 parse.js —— CI 里跑得到的那一段。
    //   ③ 学期不再问用户：上游用 showSingleSelection 让用户从「按学期号猜出来的 ±4 个学期」里挑
    //      （学期号还是写死的 483 + 每学期 20）。这里改成**自动取教务当前选中的那个学期**：
    //      先读当前页面上的学期组件，读不到就取一次课表页 HTML 读同一个组件。
    //   ④ 地址不再写死 https://eams.uestc.edu.cn：改成按当前页面的 origin + 上下文路径 /eams 拼，
    //      所以请求永远与用户打开的教务页面同源（走 http 还是 https、有没有挂在门户/网关前缀下，
    //      都由用户实际打开的地址决定）。见 manifest 的 allowHosts 与 AUDIT.md §1。
    //   ⑤ 上游从当前页面 DOM 里读 ids（学号），但没有别的办法拿到「当前学期」，只能问用户。
    //      这里保留上游的 DOM 读法，仅在读不到时补一条对课表页的 GET（同族 HPU/ZUA/NEUQ 都这么做）。
    //   ⑥ 上游不读学期列表、也没有学期日历；这里加了 dataQuery 与 calendar-info 两条**只读**请求，
    //      用来给学期起止日期和总周数（拿不到就交 null，由 parse.js 推算并写进 warnings）。
    //   ⑦ 上游的 showAlert / showToast / saveImportedCourses / savePresetTimeSlots /
    //      saveCourseConfig / notifyTaskCompletion 这些桥调用全部没有移植。
    //   ⑧ 学号（ids）只用于构造课表请求，**不交给 parse.js**、不进载荷、不进 fixture。
    //
    // 取数方式：全部同源请求（地址由 window.location.origin + 上下文路径拼出来），最多四条：
    //   ① （按需）GET  /eams/courseTableForStd.action?sf_request_type=ajax  读 ids / 学期组件 id
    //   ② POST /eams/dataQuery.action?sf_request_type=ajax（dataType=semesterCalendar）→ 学期列表原文
    //   ③ POST /eams/courseTableForStd!courseTable.action（semester.id + ids）→ 课表 HTML 全文
    //   ④ （可选）POST /eams/base/calendar-info.action（version=1 + semesterId）→ 学期日历 HTML
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。

    var EAMS_PATH = '/eams';

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 树维 EAMS 的上下文路径是 /eams；万一学校把它挂在门户 / 网关前缀下（例如 /webvpn/eams/...），
    // 跟着当前地址里那一段走，脚本不替教务系统写死主机，也不写死前缀。
    function eamsBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(EAMS_PATH + '/');
        if (at >= 0) return originOf() + path.substring(0, at + EAMS_PATH.length);
        return originOf() + EAMS_PATH;
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

    function tidy(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function digitsOnly(value) {
        var raw = String(value === null || value === undefined ? '' : value).replace(/\s+/g, '');
        return /^\d+$/.test(raw) ? raw : null;
    }

    function textOf(node) {
        if (!node) return '';
        if (node.textContent !== undefined && node.textContent !== null) return tidy(node.textContent);
        return tidy(node.innerText);
    }

    function tagNameOf(node) {
        return node && node.tagName ? String(node.tagName).toUpperCase() : '';
    }

    // 学期组件的值：<select> 读它当前选中的值，隐藏 input 读 value 属性
    function semesterValueOf(node) {
        if (!node) return null;
        if (tagNameOf(node) === 'SELECT') return digitsOnly(node.value);
        if (!node.getAttribute) return null;
        return digitsOnly(node.getAttribute('value'));
    }

    // 学期组件的显示文字（教务自己写的学期名最准）：<select> 当前那一项的文本
    function semesterLabelOf(node) {
        if (!node || tagNameOf(node) !== 'SELECT' || !node.options) return null;
        var option = node.options[node.selectedIndex];
        if (!option) return null;
        var label = textOf(option);
        return label ? label : null;
    }

    // 当前页面上就已经有的线索：用户此刻正开在课表页时，不必再请求一次课表页
    function hintFromPage() {
        var hints = { ids: null, tagId: null, semesterId: null, semesterLabel: null };
        var i;
        var inputs = document.querySelectorAll('form input[name="ids"]');
        for (i = 0; i < inputs.length; i++) {
            var value = digitsOnly(inputs[i].value);
            if (value) { hints.ids = value; break; }
        }
        if (!hints.ids) {
            var params = document.querySelectorAll('form input[name="params"]');
            for (i = 0; i < params.length; i++) {
                var match = /[?&]ids=(\d+)/.exec(String(params[i].value || ''));
                if (match) { hints.ids = match[1]; break; }
            }
        }
        var bars = document.querySelectorAll('[id$="Semester"]');
        for (i = 0; i < bars.length; i++) {
            var id = String(bars[i].id || '');
            if (!/^semesterBar\d+Semester$/.test(id)) continue;
            hints.tagId = id;
            hints.semesterId = semesterValueOf(bars[i]);
            hints.semesterLabel = semesterLabelOf(bars[i]);
            break;
        }
        return hints;
    }

    // 课表页 HTML 里的同两个字段（同族 HPU / ZUA / NEUQ / CUIT 都是这么读的）
    function fillFromEntryHtml(html, hints) {
        var source = String(html || '');
        if (!hints.ids) {
            var ids = /bg\.form\.addInput\(\s*form\s*,\s*["']ids["']\s*,\s*["'](\d+)["']\s*\)/.exec(source);
            if (!ids) ids = /<input[^>]*\bname=["']ids["'][^>]*\bvalue=["'](\d+)["']/i.exec(source);
            if (!ids) ids = /<input[^>]*\bvalue=["'](\d+)["'][^>]*\bname=["']ids["']/i.exec(source);
            if (ids) hints.ids = ids[1];
        }
        if (!hints.tagId) {
            var bar = /\bid=["'](semesterBar\d+Semester)["']/.exec(source);
            if (bar) hints.tagId = bar[1];
        }
        if (!hints.semesterId && hints.tagId) {
            var escaped = hints.tagId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            var tag = new RegExp('<[^>]*\\bid=["\']' + escaped + '["\'][^>]*>', 'i').exec(source);
            if (tag) {
                var value = /\bvalue=["'](\d+)["']/i.exec(tag[0]);
                if (value) hints.semesterId = value[1];
            }
        }
        return hints;
    }

    function gatherHints() {
        var hints = hintFromPage();
        if (hints.ids && hints.tagId && hints.semesterId) return Promise.resolve(hints);
        return get(eamsBase() + '/courseTableForStd.action?sf_request_type=ajax')
            .then(function (html) {
                return fillFromEntryHtml(html, hints);
            });
    }

    // 学期列表原文里的当前学期号（semesterId:523 或 semesterId:"523"）
    function semesterIdFromRaw(raw) {
        var match = /\bsemesterId\s*:\s*["']?(\d+)["']?/.exec(String(raw || ''));
        return match ? match[1] : null;
    }

    // 失败一律交 null：附加接口挂掉不该让整个导入失败，由 parse.js 回落到推算并写进 warnings
    function optionalText(url, body) {
        return post(url, body).then(function (text) {
            return String(text);
        }, function () {
            return null;
        });
    }

    function todayIso() {
        var now = new Date();
        var pad = function (n) { return (n < 10 ? '0' : '') + n; };
        return now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
    }

    return gatherHints().then(function (hints) {
        var dataQueryBody = hints.tagId
            ? 'tagId=' + encodeURIComponent(hints.tagId) + '&dataType=semesterCalendar'
            : null;
        var semesterRaw = dataQueryBody
            ? optionalText(eamsBase() + '/dataQuery.action?sf_request_type=ajax', dataQueryBody)
            : Promise.resolve(null);

        return semesterRaw.then(function (raw) {
            var semesterId = hints.semesterId || semesterIdFromRaw(raw);
            if (!semesterId) {
                throw new Error(
                    '没读到当前学期的编号：请先在教务系统里打开「我的课表」页面，再点「提取课表」'
                );
            }
            if (!hints.ids) {
                throw new Error(
                    '没读到你的学号参数：请先在教务系统里打开「我的课表」页面，再点「提取课表」；' +
                    '如果已经打开，请确认登录状态没有失效'
                );
            }
            // 请求体与上游 UESTC/uestc.js 逐字一致（上游用的是 X-Requested-With，不加 sf_request_type）
            var courseBody = 'ignoreHead=1&setting.kind=std&startWeek=&project.id=1&isEng=0' +
                '&semester.id=' + encodeURIComponent(semesterId) +
                '&ids=' + encodeURIComponent(hints.ids);
            return post(eamsBase() + '/courseTableForStd!courseTable.action', courseBody)
                .then(function (courseHtml) {
                    var calendarBody = 'version=1&semesterId=' + encodeURIComponent(semesterId);
                    return optionalText(eamsBase() + '/base/calendar-info.action', calendarBody)
                        .then(function (calendarHtml) {
                            return JSON.stringify({
                                // 取数当天的日期：教务不给开学日时 parse.js 只能拿它当推算基准，
                                // 原样交出去（而不是让 parse.js 自己去问系统时间），回归用例才钉得住
                                today: todayIso(),
                                url: String(window.location ? window.location.href : ''),
                                tagId: hints.tagId || null,
                                semesterId: semesterId,
                                semesterLabel: hints.semesterLabel || null,
                                semesterRaw: raw,
                                calendarHtml: calendarHtml,
                                courseHtml: String(courseHtml)
                            });
                        });
                });
        });
    });
})()
